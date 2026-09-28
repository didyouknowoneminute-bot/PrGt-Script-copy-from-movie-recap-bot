package com.example

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
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
    data class ApiCallResult(
        val success: Boolean,
        val isSpare: Boolean = false,
        val mainTotal: Int = 0,
        val mainSuccess: Int = 0,
        val mainFailed: Int = 0,
        val spareTotal: Int = 0,
        val spareSuccess: Int = 0,
        val spareFailed: Int = 0,
        val usedSpareKey: Boolean = false
    ) : GenerationUpdate()
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
        spareApiKey: String = "",
        rpdEnabled: Boolean = false,
        rpdLimit: Int = 20,
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
        specificPartsToGenerate: List<Int>? = null,
        rpm: Int = 20
    ): Flow<GenerationUpdate> = channelFlow {
        send(GenerationUpdate.Progress(0.0f))
        
        if (apiKey.isBlank()) {
            send(GenerationUpdate.Error("API Key is missing. Please add it in settings."))
            return@channelFlow
        }
        if (mediaUri == null && existingGeminiFileUri == null) {
            send(GenerationUpdate.Error("Please select a video or audio file first."))
            return@channelFlow
        }

        try {
            val actualModelName = getModelName(modelName)
            val finalGeminiFileUri: String
            var durationMs = 30000L // Default fallback
            
            send(GenerationUpdate.Log("AI Engine: $actualModelName"))
            
            if (existingGeminiFileUri != null && existingDurationMs != null) {
                send(GenerationUpdate.Log("[15%] Using existing cached media on server..."))
                send(GenerationUpdate.Progress(0.15f))
                finalGeminiFileUri = existingGeminiFileUri
                durationMs = existingDurationMs
            } else {
                send(GenerationUpdate.Log("[0%] Preparing media upload..."))
                send(GenerationUpdate.Progress(0.01f))
                
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
                    }
                }
                
                val fileUriInfo = when (uploadResult) {
                    is UploadResult.Success -> Pair(uploadResult.uri, uploadResult.name)
                    is UploadResult.Failure -> {
                        send(GenerationUpdate.Error("Upload failed:\n${uploadResult.errorDetail}"))
                        return@channelFlow
                    }
                }
                
                send(GenerationUpdate.Log("[12%] Media upload complete. File cached on server."))
                send(GenerationUpdate.Progress(0.12f))

                // Wait for video processing if it's a video
                if (mediaMimeType?.startsWith("video") == true) {
                    send(GenerationUpdate.Log("[13%] Waiting for Gemini server to process video..."))
                    send(GenerationUpdate.Progress(0.13f))
                    var isActive = false
                    var pollCount = 0
                    while(!isActive) {
                        val state = checkFileState(apiKey, fileUriInfo.second)
                        if (state == "ACTIVE") {
                            isActive = true
                        } else if (state == "FAILED") {
                            send(GenerationUpdate.Error("Gemini failed to process the uploaded video."))
                            return@channelFlow
                        } else {
                            pollCount++
                            val currentProcProgress = (0.13f + (pollCount * 0.01f)).coerceAtMost(0.15f)
                            send(GenerationUpdate.Progress(currentProcProgress))
                            send(GenerationUpdate.Log("[${(currentProcProgress * 100).toInt()}%] Gemini is indexing video frames (State: $state)... waiting."))
                            delay(5000)
                        }
                    }
                    send(GenerationUpdate.Log("[15%] Video processing and frame indexing complete."))
                }

                send(GenerationUpdate.Progress(0.15f))

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
                    send(GenerationUpdate.Log("(Note: Could not detect exact duration, defaulting to 30s)"))
                }
                
                // Emit FileInfo for saving to database
                send(GenerationUpdate.FileInfo(fileUriInfo.first, mediaMimeType ?: "video/mp4", durationMs))
                finalGeminiFileUri = fileUriInfo.first
            }

            val parts = partsCount.toInt()
            send(GenerationUpdate.InitParts(parts))
            
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
                    "အဓိကဇာတ်ကောင်၏ ရှုထောင့်အမြင် (First-Person POV / 'ကျွန်တော်/ကျွန်မ/ငါ') မှ ကိုယ်တိုင်ကြုံတွေ့နေရသကဲ့သို့ စကားပြောသဘာဝအတိုင်း ခံစားချက်ပြင်းပြစွာ ပြန်ပြောပြရမည်။ (စာအရေးအသားပုံစံ လုံးဝမသုံးဘဲ ကိုယ်တွေ့ပြောစကားအတိုင်း ပြောပြရမည်။)"
                pov.contains("Third", ignoreCase = true) || pov.contains("Omniscient", ignoreCase = true) -> 
                    "ပြင်ပဇာတ်ကြောင်းပြောသူအမြင် (Third-Person / Omniscient Narrator) မှ 'သူ'၊ 'သူမ'၊ 'သူတို့' စသည်ဖြင့် အရာအားလုံးကို ခြုံငုံသိမြင်နေသော ပုံစံဖြင့် မိတ်ဆွေတစ်ယောက်ကို ဇာတ်လမ်းပြန်ပြောပြနေသလို စကားပြောသဘာဝအတိုင်း ဇာတ်ကြောင်းပြောပြရမည်။ (စာအရေးအသားပုံစံ လုံးဝမသုံးရ။)"
                pov.contains("Second", ignoreCase = true) -> 
                    "ကြည့်ရှုသူ/နားဆင်သူအား တိုက်ရိုက်ပြောဆိုသော အမြင် (Second-Person POV / 'မင်း/ခင်ဗျား/သင်') ဖြင့် စကားပြောသဘာဝအတိုင်း စိတ်ဝင်စားဖွယ် ပြောပြရမည်။ (စာအရေးအသားပုံစံ လုံးဝမသုံးရ။)"
                else -> "$pov ရှုထောင့်အမြင်အတိုင်း စကားပြောသဘာဝအတိုင်း ဇာတ်ကြောင်းပြောပြရမည်။"
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
                |၁။ စကားပြောပုံစံ စည်းကမ်း (Spoken Burmese): မြန်မာစာပေသုံး စာအရေးအသားပုံစံ (Formal Written/Literary Style) ကို လုံးဝ (လုံးဝ) မသုံးရ။ လူအချင်းချင်း စိတ်ဝင်စားဖွယ် ဇာတ်လမ်းပြန်ပြောပြနေသော သဘာဝကျသည့် လူပြောစကား/စကားပြောပုံစံ (Conversational Spoken Voiceover Style) ဖြင့်သာ ရေးသားရမည်။
                |- ဝါကျအဆုံးတွင် 'သည်' ကို လုံးဝမသုံးရ -> 'တယ် / ခဲ့တယ် / သွားတယ် / ဖြစ်သွားတယ်' သာ သုံးရမည်။
                |- '၏' ကို လုံးဝမသုံးရ -> 'ရဲ့' သာ သုံးရမည်။
                |- '၌' ကို လုံးဝမသုံးရ -> 'မှာ' သာ သုံးရမည်။
                |- '၍' သို့မဟုတ် 'ပြီးနောက်' ကို လုံးဝမသုံးရ -> 'ပြီးတော့ / အဲ့ဒီနောက်' သာ သုံးရမည်။
                |- 'သောကြောင့်' သို့မဟုတ် 'သဖြင့်' ကို လုံးဝမသုံးရ -> 'မို့လို့ / ကြောင့်' သာ သုံးရမည်။
                |- 'လျက်' ကို လုံးဝမသုံးရ -> 'ရင်းနဲ့ / နေတုန်း' သာ သုံးရမည်။
                |၂။ မြန်မာဘာသာအတွက် အင်္ဂလိပ်စာလုံး လုံးဝမပါစေရန်နှင့် Transliteration လုပ်ရန်။ 
                |၃။ ကိန်းဂဏန်းများကို စာလုံးပေါင်း၍ဖော်ပြရန် (ဥပမာ - 1 အစား 'တစ်'၊ 100 အစား 'တစ်ရာ'၊ 786 အစား 'ခုနှစ်ရာ ရှစ်ဆယ့်ခြောက်'၊ 3545 အစား 'သုံးထောင့်ငါးရာလေးဆယ့်ငါး')။
                |၄။ ဇာတ်လမ်းကို မူရင်းအစီအစဉ်အတိုင်း (Chronological Sequence) အတိအကျ ရေးသားရန်။
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

            // Track failed parts and completed parts
            val failedParts = java.util.Collections.synchronizedList(mutableListOf<Int>())
            val successfulCount = java.util.concurrent.atomic.AtomicInteger(0)
            val failedCount = java.util.concurrent.atomic.AtomicInteger(0)
            val workingCount = java.util.concurrent.atomic.AtomicInteger(0)
            val partsToProcess = (0 until parts).filter { specificPartsToGenerate == null || specificPartsToGenerate.contains(it) }
            val totalToProcess = partsToProcess.size

            send(GenerationUpdate.Log("Starting generation with $rpm RPM (Pipelined Parallel mode)..."))
            if (rpdEnabled) {
                val mStatus = RpdTracker.getStatus(context, apiKey, rpdLimit)
                send(GenerationUpdate.Log("ℹ️ RPD Cache: Main Key has ${mStatus.usedCount}/$rpdLimit requests used today (${mStatus.remainingCount} available)."))
                send(GenerationUpdate.Log("ℹ️ Daily Quota Reset: ${mStatus.resetTimeDescription} (${mStatus.hoursUntilReset}h ${mStatus.minutesUntilReset}m until midnight PST/PDT)."))
                if (spareApiKey.isNotBlank()) {
                    val sStatus = RpdTracker.getStatus(context, spareApiKey, rpdLimit)
                    send(GenerationUpdate.Log("ℹ️ RPD Cache: Spare Key has ${sStatus.usedCount}/$rpdLimit requests used today (${sStatus.remainingCount} available)."))
                }
            }

            // Rate limiter: sliding window + strict minimum interval between ANY two requests
            val rateLimitMutex = Mutex()
            var lastRequestTimeMs = 0L
            val requestTimestamps = mutableListOf<Long>()
            val minDelayBetweenRequestsMs = (60_000L / maxOf(1, rpm))

            suspend fun acquireRateLimitSlot() {
                rateLimitMutex.withLock {
                    while (true) {
                        val now = System.currentTimeMillis()
                        // 1. Remove timestamps older than 60 seconds
                        requestTimestamps.removeAll { now - it >= 60_000L }

                        // 2. Strict minimum spacing between any two consecutive requests
                        val timeSinceLast = now - lastRequestTimeMs
                        val intervalWait = if (lastRequestTimeMs > 0 && timeSinceLast < minDelayBetweenRequestsMs) {
                            minDelayBetweenRequestsMs - timeSinceLast
                        } else {
                            0L
                        }

                        // 3. Sliding window: wait until oldest request exits the 60-second window
                        val windowWait = if (requestTimestamps.size >= rpm) {
                            val oldest = requestTimestamps.firstOrNull() ?: now
                            maxOf(100L, 60_000L - (now - oldest) + 50L)
                        } else {
                            0L
                        }

                        val waitTime = maxOf(intervalWait, windowWait)
                        if (waitTime <= 0L) {
                            val currentTime = System.currentTimeMillis()
                            requestTimestamps.add(currentTime)
                            lastRequestTimeMs = currentTime
                            return@withLock
                        }

                        delay(waitTime)
                    }
                }
            }

            // Key and RPD state management
            val keyMutex = Mutex()
            var activeApiKey = apiKey
            var activeGeminiFileUri = finalGeminiFileUri
            val mainInitialUsed = if (rpdEnabled) RpdTracker.getStatus(context, apiKey, rpdLimit).usedCount else 0
            val spareInitialUsed = if (rpdEnabled && spareApiKey.isNotBlank()) RpdTracker.getStatus(context, spareApiKey, rpdLimit).usedCount else 0

            var mainTotalCalls = mainInitialUsed
            var mainSuccessCalls = 0
            var mainFailedCalls = 0
            var spareTotalCalls = spareInitialUsed
            var spareSuccessCalls = 0
            var spareFailedCalls = 0
            var hasSwitchedToSpare = false
            var isSpareMediaUploaded = false

            suspend fun getOrSwitchKeyAndFile(): Triple<String, String, Boolean> {
                return keyMutex.withLock {
                    if (rpdEnabled && !hasSwitchedToSpare && mainTotalCalls >= rpdLimit) {
                        if (spareApiKey.isNotBlank()) {
                            send(GenerationUpdate.Log("⚠️ Main API Key RPD limit ($rpdLimit) ပြည့်သွားပါပြီ (Total: $mainTotalCalls, Success: $mainSuccessCalls, Failed: $mainFailedCalls)။"))
                            send(GenerationUpdate.Log("🔄 Spare API Key သို့ ပြောင်းလဲအသုံးပြုနေပါသည်..."))
                            
                            if (!isSpareMediaUploaded && mediaUri != null) {
                                send(GenerationUpdate.Log("📤 Spare API Key ဖြင့် Media ဖိုင်အား ဆာဗာသို့ ပြန်လည် Upload တင်နေပါသည်..."))
                                val uploadResult = uploadFile(spareApiKey, mediaUri, mediaMimeType ?: "video/mp4") { _, _, _ -> }
                                val spareFileUriInfo = when (uploadResult) {
                                    is UploadResult.Success -> Pair(uploadResult.uri, uploadResult.name)
                                    is UploadResult.Failure -> {
                                        send(GenerationUpdate.Error("Spare API Key ဖြင့် Upload တင်ခြင်း မအောင်မြင်ပါ:\n${uploadResult.errorDetail}"))
                                        throw Exception("Spare API Key upload failed: ${uploadResult.errorDetail}")
                                    }
                                }
                                
                                if (mediaMimeType?.startsWith("video") == true) {
                                    send(GenerationUpdate.Log("[Spare Key] Waiting for Gemini server to process video..."))
                                    var isActive = false
                                    while (!isActive) {
                                        val state = checkFileState(spareApiKey, spareFileUriInfo.second)
                                        if (state == "ACTIVE") {
                                            isActive = true
                                        } else if (state == "FAILED") {
                                            send(GenerationUpdate.Error("Gemini server failed to process video with Spare API Key."))
                                            throw Exception("Gemini server failed to process video with Spare API Key")
                                        } else {
                                            delay(3000)
                                        }
                                    }
                                }
                                activeGeminiFileUri = spareFileUriInfo.first
                                isSpareMediaUploaded = true
                                send(GenerationUpdate.Log("✅ Spare API Key ဖြင့် Media ဖိုင် အဆင်သင့်ဖြစ်ပါပြီ။"))
                            }
                            
                            activeApiKey = spareApiKey
                            hasSwitchedToSpare = true
                            send(GenerationUpdate.Log("🚀 ကျန်ရှိသော Script အပိုင်းများကို Spare API Key ဖြင့် ဆက်လက်ထုတ်လုပ်ပါမည်။"))
                        } else {
                            send(GenerationUpdate.Log("⚠️ Main API Key RPD limit ($rpdLimit) ပြည့်သွားသော်လည်း Spare API Key ထည့်သွင်းထားခြင်း မရှိပါ။ Settings တွင် Spare API Key ထည့်သွင်းပေးပါ။"))
                        }
                    }
                    Triple(activeApiKey, activeGeminiFileUri, hasSwitchedToSpare)
                }
            }

            suspend fun recordCallOutcome(isSpare: Boolean, success: Boolean) {
                keyMutex.withLock {
                    if (!isSpare) {
                        mainTotalCalls++
                        if (success) mainSuccessCalls++ else mainFailedCalls++
                        if (rpdEnabled) {
                            RpdTracker.recordCall(context, apiKey)
                        }
                    } else {
                        spareTotalCalls++
                        if (success) spareSuccessCalls++ else spareFailedCalls++
                        if (rpdEnabled && spareApiKey.isNotBlank()) {
                            RpdTracker.recordCall(context, spareApiKey)
                        }
                    }
                    val currentRpdTotal = mainTotalCalls + spareTotalCalls
                    if (rpdEnabled) {
                        val keyName = if (isSpare) "Spare Key" else "Main Key"
                        val countOnKey = if (isSpare) spareTotalCalls else mainTotalCalls
                        val remainingOnKey = (rpdLimit - countOnKey).coerceAtLeast(0)
                        send(GenerationUpdate.Log("ℹ️ RPD [$keyName]: Request #$countOnKey/$rpdLimit (Available: $remainingOnKey, Total: $currentRpdTotal, Success: ${mainSuccessCalls + spareSuccessCalls}, Failed: ${mainFailedCalls + spareFailedCalls})"))
                    }
                    send(GenerationUpdate.ApiCallResult(
                        success = success,
                        isSpare = isSpare,
                        mainTotal = mainTotalCalls,
                        mainSuccess = mainSuccessCalls,
                        mainFailed = mainFailedCalls,
                        spareTotal = spareTotalCalls,
                        spareSuccess = spareSuccessCalls,
                        spareFailed = spareFailedCalls,
                        usedSpareKey = hasSwitchedToSpare
                    ))
                }
            }

            var remainingFailedParts = listOf<Int>()
            coroutineScope {
                val statusTimerJob = launch {
                    var minute = 1
                    while (isActive) {
                        delay(60_000L)
                        val success = successfulCount.get()
                        val failed = failedCount.get()
                        val working = workingCount.get().coerceAtLeast(0)
                        val pending = (totalToProcess - (success + failed + working)).coerceAtLeast(0)
                        val remaining = (totalToProcess - success).coerceAtLeast(0)
                        val timeLabel = String.format(java.util.Locale.US, "%02d:00", minute)
                        send(GenerationUpdate.Log("📊 [Status $timeLabel] Success: $success | Failed: $failed | Working: $working | Pending: $pending | Remaining: $remaining (Total: $totalToProcess)"))
                        minute++
                    }
                }

                try {
                    coroutineScope {
                        partsToProcess.forEachIndexed { index, i ->
                            val startMs = i * segmentDurationMs
                            val endMs = if (i == parts - 1) durationMs else (i + 1) * segmentDurationMs
                            val startStr = formatTime(startMs)
                            val endStr = formatTime(endMs)

                            send(GenerationUpdate.ScriptChunk(i, "", clearFirst = true))
                            send(GenerationUpdate.Log("[$actualModelName] Queued Part ${i + 1}/$parts ($startStr - $endStr)..."))

                            launch {
                                workingCount.incrementAndGet()
                                try {

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
                            5. Spoken Ready: Timestamp များ၊ Scene Number များ၊ ဇယားများ၊ ကွင်းစကွင်းပိတ် ညွှန်းဆိုချက်များ လုံးဝ (လုံးဝ) မထည့်ဘဲ၊ လူကိုယ်တိုင် အသံထွက်ဖတ်ရန် အဆင်သင့်ဖြစ်သော စကားပြောသဘာဝအတိုင်း "ဇာတ်ကြောင်းပြော စာသားသက်သက်" ကိုသာ ထုတ်ပေးပါ။ (စာအုပ်ဖတ်ပြသည့် စာစကား/စာအရေးအသားပုံစံ လုံးဝမဖြစ်စေရ၊ လူပြောစကားသဘာဝအတိုင်းသာ ဖြစ်ရမည်။)
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
                                            if (isVideo) {
                                                val startSec = startMs / 1000
                                                val endSec = (endMs / 1000).coerceAtLeast(startSec + 1)
                                                put("video_metadata", JSONObject().apply {
                                                    put("start_offset", JSONObject().apply {
                                                        put("seconds", startSec)
                                                    })
                                                    put("end_offset", JSONObject().apply {
                                                        put("seconds", endSec)
                                                    })
                                                })
                                            }
                                        })
                                        put(JSONObject().apply {
                                            put("text", prompt)
                                        })
                                    })
                                })
                            })
                        }

                        var success = false
                        var attempts = 0
                        val maxAttempts = 3
                        
                        while (!success && attempts < maxAttempts) {
                            attempts++
                            if (attempts > 1) {
                                send(GenerationUpdate.Log("Retrying Segment ${i + 1}/$parts (Attempt $attempts/$maxAttempts)..."))
                            }
                            
                            acquireRateLimitSlot()
                            
                            val (callApiKey, callFileUri, isSpareCall) = getOrSwitchKeyAndFile()
                            val keyLabel = if (isSpareCall) " [Spare Key]" else ""
                            send(GenerationUpdate.Log("🚀 Sending Part ${i + 1}/$parts ($startStr - $endStr)${if (attempts > 1) " [Retry $attempts]" else ""}$keyLabel to AI..."))
                            
                            val requestBody = createSegmentRequestBody(
                                systemInstruction = systemInstruction,
                                prompt = prompt,
                                mediaMimeType = mediaMimeType,
                                fileUri = callFileUri,
                                isVideo = isVideo,
                                startMs = startMs,
                                endMs = endMs
                            )
                            val request = Request.Builder()
                                .url("https://generativelanguage.googleapis.com/v1beta/models/$actualModelName:streamGenerateContent?key=$callApiKey")
                                .post(requestBody)
                                .build()
                            
                            try {
                                client.newCall(request).execute().use { response ->
                                    if (!response.isSuccessful) {
                                        recordCallOutcome(isSpareCall, false)
                                        val errorBody = response.body?.string() ?: ""
                                        val errorDetail = extractApiErrorMessage(response.code, response.message, errorBody)
                                        send(GenerationUpdate.Log("❌ Part ${i + 1} (Attempt $attempts/$maxAttempts) Failed: HTTP ${response.code} - $errorDetail"))
                                        if (attempts == maxAttempts) {
                                            send(GenerationUpdate.ScriptChunk(i, "API Error in Part ${i + 1}: ${response.code} ${response.message}\n$errorBody\n\n", isError = true))
                                        }
                                        return@use
                                    }
                                    
                                    val responseString = response.body?.string() ?: ""
                                    
                                    try {
                                        val jsonArray = JSONArray(responseString)
                                        val partTextBuilder = StringBuilder()
                                        for (j in 0 until jsonArray.length()) {
                                            val chunk = jsonArray.getJSONObject(j)
                                            val candidates = chunk.optJSONArray("candidates")
                                            if (candidates != null && candidates.length() > 0) {
                                                val contentParts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
                                                if (contentParts != null && contentParts.length() > 0) {
                                                    val text = contentParts.getJSONObject(0).optString("text", "")
                                                    partTextBuilder.append(text)
                                                }
                                            }
                                        }
                                        val resultText = partTextBuilder.toString()
                                        if (resultText.isNotBlank()) {
                                            send(GenerationUpdate.ScriptChunk(i, resultText))
                                            success = true
                                            recordCallOutcome(isSpareCall, true)
                                        } else {
                                            throw Exception("Empty response candidate text")
                                        }
                                    } catch (e: Exception) {
                                        recordCallOutcome(isSpareCall, false)
                                        send(GenerationUpdate.Log("❌ Part ${i + 1} (Attempt $attempts/$maxAttempts) Parsing Error: ${e.message ?: "Unknown parse error"}"))
                                        if (attempts == maxAttempts) {
                                            send(GenerationUpdate.ScriptChunk(i, "Error parsing output for Part ${i + 1}: ${e.message}\nRaw Output: $responseString", isError = true))
                                        }
                                        return@use
                                    }
                                }
                            } catch (e: Exception) {
                                recordCallOutcome(isSpareCall, false)
                                send(GenerationUpdate.Log("❌ Part ${i + 1} (Attempt $attempts/$maxAttempts) Network Error: ${e.javaClass.simpleName} - ${e.message ?: "Connection failed"}"))
                                if (attempts == maxAttempts) {
                                    send(GenerationUpdate.ScriptChunk(i, "Network Error in Part ${i + 1}: ${e.message}\n\n", isError = true))
                                }
                            }
                        }
                        
                        if (success) {
                            val currentSuccess = successfulCount.incrementAndGet()
                            val currentProgress = 0.15f + (0.85f * (currentSuccess.toFloat() / maxOf(1, totalToProcess)))
                            send(GenerationUpdate.Progress(currentProgress))
                            send(GenerationUpdate.Log("✅ Part ${i + 1}/$parts ($startStr - $endStr) completed successfully."))
                        } else {
                            failedCount.incrementAndGet()
                            failedParts.add(i)
                            send(GenerationUpdate.Log("❌ Part ${i + 1} failed after $maxAttempts attempts."))
                            send(GenerationUpdate.ScriptChunk(i, "\n[အပိုင်း (${i + 1}) အား ၃ ကြိမ်တိုင်တိုင် ကြိုးစားသော်လည်း မအောင်မြင်ပါ။]\n", isError = true))
                        }
                    } finally {
                        workingCount.decrementAndGet()
                    }
                }
            }
        }
        
        // Pass 2 & User Prompts: Retry failed parts
        remainingFailedParts = failedParts.toList()
        
        while (remainingFailedParts.isNotEmpty()) {
            send(GenerationUpdate.Log("Retrying ${remainingFailedParts.size} failed segment(s)..."))
            val newlyFailed = mutableListOf<Int>()
            
            for (i in remainingFailedParts) {
                val startMs = i * segmentDurationMs
                val endMs = if (i == parts - 1) durationMs else (i + 1) * segmentDurationMs
                val startStr = formatTime(startMs)
                val endStr = formatTime(endMs)
                
                send(GenerationUpdate.Log("Retrying Segment ${i + 1}/$parts [$startStr - $endStr]..."))
                send(GenerationUpdate.ScriptChunk(i, "", clearFirst = true)) // Clear error message

                workingCount.incrementAndGet()
                failedCount.decrementAndGet()
                var success = false
                try {

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
                        5. Spoken Ready: Timestamp များ၊ Scene Number များ၊ ဇယားများ၊ ကွင်းစကွင်းပိတ် ညွှန်းဆိုချက်များ လုံးဝ (လုံးဝ) မထည့်ဘဲ၊ လူကိုယ်တိုင် အသံထွက်ဖတ်ရန် အဆင်သင့်ဖြစ်သော စကားပြောသဘာဝအတိုင်း "ဇာတ်ကြောင်းပြော စာသားသက်သက်" ကိုသာ ထုတ်ပေးပါ။ (စာအုပ်ဖတ်ပြသည့် စာစကား/စာအရေးအသားပုံစံ လုံးဝမဖြစ်စေရ၊ လူပြောစကားသဘာဝအတိုင်းသာ ဖြစ်ရမည်။)
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
                                        if (isVideo) {
                                            val startSec = startMs / 1000
                                            val endSec = (endMs / 1000).coerceAtLeast(startSec + 1)
                                            put("video_metadata", JSONObject().apply {
                                                put("start_offset", JSONObject().apply {
                                                    put("seconds", startSec)
                                                })
                                                put("end_offset", JSONObject().apply {
                                                    put("seconds", endSec)
                                                })
                                            })
                                        }
                                    })
                                    put(JSONObject().apply {
                                        put("text", prompt)
                                    })
                                })
                            })
                        })
                    }

                    success = false
                    var attempts = 0
                    val maxAttempts = 3
                    
                    while (!success && attempts < maxAttempts) {
                        attempts++
                        if (attempts > 1) {
                            send(GenerationUpdate.Log("Final Retry Segment ${i + 1}/$parts (Attempt $attempts/$maxAttempts)..."))
                        }
                        
                        acquireRateLimitSlot()
                        
                        val (callApiKey, callFileUri, isSpareCall) = getOrSwitchKeyAndFile()
                        val keyLabel = if (isSpareCall) " [Spare Key]" else ""
                        send(GenerationUpdate.Log("🚀 Sending Final Retry Part ${i + 1}/$parts ($startStr - $endStr)${if (attempts > 1) " [Attempt $attempts]" else ""}$keyLabel to AI..."))
                        
                        val requestBody = createSegmentRequestBody(
                            systemInstruction = systemInstruction,
                            prompt = prompt,
                            mediaMimeType = mediaMimeType,
                            fileUri = callFileUri,
                            isVideo = isVideo,
                            startMs = startMs,
                            endMs = endMs
                        )
                        val request = Request.Builder()
                            .url("https://generativelanguage.googleapis.com/v1beta/models/$actualModelName:streamGenerateContent?key=$callApiKey")
                            .post(requestBody)
                            .build()
                        
                        try {
                            client.newCall(request).execute().use { response ->
                                if (!response.isSuccessful) {
                                    recordCallOutcome(isSpareCall, false)
                                    val errorBody = response.body?.string() ?: ""
                                    val errorDetail = extractApiErrorMessage(response.code, response.message, errorBody)
                                    send(GenerationUpdate.Log("❌ Final Retry Part ${i + 1} (Attempt $attempts/$maxAttempts) Failed: HTTP ${response.code} - $errorDetail"))
                                    if (attempts == maxAttempts) {
                                        send(GenerationUpdate.ScriptChunk(i, "\n[Final Retry Error in Part ${i + 1}: ${response.code} ${response.message}\n$errorBody]\n", isError = true))
                                    }
                                    return@use
                                }
                                
                                val responseString = response.body?.string() ?: ""
                                
                                try {
                                    val jsonArray = JSONArray(responseString)
                                    val partTextBuilder = StringBuilder()
                                    for (j in 0 until jsonArray.length()) {
                                        val chunk = jsonArray.getJSONObject(j)
                                        val candidates = chunk.optJSONArray("candidates")
                                        if (candidates != null && candidates.length() > 0) {
                                            val contentParts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
                                            if (contentParts != null && contentParts.length() > 0) {
                                                val text = contentParts.getJSONObject(0).optString("text", "")
                                                partTextBuilder.append(text)
                                            }
                                        }
                                    }
                                    val resultText = partTextBuilder.toString()
                                    if (resultText.isNotBlank()) {
                                        send(GenerationUpdate.ScriptChunk(i, resultText))
                                        success = true
                                        recordCallOutcome(isSpareCall, true)
                                    } else {
                                        throw Exception("Empty response candidate text")
                                    }
                                } catch (e: Exception) {
                                    recordCallOutcome(isSpareCall, false)
                                    send(GenerationUpdate.Log("❌ Final Retry Part ${i + 1} (Attempt $attempts/$maxAttempts) Parsing Error: ${e.message ?: "Unknown parse error"}"))
                                    if (attempts == maxAttempts) {
                                        send(GenerationUpdate.ScriptChunk(i, "\n[Error parsing final output for Part ${i + 1}: ${e.message}]\n", isError = true))
                                    }
                                    return@use
                                }
                            }
                        } catch (e: Exception) {
                            recordCallOutcome(isSpareCall, false)
                            send(GenerationUpdate.Log("❌ Final Retry Part ${i + 1} (Attempt $attempts/$maxAttempts) Network Error: ${e.javaClass.simpleName} - ${e.message ?: "Connection failed"}"))
                            if (attempts == maxAttempts) {
                                send(GenerationUpdate.ScriptChunk(i, "\n[Network Error in Final Retry Part ${i + 1}: ${e.message}]\n", isError = true))
                            }
                        }
                    }
                } finally {
                    workingCount.decrementAndGet()
                }
                    
                    if (success) {
                        val currentSuccess = successfulCount.incrementAndGet()
                        val currentProgress = 0.15f + (0.85f * (currentSuccess.toFloat() / maxOf(1, totalToProcess)))
                        send(GenerationUpdate.Progress(currentProgress))
                        send(GenerationUpdate.Log("✅ Part ${i + 1}/$parts [$startStr - $endStr] recovered successfully."))
                    } else {
                        failedCount.incrementAndGet()
                        newlyFailed.add(i)
                    }
                }
                
                remainingFailedParts = newlyFailed
                
                if (remainingFailedParts.isNotEmpty()) {
                    if (retryDecisionChannel != null) {
                        send(GenerationUpdate.RequireRetryDecision(remainingFailedParts))
                        // Suspend and wait for user's decision from the UI
                        val shouldRetry = retryDecisionChannel.receive()
                        if (!shouldRetry) {
                            send(GenerationUpdate.Log("User chose to accept script with missing parts."))
                            break // Stop retrying, exit the loop
                        } else {
                            send(GenerationUpdate.Log("User requested another retry for failed parts."))
                        }
                    } else {
                        break // No channel, just stop
                    }
                }
            }
        } finally {
            statusTimerJob.cancel()
        }
    }

            if (remainingFailedParts.isEmpty() && successfulCount.get() >= totalToProcess) {
                send(GenerationUpdate.Log("[100%] All script segments generated successfully!"))
                send(GenerationUpdate.Progress(1.0f))
            } else {
                val finalPct = ((0.15f + (0.85f * (successfulCount.get().toFloat() / maxOf(1, totalToProcess)))) * 100).toInt()
                send(GenerationUpdate.Log("[$finalPct%] Script generation finished with ${remainingFailedParts.size} incomplete part(s)."))
            }
            send(GenerationUpdate.Complete)

        } catch (e: Exception) {
            send(GenerationUpdate.Log("Exception: ${e.message}"))
            send(GenerationUpdate.Error(e.message ?: "Unknown error"))
            Log.e("GeminiApiClient", "Error", e)
        }
    }.flowOn(Dispatchers.IO)

    private fun createSegmentRequestBody(
        systemInstruction: String,
        prompt: String,
        mediaMimeType: String?,
        fileUri: String,
        isVideo: Boolean,
        startMs: Long,
        endMs: Long
    ): RequestBody {
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
                                put("file_uri", fileUri)
                            })
                            if (isVideo) {
                                val startSec = startMs / 1000
                                val endSec = (endMs / 1000).coerceAtLeast(startSec + 1)
                                put("video_metadata", JSONObject().apply {
                                    put("start_offset", JSONObject().apply {
                                        put("seconds", startSec)
                                    })
                                    put("end_offset", JSONObject().apply {
                                        put("seconds", endSec)
                                    })
                                })
                            }
                        })
                        put(JSONObject().apply {
                            put("text", prompt)
                        })
                    })
                })
            })
        }
        return jsonBody.toString().toRequestBody("application/json".toMediaTypeOrNull())
    }

    private fun extractApiErrorMessage(code: Int, message: String, errorBody: String?): String {
        if (errorBody.isNullOrBlank()) return "$code $message"
        try {
            val json = JSONObject(errorBody)
            val errObj = json.optJSONObject("error")
            if (errObj != null) {
                val status = errObj.optString("status")
                val msg = errObj.optString("message")
                val detail = if (status.isNotBlank()) "$status: $msg" else msg
                if (detail.isNotBlank()) return detail
            }
        } catch (_: Exception) {
            // Not a JSON object or parsing failed
        }
        val clean = errorBody.replace("\n", " ").trim()
        return if (clean.length > 150) clean.take(150) + "..." else clean
    }
    
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
