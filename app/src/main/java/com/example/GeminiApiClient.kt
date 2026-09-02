package com.example

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.source
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

sealed class GenerationUpdate {
    data class Log(val message: String) : GenerationUpdate()
    data class Progress(val progress: Float) : GenerationUpdate()
    data class InitParts(val totalParts: Int) : GenerationUpdate()
    data class ScriptChunk(val partIndex: Int, val text: String, val isError: Boolean = false, val clearFirst: Boolean = false) : GenerationUpdate()
    data class RequireRetryDecision(val failedParts: List<Int>) : GenerationUpdate()
    data class FileInfo(val geminiFileUri: String, val mimeType: String, val durationMs: Long) : GenerationUpdate()
    data class Error(val message: String) : GenerationUpdate()
    data class ApiCallResult(val success: Boolean) : GenerationUpdate()
    object Complete : GenerationUpdate()
}

class GeminiApiClient(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    private fun getModelName(uiModelName: String): String {
        return when {
            uiModelName.contains("3.7", ignoreCase = true) -> "gemini-3.7-flash"
            uiModelName.contains("3.6", ignoreCase = true) -> "gemini-3.6-flash"
            uiModelName.contains("3.5-flash-lite", ignoreCase = true) || uiModelName.contains("3.5 Flash-Lite", ignoreCase = true) -> "gemini-3.5-flash-lite"
            uiModelName.contains("3.5", ignoreCase = true) -> "gemini-3.5-flash"
            uiModelName.contains("3.1", ignoreCase = true) -> "gemini-3.1-flash-lite"
            uiModelName.startsWith("gemini-") -> uiModelName.trim()
            else -> "gemini-3.7-flash"
        }
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%02d:%02d", minutes, seconds)
    }

    suspend fun generateRecapScript(
        apiKey: String,
        modelName: String,
        partsCount: Float,
        length: String,
        pov: String,
        tone: String,
        language: String,
        mediaUri: Uri?,
        mediaMimeType: String?,
        retryDecisionChannel: kotlinx.coroutines.channels.ReceiveChannel<Boolean>? = null,
        existingGeminiFileUri: String? = null,
        existingDurationMs: Long? = null,
        specificPartsToGenerate: List<Int>? = null
    ): Flow<GenerationUpdate> = flow {
        emit(GenerationUpdate.Progress(0.0f))
        
        if (apiKey.isBlank()) {
            emit(GenerationUpdate.Error("API Key is missing. Please add it in settings."))
            return@flow
        }
        if (mediaUri == null && existingGeminiFileUri == null) {
            emit(GenerationUpdate.Error("Please select a video or audio file first."))
            return@flow
        }

        try {
            val actualModelName = getModelName(modelName)
            val finalGeminiFileUri: String
            var durationMs = 30000L // Default fallback
            
            if (existingGeminiFileUri != null && existingDurationMs != null) {
                emit(GenerationUpdate.Log("[15%] Using existing cached media on server..."))
                emit(GenerationUpdate.Progress(0.15f))
                finalGeminiFileUri = existingGeminiFileUri
                durationMs = existingDurationMs
            } else {
                emit(GenerationUpdate.Log("[0%] Preparing media upload..."))
                emit(GenerationUpdate.Progress(0.01f))
                
                var lastLogPercent = -1
                val uploadResult = uploadFile(apiKey, mediaUri!!, mediaMimeType ?: "video/mp4") { ratio, uploadedBytes, totalBytes ->
                    // Upload takes 0% to 12% of total process
                    val totalUploadProgress = 0.01f + (ratio * 0.11f)
                    val uploadPercent = (ratio * 100).toInt()
                    
                    // Log upload percentage at milestones (every 25%)
                    if (uploadPercent % 25 == 0 && uploadPercent != lastLogPercent) {
                        lastLogPercent = uploadPercent
                        val mbUploaded = String.format(java.util.Locale.US, "%.1f", uploadedBytes / (1024.0 * 1024.0))
                        val mbTotal = String.format(java.util.Locale.US, "%.1f", totalBytes / (1024.0 * 1024.0))
                        kotlinx.coroutines.runBlocking {
                            // Synchronously update
                        }
                    }
                }
                
                val fileUriInfo = when (uploadResult) {
                    is UploadResult.Success -> Pair(uploadResult.uri, uploadResult.name)
                    is UploadResult.Failure -> {
                        emit(GenerationUpdate.Error("Upload failed:\n${uploadResult.errorDetail}"))
                        return@flow
                    }
                }
                
                emit(GenerationUpdate.Log("[12%] Media upload complete. File cached on server."))
                emit(GenerationUpdate.Progress(0.12f))

                // Wait for video processing if it's a video
                if (mediaMimeType?.startsWith("video") == true) {
                    emit(GenerationUpdate.Log("[13%] Waiting for Gemini server to process video..."))
                    emit(GenerationUpdate.Progress(0.13f))
                    var isActive = false
                    var pollCount = 0
                    while(!isActive) {
                        val state = checkFileState(apiKey, fileUriInfo.second)
                        if (state == "ACTIVE") {
                            isActive = true
                        } else if (state == "FAILED") {
                            emit(GenerationUpdate.Error("Gemini failed to process the uploaded video."))
                            return@flow
                        } else {
                            pollCount++
                            val currentProcProgress = (0.13f + (pollCount * 0.01f)).coerceAtMost(0.15f)
                            emit(GenerationUpdate.Progress(currentProcProgress))
                            emit(GenerationUpdate.Log("[${(currentProcProgress * 100).toInt()}%] Gemini is indexing video frames (State: $state)... waiting."))
                            delay(5000)
                        }
                    }
                    emit(GenerationUpdate.Log("[15%] Video processing and frame indexing complete."))
                }

                emit(GenerationUpdate.Progress(0.15f))

                // Extract Video Duration
                try {
                    val retriever = MediaMetadataRetriever()
                    retriever.setDataSource(context, mediaUri)
                    val timeString = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    if (timeString != null) {
                        durationMs = timeString.toLong()
                    }
                    retriever.release()
                } catch (e: Exception) {
                    Log.e("GeminiApiClient", "Failed to extract duration", e)
                    emit(GenerationUpdate.Log("(Note: Could not detect exact duration, defaulting to 30s)"))
                }
                
                // Emit FileInfo for saving to database
                emit(GenerationUpdate.FileInfo(fileUriInfo.first, mediaMimeType ?: "video/mp4", durationMs))
                finalGeminiFileUri = fileUriInfo.first
            }

            val parts = partsCount.toInt()
            emit(GenerationUpdate.InitParts(parts))
            
            val segmentDurationMs = durationMs / parts
            val segmentDurationMins = segmentDurationMs / 60000.0

            val isAudio = mediaMimeType?.startsWith("audio") == true
            val isVideo = !isAudio

            val (minWords, maxWords) = when (length) {
                "Short" -> Pair(maxOf(30, (60 * segmentDurationMins).toInt()), maxOf(47, (95 * segmentDurationMins).toInt()))
                "Medium" -> Pair(maxOf(55, (110 * segmentDurationMins).toInt()), maxOf(77, (155 * segmentDurationMins).toInt()))
                "TTS match" -> Pair(maxOf(60, (120 * segmentDurationMins).toInt()), maxOf(70, (140 * segmentDurationMins).toInt()))
                "Long" -> Pair(maxOf(85, (170 * segmentDurationMins).toInt()), maxOf(120, (240 * segmentDurationMins).toInt()))
                else -> Pair(maxOf(55, (110 * segmentDurationMins).toInt()), maxOf(77, (155 * segmentDurationMins).toInt()))
            }
            
            val povDetail = when {
                pov.contains("Main Character", ignoreCase = true) || pov.contains("First", ignoreCase = true) -> 
                    "အဓိကဇာတ်ကောင်၏ ရှုထောင့်အမြင် (First-Person POV / 'ကျွန်တော်/ကျွန်မ/ငါ') မှ ကိုယ်တိုင်ကြုံတွေ့နေရသကဲ့သို့ ခံစားချက်ပြင်းပြစွာ ပြောပြရမည်။"
                pov.contains("Third", ignoreCase = true) || pov.contains("Omniscient", ignoreCase = true) -> 
                    "ပြင်ပဇာတ်ကြောင်းပြောသူအမြင် (Third-Person / Omniscient Narrator) မှ 'သူ'၊ 'သူမ'၊ 'သူတို့' စသည်ဖြင့် အရာအားလုံးကို ခြုံငုံသိမြင်နေသော ပုံစံဖြင့် ဇာတ်ကြောင်းပြောပြရမည်။"
                pov.contains("Second", ignoreCase = true) -> 
                    "ကြည့်ရှုသူ/နားဆင်သူအား တိုက်ရိုက်ပြောဆိုသော အမြင် (Second-Person POV / 'မင်း/ခင်ဗျား/သင်') ဖြင့် စိတ်ဝင်စားဖွယ် ပြောပြရမည်။"
                else -> "$pov ရှုထောင့်အမြင်အတိုင်း ဇာတ်ကြောင်းပြောပြရမည်။"
            }

            val toneDetail = "$tone (ရွေးချယ်ထားသော '$tone' လေသံအတိုင်း နားထောင်သူ စိတ်ဝင်စားအောင် ရေးသားပါ)"
            val lengthInstruction = "ရွေးချယ်ထားသော Script Length '$length' အရ စကားလုံးအရေအတွက် $minWords မှ $maxWords လုံး အတွင်း အတိအကျဖြစ်အောင် ရေးသားပေးရမည်။ (ပိုတိုခြင်း၊ ပိုရှည်လွန်းခြင်း လုံးဝ မဖြစ်ရပါ။)"

            val modalityInstruction = if (isVideo) {
                """
                |Media Source: ဗီဒီယို (Video - Visual Actions + Spoken Dialogues & Audio)
                |- ဤဗီဒီယိုအပိုင်းအခြားအတွင်း ဖြစ်ပျက်နေသော ဇာတ်ကောင်များ၏ လှုပ်ရှားမှု/မြင်ကွင်းများ (Visual actions, scenes, character expressions) နှင့် ဇာတ်ကောင်များ ပြောဆိုသော စကားသံ/နောက်ခံအသံများ (Spoken dialogues, speech context, sound effects) နှစ်ခုစလုံးကို အသေးစိတ် ကြည့်ရှုနားထောင်ပြီး အခြေခံ၍ ရေးသားရမည်။
                """.trimMargin()
            } else {
                """
                |Media Source: အသံဖိုင် (Audio Only - Speech & Sound Context)
                |- ဤအသံဖိုင်အပိုင်းအခြားအတွင်း ပြောဆိုသော စကားသံများ (Spoken dialogues, speeches, narration) နှင့် နောက်ခံအသံ/အသံအရည်အသွေးများ (Background audio context, sound cues) ကိုသာ အပြည့်အဝ နားထောင်ပြီး အခြေခံ၍ ရေးသားရမည်။ မရှိသော visual မြင်ကွင်းများကို ကိုယ်တိုင်မဖန်တီးဘဲ အသံထဲပါသော အကြောင်းအရာကိုသာ အခြေခံရမည်။
                """.trimMargin()
            }

            val languageConstraint = if (language == "Myanmar") {
                """
                |မြန်မာဘာသာအတွက် အင်္ဂလိပ်စာလုံး လုံးဝမပါစေရန်နှင့် Transliteration လုပ်ရန်။ 
                |ကိန်းဂဏန်းများကို စာလုံးပေါင်း၍ဖော်ပြရန် (ဥပမာ - 1 အစား 'တစ်'၊ 100 အစား 'တစ်ရာ'၊ 786 အစား 'ခုနှစ်ရာ ရှစ်ဆယ့်ခြောက်'၊ 3545 အစား 'သုံးထောင့်ငါးရာလေးဆယ့်ငါး')။
                |ဇာတ်လမ်းကို မူရင်းအစီအစဉ်အတိုင်း (Chronological Sequence) အတိအကျ ရေးသားရန်။
                """.trimMargin()
            } else {
                """
                |CRITICAL: The final output MUST BE ENTIRELY IN $language. Do NOT output any Myanmar language in the generated script!
                |Use natural and fluent $language.
                |Write strictly in chronological sequence.
                |Spell out all numbers using words instead of digits (e.g., use 'one' instead of '1', 'one hundred' instead of '100').
                |Avoid abbreviations and contractions (e.g., use 'do not' instead of 'don't').
                |Replace symbols with words for TTS-readability (e.g., use 'and' instead of '&', 'percent' instead of '%').
                """.trimMargin()
            }

            // Track failed parts
            val failedParts = mutableListOf<Int>()

            // Pass 1: Loop sequentially through each part
            for (i in 0 until parts) {
                if (specificPartsToGenerate != null && !specificPartsToGenerate.contains(i)) {
                    continue // Skip parts not requested
                }

                val startMs = i * segmentDurationMs
                val endMs = if (i == parts - 1) durationMs else (i + 1) * segmentDurationMs
                
                val startStr = formatTime(startMs)
                val endStr = formatTime(endMs)
                
                val startPercent = (15 + ((85f * i) / parts)).toInt()
                val targetEndPercent = (15 + ((85f * (i + 1)) / parts)).toInt()
                
                emit(GenerationUpdate.Progress(startPercent / 100f))
                emit(GenerationUpdate.Log("[$startPercent%] Generating Segment ${i + 1}/$parts ($startStr - $endStr)..."))
                emit(GenerationUpdate.ScriptChunk(i, "", clearFirst = true)) // Clear the placeholder

                val timingConstraint = if (isVideo) {
                    """
                    Timestamp $startStr မှ $endStr (Seconds: ${startMs/1000}s to ${endMs/1000}s) အတွင်းရှိ ဗီဒီယိုမြင်ကွင်းနှင့် အသံပါအဖြစ်အပျက်များကိုသာ အတိအကျ အခြေခံရေးသားပါ။ 
                    (အရေးကြီးသတိပေးချက်: ဤသတ်မှတ်ထားသော အချိန်အပိုင်းအခြားမတိုင်မီ (သို့) ပြီးနောက်တွင် ဖြစ်ပျက်ခဲ့သော အကြောင်းအရာများကို လုံးဝ (လုံးဝ) ထည့်သွင်းခြင်းမပြုရပါ။ 
                    သင်ရေးသားလိုက်သော အကြောင်းအရာများသည် $startStr နှင့် $endStr ကြားတွင် အမှန်တကယ်ဖြစ်ပျက်ခဲ့ခြင်း ရှိမရှိ သေချာစွာ စစ်ဆေးပြီးမှသာ ရေးသားပါ။)
                    """.trimIndent()
                } else {
                    """
                    Timestamp $startStr မှ $endStr (Seconds: ${startMs/1000}s to ${endMs/1000}s) အတွင်းရှိ အသံဖိုင်ပါ စကားပြောသံနှင့် အကြောင်းအရာများကိုသာ အတိအကျ အခြေခံရေးသားပါ။ 
                    (အရေးကြီးသတိပေးချက်: ဤသတ်မှတ်ထားသော အချိန်အပိုင်းအခြားမတိုင်မီ (သို့) ပြီးနောက်တွင် ဖြစ်ပျက်ခဲ့သော အကြောင်းအရာများကို လုံးဝ (လုံးဝ) ထည့်သွင်းခြင်းမပြုရပါ။ 
                    သင်ရေးသားလိုက်သော အကြောင်းအရာများသည် $startStr နှင့် $endStr ကြားတွင် အမှန်တကယ်ပါဝင်ခြင်း ရှိမရှိ သေချာစွာ စစ်ဆေးပြီးမှသာ ရေးသားပါ။)
                    """.trimIndent()
                }
                
                val systemInstruction = """
                    သင်သည် အလွန်အသေးစိတ်ကျသော PrGt's Script (ဇာတ်လမ်းအကျဉ်း) ဇာတ်ကြောင်းပြောသူ (Narrator) ဖြစ်သည်။
                    
                    ညွှန်ကြားချက်များ:
                    1. Input Media Analysis:
                    $modalityInstruction
                    2. Narrator POV: $povDetail
                    3. Script Tone: $toneDetail
                    4. Length Constraint: $lengthInstruction
                    5. Spoken Ready: Timestamp များ၊ Scene Number များ၊ ဇယားများ၊ ကွင်းစကွင်းပိတ် ညွှန်းဆိုချက်များ လုံးဝ (လုံးဝ) မထည့်ဘဲ၊ လူကိုယ်တိုင် အသံထွက်ဖတ်ရန် အဆင်သင့်ဖြစ်သော "ဇာတ်ကြောင်းပြော စာသားသက်သက်" ကိုသာ ထုတ်ပေးပါ။
                    6. Timing & Context: $timingConstraint
                    7. Language Constraints: $languageConstraint
                """.trimIndent()

                val prompt = "အထက်ပါ ညွှန်ကြားချက်များအတိုင်း $timingConstraint ကို PrGt's Script ရေးသားပေးပါ။"

                val jsonBody = JSONObject().apply {
                    put("system_instruction", JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply { put("text", systemInstruction) })
                        })
                    })
                    put("contents", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("parts", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("file_data", JSONObject().apply {
                                        put("mime_type", mediaMimeType ?: "video/mp4")
                                        put("file_uri", finalGeminiFileUri)
                                    })
                                })
                                put(JSONObject().apply {
                                    put("text", prompt)
                                })
                            })
                        })
                    })
                }

                val requestBody = jsonBody.toString().toRequestBody("application/json".toMediaTypeOrNull())

                val request = Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models/$actualModelName:streamGenerateContent?key=$apiKey")
                    .post(requestBody)
                    .build()

                var success = false
                var attempts = 0
                val maxAttempts = 3
                
                while (!success && attempts < maxAttempts) {
                    attempts++
                    if (attempts > 1) {
                        emit(GenerationUpdate.Log("Retrying Segment ${i + 1}/$parts (Attempt $attempts/$maxAttempts)..."))
                        kotlinx.coroutines.delay(2000)
                    }
                    
                    try {
                        client.newCall(request).execute().use { response ->
                            if (!response.isSuccessful) {
                                emit(GenerationUpdate.ApiCallResult(false))
                                if (attempts == maxAttempts) {
                                    val errorBody = response.body?.string()
                                    emit(GenerationUpdate.Log("API Error in Part ${i + 1}"))
                                    emit(GenerationUpdate.ScriptChunk(i, "API Error in Part ${i + 1}: ${response.code} ${response.message}\n$errorBody\n\n"))
                                }
                                return@use
                            }
                            
                            val responseString = response.body?.string() ?: ""
                            
                            try {
                                val jsonArray = JSONArray(responseString)
                                for (j in 0 until jsonArray.length()) {
                                    val chunk = jsonArray.getJSONObject(j)
                                    val candidates = chunk.optJSONArray("candidates")
                                    if (candidates != null && candidates.length() > 0) {
                                        val contentParts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
                                        if (contentParts != null && contentParts.length() > 0) {
                                            val text = contentParts.getJSONObject(0).optString("text", "")
                                            emit(GenerationUpdate.ScriptChunk(i, text))
                                        }
                                    }
                                }
                                success = true
                                emit(GenerationUpdate.ApiCallResult(true))
                            } catch (e: Exception) {
                                emit(GenerationUpdate.ApiCallResult(false))
                                if (attempts == maxAttempts) {
                                    emit(GenerationUpdate.Log("Error parsing output for Part ${i + 1}"))
                                    emit(GenerationUpdate.ScriptChunk(i, "Error parsing output for Part ${i + 1}: ${e.message}\nRaw Output: $responseString"))
                                }
                                return@use
                            }
                        }
                    } catch (e: Exception) {
                        emit(GenerationUpdate.ApiCallResult(false))
                        if (attempts == maxAttempts) {
                            emit(GenerationUpdate.Log("Network Error in Part ${i + 1}"))
                            emit(GenerationUpdate.ScriptChunk(i, "Network Error in Part ${i + 1}: ${e.message}\n\n"))
                        }
                    }
                }
                
                if (success) {
                    emit(GenerationUpdate.Log("[$targetEndPercent%] Segment ${i + 1}/$parts ($startStr - $endStr) completed."))
                } else {
                    failedParts.add(i)
                    emit(GenerationUpdate.Log("[$targetEndPercent%] Segment ${i + 1} failed after 3 attempts. Will retry at the end."))
                    emit(GenerationUpdate.ScriptChunk(i, "\n[အပိုင်း (${i + 1}) အား ၃ ကြိမ်တိုင်တိုင် ကြိုးစားသော်လည်း မအောင်မြင်ပါ။ နောက်ဆုံးမှ ပြန်လည်ကြိုးစားပါမည်။]\n", isError = true))
                    // Do not break; continue to the next part
                }
                
                val currentProgress = targetEndPercent / 100f
                emit(GenerationUpdate.Progress(currentProgress))
            }
            
            // Pass 2 & User Prompts: Retry failed parts
            var remainingFailedParts = failedParts.toList()
            
            while (remainingFailedParts.isNotEmpty()) {
                emit(GenerationUpdate.Log("Retrying ${remainingFailedParts.size} failed segment(s)..."))
                val newlyFailed = mutableListOf<Int>()
                
                for (i in remainingFailedParts) {
                    val startMs = i * segmentDurationMs
                    val endMs = if (i == parts - 1) durationMs else (i + 1) * segmentDurationMs
                    val startStr = formatTime(startMs)
                    val endStr = formatTime(endMs)
                    
                    emit(GenerationUpdate.Log("Retrying Segment ${i + 1}/$parts [$startStr - $endStr]..."))
                    emit(GenerationUpdate.ScriptChunk(i, "", clearFirst = true)) // Clear error message

                    val timingConstraint = if (isVideo) {
                        """
                        Timestamp $startStr မှ $endStr (Seconds: ${startMs/1000}s to ${endMs/1000}s) အတွင်းရှိ ဗီဒီယိုမြင်ကွင်းနှင့် အသံပါအဖြစ်အပျက်များကိုသာ အတိအကျ အခြေခံရေးသားပါ။ 
                        (အရေးကြီးသတိပေးချက်: ဤသတ်မှတ်ထားသော အချိန်အပိုင်းအခြားမတိုင်မီ (သို့) ပြီးနောက်တွင် ဖြစ်ပျက်ခဲ့သော အကြောင်းအရာများကို လုံးဝ (လုံးဝ) ထည့်သွင်းခြင်းမပြုရပါ။ 
                        သင်ရေးသားလိုက်သော အကြောင်းအရာများသည် $startStr နှင့် $endStr ကြားတွင် အမှန်တကယ်ဖြစ်ပျက်ခဲ့ခြင်း ရှိမရှိ သေချာစွာ စစ်ဆေးပြီးမှသာ ရေးသားပါ။)
                        """.trimIndent()
                    } else {
                        """
                        Timestamp $startStr မှ $endStr (Seconds: ${startMs/1000}s to ${endMs/1000}s) အတွင်းရှိ အသံဖိုင်ပါ စကားပြောသံနှင့် အကြောင်းအရာများကိုသာ အတိအကျ အခြေခံရေးသားပါ။ 
                        (အရေးကြီးသတိပေးချက်: ဤသတ်မှတ်ထားသော အချိန်အပိုင်းအခြားမတိုင်မီ (သို့) ပြီးနောက်တွင် ဖြစ်ပျက်ခဲ့သော အကြောင်းအရာများကို လုံးဝ (လုံးဝ) ထည့်သွင်းခြင်းမပြုရပါ။ 
                        သင်ရေးသားလိုက်သော အကြောင်းအရာများသည် $startStr နှင့် $endStr ကြားတွင် အမှန်တကယ်ပါဝင်ခြင်း ရှိမရှိ သေချာစွာ စစ်ဆေးပြီးမှသာ ရေးသားပါ။)
                        """.trimIndent()
                    }
                    
                    val systemInstruction = """
                        သင်သည် အလွန်အသေးစိတ်ကျသော PrGt's Script (ဇာတ်လမ်းအကျဉ်း) ဇာတ်ကြောင်းပြောသူ (Narrator) ဖြစ်သည်။
                        
                        ညွှန်ကြားချက်များ:
                        1. Input Media Analysis:
                        $modalityInstruction
                        2. Narrator POV: $povDetail
                        3. Script Tone: $toneDetail
                        4. Length Constraint: $lengthInstruction
                        5. Spoken Ready: Timestamp များ၊ Scene Number များ၊ ဇယားများ၊ ကွင်းစကွင်းပိတ် ညွှန်းဆိုချက်များ လုံးဝ (လုံးဝ) မထည့်ဘဲ၊ လူကိုယ်တိုင် အသံထွက်ဖတ်ရန် အဆင်သင့်ဖြစ်သော "ဇာတ်ကြောင်းပြော စာသားသက်သက်" ကိုသာ ထုတ်ပေးပါ။
                        6. Timing & Context: $timingConstraint
                        7. Language Constraints: $languageConstraint
                    """.trimIndent()

                    val prompt = "အထက်ပါ ညွှန်ကြားချက်များအတိုင်း $timingConstraint ကို PrGt's Script ရေးသားပေးပါ။"

                    val jsonBody = JSONObject().apply {
                        put("system_instruction", JSONObject().apply {
                            put("parts", JSONArray().apply {
                                put(JSONObject().apply { put("text", systemInstruction) })
                            })
                        })
                        put("contents", JSONArray().apply {
                            put(JSONObject().apply {
                                put("role", "user")
                                put("parts", JSONArray().apply {
                                    put(JSONObject().apply {
                                        put("file_data", JSONObject().apply {
                                            put("mime_type", mediaMimeType ?: "video/mp4")
                                            put("file_uri", finalGeminiFileUri)
                                        })
                                    })
                                    put(JSONObject().apply {
                                        put("text", prompt)
                                    })
                                })
                            })
                        })
                    }

                    val requestBody = jsonBody.toString().toRequestBody("application/json".toMediaTypeOrNull())

                    val request = Request.Builder()
                        .url("https://generativelanguage.googleapis.com/v1beta/models/$actualModelName:streamGenerateContent?key=$apiKey")
                        .post(requestBody)
                        .build()

                    var success = false
                    var attempts = 0
                    val maxAttempts = 3
                    
                    while (!success && attempts < maxAttempts) {
                        attempts++
                        if (attempts > 1) {
                            emit(GenerationUpdate.Log("Final Retry Segment ${i + 1}/$parts (Attempt $attempts/$maxAttempts)..."))
                            kotlinx.coroutines.delay(2000)
                        }
                        
                        try {
                            client.newCall(request).execute().use { response ->
                                if (!response.isSuccessful) {
                                    emit(GenerationUpdate.ApiCallResult(false))
                                    if (attempts == maxAttempts) {
                                        val errorBody = response.body?.string()
                                        emit(GenerationUpdate.Log("Final Retry Error in Part ${i + 1}"))
                                        emit(GenerationUpdate.ScriptChunk(i, "\n[Final Retry Error in Part ${i + 1}: ${response.code} ${response.message}\n$errorBody]\n", isError = true))
                                    }
                                    return@use
                                }
                                
                                val responseString = response.body?.string() ?: ""
                                
                                try {
                                    val jsonArray = JSONArray(responseString)
                                    for (j in 0 until jsonArray.length()) {
                                        val chunk = jsonArray.getJSONObject(j)
                                        val candidates = chunk.optJSONArray("candidates")
                                        if (candidates != null && candidates.length() > 0) {
                                            val contentParts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
                                            if (contentParts != null && contentParts.length() > 0) {
                                                val text = contentParts.getJSONObject(0).optString("text", "")
                                                emit(GenerationUpdate.ScriptChunk(i, text))
                                            }
                                        }
                                    }
                                    success = true
                                    emit(GenerationUpdate.ApiCallResult(true))
                                } catch (e: Exception) {
                                    emit(GenerationUpdate.ApiCallResult(false))
                                    if (attempts == maxAttempts) {
                                        emit(GenerationUpdate.Log("Error parsing final output for Part ${i + 1}"))
                                        emit(GenerationUpdate.ScriptChunk(i, "\n[Error parsing final output for Part ${i + 1}: ${e.message}]\n", isError = true))
                                    }
                                    return@use
                                }
                            }
                        } catch (e: Exception) {
                            emit(GenerationUpdate.ApiCallResult(false))
                            if (attempts == maxAttempts) {
                                emit(GenerationUpdate.Log("Network Error in Final Retry Part ${i + 1}"))
                                emit(GenerationUpdate.ScriptChunk(i, "\n[Network Error in Final Retry Part ${i + 1}: ${e.message}]\n", isError = true))
                            }
                        }
                    }
                    
                    if (!success) {
                        newlyFailed.add(i)
                    }
                }
                
                remainingFailedParts = newlyFailed
                
                if (remainingFailedParts.isNotEmpty()) {
                    if (retryDecisionChannel != null) {
                        emit(GenerationUpdate.RequireRetryDecision(remainingFailedParts))
                        // Suspend and wait for user's decision from the UI
                        val shouldRetry = retryDecisionChannel.receive()
                        if (!shouldRetry) {
                            emit(GenerationUpdate.Log("User chose to accept script with missing parts."))
                            break // Stop retrying, exit the loop
                        } else {
                            emit(GenerationUpdate.Log("User requested another retry for failed parts."))
                        }
                    } else {
                        break // No channel, just stop
                    }
                }
            }

            emit(GenerationUpdate.Log("[100%] All script segments generated successfully!"))
            emit(GenerationUpdate.Progress(1.0f))
            emit(GenerationUpdate.Complete)

        } catch (e: Exception) {
            emit(GenerationUpdate.Log("Exception: ${e.message}"))
            emit(GenerationUpdate.Error(e.message ?: "Unknown error"))
            Log.e("GeminiApiClient", "Error", e)
        }
    }.flowOn(Dispatchers.IO)
    
    private fun checkFileState(apiKey: String, name: String): String {
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/$name?key=$apiKey")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val json = JSONObject(response.body?.string() ?: "{}")
                return json.optString("state", "PROCESSING")
            }
        }
        return "PROCESSING"
    }

    private fun getFileSize(uri: Uri): Long {
        var size = -1L
        try {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (sizeIndex != -1 && cursor.moveToFirst()) {
                    size = cursor.getLong(sizeIndex)
                }
            }
        } catch (e: Exception) {
            Log.e("GeminiApiClient", "Failed to get file size via query", e)
        }
        if (size <= 0) {
            try {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
                    size = fd.statSize
                }
            } catch (e: Exception) {
                Log.e("GeminiApiClient", "Failed to get file size via openFileDescriptor", e)
            }
        }
        if (size <= 0) {
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    var count = 0L
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        count += read
                    }
                    size = count
                }
            } catch (e: Exception) {
                Log.e("GeminiApiClient", "Failed to calculate file size via stream", e)
            }
        }
        return size
    }

    sealed class UploadResult {
        data class Success(val uri: String, val name: String) : UploadResult()
        data class Failure(val errorDetail: String) : UploadResult()
    }

    private fun uploadFile(apiKey: String, fileUri: Uri, mimeType: String, onProgress: (Float, Long, Long) -> Unit): UploadResult {
        val fileSize = getFileSize(fileUri)
        if (fileSize <= 0) {
            val err = "Invalid file size ($fileSize bytes). Could not read selected media."
            Log.e("GeminiApiClient", "Cannot upload file: $err")
            return UploadResult.Failure(err)
        }

        val initJson = JSONObject().apply {
            put("file", JSONObject().apply {
                put("display_name", "upload_${System.currentTimeMillis()}")
            })
        }
        
        val requestBodyInit = initJson.toString().toRequestBody("application/json".toMediaTypeOrNull())
        
        val initRequest = Request.Builder()
            .url("https://generativelanguage.googleapis.com/upload/v1beta/files?uploadType=resumable&key=$apiKey")
            .post(requestBodyInit)
            .addHeader("X-Goog-Upload-Protocol", "resumable")
            .addHeader("X-Goog-Upload-Command", "start")
            .addHeader("X-Goog-Upload-Header-Content-Length", fileSize.toString())
            .addHeader("X-Goog-Upload-Header-Content-Type", mimeType)
            .build()

        var uploadUrl = ""
        try {
            client.newCall(initRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    val errBody = response.body?.string() ?: "Empty body"
                    val errMsg = "Upload init failed (HTTP ${response.code} ${response.message}): $errBody"
                    Log.e("GeminiApiClient", errMsg)
                    return UploadResult.Failure(errMsg)
                }
                uploadUrl = response.header("X-Goog-Upload-URL") ?: ""
                if (uploadUrl.isEmpty()) {
                    val errMsg = "Gemini server did not return X-Goog-Upload-URL header."
                    Log.e("GeminiApiClient", errMsg)
                    return UploadResult.Failure(errMsg)
                }
            }
        } catch (e: Exception) {
            val errMsg = "Network exception during upload init: ${e.message}"
            Log.e("GeminiApiClient", errMsg, e)
            return UploadResult.Failure(errMsg)
        }

        val fileRequestBody = object : RequestBody() {
            override fun contentType() = mimeType.toMediaTypeOrNull()
            override fun contentLength() = fileSize
            override fun writeTo(sink: BufferedSink) {
                context.contentResolver.openInputStream(fileUri)?.use { input ->
                    val buffer = ByteArray(64 * 1024) // 64 KB buffer for precise progress tracking
                    var uploadedBytes = 0L
                    var read: Int
                    var lastReportedPercent = -1
                    while (input.read(buffer).also { read = it } != -1) {
                        sink.write(buffer, 0, read)
                        sink.flush()
                        uploadedBytes += read
                        val ratio = if (fileSize > 0) uploadedBytes.toFloat() / fileSize.toFloat() else 0f
                        val currentPercent = (ratio * 100).toInt()
                        if (currentPercent != lastReportedPercent) {
                            lastReportedPercent = currentPercent
                            onProgress(ratio.coerceIn(0f, 1f), uploadedBytes, fileSize)
                        }
                    }
                }
            }
        }

        val uploadRequest = Request.Builder()
            .url(uploadUrl)
            .post(fileRequestBody)
            .addHeader("X-Goog-Upload-Protocol", "resumable")
            .addHeader("X-Goog-Upload-Command", "upload, finalize")
            .addHeader("X-Goog-Upload-Offset", "0")
            .build()

        try {
            client.newCall(uploadRequest).execute().use { response ->
                val respBody = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val responseJson = JSONObject(respBody)
                    val fileObj = responseJson.optJSONObject("file")
                    val uri = fileObj?.optString("uri")
                    val name = fileObj?.optString("name")
                    if (!uri.isNullOrEmpty() && !name.isNullOrEmpty()) {
                        return UploadResult.Success(uri, name)
                    } else {
                        val errMsg = "Upload succeeded but file info missing in response: $respBody"
                        Log.e("GeminiApiClient", errMsg)
                        return UploadResult.Failure(errMsg)
                    }
                } else {
                    val errMsg = "Binary upload failed (HTTP ${response.code} ${response.message}): $respBody"
                    Log.e("GeminiApiClient", errMsg)
                    return UploadResult.Failure(errMsg)
                }
            }
        } catch (e: Exception) {
            val errMsg = "Binary upload exception: ${e.message}"
            Log.e("GeminiApiClient", errMsg, e)
            return UploadResult.Failure(errMsg)
        }
    }
}
