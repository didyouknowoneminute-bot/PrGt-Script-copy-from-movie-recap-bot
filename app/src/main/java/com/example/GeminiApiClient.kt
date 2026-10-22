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

data class DetectedCharacter(
    val nameAndAppearance: String,
    val relationshipStatus: String = "",
    val pronoun: String,
    val isRecommendedMain: Boolean = false
)

data class SpareKeySlot(
    val key: String = "",
    val enabled: Boolean = true
)

sealed class GenerationUpdate {
    data class Log(val message: String) : GenerationUpdate()
    data class Progress(val progress: Float) : GenerationUpdate()
    data class InitParts(val totalParts: Int) : GenerationUpdate()
    data class ScriptChunk(val partIndex: Int, val text: String, val isError: Boolean = false, val clearFirst: Boolean = false) : GenerationUpdate()
    data class RequireRetryDecision(val failedParts: List<Int>) : GenerationUpdate()
    data class RequireNewKeyForRpd(val failedParts: List<Int>, val currentLimit: Int) : GenerationUpdate()
    data class RequireCharacterConfirmation(
        val characters: List<DetectedCharacter>,
        val recommendedIndex: Int,
        val rawProfileText: String
    ) : GenerationUpdate()
    data class CharacterProfileSaved(
        val profileText: String,
        val confirmedMainCharacter: String
    ) : GenerationUpdate()
    data class FileInfo(val geminiFileUri: String, val mimeType: String, val durationMs: Long) : GenerationUpdate()
    data class Error(val message: String) : GenerationUpdate()
    data class ProfileFatalError(val message: String) : GenerationUpdate()
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
            uiModelName.contains("3.8", ignoreCase = true) -> "gemini-3.8-flash"
            uiModelName.contains("3.7", ignoreCase = true) -> "gemini-3.7-flash"
            uiModelName.contains("3.6", ignoreCase = true) -> "gemini-3.6-flash"
            uiModelName.contains("3.5", ignoreCase = true) -> "gemini-3.5-flash-lite"
            uiModelName.contains("3.1", ignoreCase = true) -> "gemini-3.1-flash-lite"
            uiModelName.contains("3-flash", ignoreCase = true) || uiModelName.contains("3 flash", ignoreCase = true) || uiModelName.contains("gemini-3", ignoreCase = true) || uiModelName.trim() == "3" -> "gemini-3-flash-preview"
            uiModelName.startsWith("gemini-") -> uiModelName.trim()
            else -> "gemini-3.6-flash"
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
        newKeyChannel: kotlinx.coroutines.channels.ReceiveChannel<String>? = null,
        existingGeminiFileUri: String? = null,
        existingDurationMs: Long? = null,
        specificPartsToGenerate: List<Int>? = null,
        rpm: Int = 20,
        characterHint: String = "",
        characterConfirmationChannel: kotlinx.coroutines.channels.ReceiveChannel<String>? = null,
        mediaFileName: String = "",
        priorSeriesProfiles: Map<Int, String> = emptyMap(),
        existingCharacterProfile: String = "",
        mainKeyEnabled: Boolean = true,
        spareApiKeysList: List<String> = emptyList(),
        spareKeySlots: List<SpareKeySlot> = emptyList(),
        parallelKeyCount: Int = 1,
        srtContent: String = ""
    ): Flow<GenerationUpdate> = channelFlow {
        send(GenerationUpdate.Progress(0.0f))

        data class ActiveKeyEntry(
            val label: String,
            val key: String,
            val isSpare: Boolean
        )

        val resolvedSpareKeys = if (spareApiKeysList.isNotEmpty()) {
            spareApiKeysList.map { it.trim() }.filter { it.isNotBlank() }
        } else if (spareApiKey.isNotBlank()) {
            listOf(spareApiKey.trim())
        } else {
            emptyList()
        }

        val activeKeyQueue = mutableListOf<ActiveKeyEntry>()
        if (mainKeyEnabled && apiKey.isNotBlank()) {
            activeKeyQueue.add(ActiveKeyEntry("Main Key", apiKey.trim(), isSpare = false))
        }
        if (spareKeySlots.isNotEmpty()) {
            spareKeySlots.forEachIndexed { idx, slot ->
                val sKey = slot.key.trim()
                if (slot.enabled && sKey.isNotBlank() && activeKeyQueue.none { it.key == sKey }) {
                    activeKeyQueue.add(ActiveKeyEntry("Spare Key #${idx + 1}", sKey, isSpare = true))
                }
            }
        } else {
            resolvedSpareKeys.forEachIndexed { idx, sKey ->
                if (activeKeyQueue.none { it.key == sKey }) {
                    activeKeyQueue.add(ActiveKeyEntry("Spare Key #${idx + 1}", sKey, isSpare = true))
                }
            }
        }

        if (activeKeyQueue.isEmpty()) {
            send(GenerationUpdate.Error("အသုံးပြုနိုင်သော API Key မရှိပါ။ Settings ထဲတွင် API Key ထည့်သွင်းပြီး Switch (ON) ဖွင့်ပေးပါ။"))
            return@channelFlow
        }
        if (mediaUri == null && existingGeminiFileUri == null) {
            send(GenerationUpdate.Error("Please select a video or audio file first."))
            return@channelFlow
        }

        val initialPrimaryEntry = activeKeyQueue.first()
        val initialPrimaryKey = initialPrimaryEntry.key

        try {
            var actualModelName = getModelName(modelName)
            var finalGeminiFileUri: String
            var durationMs = 30000L // Default fallback
            
            send(GenerationUpdate.Log("AI Engine: $actualModelName"))

            val parsedSubtitles = if (srtContent.isNotBlank()) {
                val cues = SrtParser.parseSrtContent(srtContent)
                if (cues.isNotEmpty()) {
                    send(GenerationUpdate.Log("📑 SRT စာတန်းထိုးဖိုင် (${cues.size} cues) အား ထည့်သွင်းအသုံးပြုပါမည် (စကားပြော Context အပြည့်အဝ ရရှိပါမည်)။"))
                }
                cues
            } else emptyList()

            val uploadedFileUriByKey = java.util.concurrent.ConcurrentHashMap<String, String>()
            val uploadMutexByKey = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

            suspend fun ensureMediaUploadedForKey(targetKey: String, targetLabel: String): String {
                val perKeyMutex = uploadMutexByKey.getOrPut(targetKey) { Mutex() }
                return perKeyMutex.withLock {
                    val existingUploaded = uploadedFileUriByKey[targetKey]
                    if (!existingUploaded.isNullOrBlank()) {
                        return@withLock existingUploaded
                    }
                    if (mediaUri != null) {
                        val cachedForTarget = GeminiFileKeyCache.getCachedFile(context, mediaUri, targetKey)
                        if (cachedForTarget != null) {
                            send(GenerationUpdate.Log("✅ $targetLabel အတွက် သိမ်းဆည်းထားသော Media ဖိုင်အား ပြန်လည်အသုံးပြုပါမည်။"))
                            uploadedFileUriByKey[targetKey] = cachedForTarget.first
                            return@withLock cachedForTarget.first
                        } else {
                            val maxUploadAttempts = 3
                            var lastUploadError = ""
                            var targetFileUriInfo: Pair<String, String>? = null

                            for (attempt in 1..maxUploadAttempts) {
                                val attemptSuffix = if (attempt > 1) " (ကြိုးစားမှု $attempt/$maxUploadAttempts)" else ""
                                send(GenerationUpdate.Log("📤 $targetLabel ဖြင့် Media ဖိုင်အား ဆာဗာသို့ Upload တင်နေပါသည်$attemptSuffix..."))
                                val uploadResult = uploadFile(targetKey, mediaUri, mediaMimeType ?: "video/mp4") { _, _, _ -> }
                                when (uploadResult) {
                                    is UploadResult.Success -> {
                                        val candidateUriInfo = Pair(uploadResult.uri, uploadResult.name)
                                        if (mediaMimeType?.startsWith("video") == true) {
                                            send(GenerationUpdate.Log("[$targetLabel] Waiting for Gemini server to process video..."))
                                            var isActive = false
                                            var processFailed = false
                                            while (!isActive && !processFailed) {
                                                val state = checkFileState(targetKey, candidateUriInfo.second)
                                                if (state == "ACTIVE") {
                                                    isActive = true
                                                } else if (state == "FAILED") {
                                                    processFailed = true
                                                    lastUploadError = "Gemini server failed to process video with $targetLabel"
                                                } else {
                                                    delay(3000)
                                                }
                                            }
                                            if (isActive) {
                                                targetFileUriInfo = candidateUriInfo
                                                break
                                            }
                                        } else {
                                            targetFileUriInfo = candidateUriInfo
                                            break
                                        }
                                    }
                                    is UploadResult.Failure -> {
                                        lastUploadError = uploadResult.errorDetail
                                    }
                                }

                                if (attempt < maxUploadAttempts) {
                                    val delaySec = attempt * 3
                                    send(GenerationUpdate.Log("⚠️ $targetLabel ဖြင့် Media Upload မအောင်မြင်ပါ ($lastUploadError)။ $delaySec စက္ကန့်အကြာတွင် ပြန်လည်ကြိုးစားပါမည်..."))
                                    delay(delaySec * 1000L)
                                }
                            }

                            if (targetFileUriInfo == null) {
                                send(GenerationUpdate.Error("$targetLabel ဖြင့် Media Upload $maxUploadAttempts ကြိမ် ပြန်လည်ကြိုးစားသော်လည်း မအောင်မြင်ပါ:\n$lastUploadError"))
                                throw Exception("$targetLabel upload failed after $maxUploadAttempts attempts: $lastUploadError")
                            }

                            GeminiFileKeyCache.saveCachedFile(context, mediaUri, targetKey, targetFileUriInfo.first, targetFileUriInfo.second)
                            uploadedFileUriByKey[targetKey] = targetFileUriInfo.first
                            send(GenerationUpdate.Log("✅ $targetLabel ဖြင့် Media ဖိုင် အဆင်သင့်ဖြစ်ပါပြီ။"))
                            return@withLock targetFileUriInfo.first
                        }
                    }
                    uploadedFileUriByKey[initialPrimaryKey] ?: ""
                }
            }

            // Verify if existingGeminiFileUri is actually valid and accessible with current initialPrimaryKey
            var isExistingUriAccessible = false
            if (existingGeminiFileUri != null && existingDurationMs != null) {
                try {
                    val fileId = existingGeminiFileUri.substringAfterLast("/")
                    val testState = checkFileState(initialPrimaryKey, "files/$fileId")
                    if (testState == "ACTIVE") {
                        isExistingUriAccessible = true
                    } else if (testState == "NOT_FOUND" || testState == "FAILED") {
                        send(GenerationUpdate.Log("ℹ️ API Key အသစ် (သို့မဟုတ် ဖိုင်သက်တမ်းကုန်ဆုံးမှု) ကြောင့် Media ဖိုင်အား Gemini Cloud သို့ အသစ်ပြန်လည် Upload တင်ပါမည်..."))
                        isExistingUriAccessible = false
                    } else {
                        // For other states (e.g. PROCESSING)
                        isExistingUriAccessible = true
                    }
                } catch (e: Exception) {
                    isExistingUriAccessible = false
                }
            }

            val cachedFile = if (!isExistingUriAccessible && mediaUri != null) {
                GeminiFileKeyCache.getCachedFile(context, mediaUri, initialPrimaryKey)
            } else null
            
            if (isExistingUriAccessible && existingGeminiFileUri != null && existingDurationMs != null) {
                send(GenerationUpdate.Log("[15%] Using existing cached media on server..."))
                send(GenerationUpdate.Progress(0.15f))
                finalGeminiFileUri = existingGeminiFileUri
                durationMs = existingDurationMs
            } else if (cachedFile != null) {
                send(GenerationUpdate.Log("[15%] Using cached Gemini media upload for ${initialPrimaryEntry.label}..."))
                send(GenerationUpdate.Progress(0.15f))
                finalGeminiFileUri = cachedFile.first
                try {
                    val retriever = MediaMetadataRetriever()
                    retriever.setDataSource(context, mediaUri)
                    val timeString = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    if (timeString != null) durationMs = timeString.toLong()
                    retriever.release()
                } catch (e: Exception) {
                    // fallback duration
                }
                send(GenerationUpdate.FileInfo(finalGeminiFileUri, mediaMimeType ?: "video/mp4", durationMs))
            } else {
                if (mediaUri == null) {
                    send(GenerationUpdate.Error("API Key အသစ်ဖြင့် Upload ပြန်တင်ရန် မူရင်း Video/Audio ဖိုင် မရှိတော့ပါ။ ကျေးဇူးပြု၍ ဖိုင်အား အသစ်ပြန်လည် ရွေးချယ်ပေးပါ။"))
                    return@channelFlow
                }
                send(GenerationUpdate.Log("[0%] Preparing media upload to Gemini (${initialPrimaryEntry.label})..."))
                send(GenerationUpdate.Progress(0.01f))

                // User setting: Start uploading spare keys (parallel keys) 15 seconds after Primary Key starts uploading
                val pendingPartsCount = specificPartsToGenerate?.size ?: partsCount.toInt().coerceAtLeast(1)
                val targetParallelCount = minOf(parallelKeyCount, pendingPartsCount)
                val candidateSpareKeys = if (targetParallelCount > 1) {
                    activeKeyQueue.filter { it.key != initialPrimaryKey && it.isSpare }
                        .filter { entry ->
                            val used = if (rpdEnabled) RpdTracker.getStatus(context, entry.key, rpdLimit, actualModelName).usedCount else 0
                            !rpdEnabled || used < rpdLimit
                        }
                        .take(targetParallelCount - 1)
                } else emptyList()

                if (candidateSpareKeys.isNotEmpty()) {
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                        delay(15_000L) // Exactly 15 seconds after Primary Key upload started
                        send(GenerationUpdate.Log("⏱️ Primary Key upload စတင်ပြီး 15s ပြည့်သဖြင့် Spare/Parallel Key (${candidateSpareKeys.size}) ခု [${candidateSpareKeys.joinToString(", ") { it.label }}] အား နောက်ကွယ်မှ စတင် Upload တင်နေပါသည်..."))
                        candidateSpareKeys.forEach { entry ->
                            launch {
                                try {
                                    ensureMediaUploadedForKey(entry.key, entry.label)
                                } catch (_: Exception) {
                                    // Background pre-upload failure will be retried on demand
                                }
                            }
                        }
                    }
                }
                
                var lastLogPercent = -1
                val uploadResult = uploadFile(initialPrimaryKey, mediaUri!!, mediaMimeType ?: "video/mp4") { ratio, uploadedBytes, totalBytes ->
                    val uploadPercent = (ratio * 100).toInt()
                    if (uploadPercent % 25 == 0 && uploadPercent != lastLogPercent) {
                        lastLogPercent = uploadPercent
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
                        val state = checkFileState(initialPrimaryKey, fileUriInfo.second)
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
                
                GeminiFileKeyCache.saveCachedFile(context, mediaUri, initialPrimaryKey, fileUriInfo.first, fileUriInfo.second)
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
            
            val isMainCharPov = pov.contains("Main Character", ignoreCase = true) || pov.contains("First", ignoreCase = true)
            val povDetail = when {
                isMainCharPov -> 
                    """
                    အဓိကဇာတ်ကောင်၏ ရှုထောင့်အမြင် (First-Person POV / လူကြီး-လူငယ်ဆိုလျှင် 'ကျွန်တော်/ကျွန်မ'၊ ကလေးငယ်ဆိုလျှင် ယောက်ျားလေး 'သား' / မိန်းကလေး 'သမီး') မှ ကိုယ်တိုင်ကြုံတွေ့နေရသကဲ့သို့ စကားပြောသဘာဝအတိုင်း ခံစားချက်ပြင်းပြစွာ ပြန်ပြောပြရမည်။ (စာအရေးအသားပုံစံ လုံးဝမသုံးဘဲ ကိုယ်တွေ့ပြောစကားအတိုင်း ပြောပြရမည်။)
                    - ပင်မဇာတ်ဆောင် တစ်ဦးတည်းကိုသာ အစမှအဆုံး သတ်မှတ်ထားသော First-Person နာမ်စား ('ကျွန်တော်/ကျွန်မ' သို့မဟုတ် ကလေးငယ်ဖြစ်ပါက 'သား/သမီး') အဖြစ် တသမတ်တည်း သုံးစွဲရမည်။
                    - အကယ်၍ ပင်မဇာတ်ဆောင်သည် ကလေးငယ် ဖြစ်ပါက 'ကျွန်တော်/ကျွန်မ' ဟု လုံးဝ မသုံးဘဲ ယောက်ျားလေးဆိုလျှင် 'သား'၊ မိန်းကလေးဆိုလျှင် 'သမီး' ဟုသာ သဘာဝကျကျ သုံးနှုန်းရမည်။
                    - အကယ်၍ ဤစက္ကန့်ပိုင်းအတွင်း ပင်မဇာတ်ဆောင် ကိုယ်တိုင် မြင်ကွင်းထဲ မပါဝင်ဘဲ အခြားဇာတ်ကောင်များသာ ပေါ်နေပါက၊ အခြားဇာတ်ကောင်ကို 'ကျွန်တော်/ကျွန်မ/သား/သမီး' ဟု လုံးဝ မပြောင်းဘဲ၊ ပင်မဇာတ်ဆောင်က အဆိုပါအခြားဇာတ်ကောင်များ၏ လှုပ်ရှားမှုကို (သူက... / အဲ့ဒီလူက.../ (နာမည်/တော်စပ်ပုံ)က ဟု) ပြန်ပြောပြသည့် ပုံစံဖြင့်သာ ရေးသားရန်။
                    """.trimIndent()
                pov.contains("Storyteller", ignoreCase = true) || pov.contains("Third", ignoreCase = true) || pov.contains("Omniscient", ignoreCase = true) -> 
                    """
                    ပြင်ပဇာတ်ကြောင်းပြောသူအမြင် (Third-Person / Omniscient Storyteller Script) မှ ဇာတ်ကောင်များ၏ အမည်၊ တော်စပ်ပုံနှင့် 'သူ'၊ 'သူမ'၊ 'သူတို့' စသည်တို့ကို သုံး၍ အရာအားလုံးကို ခြုံငုံသိမြင်နေသော ပုံစံဖြင့် မိတ်ဆွေတစ်ယောက်ကို ဇာတ်လမ်းပြန်ပြောပြနေသလို စကားပြောသဘာဝအတိုင်း အဓိပ္ပါယ်ပေါ်လွင်စွာ ဇာတ်ကြောင်းပြောပြရမည်။ (စာအရေးအသားပုံစံ လုံးဝမသုံးရ။)
                    - ဇာတ်လမ်းအနှစ်သာရနှင့် အကြောင်းအရင်းကို ရှင်းပြရန်: ဇာတ်ကောင်များ ဘာကြောင့် ဘာလုပ်နေသည်၊ သူတို့၏ ရည်ရွယ်ချက်နှင့် စိတ်ခံစားချက်က ဘာဖြစ်သည်ကို နားထောင်သူ ကွင်းကွင်းကွက်ကွက် နားလည်အောင် ပြောပြရမည်။
                    - အပြန်အလှန် ပြောစကားများကို ချိတ်ဆက်ပြောပြရန် (Conversational Back-and-Forth): ဇာတ်ကောင်များ စကားပြောခန်းများတွင် "ဘယ်သူက ဘာအကြောင်းအရာကို ဘယ်လိုပြောလိုက်တဲ့အခါ၊ ဘယ်သူက ဘယ်လို ပြန်ပြော/ပြန်တုံ့ပြန်လိုက်တယ်" ဆိုသည့် အပြန်အလှန် ပြောဆိုချက်များ၏ အဓိပ္ပါယ်နှင့် အနှစ်သာရကို သဘာဝကျကျ ချိတ်ဆက်ရှင်းပြရမည်။
                    - အပေါ်ယံ မြင်ကွင်းဖော်ပြချက် တားမြစ်ချက်: ပြောဆိုနေသော အကြောင်းအရာကို မရှင်းပြဘဲ "လူနှစ်ယောက် စကားပြောနေကြတယ်"၊ "ရပ်နေတယ်"၊ "ထိုင်နေတယ်" ကဲ့သို့ အဓိပ္ပါယ်မပေါ်သော အပေါ်ယံ မြင်ကွင်းဖော်ပြချက် (Empty Surface Visual Description) များကို လုံးဝ (လုံးဝ) မရေးရပါ။
                    """.trimIndent()
                pov.contains("Second", ignoreCase = true) -> 
                    "ကြည့်ရှုသူ/နားဆင်သူအား တိုက်ရိုက်ပြောဆိုသော အမြင် (Second-Person POV / 'မင်း/ခင်ဗျား/သင်') ဖြင့် စကားပြောသဘာဝအတိုင်း စိတ်ဝင်စားဖွယ် ပြောပြရမည်။ (စာအရေးအသားပုံစံ လုံးဝမသုံးရ။)"
                else -> "$pov ရှုထောင့်အမြင်အတိုင်း စကားပြောသဘာဝအတိုင်း ဇာတ်ကြောင်းပြောပြရမည်။"
            }

            val toneDetail = if (language == "Myanmar") {
                when {
                    tone.contains("Dramatic", ignoreCase = true) ->
                        """
                        Dramatic (ဒရာမာ / ခံစားချက်အသားပေး လေသံ):
                        - ဇာတ်ကောင်များ၏ ရင်ထဲက ခံစားချက်၊ ဝမ်းနည်းမှု၊ ပျော်ရွှင်မှု၊ နာကျင်မှုနှင့် ဇာတ်လမ်းအချိုးအကွေ့များကို နားဆင်သူ ရင်ထဲထိရှပြီး စီးမျောသွားအောင် လေးနက်ပြီး ခံစားချက်ပြင်းပြသော လူပြောစကားလေသံဖြင့် ရေးသားရန်။
                        """.trimIndent()
                    tone.contains("Comedic", ignoreCase = true) ->
                        """
                        Comedic (ဟာသ / ပေါ့ပေါ့ပါးပါး ပျော်စရာ လေသံ):
                        - ဇာတ်လမ်းအဖြစ်အပျက်များကို ပေါ့ပေါ့ပါးပါး၊ သွက်သွက်လက်လက်နှင့် နားထောင်သူ ပြုံးမိ/ရယ်မိစေမည့် ဟာသဉာဏ်နှောသော အသုံးအနှုန်းများ၊ သဘာဝကျပြီး ပျော်စရာကောင်းသော အရွှန်းဖောက် လူပြောစကားလေသံဖြင့် ရေးသားရန်။
                        """.trimIndent()
                    tone.contains("Suspenseful", ignoreCase = true) ->
                        """
                        Suspenseful (သည်းထိတ်ရင်ဖို / စိတ်လှုပ်ရှားဖွယ် လေသံ):
                        - ဘာဆက်ဖြစ်မလဲဟု နားထောင်သူ ရင်တထိတ်ထိတ်ဖြင့် သိချင်စိတ်ပြင်းပြလာအောင် လျှို့ဝှက်ဆန်းကြယ်မှု၊ အန္တရာယ်အငွေ့အသက်နှင့် စိတ်လှုပ်ရှားဖွယ် အရှိန်အဟုန်တို့ကို ပေါ်လွင်စေသော ဆွဲဆောင်မှုရှိသည့် လူပြောစကားလေသံဖြင့် ရေးသားရန်။
                        """.trimIndent()
                    tone.contains("Cute", ignoreCase = true) || tone.contains("Adorable", ignoreCase = true) ->
                        """
                        Cute & Adorable (ချစ်စရာကောင်းသော / ကလေးသဘာဝ နူးညံ့သည့် လေသံ):
                        - အပြစ်ကင်းစင်ပြီး ချစ်စရာကောင်းသော၊ နွေးထွေးနူးညံ့သည့် လေသံဖြင့် ရေးသားရန်။
                        - အထူးသဖြင့် ကလေးငယ် ဇာတ်ကောင်များ (သား/သမီး) သို့မဟုတ် ချစ်စရာ ဇာတ်ကောင်များ ပါဝင်ပါက ကလေးတို့၏ အပြစ်ကင်းစင်ပုံ၊ ချစ်စဖွယ် အမူအရာလေးများနှင့် ချစ်စနိုး အသုံးအနှုန်းလေးများ (ဥပမာ - 'လေး' အသုံးအနှုန်းများ၊ နူးညံ့ချိုသာသော ကလေးသဘာဝ စကားလုံးများ) ကို ပေါ်လွင်အောင် နားထောင်သူ အသည်းယားပြီး ချစ်ခင်သွားစေမည့် ချစ်စရာ လူပြောစကားပုံစံဖြင့် ရေးသားရန်။
                        """.trimIndent()
                    tone.contains("Action", ignoreCase = true) || tone.contains("High-Energy", ignoreCase = true) ->
                        """
                        Action & High-Energy (အက်ရှင် / အရှိန်ပြင်းသွက်လက်သော လေသံ):
                        - အက်ရှင်ဇာတ်ဝင်ခန်းများ၊ တိုက်ခိုက်ခန်းများ၊ သဲထိတ်ရင်ဖို လိုက်လံဖမ်းဆီးခန်းများနှင့် အပြောင်းအလဲမြန်သော အဖြစ်အပျက်များကို အရှိန်အဟုန်ပြင်းပြင်း၊ သွက်သွက်လက်လက်နှင့် စိတ်လှုပ်ရှားတက်ကြွစေမည့် စကားလုံးများဖြင့် ရေးသားရန်။
                        - ဝါကျများကို တိုတိုပြတ်ပြတ်နှင့် စည်းချက်မှန်မှန် (Punchy & Fast-paced) သုံးစွဲပြီး နားဆင်သူ၏ ရင်ခုန်သံကို မြန်ဆန်လာစေမည့် ပြင်းထန်သွက်လက်သော လူပြောစကားလေသံဖြင့် ရေးသားရန်။
                        """.trimIndent()
                    tone.contains("Psychological", ignoreCase = true) || tone.contains("Thriller", ignoreCase = true) ->
                        """
                        Psychological Thriller (စိတ်ပိုင်းဆိုင်ရာသည်းထိတ်ရင်ဖို / ဇာတ်ကောင်စိတ်ခံစားမှု အသားပေး လေသံ):
                        - ဇာတ်ကောင်၏ အတွင်းစိတ်လှုပ်ရှားမှု၊ စိတ်ဖိစီးမှု၊ ကြောက်ရွံ့မှု၊ သံသယနှင့် စိတ်ပိုင်းဆိုင်ရာ အကျပ်အတည်း (Inner Conflict & Mental Pressure) များကို အဓိက ပေါ်လွင်အောင် ရေးသားရန်။
                        - အန္တရာယ်များနှင့် မသေချာမရေရာမှုများကြားတွင် ဇာတ်ကောင် ရှင်သန်ရုန်းကန်ရပုံ (Survival Instinct) ကို နားထောင်သူ ကိုယ်တိုင် ဇာတ်ကောင်နေရာသို့ ရောက်သွားသလို အသက်ရှူမှားလောက်အောင် ရင်တမမ စိတ်လှုပ်ရှားစေမည့် လူပြောစကားလေသံဖြင့် ရေးသားရန်။
                        """.trimIndent()
                    tone.contains("Character-Driven", ignoreCase = true) || tone.contains("Intense", ignoreCase = true) ->
                        """
                        Intense & Character-Driven (ဇာတ်ကောင်ဗဟိုပြု အပြင်းစား စိတ်လှုပ်ရှားဖွယ် လေသံ):
                        - ဇာတ်လမ်းအဖြစ်အပျက်ထက် "ဇာတ်ကောင်၏ ရွေးချယ်ဆုံးဖြတ်ချက်များ၊ ပေးဆပ်ရမှုများနှင့် စိတ်ခံစားမှုပြင်းထန်မှု" ကို အဓိက ဦးစားပေး ဖော်ပြရန်။
                        - ဇာတ်ကောင်ရင်ဆိုင်နေရသော အန္တရာယ်နှင့် စိန်ခေါ်မှုကြီးမားပုံ (High Stakes & Emotional Intensity) ကို နားထောင်သူ စိတ်ဝင်စားမှု အစအဆုံး မပြတ်သွားအောင် ဆွဲဆောင်မှုရှိသော လူပြောစကားလေသံဖြင့် ရေးသားရန်။
                        """.trimIndent()
                    tone.contains("Dark", ignoreCase = true) || tone.contains("Gritty", ignoreCase = true) ->
                        """
                        Dark & Gritty (အမှောင်ဘက်လွှမ်းပြီး ပြင်းထန်ရက်စက်သော လေသံ):
                        - ဇာတ်လမ်း၏ ခက်ခဲကြမ်းတမ်းမှု၊ လျှို့ဝှက်ချက်များ၊ သစ္စာဖောက်မှု၊ အန္တရာယ်ကြီးမားမှုနှင့် လက်စားချေမှု အငွေ့အသက်များကို အလေးထား ရေးသားရန်။
                        - မည်သည့်အရာမှ လွယ်လွယ်ကူကူမပြီးဘဲ အမှန်တရား၏ ခါးသီးမှုနှင့် လောက၏ အမှောင်ဘက်ခြမ်းကို ဖော်ကျူးသည့် ပြတ်သားလေးနက်သော လူပြောစကားလေသံဖြင့် ရေးသားရန်။
                        """.trimIndent()
                    tone.contains("Heroic", ignoreCase = true) || tone.contains("Epic", ignoreCase = true) ->
                        """
                        Heroic & Epic (သူရဲကောင်းဆန်ဆန် / ဇာတ်ကောင်စွမ်းရည်မြှင့် လေသံ):
                        - ဇာတ်ကောင်၏ စိတ်ဓာတ်ကြံ့ခိုင်မှု၊ ကြီးမားသော စိန်ခေါ်မှုအခက်အခဲများကို မဆုတ်မနစ် ရင်ဆိုင်ကျော်လွှားပုံနှင့် သူရဲကောင်းဆန်သော အောင်ပွဲခံအခိုက်အတန့်များကို ကြီးကျယ်ခမ်းနားစွာ ပုံဖော်ရန်။
                        - နားဆင်သူများ စိတ်ဓာတ်တက်ကြွပြီး ဇာတ်ကောင်ဘက်မှ အားရကျေနပ်စေမည့် လှုံ့ဆော်မှုအပြည့်ရှိသော လူပြောစကားလေသံဖြင့် ရေးသားရန်။
                        """.trimIndent()
                    else -> "$tone (ရွေးချယ်ထားသော '$tone' လေသံအတိုင်း နားထောင်သူ စိတ်ဝင်စားအောင် ရေးသားပါ)"
                }
            } else {
                when {
                    tone.contains("Dramatic", ignoreCase = true) ->
                        """
                        Dramatic (Emotional & Deeply Engaging Tone):
                        - Narrate with deep emotional resonance, highlighting the characters' inner feelings, struggles, joys, pains, and dramatic turning points in a natural, heartfelt spoken voiceover style.
                        """.trimIndent()
                    tone.contains("Comedic", ignoreCase = true) ->
                        """
                        Comedic (Lighthearted, Witty & Fun Tone):
                        - Narrate in a lively, playful, and humorous spoken voiceover style with natural wit and entertaining phrasing that makes the listener smile and enjoy the story.
                        """.trimIndent()
                    tone.contains("Suspenseful", ignoreCase = true) ->
                        """
                        Suspenseful (Thrilling, Mysterious & Edge-of-Seat Tone):
                        - Narrate with gripping tension, mystery, and anticipation that keeps the listener hooked and eager to know what happens next.
                        """.trimIndent()
                    tone.contains("Cute", ignoreCase = true) || tone.contains("Adorable", ignoreCase = true) ->
                        """
                        Cute & Adorable (Sweet, Innocent & Heartwarming Tone):
                        - Narrate in a warm, gentle, innocent, and endearing spoken style. Especially when child characters or cute moments are involved, use affectionate, soft, and charming expressions that highlight their innocence and make the listener adore them.
                        """.trimIndent()
                    tone.contains("Action", ignoreCase = true) || tone.contains("High-Energy", ignoreCase = true) ->
                        """
                        Action & High-Energy (Fast-Paced, Dynamic & Adrenaline-Fueled Tone):
                        - Narrate with high intensity, relentless pace, and gripping energy that matches action sequences, chase scenes, fight choreography, and fast-moving plot twists.
                        - Use short, punchy, dynamic sentences with strong verbs and high-stakes spoken phrasing to keep the listener's adrenaline pumping throughout the voiceover.
                        """.trimIndent()
                    tone.contains("Psychological", ignoreCase = true) || tone.contains("Thriller", ignoreCase = true) ->
                        """
                        Psychological Thriller (Mind Games, Paranoia & Psychological Stakes):
                        - Narrate by intensely emphasizing the characters' inner turmoil, psychological pressure, fear, doubt, paranoia, and deep moral dilemmas (Inner Conflict & Mental Pressure).
                        - Capture the protagonist's desperate fight for survival amid uncertainty and danger, keeping the listener on edge and holding their breath as if they were living through the character's nightmare.
                        """.trimIndent()
                    tone.contains("Character-Driven", ignoreCase = true) || tone.contains("Intense", ignoreCase = true) ->
                        """
                        Intense & Character-Driven (High Emotional Stakes & Character-Centric Voiceover):
                        - Prioritize the characters' critical choices, sacrifices, emotional turning points, and personal stakes above generic plot actions.
                        - Narrate with high-stakes urgency and intense emotional resonance, keeping the listener deeply invested in the protagonist's journey and difficult decisions.
                        """.trimIndent()
                    tone.contains("Dark", ignoreCase = true) || tone.contains("Gritty", ignoreCase = true) ->
                        """
                        Dark & Gritty (Raw, Ruthless, Bleak & High-Tension Tone):
                        - Emphasize the harsh, unforgiving realities, underworld tension, betrayal, moral ambiguity, and revenge-driven atmosphere of the story.
                        - Use a blunt, grounded, and brooding spoken style that pulls no punches and highlights the bitter, brutal truths of the world.
                        """.trimIndent()
                    tone.contains("Heroic", ignoreCase = true) || tone.contains("Epic", ignoreCase = true) ->
                        """
                        Heroic & Epic (Inspiring, Grand & Triumphant Voiceover):
                        - Magnify the protagonist's indomitable willpower, perseverance against impossible odds, and thrilling heroic breakthroughs in a grand, cinematic style.
                        - Deliver an electrifying and uplifting spoken narration that rallies the listener and makes every triumph feel deeply earned and satisfying.
                        """.trimIndent()
                    else -> "$tone (Narrate strictly in an engaging '$tone' spoken voiceover tone)"
                }
            }
            val lengthInstruction = "ရွေးချယ်ထားသော Script Length '$length' အရ စကားလုံးအရေအတွက် $minWords မှ $maxWords လုံး အတွင်း အတိအကျဖြစ်အောင် ရေးသားပေးရမည်။ (ပိုတိုခြင်း၊ ပိုရှည်လွန်းခြင်း လုံးဝ မဖြစ်ရပါ။)"

            val modalityInstruction = if (isVideo) {
                """
                |Media Source: ဗီဒီယို (Video - Visual Actions + Spoken Dialogues & Plot Context)
                |- ဤဗီဒီယိုအပိုင်းအခြားအတွင်း ဖြစ်ပျက်နေသော ဇာတ်ကောင်များ၏ လှုပ်ရှားမှု/မြင်ကွင်းများ (Visual actions, scenes, character expressions) နှင့် ဇာတ်ကောင်များ ပြောဆိုသော စကားသံ/နောက်ခံအသံများ (Spoken dialogues, speech context, sound effects) နှစ်ခုစလုံးကို အသေးစိတ် ကြည့်ရှုနားထောင်ပြီး အခြေခံ၍ ရေးသားရမည်။
                |- အရေးကြီးစည်းကမ်း (Meaningful Plot & Dialogue Context): ဗီဒီယိုထဲတွင် ဇာတ်ကောင်များ စကားပြောနေပါက "စကားပြောနေကြသည်" ဟုသာ အပေါ်ယံ မရေးဘဲ၊ စကားပြောသံ (နှင့် ပေးထားသော Subtitles စာတန်းထိုးများ) ကို သေချာနားထောင်/ဖတ်ရှု၍ ဘာအကြောင်းအရာကြောင့် မည်သူက မည်သူ့ကို ဘာပြောနေသည်၊ မည်သည့် ပဋိပက္ခ/ခံစားချက် ဖြစ်နေသည် ဆိုသည့် ဇာတ်လမ်း Context နှင့် အဓိပ္ပါယ်အနှစ်သာရ ပေါ်လွင်အောင် အဓိက ဖော်ထုတ်ရေးသားရမည်။
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
                val firstStatus = RpdTracker.getStatus(context, initialPrimaryKey, rpdLimit, actualModelName)
                send(GenerationUpdate.Log("ℹ️ Daily Quota Reset: ${firstStatus.resetTimeDescription} (${firstStatus.hoursUntilReset}h ${firstStatus.minutesUntilReset}m until midnight PST/PDT)."))
                activeKeyQueue.forEach { entry ->
                    val st = RpdTracker.getStatus(context, entry.key, rpdLimit, actualModelName)
                    send(GenerationUpdate.Log("ℹ️ RPD Cache [${entry.label}] ($actualModelName): ${st.usedCount}/$rpdLimit requests used today (${st.remainingCount} available)."))
                }
            }

            // Rate limiter: per-key sliding window + strict minimum interval between ANY two requests on the same key
            class PerKeyRateLimiter {
                val mutex = Mutex()
                var lastRequestTimeMs = 0L
                val requestTimestamps = mutableListOf<Long>()
            }
            val keyRateLimiters = java.util.concurrent.ConcurrentHashMap<String, PerKeyRateLimiter>()
            val minDelayBetweenRequestsMs = (60_000L / maxOf(1, rpm))

            suspend fun acquireRateLimitSlotForKey(targetKey: String) {
                val limiter = keyRateLimiters.getOrPut(targetKey) { PerKeyRateLimiter() }
                limiter.mutex.withLock {
                    while (true) {
                        val now = System.currentTimeMillis()
                        // 1. Remove timestamps older than 60 seconds
                        limiter.requestTimestamps.removeAll { now - it >= 60_000L }

                        // 2. Strict minimum spacing between any two consecutive requests on this key
                        val timeSinceLast = now - limiter.lastRequestTimeMs
                        val intervalWait = if (limiter.lastRequestTimeMs > 0 && timeSinceLast < minDelayBetweenRequestsMs) {
                            minDelayBetweenRequestsMs - timeSinceLast
                        } else {
                            0L
                        }

                        // 3. Sliding window: wait until oldest request exits the 60-second window on this key
                        val windowWait = if (limiter.requestTimestamps.size >= rpm) {
                            val oldest = limiter.requestTimestamps.firstOrNull() ?: now
                            maxOf(100L, 60_000L - (now - oldest) + 50L)
                        } else {
                            0L
                        }

                        val waitTime = maxOf(intervalWait, windowWait)
                        if (waitTime <= 0L) {
                            val currentTime = System.currentTimeMillis()
                            limiter.requestTimestamps.add(currentTime)
                            limiter.lastRequestTimeMs = currentTime
                            return@withLock
                        }

                        delay(waitTime)
                    }
                }
            }

            // Multi-Key and RPD state management (supports up to 20 keys)
            val keyMutex = Mutex()
            var currentKeyIndex = 0
            var activeApiKey = initialPrimaryKey
            var activeGeminiFileUri = finalGeminiFileUri
            var activeKeyLabel = initialPrimaryEntry.label
            uploadedFileUriByKey[initialPrimaryKey] = finalGeminiFileUri
            val usedCountByKey = mutableMapOf<String, Int>()
            activeKeyQueue.forEach { entry ->
                usedCountByKey[entry.key] = if (rpdEnabled) RpdTracker.getStatus(context, entry.key, rpdLimit, actualModelName).usedCount else 0
            }

            suspend fun acquireRateLimitSlot() {
                acquireRateLimitSlotForKey(activeApiKey)
            }

            var mainTotalCalls = if (mainKeyEnabled && apiKey.isNotBlank()) (usedCountByKey[apiKey.trim()] ?: 0) else 0
            var mainSuccessCalls = 0
            var mainFailedCalls = 0
            var spareTotalCalls = activeKeyQueue.filter { it.isSpare }.sumOf { usedCountByKey[it.key] ?: 0 }
            var spareSuccessCalls = 0
            var spareFailedCalls = 0
            var hasSwitchedToSpare = initialPrimaryEntry.isSpare
            val inFlightHttpRequests = java.util.concurrent.atomic.AtomicInteger(0)

            data class ReservedKeySlot(
                val apiKey: String,
                val fileUri: String,
                val isSpare: Boolean,
                val keyLabel: String,
                val slotNumber: Int,
                val totalRpdUsed: Int
            )

            var rpdExhaustedAndStoppedByUser = false

            suspend fun getOrSwitchKeyAndFile(): ReservedKeySlot {
                return keyMutex.withLock {
                    var reservedSlot: ReservedKeySlot? = null
                    while (reservedSlot == null) {
                        if (rpdEnabled) {
                            var curUsed = usedCountByKey[activeApiKey] ?: RpdTracker.getStatus(context, activeApiKey, rpdLimit, actualModelName).usedCount
                            usedCountByKey[activeApiKey] = curUsed
                            if (curUsed >= rpdLimit) {
                                val availableIdx = activeKeyQueue.indexOfFirst { entry ->
                                    val u = usedCountByKey[entry.key] ?: RpdTracker.getStatus(context, entry.key, rpdLimit, actualModelName).usedCount
                                    usedCountByKey[entry.key] = u
                                    u < rpdLimit
                                }
                                if (availableIdx >= 0) {
                                    val exhaustedLabel = activeKeyLabel
                                    currentKeyIndex = availableIdx
                                    val nextEntry = activeKeyQueue[availableIdx]
                                    val nextUsed = usedCountByKey[nextEntry.key] ?: 0
                                    send(GenerationUpdate.Log("⚠️ $exhaustedLabel RPD limit ($actualModelName: $rpdLimit/$rpdLimit) ပြည့်သွားပါပြီ။"))
                                    send(GenerationUpdate.Log("🔄 ${nextEntry.label} သို့ အလိုအလျောက် ပြောင်းလဲအသုံးပြုနေပါသည်..."))
                                    activeGeminiFileUri = ensureMediaUploadedForKey(nextEntry.key, nextEntry.label)
                                    activeApiKey = nextEntry.key
                                    activeKeyLabel = nextEntry.label
                                    hasSwitchedToSpare = nextEntry.isSpare
                                    curUsed = nextUsed
                                    send(GenerationUpdate.Log("🚀 ကျန်ရှိသော Script အပိုင်းများကို ${nextEntry.label} ဖြင့် ဆက်လက်ထုတ်လုပ်ပါမည်။"))
                                } else {
                                    currentKeyIndex = activeKeyQueue.lastIndex
                                }
                            } else {
                                activeGeminiFileUri = uploadedFileUriByKey[activeApiKey] ?: ensureMediaUploadedForKey(activeApiKey, activeKeyLabel)
                            }
                        }

                        val currentKeyUsed = usedCountByKey[activeApiKey] ?: 0
                        val allKeysExhausted = rpdEnabled && activeKeyQueue.all { entry ->
                            val u = usedCountByKey[entry.key] ?: RpdTracker.getStatus(context, entry.key, rpdLimit, actualModelName).usedCount
                            usedCountByKey[entry.key] = u
                            u >= rpdLimit
                        }
                        val isExhausted = rpdEnabled && currentKeyUsed >= rpdLimit && allKeysExhausted

                        if (isExhausted && newKeyChannel != null) {
                            if (inFlightHttpRequests.get() > 0) {
                                send(GenerationUpdate.Log("⏳ RPD ပြည့်သွားပါပြီ။ လက်ရှိ ဆာဗာသို့ ပို့ထားပြီးသော Processing requests (${inFlightHttpRequests.get()} ခု) ၏ response များ အပြီးထိ စောင့်ဆိုင်းနေပါသည်..."))
                                while (inFlightHttpRequests.get() > 0) {
                                    delay(500)
                                }
                                send(GenerationUpdate.Log("✅ လက်ရှိ Processing requests အားလုံး response ရရှိပြီးစီးပါပြီ။"))
                            }

                            send(GenerationUpdate.Log("⚠️ ဖွင့်ထားသော API Keys အားလုံး၏ RPD Limit ($actualModelName: $rpdLimit/$rpdLimit ကြိမ်) ပြည့်သွားပါပြီ။ API Key အသစ် တောင်းခံနေပါသည်..."))
                            send(GenerationUpdate.RequireNewKeyForRpd(emptyList(), rpdLimit))
                            val userSuppliedKey = newKeyChannel.receive().trim()
                            if (userSuppliedKey.isNotBlank()) {
                                val (incomingModel, keyToUse) = if (userSuppliedKey.startsWith("MODEL:")) {
                                    val parts = userSuppliedKey.removePrefix("MODEL:").split(":", limit = 2)
                                    val m = parts.getOrNull(0)?.trim() ?: ""
                                    val k = parts.getOrNull(1)?.trim() ?: ""
                                    m to k
                                } else {
                                    "" to userSuppliedKey
                                }

                                if (incomingModel.isNotBlank()) {
                                    val newResolvedModel = getModelName(incomingModel)
                                    if (newResolvedModel != actualModelName) {
                                        actualModelName = newResolvedModel
                                        send(GenerationUpdate.Log("🔄 Model အား $actualModelName သို့ ပြောင်းလဲအသုံးပြုပါမည်။"))
                                        usedCountByKey.clear()
                                    }
                                }

                                val targetKey = keyToUse.ifBlank { activeApiKey }
                                val existingQueueEntry = activeKeyQueue.firstOrNull { it.key == targetKey }
                                if (existingQueueEntry != null) {
                                    val newStatus = RpdTracker.getStatus(context, targetKey, rpdLimit, actualModelName)
                                    usedCountByKey[targetKey] = newStatus.usedCount
                                    if (newStatus.usedCount >= rpdLimit) {
                                        send(GenerationUpdate.Log("⚠️ $actualModelName အတွက် ${existingQueueEntry.label} သည် RPD Limit (${newStatus.usedCount}/$rpdLimit) ပြည့်နေပါသည်။ နောက်ထပ် Key/Model အသစ် လိုအပ်ပါသည်..."))
                                        continue
                                    }
                                    activeApiKey = targetKey
                                    activeKeyLabel = existingQueueEntry.label
                                    hasSwitchedToSpare = existingQueueEntry.isSpare
                                    currentKeyIndex = activeKeyQueue.indexOf(existingQueueEntry)
                                    activeGeminiFileUri = uploadedFileUriByKey[targetKey] ?: ensureMediaUploadedForKey(targetKey, activeKeyLabel)
                                    send(GenerationUpdate.Log("🔑 ${existingQueueEntry.label} ဖြင့် $actualModelName တွင် ဆက်လက်လုပ်ဆောင်ပါမည် (RPD: ${newStatus.usedCount}/$rpdLimit)။"))
                                } else if (targetKey.isNotBlank()) {
                                    val newStatus = RpdTracker.getStatus(context, targetKey, rpdLimit, actualModelName)
                                    usedCountByKey[targetKey] = newStatus.usedCount
                                    if (newStatus.usedCount >= rpdLimit) {
                                        send(GenerationUpdate.Log("⚠️ ထည့်သွင်းလိုက်သော API Key အသစ်သည်လည်း ယနေ့အတွက် RPD Limit ($actualModelName: ${newStatus.usedCount}/$rpdLimit) ပြည့်နေပြီးသား ဖြစ်နေပါသည်။ နောက်ထပ် Key အသစ် ထပ်မံတောင်းခံပါမည်..."))
                                        continue
                                    }
                                    val newEntryLabel = "Spare Key #${activeKeyQueue.count { it.isSpare } + 1}"
                                    send(GenerationUpdate.Log("🔑 API Key အသစ် ($newEntryLabel) ရရှိပါပြီ။ Media ဖိုင်အား စစ်ဆေး/Upload တင်နေပါသည်..."))
                                    activeGeminiFileUri = ensureMediaUploadedForKey(targetKey, newEntryLabel)
                                    activeKeyQueue.add(ActiveKeyEntry(newEntryLabel, targetKey, isSpare = true))
                                    currentKeyIndex = activeKeyQueue.lastIndex
                                    activeApiKey = targetKey
                                    activeKeyLabel = newEntryLabel
                                    hasSwitchedToSpare = true
                                } else {
                                    val availableQueueEntry = activeKeyQueue.firstOrNull { entry ->
                                        val st = RpdTracker.getStatus(context, entry.key, rpdLimit, actualModelName)
                                        st.usedCount < rpdLimit
                                    }
                                    if (availableQueueEntry != null) {
                                        val st = RpdTracker.getStatus(context, availableQueueEntry.key, rpdLimit, actualModelName)
                                        usedCountByKey[availableQueueEntry.key] = st.usedCount
                                        activeApiKey = availableQueueEntry.key
                                        activeKeyLabel = availableQueueEntry.label
                                        hasSwitchedToSpare = availableQueueEntry.isSpare
                                        currentKeyIndex = activeKeyQueue.indexOf(availableQueueEntry)
                                        activeGeminiFileUri = uploadedFileUriByKey[availableQueueEntry.key] ?: ensureMediaUploadedForKey(availableQueueEntry.key, activeKeyLabel)
                                        send(GenerationUpdate.Log("🔑 ${availableQueueEntry.label} ဖြင့် $actualModelName တွင် ဆက်လက်လုပ်ဆောင်ပါမည် (RPD: ${st.usedCount}/$rpdLimit)။"))
                                    } else {
                                        send(GenerationUpdate.Log("⚠️ $actualModelName အတွက် Keys အားလုံး RPD ပြည့်နေပါသည်။ Key အသစ် လိုအပ်ပါသည်..."))
                                        continue
                                    }
                                }
                            } else {
                                rpdExhaustedAndStoppedByUser = true
                                throw kotlinx.coroutines.CancellationException("RPD_EXHAUSTED_STOP")
                            }
                        }

                        val updatedKeyCount = (usedCountByKey[activeApiKey] ?: 0) + 1
                        usedCountByKey[activeApiKey] = updatedKeyCount
                        if (rpdEnabled) {
                            RpdTracker.recordCall(context, activeApiKey, actualModelName)
                        }

                        if (!hasSwitchedToSpare) {
                            mainTotalCalls = updatedKeyCount
                        } else {
                            spareTotalCalls++
                        }
                        val totalUsed = mainTotalCalls + spareTotalCalls

                        reservedSlot = ReservedKeySlot(
                            apiKey = activeApiKey,
                            fileUri = activeGeminiFileUri,
                            isSpare = hasSwitchedToSpare,
                            keyLabel = activeKeyLabel,
                            slotNumber = updatedKeyCount,
                            totalRpdUsed = totalUsed
                        )
                    }
                    reservedSlot!!
                }
            }

            suspend fun logReservedRequestSent(slot: ReservedKeySlot) {
                if (rpdEnabled) {
                    val remainingOnKey = (rpdLimit - slot.slotNumber).coerceAtLeast(0)
                    send(GenerationUpdate.Log("ℹ️ RPD [${slot.keyLabel}]: Outgoing Request Sent #${slot.slotNumber}/$rpdLimit (Available: $remainingOnKey, Total: ${slot.totalRpdUsed})"))
                }
            }

            suspend fun recordCallOutcome(isSpare: Boolean, success: Boolean) {
                keyMutex.withLock {
                    if (!isSpare) {
                        if (success) mainSuccessCalls++ else mainFailedCalls++
                    } else {
                        if (success) spareSuccessCalls++ else spareFailedCalls++
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

            // Pre-Analysis Step: Extract Character Profile & Protagonist Identity (Plot Spoiler Free)
            // Optimize: Start pre-uploading media for Spare / Parallel Keys in background
            // while the Primary Key extracts character profiles and awaits user confirmation.
            val preuploadJob: kotlinx.coroutines.Job? = if (parallelKeyCount > 1) {
                val candidateSpareKeys = keyMutex.withLock {
                    activeKeyQueue.filter { it.isSpare }
                        .filter { entry ->
                            val used = usedCountByKey[entry.key] ?: if (rpdEnabled) RpdTracker.getStatus(context, entry.key, rpdLimit, actualModelName).usedCount else 0
                            !rpdEnabled || used < rpdLimit
                        }
                        .take(parallelKeyCount - 1)
                }
                if (candidateSpareKeys.isNotEmpty()) {
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                        candidateSpareKeys.forEach { entry ->
                            launch {
                                try {
                                    ensureMediaUploadedForKey(entry.key, entry.label)
                                } catch (_: Exception) {
                                    // Background pre-upload failure will be retried on demand
                                }
                            }
                        }
                    }
                } else null
            } else null

            var confirmedMainCharacter = characterHint.trim()
            val seriesInfo = GeminiFileKeyCache.parseSeriesInfo(mediaFileName)
            val combinedPriorMap = priorSeriesProfiles
                .filterKeys { k -> seriesInfo.partNumber == null || k != seriesInfo.partNumber }
                .toSortedMap()

            fun extractCharacterCoreKey(desc: String): String {
                val trimmed = desc.trim().removePrefix("-").removePrefix("*").trim()
                val beforeBracket = trimmed
                    .substringBefore("(")
                    .substringBefore("（")
                    .substringBefore("[")
                    .substringBefore("【")
                    .substringBefore("/")
                    .substringBefore(" - ")
                    .substringBefore(" — ")
                    .substringBefore(" – ")
                    .substringBefore(":")
                    .substringBefore("：")
                    .trim()
                val candidate = if (beforeBracket.length >= 2) beforeBracket else trimmed
                return candidate.lowercase().replace(Regex("""\s+"""), " ").trim()
            }

            fun parseProfileToDetectedCharacters(profileText: String): List<DetectedCharacter> {
                val result = mutableListOf<DetectedCharacter>()
                profileText.lines().forEach { line ->
                    val trimmed = line.trim().removePrefix("-").removePrefix("*").trim()
                    if (trimmed.contains("CHARACTER_ITEM:", ignoreCase = true) || trimmed.contains("|")) {
                        val content = trimmed.substringAfter("CHARACTER_ITEM:", trimmed).trim()
                        val segments = content.split("|").map { it.trim() }
                        if (segments.size >= 4) {
                            val roleTag = segments[0].uppercase()
                            val isMain = roleTag.contains("MAIN")
                            val desc = segments[1]
                            val relationship = segments[2]
                            val pronoun = segments[3].ifBlank { "ကျွန်တော်" }
                            if (desc.isNotBlank()) {
                                result.add(DetectedCharacter(desc, relationship, pronoun, isMain))
                            }
                        } else if (segments.size >= 2) {
                            val roleTag = segments[0].uppercase()
                            val isMain = roleTag.contains("MAIN")
                            val desc = segments[1]
                            val pronoun = if (segments.size >= 3 && segments[2].isNotBlank()) segments[2] else "ကျွန်တော်"
                            if (desc.isNotBlank()) {
                                result.add(DetectedCharacter(desc, "", pronoun, isMain))
                            }
                        }
                    }
                }
                return result
            }

            fun formatDetectedCharactersToProfile(chars: List<DetectedCharacter>): String {
                return chars.joinToString("\n") { ch ->
                    val role = if (ch.isRecommendedMain) "MAIN" else "SUPPORT"
                    val rel = ch.relationshipStatus.ifBlank { "ဇာတ်ကောင်" }
                    val pron = ch.pronoun.ifBlank { "ကျွန်တော်" }
                    "CHARACTER_ITEM: $role | ${ch.nameAndAppearance} | $rel | $pron"
                }
            }

            fun buildDeduplicatedPriorProfile(priorMap: Map<Int, String>, maxChars: Int = 25): String {
                if (priorMap.isEmpty()) return ""
                val charMap = linkedMapOf<String, DetectedCharacter>()
                val lastSeenPartMap = mutableMapOf<String, Int>()
                val seenCountMap = mutableMapOf<String, Int>()
                priorMap.forEach { (partNum, pText) ->
                    val parsed = parseProfileToDetectedCharacters(pText)
                    parsed.forEach { item ->
                        val coreKey = extractCharacterCoreKey(item.nameAndAppearance)
                        if (coreKey.isNotBlank()) {
                            val existing = charMap[coreKey]
                            if (existing == null) {
                                charMap[coreKey] = item
                                lastSeenPartMap[coreKey] = partNum
                                seenCountMap[coreKey] = 1
                            } else {
                                val mergedRel = item.relationshipStatus.ifBlank { existing.relationshipStatus }
                                val mergedPronoun = item.pronoun.ifBlank { existing.pronoun }
                                val mergedMain = item.isRecommendedMain || existing.isRecommendedMain
                                charMap[coreKey] = DetectedCharacter(
                                    nameAndAppearance = item.nameAndAppearance.ifBlank { existing.nameAndAppearance },
                                    relationshipStatus = mergedRel,
                                    pronoun = mergedPronoun,
                                    isRecommendedMain = mergedMain
                                )
                                lastSeenPartMap[coreKey] = partNum
                                seenCountMap[coreKey] = (seenCountMap[coreKey] ?: 1) + 1
                            }
                        }
                    }
                }
                if (charMap.isEmpty()) {
                    return priorMap.values.last().trim()
                }
                val sortedKeys = charMap.keys.sortedWith(
                    compareByDescending<String> { charMap[it]?.isRecommendedMain == true }
                        .thenByDescending { lastSeenPartMap[it] ?: 0 }
                        .thenByDescending { seenCountMap[it] ?: 0 }
                ).take(maxChars)

                var mainAssigned = false
                val finalChars = sortedKeys.mapNotNull { key ->
                    val ch = charMap[key] ?: return@mapNotNull null
                    val isMain = if (ch.isRecommendedMain && !mainAssigned) {
                        mainAssigned = true
                        true
                    } else false
                    ch.copy(isRecommendedMain = isMain)
                }
                return formatDetectedCharactersToProfile(finalChars)
            }

            fun mergeCurrentWithPriorProfile(currentText: String, deduplicatedPriorText: String, maxChars: Int = 25): String {
                if (deduplicatedPriorText.isBlank()) return currentText.trim()
                val currentChars = parseProfileToDetectedCharacters(currentText)
                val priorChars = parseProfileToDetectedCharacters(deduplicatedPriorText)
                if (currentChars.isEmpty()) return currentText.trim().ifBlank { deduplicatedPriorText.trim() }
                if (priorChars.isEmpty()) return currentText.trim()

                val mergedByCoreKey = linkedMapOf<String, DetectedCharacter>()
                // 1. Keep current part's characters at the top with their latest Relationship Status; fill missing relationship/pronoun from prior if blank
                val priorLookup = priorChars.associateBy { extractCharacterCoreKey(it.nameAndAppearance) }
                currentChars.forEach { cur ->
                    val key = extractCharacterCoreKey(cur.nameAndAppearance)
                    if (key.isNotBlank() && !mergedByCoreKey.containsKey(key)) {
                        val priorMatch = priorLookup[key]
                        val finalRel = cur.relationshipStatus.ifBlank { priorMatch?.relationshipStatus ?: "" }
                        val finalPron = cur.pronoun.ifBlank { priorMatch?.pronoun ?: "ကျွန်တော်" }
                        val finalMain = cur.isRecommendedMain || (currentChars.none { it.isRecommendedMain } && priorMatch?.isRecommendedMain == true)
                        mergedByCoreKey[key] = cur.copy(
                            relationshipStatus = finalRel,
                            pronoun = finalPron,
                            isRecommendedMain = finalMain
                        )
                    }
                }
                // 2. Append prior recurring/important characters that the AI omitted in this part
                priorChars.forEach { prior ->
                    if (mergedByCoreKey.size < maxChars) {
                        val key = extractCharacterCoreKey(prior.nameAndAppearance)
                        if (key.isNotBlank() && !mergedByCoreKey.containsKey(key)) {
                            val hasMainAlready = mergedByCoreKey.values.any { it.isRecommendedMain }
                            mergedByCoreKey[key] = prior.copy(isRecommendedMain = if (hasMainAlready) false else prior.isRecommendedMain)
                        }
                    }
                }
                // Ensure MAIN character is at the top and only one MAIN is marked
                val ordered = mergedByCoreKey.values.sortedByDescending { it.isRecommendedMain }
                var mainSeen = false
                val normalized = ordered.map { ch ->
                    val isMain = if (ch.isRecommendedMain && !mainSeen) {
                        mainSeen = true
                        true
                    } else false
                    ch.copy(isRecommendedMain = isMain)
                }
                return formatDetectedCharactersToProfile(normalized)
            }

            val deduplicatedPriorProfile = buildDeduplicatedPriorProfile(combinedPriorMap)
            val priorContextKey = if (deduplicatedPriorProfile.isNotBlank()) {
                "prior_${deduplicatedPriorProfile.hashCode()}"
            } else ""

            var extractedCharacterProfile = existingCharacterProfile.trim().ifBlank {
                GeminiFileKeyCache.getCachedCharacterProfile(
                    context = context,
                    mediaUri = mediaUri,
                    geminiFileUri = finalGeminiFileUri,
                    characterHint = priorContextKey,
                    language = language,
                    fileName = mediaFileName
                ) ?: ""
            }
            val wasCharacterProfileCached = extractedCharacterProfile.isNotBlank()

            if (extractedCharacterProfile.isNotBlank()) {
                if (deduplicatedPriorProfile.isNotBlank()) {
                    extractedCharacterProfile = mergeCurrentWithPriorProfile(extractedCharacterProfile, deduplicatedPriorProfile)
                }
                send(GenerationUpdate.Log("✅ Cached Character Profile & Relationship Status အား ပြန်လည်အသုံးပြုပါမည်။"))
            } else {
                val hasPriorProfiles = combinedPriorMap.isNotEmpty() && deduplicatedPriorProfile.isNotBlank()
                if (hasPriorProfiles) {
                    val priorPartNames = combinedPriorMap.keys.joinToString(", ") { "Part $it" }
                    send(GenerationUpdate.Log("🔗 အရှေ့အပိုင်းများ ($priorPartNames) မှ Character List နှင့် Relationship Status များကို ဆွဲယူပြီး ယခုအပိုင်းနှင့် ပေါင်းစည်းစစ်ဆေးနေပါသည်..."))
                } else {
                    if (specificPartsToGenerate != null) {
                        send(GenerationUpdate.Log("🔍 Cache ထဲတွင် Character Profile မရှိသေးသဖြင့် အသစ် ပြန်လည်စစ်ဆေးထုတ်ယူနေပါသည်..."))
                    } else {
                        send(GenerationUpdate.Log("🔍 ဗီဒီယိုတစ်ပုဒ်လုံး၏ Character Profile နှင့် ပင်မဇာတ်ဆောင် (Main Protagonist) သတ်မှတ်ချက်ကို ဦးစွာ စစ်ဆေးထုတ်ယူနေပါသည်..."))
                    }
                }

                val hintInstruction = if (characterHint.isNotBlank()) {
                    "User သတ်မှတ်ပေးထားသော အဓိကဇာတ်ဆောင် (User Hint): \"$characterHint\" — ဤသတ်မှတ်ချက်ကို အဓိက ဦးစားပေးအတည်ပြု၍ ပင်မဇာတ်ဆောင်အဖြစ် သတ်မှတ်ပေးပါ။"
                } else {
                    "ဗီဒီယို/အသံဖိုင် တစ်ခုလုံးတွင် အဓိကအကျဆုံး ပင်မဇာတ်ဆောင် (Main Protagonist) တစ်ဦးတည်းကို အတိအကျ ရွေးချယ်သတ်မှတ်ပေးပါ။"
                }
                val priorSeriesInstruction = if (hasPriorProfiles) {
                    val priorPartNames = combinedPriorMap.keys.joinToString(", ") { "Part $it" }
                    buildString {
                        appendLine("အရေးကြီးသော အရှေ့အပိုင်းများ ($priorPartNames) ၏ ပေါင်းစည်းထားသော ဇာတ်ကောင်နှင့် ဆက်ဆံရေး မှတ်တမ်း (Previous Parts Unified Character & Relationship Reference):")
                        appendLine(deduplicatedPriorProfile)
                        appendLine()
                        appendLine("ပေါင်းစည်းရန် စည်းကမ်းချက် (Smart Merge & Deduplication Rule):")
                        appendLine("1. အထက်ပါ အရှေ့အပိုင်းများမှ ဇာတ်ကောင်အားလုံးကို လုံးဝ မချန်လှပ်ဘဲ အခြေခံယူ၍ ယခု Video Part တွင် ပါဝင်သော ဇာတ်ကောင်များနှင့် ပေါင်းစပ်ကာ Unified Character List တစ်ခုတည်းအဖြစ် ထုတ်ပေးပါ။")
                        appendLine("2. ထပ်နေသော ဇာတ်ကောင်များ (Duplicate Characters) ကို လုံးဝ နှစ်ကြောင်းမရေးဘဲ အဓိကနာမည် (Core Name) တူပါက တစ်ကြောင်းတည်းအဖြစ် ပေါင်းစည်း (Merge) ပေးပါ။")
                        appendLine("3. ဇာတ်ကောင်များ၏ အမည်၊ နာမ်စား နှင့် ပင်မဇာတ်ဆောင် (MAIN) သတ်မှတ်ချက်ကို အရှေ့အပိုင်းများနှင့် တသမတ်တည်း (Consistent) ဖြစ်အောင် ထိန်းသိမ်းပါ။")
                        appendLine("4. အကယ်၍ ယခုအပိုင်းတွင် ဇာတ်ကောင်အချင်းချင်း Relationship Status သို့မဟုတ် ဝတ်စုံ ပြောင်းလဲ/တိုးတက်သွားပါက နောက်ဆုံးအခြေအနေ (Updated Relationship Status) ကို အပ်ဒိတ်လုပ်ပေးပြီး၊ ဇာတ်ကောင်အသစ်များ ပါလာပါက အသစ်ထပ်ပေါင်းထည့်ပေးပါ။")
                    }
                } else ""

                val profilePrompt = """
                    ဤ Media ဖိုင်တစ်ခုလုံးကို စစ်ဆေးပြီး အဓိကဇာတ်ကောင်များစာရင်းနှင့် ၎င်းတို့အချင်းချင်း တော်စပ်ပုံ/ဆက်ဆံရေး (Relationship Status) ကို အောက်ပါ သတ်မှတ်ထားသော Format အတိုင်း တိကျရှင်းလင်းစွာ ထုတ်ပေးပါ။
                    (အရေးကြီးသတိပေးချက်: ဇာတ်လမ်းအဖြစ်အပျက်များ / Plot Summary / Spoiler လုံးဝ (လုံးဝ) မထည့်ရပါ။ ဇာတ်ကောင်များ၏ အချက်အလက်နှင့် တော်စပ်ပုံ သက်သက်ကိုသာ ထုတ်ပေးရမည်။)

                    $hintInstruction

                    $priorSeriesInstruction

                    Output Format (ဇာတ်ကောင်တစ်ဦးချင်းစီကို အောက်ပါပုံစံအတိုင်း တစ်ကြောင်းစီ ရေးပေးပါ):
                    CHARACTER_ITEM: [MAIN သို့မဟုတ် SUPPORT] | [အမည်/ခေါ်ဝေါ်ပုံ နှင့် ဝတ်စုံအရောင်/ပုံပန်းသဏ္ဌာန် အမှတ်အသား] | [Relationship Status - ပင်မဇာတ်ဆောင်နှင့် တော်စပ်ပုံ/ဆက်ဆံရေး] | [First-Person POV သုံးရမည့် နာမ်စား - လူကြီး/လူငယ်ဆိုလျှင် ကျွန်တော် သို့မဟုတ် ကျွန်မ၊ ကလေးငယ်ဆိုလျှင် ယောက်ျားလေး သား သို့မဟုတ် မိန်းကလေး သမီး]

                    နာမ်စား သတ်မှတ်ရန် အထူးစည်းကမ်းချက်:
                    - လူကြီး/လူငယ် အမျိုးသား ဖြစ်ပါက -> ကျွန်တော်
                    - လူကြီး/လူငယ် အမျိုးသမီး ဖြစ်ပါက -> ကျွန်မ
                    - ကလေးငယ် (ယောက်ျားလေး) ဖြစ်ပါက 'ကျွန်တော်' ဟု လုံးဝမသုံးဘဲ -> သား ဟု သတ်မှတ်ပါ။
                    - ကလေးငယ် (မိန်းကလေး) ဖြစ်ပါက 'ကျွန်မ' ဟု လုံးဝမသုံးဘဲ -> သမီး ဟု သတ်မှတ်ပါ။

                    ဥပမာ:
                    CHARACTER_ITEM: MAIN | ဂျွန် (အင်္ကျီအနက်ဝတ်ထားသော လူငယ်) | အဓိကဇာတ်ဆောင် (မေရီ၏ ခင်ပွန်း / တွမ်လေး၏ ဖခင်) | ကျွန်တော်
                    CHARACTER_ITEM: SUPPORT | မေရီ (အင်္ကျီအနီဝတ် အမျိုးသမီး) | ဂျွန်၏ ဇနီး | ကျွန်မ
                    CHARACTER_ITEM: SUPPORT | တွမ်လေး (အင်္ကျီအပြာဝတ် ကလေးငယ်) | ဂျွန်နှင့် မေရီတို့၏ သားငယ် | သား
                    CHARACTER_ITEM: SUPPORT | လူစီ (ဂါဝန်အပန်းရောင်ဝတ် ကလေးမလေး) | ဂျွန်နှင့် မေရီတို့၏ သမီးငယ် | သမီး

                    (မှတ်ချက် - ပင်မဇာတ်ဆောင် အဖြစ် အသင့်တော်ဆုံး တစ်ဦးတည်းကိုသာ MAIN ဟု သတ်မှတ်ပြီး ကျန်ဇာတ်ကောင်များကို SUPPORT ဟု သတ်မှတ်ပါ။ ဇာတ်ကောင်တစ်ဦးလျှင် တစ်ကြောင်းသာ ရေးပါ။)
                """.trimIndent()

                // If prior series profiles exist: 1 initial attempt + 1 retry (total 2).
                // If no prior series profiles exist: retry up to 5 attempts based on RPM & RPD.
                val maxProfileAttempts = if (hasPriorProfiles) 2 else 5
                var profileAttempt = 0
                var lastProfileErrorDetail = "Unknown Error"

                while (extractedCharacterProfile.isBlank() && profileAttempt < maxProfileAttempts && !rpdExhaustedAndStoppedByUser) {
                    profileAttempt++
                    if (profileAttempt > 1) {
                        val retryCount = profileAttempt - 1
                        val maxRetries = maxProfileAttempts - 1
                        send(GenerationUpdate.Log("🔄 Character Profile & Relationship Status ပြန်လည်ကြိုးစားနေပါသည် (Retry $retryCount/$maxRetries - Attempt $profileAttempt/$maxProfileAttempts)..."))
                    }

                    val preSlot = try {
                        val slot = getOrSwitchKeyAndFile()
                        acquireRateLimitSlot()
                        slot
                    } catch (ce: kotlinx.coroutines.CancellationException) {
                        if (rpdExhaustedAndStoppedByUser || ce.message == "RPD_EXHAUSTED_STOP") {
                            lastProfileErrorDetail = "RPD Limit ပြည့်သွားသောကြောင့် ရပ်နားလိုက်ပါသည်"
                            break
                        } else {
                            throw ce
                        }
                    }

                    val profileBodyJson = JSONObject().apply {
                        put("contents", JSONArray().apply {
                            put(JSONObject().apply {
                                put("role", "user")
                                put("parts", JSONArray().apply {
                                    put(JSONObject().apply {
                                        put("file_data", JSONObject().apply {
                                            put("mime_type", mediaMimeType ?: "video/mp4")
                                            put("file_uri", preSlot.fileUri)
                                        })
                                    })
                                    put(JSONObject().apply {
                                        put("text", profilePrompt)
                                    })
                                })
                            })
                        })
                    }
                    val profileReqBody = profileBodyJson.toString().toRequestBody("application/json".toMediaTypeOrNull())
                    val profileReq = Request.Builder()
                        .url("https://generativelanguage.googleapis.com/v1beta/models/$actualModelName:generateContent?key=${preSlot.apiKey}")
                        .post(profileReqBody)
                        .build()

                    logReservedRequestSent(preSlot)
                    workingCount.incrementAndGet()
                    inFlightHttpRequests.incrementAndGet()
                    try {
                        client.newCall(profileReq).execute().use { resp ->
                            if (resp.isSuccessful) {
                                val bodyStr = resp.body?.string() ?: "{}"
                                val rootObj = JSONObject(bodyStr)
                                val candidates = rootObj.optJSONArray("candidates")
                                val sb = StringBuilder()
                                if (candidates != null && candidates.length() > 0) {
                                    val partsArr = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
                                    if (partsArr != null) {
                                        for (pIdx in 0 until partsArr.length()) {
                                            sb.append(partsArr.getJSONObject(pIdx).optString("text", ""))
                                        }
                                    }
                                }
                                val textOut = sb.toString().trim()
                                if (textOut.isNotBlank()) {
                                    val mergedTextOut = mergeCurrentWithPriorProfile(textOut, deduplicatedPriorProfile)
                                    extractedCharacterProfile = mergedTextOut
                                    recordCallOutcome(preSlot.isSpare, true)
                                    GeminiFileKeyCache.saveCachedCharacterProfile(
                                        context = context,
                                        mediaUri = mediaUri,
                                        geminiFileUri = finalGeminiFileUri,
                                        characterHint = priorContextKey,
                                        language = language,
                                        profileText = mergedTextOut,
                                        fileName = mediaFileName
                                    )
                                    send(GenerationUpdate.Log("✅ Character Profile & Relationship Status ရရှိပြီးစီးပါပြီ [${preSlot.keyLabel}]။"))
                                } else {
                                    recordCallOutcome(preSlot.isSpare, false)
                                    lastProfileErrorDetail = "Empty Response [${preSlot.keyLabel}]"
                                    send(GenerationUpdate.Log("⚠️ Character Profile (${profileAttempt}/$maxProfileAttempts) [${preSlot.keyLabel}] Failed: Empty Response"))
                                }
                            } else {
                                recordCallOutcome(preSlot.isSpare, false)
                                val errBody = resp.body?.string() ?: ""
                                val errMsg = extractApiErrorMessage(resp.code, resp.message, errBody)
                                lastProfileErrorDetail = "HTTP ${resp.code} ($errMsg) [${preSlot.keyLabel}]"
                                send(GenerationUpdate.Log("⚠️ Character Profile (${profileAttempt}/$maxProfileAttempts) [${preSlot.keyLabel}] Failed: HTTP ${resp.code} ($errMsg)"))
                            }
                        }
                    } catch (e: Exception) {
                        recordCallOutcome(preSlot.isSpare, false)
                        val shortNetErr = (e.message ?: "Connection failed").lineSequence().firstOrNull()?.take(50) ?: "Connection failed"
                        lastProfileErrorDetail = "Network Error (${e.javaClass.simpleName}: $shortNetErr) [${preSlot.keyLabel}]"
                        send(GenerationUpdate.Log("⚠️ Character Profile (${profileAttempt}/$maxProfileAttempts) [${preSlot.keyLabel}] Failed: ${e.javaClass.simpleName} ($shortNetErr)"))
                    } finally {
                        workingCount.decrementAndGet()
                        inFlightHttpRequests.decrementAndGet()
                    }
                }

                if (extractedCharacterProfile.isBlank()) {
                    if (hasPriorProfiles) {
                        // Fallback to deduplicated prior series parts' character list & relationship status
                        val fallbackPriorText = deduplicatedPriorProfile.ifBlank { combinedPriorMap.values.last().trim() }
                        extractedCharacterProfile = fallbackPriorText
                        val priorPartNames = combinedPriorMap.keys.joinToString(", ") { "Part $it" }
                        send(GenerationUpdate.Log("ℹ️ Character Profile အသစ်ရယူမှု မအောင်မြင်သဖြင့် အရှေ့အပိုင်း ($priorPartNames) မှ ပေါင်းစည်းထားသော Character List နှင့် Relationship Status အား တိုက်ရိုက် ယူသုံးပါမည်။"))
                    } else {
                        // No prior series profiles available and all 5 attempts failed: end processing, show 15s auto-close dialog, and leave failed record in History
                        val fatalMsg = "Character List နှင့် Relationship Status ရယူမှု ($maxProfileAttempts) ကြိမ်တိုင်တိုင် မအောင်မြင်ပါ။\n\nError Code: $lastProfileErrorDetail\n\nProcessing ကို ရပ်နားလိုက်ပါပြီ။ ခေတ္တစောင့်ဆိုင်းပြီးမှ History ထဲမှနေ၍ ပြန်လည်ကြိုးစားပေးပါ။"
                        send(GenerationUpdate.Log("❌ $fatalMsg"))
                        val firstPartIdx = partsToProcess.firstOrNull() ?: 0
                        send(
                            GenerationUpdate.ScriptChunk(
                                partIndex = firstPartIdx,
                                text = "❌ [Failed: Character List နှင့် Relationship Status ရယူမှု ($maxProfileAttempts) ကြိမ် မအောင်မြင်ပါ — $lastProfileErrorDetail]\n[အပိုင်း ${firstPartIdx + 1} - နေရာလွတ်...]\n",
                                isError = true,
                                clearFirst = true
                            )
                        )
                        send(GenerationUpdate.ProfileFatalError(fatalMsg))
                        send(GenerationUpdate.Complete)
                        return@channelFlow
                    }
                }
            }

            // Parse detected characters for user confirmation & custom name editing dialog
            var customCharacterNameRules = listOf<String>()
            if (extractedCharacterProfile.isNotBlank() && (specificPartsToGenerate == null || !wasCharacterProfileCached)) {
                val parsedCharacters = mutableListOf<DetectedCharacter>()
                extractedCharacterProfile.lines().forEach { line ->
                    val trimmed = line.trim().removePrefix("-").removePrefix("*").trim()
                    if (trimmed.contains("CHARACTER_ITEM:", ignoreCase = true) || trimmed.contains("|")) {
                        val content = trimmed.substringAfter("CHARACTER_ITEM:", trimmed).trim()
                        val segments = content.split("|").map { it.trim() }
                        if (segments.size >= 4) {
                            val roleTag = segments[0].uppercase()
                            val isMain = roleTag.contains("MAIN")
                            val desc = segments[1]
                            val relationship = segments[2]
                            val pronoun = segments[3].ifBlank { "ကျွန်တော်" }
                            if (desc.isNotBlank()) {
                                parsedCharacters.add(DetectedCharacter(desc, relationship, pronoun, isMain))
                            }
                        } else if (segments.size >= 2) {
                            val roleTag = segments[0].uppercase()
                            val isMain = roleTag.contains("MAIN")
                            val desc = segments[1]
                            val pronoun = if (segments.size >= 3 && segments[2].isNotBlank()) segments[2] else "ကျွန်တော်"
                            if (desc.isNotBlank()) {
                                parsedCharacters.add(DetectedCharacter(desc, "", pronoun, isMain))
                            }
                        }
                    }
                }
                if (parsedCharacters.isEmpty()) {
                    // Fallback single item from non-empty lines if model didn't strictly use pipes
                    val firstMeaningful = extractedCharacterProfile.lines()
                        .map { it.trim() }
                        .firstOrNull { it.isNotBlank() && !it.startsWith("1.") && !it.startsWith("2.") }
                        ?: extractedCharacterProfile.lines().firstOrNull { it.isNotBlank() }?.trim()
                        ?: ""
                    if (firstMeaningful.isNotBlank()) {
                        val pronoun = when {
                            extractedCharacterProfile.contains("သမီး") -> "သမီး"
                            extractedCharacterProfile.contains("သား") -> "သား"
                            extractedCharacterProfile.contains("ကျွန်မ") -> "ကျွန်မ"
                            else -> "ကျွန်တော်"
                        }
                        parsedCharacters.add(DetectedCharacter(firstMeaningful.take(120), "", pronoun, true))
                    }
                }

                if (parsedCharacters.isNotEmpty() && characterConfirmationChannel != null) {
                    val hintCoreKey = extractCharacterCoreKey(characterHint)
                    val recIdx = when {
                        hintCoreKey.isNotBlank() -> {
                            val byHint = parsedCharacters.indexOfFirst { extractCharacterCoreKey(it.nameAndAppearance) == hintCoreKey }
                            if (byHint >= 0) byHint else parsedCharacters.indexOfFirst { it.isRecommendedMain }.let { if (it >= 0) it else 0 }
                        }
                        else -> parsedCharacters.indexOfFirst { it.isRecommendedMain }.let { if (it >= 0) it else 0 }
                    }
                    send(GenerationUpdate.Log("⏳ User ထံမှ Character စာရင်း အတည်ပြုချက် (10s Auto-Continue) စောင့်ဆိုင်းနေပါသည်..."))
                    send(GenerationUpdate.RequireCharacterConfirmation(
                        characters = parsedCharacters,
                        recommendedIndex = recIdx,
                        rawProfileText = extractedCharacterProfile
                    ))
                    val rawConfirmationResponse = characterConfirmationChannel.receive().trim()
                    if (rawConfirmationResponse.startsWith("{")) {
                        try {
                            val respObj = JSONObject(rawConfirmationResponse)
                            val selectedMain = respObj.optString("selectedMainSummary", "").trim()
                            val updatedProfile = respObj.optString("updatedProfileText", "").trim()
                            val rulesJsonArr = respObj.optJSONArray("customNameRules")
                            val rulesList = mutableListOf<String>()
                            if (rulesJsonArr != null) {
                                for (rIdx in 0 until rulesJsonArr.length()) {
                                    val rStr = rulesJsonArr.optString(rIdx, "").trim()
                                    if (rStr.isNotBlank()) rulesList.add(rStr)
                                }
                            }
                            customCharacterNameRules = rulesList
                            if (updatedProfile.isNotBlank()) {
                                extractedCharacterProfile = updatedProfile
                                GeminiFileKeyCache.saveCachedCharacterProfile(
                                    context = context,
                                    mediaUri = mediaUri,
                                    geminiFileUri = finalGeminiFileUri,
                                    characterHint = priorContextKey,
                                    language = language,
                                    profileText = updatedProfile,
                                    fileName = mediaFileName
                                )
                            }
                            if (isMainCharPov && selectedMain.isNotBlank()) {
                                confirmedMainCharacter = selectedMain
                                send(GenerationUpdate.Log("👤 အတည်ပြုလိုက်သော ပင်မဇာတ်ဆောင် (Main Character): $confirmedMainCharacter"))
                            }
                            if (customCharacterNameRules.isNotEmpty()) {
                                customCharacterNameRules.forEach { rule ->
                                    send(GenerationUpdate.Log("✏️ Custom Character Name သတ်မှတ်ချက်: $rule"))
                                }
                            }
                        } catch (_: Exception) {
                            if (isMainCharPov && rawConfirmationResponse.isNotBlank()) {
                                confirmedMainCharacter = rawConfirmationResponse
                                send(GenerationUpdate.Log("👤 အတည်ပြုလိုက်သော ပင်မဇာတ်ဆောင် (Main Character): $confirmedMainCharacter"))
                            }
                        }
                    } else if (isMainCharPov && rawConfirmationResponse.isNotBlank()) {
                        confirmedMainCharacter = rawConfirmationResponse
                        send(GenerationUpdate.Log("👤 အတည်ပြုလိုက်သော ပင်မဇာတ်ဆောင် (Main Character): $confirmedMainCharacter"))
                    }
                }
            }

            if (extractedCharacterProfile.isNotBlank()) {
                send(GenerationUpdate.CharacterProfileSaved(
                    profileText = extractedCharacterProfile,
                    confirmedMainCharacter = confirmedMainCharacter
                ))
            }

            val characterContinuityRules = buildString {
                if (isMainCharPov && confirmedMainCharacter.isNotBlank()) {
                    appendLine("- အတည်ပြုထားသော ပင်မဇာတ်ဆောင် (Confirmed Main Protagonist): $confirmedMainCharacter (ဤဇာတ်ကောင် တစ်ဦးတည်းကိုသာ ပင်မဇာတ်ဆောင်အဖြစ် သတ်မှတ်ရန်)")
                }
                if (extractedCharacterProfile.isNotBlank()) {
                    appendLine("- ဗီဒီယိုတစ်ပုဒ်လုံး၏ Character Profile & Supporting Characters:")
                    appendLine(extractedCharacterProfile)
                }
                if (customCharacterNameRules.isNotEmpty()) {
                    appendLine("- ဇာတ်ကောင်အမည် ပြောင်းလဲရေးသားရန် အထူးညွှန်ကြားချက် (Custom Character Name Override Rules):")
                    customCharacterNameRules.forEach { rule ->
                        appendLine("  * $rule")
                    }
                }
                if (isMainCharPov) {
                    appendLine("- Main Character Continuity စည်းကမ်းချက်: အထက်ပါ အတည်ပြုထားသော ပင်မဇာတ်ဆောင် (Main Protagonist) တစ်ဦးတည်းကိုသာ Part 1 မှ နောက်ဆုံး Part အထိ သတ်မှတ်ထားသော နာမ်စား ('ကျွန်တော်/ကျွန်မ' သို့မဟုတ် ကလေးငယ်ဖြစ်ပါက ယောက်ျားလေး 'သား' / မိန်းကလေး 'သမီး') အဖြစ် တသမတ်တည်း သုံးစွဲရမည်။")
                    appendLine("- ကလေးငယ် နာမ်စား စည်းကမ်းချက်: ကလေးငယ် ဇာတ်ကောင်များအတွက် 'ကျွန်တော်/ကျွန်မ' ဟု လုံးဝ မသုံးရဘဲ ယောက်ျားလေးဆိုလျှင် 'သား'၊ မိန်းကလေးဆိုလျှင် 'သမီး' ဟုသာ အသက်အရွယ်နှင့် လိုက်ဖက်ညီစွာ သုံးနှုန်းရမည်။")
                    appendLine("- Relationship Status အသုံးပြုရန် စည်းကမ်းချက်: အခြားဇာတ်ကောင်များကို ရည်ညွှန်းရာတွင် အထက်ပါ Character Profile ရှိ ပင်မဇာတ်ဆောင်နှင့် တော်စပ်ပုံ/ဆက်ဆံရေး (Relationship Status - ဥပမာ ကျွန်တော့်အမေ / သားရဲ့အမေ / သမီးရဲ့အဖေ၊ ကျွန်တော့်ချစ်သူ၊ ကျွန်တော့်သူငယ်ချင်း စသည်) အတိုင်း သဘာဝကျကျ တသမတ်တည်း ခေါ်ဝေါ်ရေးသားရန်။")
                    appendLine("- မြင်ကွင်းထဲ မပါချိန် စည်းကမ်းချက် (Off-Screen Continuity Rule): အကယ်၍ ဤစက္ကန့်ပိုင်းအတွင်း ပင်မဇာတ်ဆောင် ကိုယ်တိုင် မြင်ကွင်းထဲ မပါဝင်ဘဲ အခြားဇာတ်ကောင်များသာ ပေါ်နေပါက၊ အခြားဇာတ်ကောင်ကို 'ကျွန်တော်/ကျွန်မ/သား/သမီး' ဟု လုံးဝ မပြောင်းဘဲ၊ ပင်မဇာတ်ဆောင်က အဆိုပါအခြားဇာတ်ကောင်များ၏ လှုပ်ရှားမှုကို (သူက... / အဲ့ဒီလူက.../ (နာမည်/တော်စပ်ပုံ)က ဟု) ပြန်ပြောပြသည့် ပုံစံဖြင့်သာ ရေးသားရန်။")
                } else {
                    appendLine("- Character & Relationship Continuity စည်းကမ်းချက်: အပိုင်းတိုင်းတွင် ဇာတ်ကောင်များ၏ အမည်၊ ခေါ်ဝေါ်ပုံ၊ ကလေးငယ်များအတွက် 'သား/သမီး' နာမ်စားအသုံးအနှုန်း နှင့် အချင်းချင်း တော်စပ်ပုံ (Relationship Status) များကို အထက်ပါ Character Profile အတိုင်း တသမတ်တည်း သုံးစွဲရန်။")
                }
            }.trim()

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
                    val requestedParallelKeys = parallelKeyCount.coerceIn(1, 10)
                    val eligibleInitialKeys = keyMutex.withLock {
                        activeKeyQueue.filter { entry ->
                            val used = usedCountByKey[entry.key] ?: if (rpdEnabled) RpdTracker.getStatus(context, entry.key, rpdLimit, actualModelName).usedCount else 0
                            usedCountByKey[entry.key] = used
                            !rpdEnabled || used < rpdLimit
                        }
                    }
                    val targetParallelCount = minOf(requestedParallelKeys, eligibleInitialKeys.size.coerceAtLeast(1), partsToProcess.size.coerceAtLeast(1))
                    val initialParallelEntries = eligibleInitialKeys.take(targetParallelCount)
                    val claimedKeySet = java.util.Collections.synchronizedSet(mutableSetOf<String>())
                    initialParallelEntries.forEach { claimedKeySet.add(it.key) }

                    if (initialParallelEntries.size > 1) {
                        send(GenerationUpdate.Log("⚡ Parallel Key Mode: Key (${initialParallelEntries.size}) ခု [${initialParallelEntries.joinToString(", ") { it.label }}] ဖြင့် စတင်ပါမည် (Ready ဖြစ်သော Key မှ ချက်ချင်း စတင်ထုတ်လုပ်ပါမည်)..."))
                    }

                    suspend fun tryReserveSlotForSpecificKey(entry: ActiveKeyEntry): ReservedKeySlot? {
                        return keyMutex.withLock {
                            val curUsed = usedCountByKey[entry.key] ?: if (rpdEnabled) RpdTracker.getStatus(context, entry.key, rpdLimit, actualModelName).usedCount else 0
                            usedCountByKey[entry.key] = curUsed
                            if (rpdEnabled && curUsed >= rpdLimit) {
                                return@withLock null
                            }
                            val updatedKeyCount = curUsed + 1
                            usedCountByKey[entry.key] = updatedKeyCount
                            if (rpdEnabled) {
                                RpdTracker.recordCall(context, entry.key, actualModelName)
                            }
                            if (!entry.isSpare) {
                                mainTotalCalls = updatedKeyCount
                            } else {
                                hasSwitchedToSpare = true
                                spareTotalCalls++
                            }
                            val totalUsed = mainTotalCalls + spareTotalCalls
                            val fileUri = uploadedFileUriByKey[entry.key] ?: activeGeminiFileUri
                            ReservedKeySlot(
                                apiKey = entry.key,
                                fileUri = fileUri,
                                isSpare = entry.isSpare,
                                keyLabel = entry.label,
                                slotNumber = updatedKeyCount,
                                totalRpdUsed = totalUsed
                            )
                        }
                    }

                    suspend fun claimNextStandbyKey(): ActiveKeyEntry? {
                        val candidate = keyMutex.withLock {
                            activeKeyQueue.firstOrNull { entry ->
                                if (claimedKeySet.contains(entry.key)) return@firstOrNull false
                                val used = usedCountByKey[entry.key] ?: if (rpdEnabled) RpdTracker.getStatus(context, entry.key, rpdLimit, actualModelName).usedCount else 0
                                usedCountByKey[entry.key] = used
                                if (rpdEnabled && used >= rpdLimit) {
                                    claimedKeySet.add(entry.key)
                                    false
                                } else {
                                    claimedKeySet.add(entry.key)
                                    true
                                }
                            }
                        }
                        if (candidate != null) {
                            send(GenerationUpdate.Log("🔄 Next Standby Key [${candidate.label}] သို့ အလိုအလျောက် ပြောင်းလဲချိတ်ဆက်နေပါသည်..."))
                            ensureMediaUploadedForKey(candidate.key, candidate.label)
                            if (candidate.isSpare) {
                                keyMutex.withLock { hasSwitchedToSpare = true }
                            }
                        }
                        return candidate
                    }

                    suspend fun executeSegmentPart(i: Int, initialKeyAndFile: ReservedKeySlot, preferredEntry: ActiveKeyEntry?) {
                        val startMs = i * segmentDurationMs
                        val endMs = if (i == parts - 1) durationMs else (i + 1) * segmentDurationMs
                        val startStr = formatTime(startMs)
                        val endStr = formatTime(endMs)

                        try {
                            val segmentSubtitles = if (parsedSubtitles.isNotEmpty()) {
                                SrtParser.getSubtitlesForSegment(parsedSubtitles, startMs, endMs)
                            } else ""

                            val subtitleContext = if (segmentSubtitles.isNotBlank()) {
                                "\n\nစကားပြောစာတန်းထိုးများ (Subtitles Transcript in $startStr - $endStr):\n$segmentSubtitles\n(ဤစကားပြောစာသားများကို အခြေခံ၍ ဇာတ်ကောင်များပြောဆိုမှု၏ ဆိုလိုရင်း Context ကို အတိအကျ ဖော်ထုတ်ရေးသားပါ။)"
                            } else ""

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
                                သင်သည် '$tone' လေသံနှင့် '$pov' ရှုထောင့်ကို အထူးကျွမ်းကျင်သော PrGt's Script (ဇာတ်လမ်းအကျဉ်း) ဇာတ်ကြောင်းပြောသူ (Narrator) ဖြစ်သည်။
                                
                                ညွှန်ကြားချက်များ:
                                1. Input Media Analysis:
                                $modalityInstruction
                                2. Narrator POV: $povDetail
                                3. Character Profile & Continuity:
                                $characterContinuityRules
                                4. Script Tone: $toneDetail
                                5. Length Constraint: $lengthInstruction
                                6. Spoken Ready: Timestamp များ၊ Scene Number များ၊ ဇယားများ၊ ကွင်းစကွင်းပိတ် ညွှန်းဆိုချက်များ လုံးဝ (လုံးဝ) မထည့်ဘဲ၊ လူကိုယ်တိုင် အသံထွက်ဖတ်ရန် အဆင်သင့်ဖြစ်သော စကားပြောသဘာဝအတိုင်း "ဇာတ်ကြောင်းပြော စာသားသက်သက်" ကိုသာ ထုတ်ပေးပါ။ (စာအုပ်ဖတ်ပြသည့် စာစကား/စာအရေးအသားပုံစံ လုံးဝမဖြစ်စေရ၊ လူပြောစကားသဘာဝအတိုင်းသာ ဖြစ်ရမည်။)
                                7. Timing & Context: $timingConstraint$subtitleContext
                                8. Language Constraints: $languageConstraint
                            """.trimIndent()

                            val prompt = "အထက်ပါ ညွှန်ကြားချက်များအတိုင်း $timingConstraint ကို '$tone' လေသံ ($toneDetail) နှင့် '$pov' ရှုထောင့်အတိုင်း အတိအကျ လိုက်နာ၍ PrGt's Script ရေးသားပေးပါ။"

                             var success = false
                            var attempts = 0
                            val maxAttempts = 3
                            var lastUsedKeyLabel = initialKeyAndFile.keyLabel

                            while (!success && attempts < maxAttempts) {
                                attempts++
                                val activeSlot = if (attempts == 1) {
                                    initialKeyAndFile
                                } else {
                                    send(GenerationUpdate.Log("Retrying Segment ${i + 1}/$parts (Attempt $attempts/$maxAttempts)..."))
                                    val retryKeyAndFile = preferredEntry?.let { tryReserveSlotForSpecificKey(it) } ?: getOrSwitchKeyAndFile()
                                    acquireRateLimitSlotForKey(retryKeyAndFile.apiKey)
                                    retryKeyAndFile
                                }
                                val callApiKey = activeSlot.apiKey
                                val callFileUri = activeSlot.fileUri
                                val isSpareCall = activeSlot.isSpare
                                lastUsedKeyLabel = activeSlot.keyLabel
                                val keyLabel = " [${activeSlot.keyLabel}]"

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

                                logReservedRequestSent(activeSlot)
                                workingCount.incrementAndGet()
                                inFlightHttpRequests.incrementAndGet()

                                try {
                                    client.newCall(request).execute().use { response ->
                                        if (!response.isSuccessful) {
                                            recordCallOutcome(isSpareCall, false)
                                            val errorBody = response.body?.string() ?: ""
                                            val errorDetail = extractApiErrorMessage(response.code, response.message, errorBody)
                                            send(GenerationUpdate.Log("❌ Part ${i + 1} ($attempts/$maxAttempts)$keyLabel Failed: HTTP ${response.code} ($errorDetail)"))
                                            if (attempts == maxAttempts) {
                                                send(GenerationUpdate.ScriptChunk(i, "❌ [Part ${i + 1} Failed$keyLabel: HTTP ${response.code} ($errorDetail)]\n", isError = true))
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
                                                val keyTag = "[${activeSlot.keyLabel}]"
                                                send(GenerationUpdate.Log("✅ Part ${i + 1}/$parts ($startStr - $endStr) completed successfully with $keyTag."))
                                            } else {
                                                throw Exception("Empty response")
                                            }
                                        } catch (e: Exception) {
                                            recordCallOutcome(isSpareCall, false)
                                            val shortParseErr = (e.message ?: "Parse error").lineSequence().firstOrNull()?.take(50) ?: "Parse error"
                                            send(GenerationUpdate.Log("❌ Part ${i + 1} ($attempts/$maxAttempts)$keyLabel Failed: $shortParseErr"))
                                            if (attempts == maxAttempts) {
                                                send(GenerationUpdate.ScriptChunk(i, "❌ [Part ${i + 1} Failed$keyLabel: $shortParseErr]\n", isError = true))
                                            }
                                            return@use
                                        }
                                    }
                                } catch (e: Exception) {
                                    recordCallOutcome(isSpareCall, false)
                                    val shortNetErr = (e.message ?: "Connection failed").lineSequence().firstOrNull()?.take(50) ?: "Connection failed"
                                    send(GenerationUpdate.Log("❌ Part ${i + 1} ($attempts/$maxAttempts)$keyLabel Failed: ${e.javaClass.simpleName} ($shortNetErr)"))
                                    if (attempts == maxAttempts) {
                                        send(GenerationUpdate.ScriptChunk(i, "❌ [Part ${i + 1} Failed$keyLabel: ${e.javaClass.simpleName} ($shortNetErr)]\n", isError = true))
                                    }
                                } finally {
                                    workingCount.decrementAndGet()
                                    inFlightHttpRequests.decrementAndGet()
                                }
                            }

                            if (success) {
                                val currentSuccess = successfulCount.incrementAndGet()
                                val currentProgress = 0.15f + (0.85f * (currentSuccess.toFloat() / maxOf(1, totalToProcess)))
                                send(GenerationUpdate.Progress(currentProgress))
                            } else {
                                failedCount.incrementAndGet()
                                failedParts.add(i)
                                send(GenerationUpdate.Log("❌ Part ${i + 1} [$lastUsedKeyLabel] failed after $maxAttempts attempts."))
                                send(GenerationUpdate.ScriptChunk(i, "[အပိုင်း (${i + 1}) [$lastUsedKeyLabel] ၃ ကြိမ် ကြိုးစားသော်လည်း မအောင်မြင်ပါ။]\n", isError = true))
                            }
                        } finally {
                            // workingCount is managed per in-flight HTTP request
                        }
                    }

                    coroutineScope {
                        // Queue all parts upfront so the user sees them queued before rate-limited dispatch
                        partsToProcess.forEach { i ->
                            val startMs = i * segmentDurationMs
                            val endMs = if (i == parts - 1) durationMs else (i + 1) * segmentDurationMs
                            val startStr = formatTime(startMs)
                            val endStr = formatTime(endMs)
                            send(GenerationUpdate.ScriptChunk(i, "", clearFirst = true))
                            send(GenerationUpdate.Log("[$actualModelName] Queued Part ${i + 1}/$parts ($startStr - $endStr)..."))
                        }

                        val overflowPendingQueue = java.util.concurrent.ConcurrentLinkedQueue<Int>()

                        if (initialParallelEntries.isEmpty()) {
                            overflowPendingQueue.addAll(partsToProcess)
                        } else {
                            val kCount = initialParallelEntries.size
                            val totalN = partsToProcess.size
                            val basePerKey = totalN / kCount
                            val remPerKey = totalN % kCount
                            val workerAssignments = mutableListOf<Pair<ActiveKeyEntry, List<Int>>>()
                            var cursor = 0
                            for (w in 0 until kCount) {
                                val countForWorker = basePerKey + (if (w < remPerKey) 1 else 0)
                                val slice = partsToProcess.subList(cursor, cursor + countForWorker)
                                cursor += countForWorker
                                workerAssignments.add(initialParallelEntries[w] to slice)
                                if (kCount > 1 && slice.isNotEmpty()) {
                                    send(GenerationUpdate.Log("📋 [${initialParallelEntries[w].label}] တာဝန်ယူမည့် အပိုင်းများ: Part ${slice.first() + 1} to Part ${slice.last() + 1} (${slice.size} ပိုင်း)"))
                                }
                            }

                            // Run all assigned key workers in parallel
                            coroutineScope {
                                workerAssignments.forEach { (initialEntry, assignedSlice) ->
                                    launch {
                                        var currentWorkerEntry: ActiveKeyEntry? = initialEntry
                                        var readyToRunAssignedSlice = true
                                        if (currentWorkerEntry != null) {
                                            val entryKey = currentWorkerEntry.key
                                            val entryLabel = currentWorkerEntry.label
                                            val existingUri = uploadedFileUriByKey[entryKey]
                                            if (existingUri.isNullOrBlank()) {
                                                send(GenerationUpdate.Log("⏳ [$entryLabel] Media Upload ပြီးစီးရန် စောင့်ဆိုင်းနေပါသည်..."))
                                                try {
                                                    ensureMediaUploadedForKey(entryKey, entryLabel)
                                                    if (currentWorkerEntry.isSpare) {
                                                        keyMutex.withLock { hasSwitchedToSpare = true }
                                                    }
                                                    send(GenerationUpdate.Log("✅ [$entryLabel] Media ဖိုင် အဆင်သင့်ဖြစ်ပါပြီ။ သတ်မှတ်ထားသော အပိုင်းများကို စတင်ထုတ်လုပ်ပါမည်။"))
                                                } catch (e: Exception) {
                                                    send(GenerationUpdate.Log("❌ [$entryLabel] Media Upload မအောင်မြင်ပါ (${e.message})။ ဤ Key ၏ အပိုင်းများကို အခြား Key သို့ လွှဲပြောင်းပါမည်..."))
                                                    overflowPendingQueue.addAll(assignedSlice)
                                                    currentWorkerEntry = claimNextStandbyKey()
                                                    readyToRunAssignedSlice = false
                                                }
                                            } else {
                                                if (currentWorkerEntry.isSpare) {
                                                    keyMutex.withLock { hasSwitchedToSpare = true }
                                                }
                                            }
                                        }

                                        // Phase A: Process this key's contiguous assigned parts
                                        if (readyToRunAssignedSlice) {
                                            for (idx in assignedSlice.indices) {
                                                val partIdx = assignedSlice[idx]
                                                val activeEntry = currentWorkerEntry
                                                val slot = activeEntry?.let { tryReserveSlotForSpecificKey(it) }
                                                if (slot != null) {
                                                    acquireRateLimitSlotForKey(slot.apiKey)
                                                    launch {
                                                        executeSegmentPart(partIdx, slot, activeEntry)
                                                    }
                                                } else {
                                                    // Current key reached its RPD limit before finishing its assigned parts!
                                                    val unfinishedParts = assignedSlice.subList(idx, assignedSlice.size)
                                                    overflowPendingQueue.addAll(unfinishedParts)
                                                    send(
                                                        GenerationUpdate.Log(
                                                            "⚠️ ${activeEntry?.label ?: "Key"} သည် RPD Limit ($rpdLimit/$rpdLimit) ပြည့်သွားသဖြင့် ကျန်ရှိသော အပိုင်း (${unfinishedParts.map { it + 1 }.joinToString(", ")}) ကို Next Key များဆီသို့ Pending အနေဖြင့် လွှဲပြောင်းတန်းဆီလိုက်ပါပြီ။"
                                                        )
                                                    )
                                                    currentWorkerEntry = claimNextStandbyKey()
                                                    break
                                                }
                                            }
                                        }

                                        // Phase B: Help drain overflowPendingQueue using remaining RPD on this key or standby keys
                                        while (currentWorkerEntry != null) {
                                            val pendingPart = overflowPendingQueue.poll() ?: break
                                            var slot = tryReserveSlotForSpecificKey(currentWorkerEntry!!)
                                            while (slot == null) {
                                                val exhaustedLabel = currentWorkerEntry!!.label
                                                send(GenerationUpdate.Log("⚠️ $exhaustedLabel သည် RPD Limit ($rpdLimit/$rpdLimit) ပြည့်သွားပါပြီ။"))
                                                currentWorkerEntry = claimNextStandbyKey()
                                                if (currentWorkerEntry == null) break
                                                slot = tryReserveSlotForSpecificKey(currentWorkerEntry!!)
                                            }
                                            if (slot != null && currentWorkerEntry != null) {
                                                val assignedEntry = currentWorkerEntry!!
                                                send(GenerationUpdate.Log("🔄 [Pending Queue] Part ${pendingPart + 1}/$parts ကို ${assignedEntry.label} ဖြင့် ဆက်လက်ထုတ်လုပ်နေပါသည်..."))
                                                acquireRateLimitSlotForKey(slot.apiKey)
                                                launch {
                                                    executeSegmentPart(pendingPart, slot, assignedEntry)
                                                }
                                            } else {
                                                overflowPendingQueue.add(pendingPart)
                                                break
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Phase C: If any overflow pending parts remain (e.g. if a worker finished early before another worker overflowed, or all keys hit RPD limit)
                        try {
                            while (overflowPendingQueue.isNotEmpty() && !rpdExhaustedAndStoppedByUser) {
                                val pendingPart = overflowPendingQueue.poll() ?: break
                                val fallbackSlot = getOrSwitchKeyAndFile()
                                send(GenerationUpdate.Log("🔄 [Pending Queue] Part ${pendingPart + 1}/$parts ကို ${fallbackSlot.keyLabel} ဖြင့် ဆက်လက်ထုတ်လုပ်နေပါသည်..."))
                                acquireRateLimitSlotForKey(fallbackSlot.apiKey)
                                launch {
                                    executeSegmentPart(pendingPart, fallbackSlot, null)
                                }
                            }
                        } catch (ce: kotlinx.coroutines.CancellationException) {
                            if (!rpdExhaustedAndStoppedByUser && ce.message != "RPD_EXHAUSTED_STOP") {
                                throw ce
                            }
                        }
                    }
        
        // Pass 2+: Automatically retry failed parts as long as RPD/Keys are available
        remainingFailedParts = failedParts.toList()
        var autoRetryRound = 0
        val maxOffModeAutoRetryRounds = 10

        while (remainingFailedParts.isNotEmpty() && !rpdExhaustedAndStoppedByUser) {
            autoRetryRound++
            if (!rpdEnabled && autoRetryRound > maxOffModeAutoRetryRounds) {
                send(GenerationUpdate.Log("⚠️ RPD OFF mode တွင် အပိုင်း (${remainingFailedParts.map { it + 1 }.joinToString(", ")}) အား အကြိမ်ရေပြည့်အောင် ကြိုးစားသော်လည်း မအောင်မြင်သဖြင့် ရပ်နားပါမည်။"))
                break
            }

            send(GenerationUpdate.Log("🔄 Auto-Retry Round #$autoRetryRound: Retrying ${remainingFailedParts.size} failed segment(s) while RPD/Keys are available..."))
            val newlyFailed = java.util.Collections.synchronizedList(mutableListOf<Int>())
            
            try {
                coroutineScope {
                    for (i in remainingFailedParts) {
                        val startMs = i * segmentDurationMs
                        val endMs = if (i == parts - 1) durationMs else (i + 1) * segmentDurationMs
                        val startStr = formatTime(startMs)
                        val endStr = formatTime(endMs)
                        failedCount.decrementAndGet()
                        send(GenerationUpdate.Log("Queued Retry Part ${i + 1}/$parts [$startStr - $endStr]..."))
                        send(GenerationUpdate.ScriptChunk(i, "", clearFirst = true)) // Clear error message
                    }

                    for (i in remainingFailedParts) {
                        val startMs = i * segmentDurationMs
                        val endMs = if (i == parts - 1) durationMs else (i + 1) * segmentDurationMs
                        val startStr = formatTime(startMs)
                        val endStr = formatTime(endMs)

                        // Acquire key and rate-limit slot sequentially BEFORE launching so retry parts also send in strict order
                        val initialRetryKeyAndFile = getOrSwitchKeyAndFile()
                        acquireRateLimitSlot()

                        launch {
                            var success = false
                            try {
                                val segmentSubtitles = if (parsedSubtitles.isNotEmpty()) {
                                SrtParser.getSubtitlesForSegment(parsedSubtitles, startMs, endMs)
                            } else ""

                            val subtitleContext = if (segmentSubtitles.isNotBlank()) {
                                "\n\nစကားပြောစာတန်းထိုးများ (Subtitles Transcript in $startStr - $endStr):\n$segmentSubtitles\n(ဤစကားပြောစာသားများကို အခြေခံ၍ ဇာတ်ကောင်များပြောဆိုမှု၏ ဆိုလိုရင်း Context ကို အတိအကျ ဖော်ထုတ်ရေးသားပါ။)"
                            } else ""

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
                                သင်သည် '$tone' လေသံနှင့် '$pov' ရှုထောင့်ကို အထူးကျွမ်းကျင်သော PrGt's Script (ဇာတ်လမ်းအကျဉ်း) ဇာတ်ကြောင်းပြောသူ (Narrator) ဖြစ်သည်။
                                
                                ညွှန်ကြားချက်များ:
                                1. Input Media Analysis:
                                $modalityInstruction
                                2. Narrator POV: $povDetail
                                3. Character Profile & Continuity:
                                $characterContinuityRules
                                4. Script Tone: $toneDetail
                                5. Length Constraint: $lengthInstruction
                                6. Spoken Ready: Timestamp များ၊ Scene Number များ၊ ဇယားများ၊ ကွင်းစကွင်းပိတ် ညွှန်းဆိုချက်များ လုံးဝ (လုံးဝ) မထည့်ဘဲ၊ လူကိုယ်တိုင် အသံထွက်ဖတ်ရန် အဆင်သင့်ဖြစ်သော စကားပြောသဘာဝအတိုင်း "ဇာတ်ကြောင်းပြော စာသားသက်သက်" ကိုသာ ထုတ်ပေးပါ။ (စာအုပ်ဖတ်ပြသည့် စာစကား/စာအရေးအသားပုံစံ လုံးဝမဖြစ်စေရ၊ လူပြောစကားသဘာဝအတိုင်းသာ ဖြစ်ရမည်။)
                                7. Timing & Context: $timingConstraint$subtitleContext
                                8. Language Constraints: $languageConstraint
                            """.trimIndent()

                            val prompt = "အထက်ပါ ညွှန်ကြားချက်များအတိုင်း $timingConstraint ကို '$tone' လေသံ ($toneDetail) နှင့် '$pov' ရှုထောင့်အတိုင်း အတိအကျ လိုက်နာ၍ PrGt's Script ရေးသားပေးပါ။"

                            success = false
                            var attempts = 0
                            val maxAttempts = 3
                            
                            while (!success && attempts < maxAttempts) {
                                attempts++
                                val activeSlot = if (attempts == 1) {
                                    initialRetryKeyAndFile
                                } else {
                                    send(GenerationUpdate.Log("Auto-Retry Round #$autoRetryRound Segment ${i + 1}/$parts (Attempt $attempts/$maxAttempts)..."))
                                    val retryKeyAndFile = getOrSwitchKeyAndFile()
                                    acquireRateLimitSlot()
                                    retryKeyAndFile
                                }
                                val callApiKey = activeSlot.apiKey
                                val callFileUri = activeSlot.fileUri
                                val isSpareCall = activeSlot.isSpare
                                val keyLabel = " [${activeSlot.keyLabel}]"
                                
                                send(GenerationUpdate.Log("🚀 Sending Retry Part ${i + 1}/$parts ($startStr - $endStr) [Round #$autoRetryRound, Attempt $attempts]$keyLabel to AI..."))
                                
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
                                
                                logReservedRequestSent(activeSlot)
                                workingCount.incrementAndGet()
                                inFlightHttpRequests.incrementAndGet()
                                
                                try {
                                    client.newCall(request).execute().use { response ->
                                        if (!response.isSuccessful) {
                                            recordCallOutcome(isSpareCall, false)
                                            val errorBody = response.body?.string() ?: ""
                                            val errorDetail = extractApiErrorMessage(response.code, response.message, errorBody)
                                            send(GenerationUpdate.Log("❌ Retry Part ${i + 1} (R#$autoRetryRound, $attempts/$maxAttempts)$keyLabel Failed: HTTP ${response.code} ($errorDetail)"))
                                            if (attempts == maxAttempts) {
                                                send(GenerationUpdate.ScriptChunk(i, "\n❌ [Part ${i + 1} Retry Failed$keyLabel: HTTP ${response.code} ($errorDetail)]\n", isError = true))
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
                                                val keyTag = "[${activeSlot.keyLabel}]"
                                                send(GenerationUpdate.Log("✅ Part ${i + 1}/$parts [$startStr - $endStr] recovered successfully with $keyTag."))
                                            } else {
                                                throw Exception("Empty response")
                                            }
                                        } catch (e: Exception) {
                                            recordCallOutcome(isSpareCall, false)
                                            val shortParseErr = (e.message ?: "Parse error").lineSequence().firstOrNull()?.take(50) ?: "Parse error"
                                            send(GenerationUpdate.Log("❌ Retry Part ${i + 1} (R#$autoRetryRound, $attempts/$maxAttempts)$keyLabel Failed: $shortParseErr"))
                                            if (attempts == maxAttempts) {
                                                send(GenerationUpdate.ScriptChunk(i, "\n❌ [Part ${i + 1} Retry Failed$keyLabel: $shortParseErr]\n", isError = true))
                                            }
                                            return@use
                                        }
                                    }
                                } catch (e: Exception) {
                                    recordCallOutcome(isSpareCall, false)
                                    val shortNetErr = (e.message ?: "Connection failed").lineSequence().firstOrNull()?.take(50) ?: "Connection failed"
                                    send(GenerationUpdate.Log("❌ Retry Part ${i + 1} (R#$autoRetryRound, $attempts/$maxAttempts)$keyLabel Failed: ${e.javaClass.simpleName} ($shortNetErr)"))
                                    if (attempts == maxAttempts) {
                                        send(GenerationUpdate.ScriptChunk(i, "\n❌ [Part ${i + 1} Retry Failed$keyLabel: ${e.javaClass.simpleName} ($shortNetErr)]\n", isError = true))
                                    }
                                } finally {
                                    workingCount.decrementAndGet()
                                    inFlightHttpRequests.decrementAndGet()
                                }
                            }
                        } finally {
                            // workingCount is managed per in-flight HTTP request
                        }
                            
                            if (success) {
                                val currentSuccess = successfulCount.incrementAndGet()
                                val currentProgress = 0.15f + (0.85f * (currentSuccess.toFloat() / maxOf(1, totalToProcess)))
                                send(GenerationUpdate.Progress(currentProgress))
                            } else {
                                failedCount.incrementAndGet()
                                newlyFailed.add(i)
                            }
                        }
                    }
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                if (rpdExhaustedAndStoppedByUser || ce.message == "RPD_EXHAUSTED_STOP") {
                    break
                } else {
                    throw ce
                }
            }
            
            remainingFailedParts = newlyFailed.toList().sorted()
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
        var status = ""
        var msg = ""
        if (!errorBody.isNullOrBlank()) {
            try {
                val json = JSONObject(errorBody)
                val errObj = json.optJSONObject("error")
                if (errObj != null) {
                    status = errObj.optString("status").trim()
                    msg = errObj.optString("message").trim()
                }
            } catch (_: Exception) {
                // Not a JSON object
            }
        }
        return when {
            code == 429 || status.equals("RESOURCE_EXHAUSTED", ignoreCase = true) -> "Quota/Rate Limit Exceeded"
            code == 503 || status.equals("UNAVAILABLE", ignoreCase = true) -> "Model Overloaded"
            code == 500 || status.equals("INTERNAL", ignoreCase = true) -> "Internal Server Error"
            code == 504 || status.equals("DEADLINE_EXCEEDED", ignoreCase = true) -> "Gateway Timeout"
            code == 400 || status.equals("INVALID_ARGUMENT", ignoreCase = true) -> {
                val shortMsg = msg.lineSequence().firstOrNull()?.take(45) ?: "Invalid Argument"
                "Invalid Request: $shortMsg"
            }
            code == 403 || status.equals("PERMISSION_DENIED", ignoreCase = true) -> "Permission Denied / Invalid Key"
            code == 404 || status.equals("NOT_FOUND", ignoreCase = true) -> "File or Model Not Found"
            msg.isNotBlank() -> {
                val cleanMsg = msg.replace("\n", " ").trim()
                if (cleanMsg.length > 55) cleanMsg.take(55) + "..." else cleanMsg
            }
            status.isNotBlank() -> status
            !errorBody.isNullOrBlank() -> {
                val clean = errorBody.replace("\n", " ").trim()
                if (clean.length > 55) clean.take(55) + "..." else clean
            }
            else -> message.ifBlank { "Error" }
        }
    }
    
    fun checkFileState(apiKey: String, name: String): String {
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/$name?key=$apiKey")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val json = JSONObject(response.body?.string() ?: "{}")
                return json.optString("state", "PROCESSING")
            } else if (response.code == 404 || response.code == 403) {
                return "NOT_FOUND"
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
