package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Save
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

private fun getMediaFileName(context: android.content.Context, uri: Uri, fallback: String): String {
    var name: String? = null
    try {
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (index != -1) {
                        name = cursor.getString(index)
                    }
                }
            }
        }
    } catch (e: Exception) {
        // ignore and fallback
    }
    if (name.isNullOrBlank()) {
        name = uri.lastPathSegment?.let { segment ->
            val cut = segment.lastIndexOf('/')
            if (cut != -1) segment.substring(cut + 1) else segment
        }
    }
    return if (!name.isNullOrBlank()) name else fallback
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(modifier: Modifier = Modifier) {
    var showSettings by remember { mutableStateOf(false) }
    
    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE) }

    // States (Loaded from Last Presets Cache)
    var parts by remember { mutableStateOf(sharedPrefs.getFloat("preset_parts", 20f).coerceIn(5f, 50f)) }
    var parallelKeys by remember { mutableStateOf(sharedPrefs.getFloat("preset_parallel_keys", 1f).coerceIn(1f, 10f)) }
    
    val scriptLengths = listOf("Short", "Medium", "Long", "TTS match")
    var selectedLength by remember {
        val saved = sharedPrefs.getString("preset_length", scriptLengths[1]) ?: scriptLengths[1]
        mutableStateOf(if (scriptLengths.contains(saved)) saved else scriptLengths[1])
    }
    
    val narratorPOVs = listOf("Storyteller Script", "Main Character POV")
    var selectedPOV by remember {
        val saved = sharedPrefs.getString("preset_pov", narratorPOVs[0]) ?: narratorPOVs[0]
        mutableStateOf(if (narratorPOVs.contains(saved)) saved else narratorPOVs[0])
    }
    var characterHint by remember { mutableStateOf(sharedPrefs.getString("preset_character_hint", "") ?: "") }
    
    val scriptTones = listOf("Dramatic", "Comedic", "Suspenseful", "Cute & Adorable", "Action & High-Energy")
    var selectedTone by remember {
        val saved = sharedPrefs.getString("preset_tone", scriptTones[0]) ?: scriptTones[0]
        mutableStateOf(if (scriptTones.contains(saved)) saved else scriptTones[0])
    }

    val outputLanguages = listOf("Myanmar", "English")
    var selectedLanguage by remember {
        val saved = sharedPrefs.getString("preset_language", outputLanguages[0]) ?: outputLanguages[0]
        mutableStateOf(if (outputLanguages.contains(saved)) saved else outputLanguages[0])
    }

    // Settings State
    val modelOptions = listOf(
        "gemini-3.8-flash" to "Gemini 3.8 Flash (Latest Preview)",
        "gemini-3.7-flash" to "Gemini 3.7 Flash (Hybrid Reasoning)",
        "gemini-3.6-flash" to "Gemini 3.6 Flash (Default)",
        "gemini-3-flash" to "Gemini 3 Flash (Fast & Efficient)",
        "gemini-3.5-flash-lite" to "Gemini 3.5 Flash-Lite (RPD 150)",
        "gemini-3.1-flash-lite" to "Gemini 3.1 Flash-Lite (RPD 150)"
    )
    val modelKeys = modelOptions.map { it.first }
    val modelDisplayMap = modelOptions.toMap()
    val modelKeyToDisplay = { key: String -> modelDisplayMap[key] ?: key }
    val modelDisplayToKey = { display: String -> modelOptions.find { it.second == display }?.first ?: display }

    var apiKey by remember { mutableStateOf(sharedPrefs.getString("gemini_api_key", "") ?: "") }
    var mainKeyEnabled by remember { mutableStateOf(sharedPrefs.getBoolean("gemini_main_key_enabled", true)) }
    var spareApiKey by remember { mutableStateOf(sharedPrefs.getString("gemini_spare_api_key", "") ?: "") }
    var spareKeySlots by remember {
        val slotsJsonStr = sharedPrefs.getString("gemini_spare_key_slots_json", null)
        val loaded = mutableListOf<SpareKeySlot>()
        if (!slotsJsonStr.isNullOrBlank()) {
            try {
                val arr = org.json.JSONArray(slotsJsonStr)
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i)
                    if (obj != null) {
                        loaded.add(
                            SpareKeySlot(
                                key = obj.optString("key", ""),
                                enabled = obj.optBoolean("enabled", true)
                            )
                        )
                    }
                }
            } catch (_: Exception) {
            }
        }
        if (loaded.isEmpty()) {
            val legacySpare = sharedPrefs.getString("gemini_spare_api_key", "") ?: ""
            loaded.add(SpareKeySlot(key = legacySpare, enabled = true))
        }
        mutableStateOf(loaded.take(19))
    }

    val saveSpareKeySlots: (List<SpareKeySlot>) -> Unit = { updatedList ->
        val capped = updatedList.take(19)
        spareKeySlots = capped
        val firstSpare = capped.firstOrNull()?.key ?: ""
        spareApiKey = firstSpare
        val arr = org.json.JSONArray()
        capped.forEach { item ->
            val obj = org.json.JSONObject().apply {
                put("key", item.key)
                put("enabled", item.enabled)
            }
            arr.put(obj)
        }
        sharedPrefs.edit()
            .putString("gemini_spare_key_slots_json", arr.toString())
            .putString("gemini_spare_api_key", firstSpare)
            .apply()
    }

    var rpdEnabled by remember { mutableStateOf(sharedPrefs.getBoolean("gemini_rpd_enabled", true)) }
    var selectedModelKey by remember { 
        val saved = sharedPrefs.getString("gemini_model", "gemini-3.6-flash") ?: "gemini-3.6-flash"
        mutableStateOf(if (modelKeys.contains(saved)) saved else "gemini-3.6-flash")
    }
    var rpmSetting by remember { mutableFloatStateOf(sharedPrefs.getFloat("gemini_rpm", 20f)) }

    // Generation State
    var generatedScript by remember { mutableStateOf("") }
    var isGenerating by remember { mutableStateOf(false) }
    var activeGenerationMediaName by remember { mutableStateOf("") }
    var generationProgress by remember { mutableFloatStateOf(0f) }
    val generationLogs = remember { mutableStateListOf<String>() }
    var elapsedTime by remember { mutableLongStateOf(0L) }
    var showStopDialog by remember { mutableStateOf(false) }
    var generationJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var saveCurrentProgressAndStop by remember { mutableStateOf<(() -> Unit)?>(null) }
    var autoSaveCurrentProgress by remember { mutableStateOf<(() -> Unit)?>(null) }
    
    var activeDecisionChannel by remember { mutableStateOf<kotlinx.coroutines.channels.Channel<Boolean>?>(null) }
    var activeNewKeyChannel by remember { mutableStateOf<kotlinx.coroutines.channels.Channel<String>?>(null) }
    var activeCharacterConfirmChannel by remember { mutableStateOf<kotlinx.coroutines.channels.Channel<String>?>(null) }
    var showCharacterConfirmDialog by remember { mutableStateOf<GenerationUpdate.RequireCharacterConfirmation?>(null) }
    var selectedCharacterDialogIndex by remember { mutableIntStateOf(0) }
    var customCharacterDialogInput by remember { mutableStateOf("") }
    var characterConfirmCountdown by remember { mutableIntStateOf(10) }
    var isEditingCharacterNames by remember { mutableStateOf(false) }
    val customCharacterNamesMap = remember { androidx.compose.runtime.mutableStateMapOf<Int, String>() }
    var showRetryDialog by remember { mutableStateOf<List<Int>?>(null) }
    var showRpdKeyDialog by remember { mutableStateOf(false) }
    var activeRunningModel by remember { mutableStateOf("") }
    var editMainKeyInput by remember { mutableStateOf("") }
    var editSpareKeyInput by remember { mutableStateOf("") }
    var currentMissingParts by remember { mutableStateOf<List<Int>>(emptyList()) }
    var retryCountdown by remember { mutableIntStateOf(15) }
    var rpdCountdown by remember { mutableIntStateOf(10) }
    
    val coroutineScope = rememberCoroutineScope()


    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(isGenerating) {
        val window = (context as? android.app.Activity)?.window
        if (isGenerating) {
            window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    DisposableEffect(lifecycleOwner, isGenerating) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (isGenerating && (event == androidx.lifecycle.Lifecycle.Event.ON_STOP || event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE)) {
                autoSaveCurrentProgress?.invoke()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(isGenerating) {
        if (isGenerating) {
            elapsedTime = 0L
            while (true) {
                kotlinx.coroutines.delay(1000L)
                elapsedTime++
            }
        }
    }

    // Media State
    var selectedMediaUri by remember { mutableStateOf<Uri?>(null) }
    var selectedMediaMimeType by remember { mutableStateOf<String?>(null) }
    var selectedMediaName by remember { mutableStateOf("No file selected") }
    var selectedMediaDurationMs by remember { mutableStateOf(0L) }

    // Persistent server-cached media state for current session
    var cachedUploadedGeminiUri by remember { mutableStateOf<String?>(null) }
    var cachedUploadedDurationMs by remember { mutableStateOf<Long?>(null) }
    var cachedUploadedMimeType by remember { mutableStateOf<String?>(null) }
    var cachedUploadedApiKey by remember { mutableStateOf<String?>(null) }

    val geminiApiClient = remember { GeminiApiClient(context) }
    
    // Database State
    val db = remember { com.example.db.AppDatabase.getDatabase(context) }
    val dao = db.prgtScriptDao()
    val historyScripts by dao.getAllScripts().collectAsState(initial = emptyList())
    var showHistory by remember { mutableStateOf(false) }
    
    var scriptToRename by remember { mutableStateOf<com.example.db.PrGtScript?>(null) }
    var newNameInput by remember { mutableStateOf("") }
    var scriptToDelete by remember { mutableStateOf<com.example.db.PrGtScript?>(null) }
    var scriptToRegenerate by remember { mutableStateOf<com.example.db.PrGtScript?>(null) }
    var scriptForSpecialParts by remember { mutableStateOf<com.example.db.PrGtScript?>(null) }
    var scriptToConfigureRetry by remember { mutableStateOf<Pair<com.example.db.PrGtScript, List<Int>>?>(null) }
    var pendingRetryScript by remember { mutableStateOf<com.example.db.PrGtScript?>(null) }
    var pendingRetryParts by remember { mutableStateOf<List<Int>?>(null) }
    var viewingHistoryScript by remember { mutableStateOf<com.example.db.PrGtScript?>(null) }
    var currentScriptId by remember { mutableStateOf<Int?>(null) }
    var isEditingScript by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current
    var showScriptInfo by remember { mutableStateOf<com.example.db.PrGtScript?>(null) }
    var errorMessageDialog by remember { mutableStateOf<String?>(null) }
    var errorDialogCountdown by remember { mutableIntStateOf(15) }
    var backPressedTime by remember { mutableLongStateOf(0L) }

    BackHandler {
        when {
            showSettings -> {
                showSettings = false
            }
            viewingHistoryScript != null -> {
                viewingHistoryScript = null
            }
            scriptToRename != null -> {
                scriptToRename = null
            }
            scriptToDelete != null -> {
                scriptToDelete = null
            }
            scriptToRegenerate != null -> {
                scriptToRegenerate = null
            }
            scriptForSpecialParts != null -> {
                scriptForSpecialParts = null
            }
            showScriptInfo != null -> {
                showScriptInfo = null
            }
            errorMessageDialog != null -> {
                errorMessageDialog = null
            }
            showHistory -> {
                showHistory = false
            }
            else -> {
                val currentTime = System.currentTimeMillis()
                if (currentTime - backPressedTime < 2000L) {
                    (context as? android.app.Activity)?.finish()
                } else {
                    backPressedTime = currentTime
                    android.widget.Toast.makeText(context, "ထွက်ရန် နောက်တစ်ကြိမ် ထပ်နှိပ်ပါ", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val launchGenerationWithUri: (com.example.db.PrGtScript?, List<Int>?, Uri?) -> Unit = { existingScript, retryParts, overrideMediaUri ->
        val generationStartTime = existingScript?.timestamp ?: System.currentTimeMillis()
        activeGenerationMediaName = if (overrideMediaUri != null) {
            getMediaFileName(context, overrideMediaUri, existingScript?.fileName ?: selectedMediaName)
        } else {
            existingScript?.fileName ?: selectedMediaName
        }
        isGenerating = true
        generatedScript = ""
        currentScriptId = null
        generationProgress = 0f
        generationLogs.clear()
        showHistory = false // Close history if open
        
        val decisionChannel = kotlinx.coroutines.channels.Channel<Boolean>()
        val newKeyChan = kotlinx.coroutines.channels.Channel<String>()
        val charConfirmChan = kotlinx.coroutines.channels.Channel<String>()
        activeDecisionChannel = decisionChannel
        activeNewKeyChannel = newKeyChan
        activeCharacterConfirmChannel = charConfirmChan
        
        generationJob = coroutineScope.launch {
            val scriptPartsMap = mutableMapOf<Int, String>()
            
            val activeModel = existingScript?.modelId?.ifBlank { selectedModelKey } ?: selectedModelKey
            activeRunningModel = activeModel
            val activeParts = existingScript?.partsCount?.toFloat() ?: parts
            val activeLength = existingScript?.targetLength ?: selectedLength
            val activePOV = existingScript?.pov ?: selectedPOV
            val activeTone = existingScript?.tone ?: selectedTone
            val activeLanguage = existingScript?.language ?: selectedLanguage
            
            var savedGeminiFileUri = if (overrideMediaUri != null) null else (existingScript?.geminiFileUri ?: if (cachedUploadedApiKey == apiKey) cachedUploadedGeminiUri else null)
            var savedMimeType = if (overrideMediaUri != null) {
                context.contentResolver.getType(overrideMediaUri) ?: (existingScript?.mimeType ?: selectedMediaMimeType)
            } else {
                existingScript?.mimeType ?: if (cachedUploadedApiKey == apiKey && cachedUploadedMimeType != null) cachedUploadedMimeType else selectedMediaMimeType
            }
            var savedDurationMs = if (overrideMediaUri != null) {
                var dur = 0L
                try {
                    val retriever = android.media.MediaMetadataRetriever()
                    retriever.setDataSource(context, overrideMediaUri)
                    val timeString = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                    dur = timeString?.toLongOrNull() ?: 0L
                    retriever.release()
                } catch (_: Exception) {}
                dur
            } else {
                val d = (existingScript?.durationSec?.toLongOrNull() ?: 0L) * 1000
                if (d <= 0L && cachedUploadedApiKey == apiKey && cachedUploadedDurationMs != null && cachedUploadedDurationMs!! > 0L) {
                    cachedUploadedDurationMs!!
                } else d
            }
            var savedPartsCount = existingScript?.partsCount ?: parts.toInt()
            
            var localTotalApiCalls = existingScript?.totalApiCalls ?: 0
            var localSuccessApiCalls = existingScript?.successApiCalls ?: 0
            var localFailedApiCalls = existingScript?.failedApiCalls ?: 0
            var localMainApiCalls = 0
            var localMainSuccessCalls = 0
            var localMainFailedCalls = 0
            var localSpareApiCalls = 0
            var localSpareSuccessCalls = 0
            var localSpareFailedCalls = 0
            var localUsedSpareKey = false
            var localCharacterProfile = ""
            var localConfirmedMainChar = ""
            var hadFatalProfileError = false
            if (existingScript != null && existingScript.rpdInfoJson.isNotBlank()) {
                try {
                    val existingRpdObj = org.json.JSONObject(existingScript.rpdInfoJson)
                    localCharacterProfile = existingRpdObj.optString("characterProfile", "")
                    localConfirmedMainChar = existingRpdObj.optString("confirmedMainCharacter", "")
                } catch (_: Exception) {
                }
            }
            var previousVersionsArray = org.json.JSONArray(existingScript?.previousVersionsJson ?: "[]")
            if (existingScript != null && existingScript.scriptText.isNotBlank()) {
                val prevVersionObj = org.json.JSONObject()
                prevVersionObj.put("timestamp", System.currentTimeMillis())
                prevVersionObj.put("scriptText", existingScript.scriptText)
                previousVersionsArray.put(prevVersionObj)
            }
            var autoSavedRecordId: Int? = existingScript?.id
            val autoSaveMutex = kotlinx.coroutines.sync.Mutex()

            val activeFileName = existingScript?.fileName ?: selectedMediaName
            val currentSeriesInfo = GeminiFileKeyCache.parseSeriesInfo(activeFileName)
            val historySeriesProfiles = mutableMapOf<Int, String>()
            val priorMainCharByPart = mutableMapOf<Int, String>()
            if (currentSeriesInfo.baseTitle.isNotBlank()) {
                historyScripts.forEach { scriptItem ->
                    val itemSeriesInfo = GeminiFileKeyCache.parseSeriesInfo(scriptItem.fileName)
                    if (GeminiFileKeyCache.isSameSeries(itemSeriesInfo.baseTitle, currentSeriesInfo.baseTitle) &&
                        itemSeriesInfo.partNumber != null &&
                        itemSeriesInfo.partNumber != currentSeriesInfo.partNumber
                    ) {
                        try {
                            if (scriptItem.rpdInfoJson.isNotBlank()) {
                                val rObj = org.json.JSONObject(scriptItem.rpdInfoJson)
                                val savedCharProf = rObj.optString("characterProfile", "")
                                val savedMain = rObj.optString("confirmedMainCharacter", "")
                                if (savedCharProf.isNotBlank() && !historySeriesProfiles.containsKey(itemSeriesInfo.partNumber)) {
                                    historySeriesProfiles[itemSeriesInfo.partNumber] = savedCharProf
                                }
                                if (savedMain.isNotBlank() && !priorMainCharByPart.containsKey(itemSeriesInfo.partNumber)) {
                                    priorMainCharByPart[itemSeriesInfo.partNumber] = savedMain
                                }
                            }
                        } catch (_: Exception) {
                        }
                    } else if (localCharacterProfile.isBlank() && scriptItem.fileName.equals(activeFileName, ignoreCase = true)) {
                        try {
                            if (scriptItem.rpdInfoJson.isNotBlank()) {
                                val rObj = org.json.JSONObject(scriptItem.rpdInfoJson)
                                val savedCharProf = rObj.optString("characterProfile", "")
                                val savedMain = rObj.optString("confirmedMainCharacter", "")
                                if (savedCharProf.isNotBlank()) {
                                    localCharacterProfile = savedCharProf
                                }
                                if (localConfirmedMainChar.isBlank() && savedMain.isNotBlank()) {
                                    localConfirmedMainChar = savedMain
                                }
                            }
                        } catch (_: Exception) {
                        }
                    }
                }
            }
            val priorSeriesMainCharHint = priorMainCharByPart.entries
                .maxByOrNull { it.key }
                ?.value
                ?.trim()
                ?: ""
            
            if (existingScript != null && retryParts != null && existingScript.scriptPartsJson.isNotBlank()) {
                try {
                    val json = org.json.JSONObject(existingScript.scriptPartsJson)
                    json.keys().forEach { k ->
                        scriptPartsMap[k.toInt()] = json.getString(k)
                    }
                } catch (e: Exception) {
                    generationLogs.add("Failed to parse old script parts.")
                }
            }
            
            var finalScript = scriptPartsMap.entries.sortedBy { it.key }.joinToString("\n\n") { it.value.trim() }
            generatedScript = finalScript

            suspend fun persistProgressToDb(): com.example.db.PrGtScript? {
                return kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    autoSaveMutex.lock()
                    try {
                        val snapshotMap = mutableMapOf<Int, String>()
                        for (partIdx in 0 until savedPartsCount) {
                            val existingVal = scriptPartsMap[partIdx]
                            snapshotMap[partIdx] = if (existingVal == null || existingVal.isBlank()) {
                                "[အပိုင်း ${partIdx + 1} - နေရာလွတ်...]\n"
                            } else {
                                existingVal
                            }
                        }
                        val snapshotText = snapshotMap.entries.sortedBy { it.key }.joinToString("\n\n") { it.value.trim() }
                        val hasAnyRealProgress = scriptPartsMap.values.any { it.isNotBlank() && !it.contains("နေရာလွတ်") } ||
                            localCharacterProfile.isNotBlank() ||
                            !savedGeminiFileUri.isNullOrBlank()
                        if (!hasAnyRealProgress || snapshotText.isBlank()) {
                            return@withContext null
                        }

                        val partsJson = org.json.JSONObject()
                        snapshotMap.forEach { (k, v) -> partsJson.put(k.toString(), v) }

                        val rpdJson = org.json.JSONObject().apply {
                            put("rpdEnabled", rpdEnabled)
                            put("rpdLimit", RpdTracker.getLimitForModel(activeModel))
                            put("usedSpareKey", localUsedSpareKey)
                            put("mainTotal", localMainApiCalls)
                            put("mainSuccess", localMainSuccessCalls)
                            put("mainFailed", localMainFailedCalls)
                            put("spareTotal", localSpareApiCalls)
                            put("spareSuccess", localSpareSuccessCalls)
                            put("spareFailed", localSpareFailedCalls)
                            put("characterProfile", localCharacterProfile)
                            put("confirmedMainCharacter", localConfirmedMainChar)
                            put("uploadApiKey", apiKey)
                        }

                        val scriptEntity = com.example.db.PrGtScript(
                            fileName = existingScript?.fileName ?: selectedMediaName,
                            fileSize = "N/A",
                            durationSec = if (savedDurationMs > 0) (savedDurationMs / 1000).toString() else "N/A",
                            scriptText = snapshotText,
                            timestamp = generationStartTime,
                            language = activeLanguage,
                            modelId = activeRunningModel.ifBlank { activeModel },
                            targetLength = activeLength,
                            pov = activePOV,
                            tone = activeTone,
                            geminiFileUri = savedGeminiFileUri,
                            mimeType = savedMimeType,
                            partsCount = savedPartsCount,
                            scriptPartsJson = partsJson.toString(),
                            totalApiCalls = localTotalApiCalls,
                            successApiCalls = localSuccessApiCalls,
                            failedApiCalls = localFailedApiCalls,
                            previousVersionsJson = previousVersionsArray.toString(),
                            rpdInfoJson = rpdJson.toString()
                        )
                        val cutoffTime = System.currentTimeMillis() - (168L * 60 * 60 * 1000)
                        dao.deleteOlderThan(cutoffTime)
                        val oldId = autoSavedRecordId
                        if (oldId != null) {
                            dao.deleteScriptById(oldId)
                        }
                        val newId = dao.insertScript(scriptEntity)
                        autoSavedRecordId = newId.toInt()
                        currentScriptId = newId.toInt()
                        dao.keepLatestFifty()
                        scriptEntity.copy(id = newId.toInt())
                    } catch (_: Exception) {
                        null
                    } finally {
                        autoSaveMutex.unlock()
                    }
                }
            }

            autoSaveCurrentProgress = {
                coroutineScope.launch {
                    persistProgressToDb()
                }
            }

            saveCurrentProgressAndStop = {
                generationJob?.cancel()
                // Ensure all parts up to savedPartsCount exist in scriptPartsMap with a placeholder if missing
                for (partIdx in 0 until savedPartsCount) {
                    val existingVal = scriptPartsMap[partIdx]
                    if (existingVal == null || existingVal.isBlank()) {
                        scriptPartsMap[partIdx] = "[အပိုင်း ${partIdx + 1} - နေရာလွတ်...]\n"
                    }
                }

                val currentText = scriptPartsMap.entries.sortedBy { it.key }.joinToString("\n\n") { it.value.trim() }
                if (currentText.isNotBlank()) {
                    coroutineScope.launch {
                        val savedEntity = persistProgressToDb()
                        isEditingScript = false
                        if (savedEntity != null) {
                            viewingHistoryScript = savedEntity
                        }
                    }
                    generatedScript = currentText
                    generationLogs.add("✅ လက်ရှိပြီးစီးသမျှ အပိုင်းများကို အောင်မြင်စွာ သိမ်းဆည်းထားပြီးပါပြီ။ နောက်မှ ကျန်အပိုင်းများကို ဆက်လက် Retry လုပ်နိုင်ပါသည်။")
                }
                isGenerating = false
                saveCurrentProgressAndStop = null
                autoSaveCurrentProgress = null
            }

            val enabledSpareKeys = spareKeySlots.filter { it.enabled && it.key.isNotBlank() }.map { it.key.trim() }

            geminiApiClient.generateRecapScript(
                apiKey = apiKey,
                spareApiKey = enabledSpareKeys.firstOrNull() ?: "",
                rpdEnabled = rpdEnabled,
                rpdLimit = RpdTracker.getLimitForModel(activeModel),
                modelName = activeModel,
                partsCount = activeParts,
                length = activeLength,
                pov = activePOV,
                tone = activeTone,
                language = activeLanguage,
                mediaUri = overrideMediaUri ?: selectedMediaUri, // This might be null if retrying, but existingGeminiFileUri will handle it
                mediaMimeType = savedMimeType ?: selectedMediaMimeType,
                retryDecisionChannel = decisionChannel,
                newKeyChannel = newKeyChan,
                existingGeminiFileUri = savedGeminiFileUri,
                existingDurationMs = if (savedDurationMs > 0) savedDurationMs else null,
                specificPartsToGenerate = retryParts,
                rpm = rpmSetting.toInt(),
                characterHint = if (activePOV.contains("Main Character", ignoreCase = true)) {
                    localConfirmedMainChar.ifBlank { characterHint.trim().ifBlank { priorSeriesMainCharHint } }
                } else "",
                characterConfirmationChannel = charConfirmChan,
                mediaFileName = activeFileName,
                priorSeriesProfiles = historySeriesProfiles,
                existingCharacterProfile = localCharacterProfile,
                mainKeyEnabled = mainKeyEnabled,
                spareApiKeysList = enabledSpareKeys,
                spareKeySlots = spareKeySlots,
                parallelKeyCount = parallelKeys.toInt()
            ).collect { update ->
                when (update) {
                    is GenerationUpdate.Log -> generationLogs.add(update.message)
                    is GenerationUpdate.Progress -> generationProgress = update.progress
                    is GenerationUpdate.FileInfo -> {
                        savedGeminiFileUri = update.geminiFileUri
                        savedMimeType = update.mimeType
                        savedDurationMs = update.durationMs
                        cachedUploadedGeminiUri = update.geminiFileUri
                        cachedUploadedDurationMs = update.durationMs
                        cachedUploadedMimeType = update.mimeType
                        cachedUploadedApiKey = apiKey
                        coroutineScope.launch { persistProgressToDb() }
                    }
                    is GenerationUpdate.InitParts -> {
                        savedPartsCount = update.totalParts
                        if (retryParts == null) {
                            scriptPartsMap.clear()
                            for (i in 0 until update.totalParts) {
                                scriptPartsMap[i] = "[အပိုင်း ${i + 1} - နေရာလွတ်...]\n"
                            }
                        }
                        finalScript = scriptPartsMap.entries.sortedBy { it.key }.joinToString("\n\n") { it.value.trim() }
                        generatedScript = finalScript
                        coroutineScope.launch { persistProgressToDb() }
                    }
                    is GenerationUpdate.ScriptChunk -> {
                        if (update.clearFirst) {
                            scriptPartsMap[update.partIndex] = ""
                        }
                        if (update.isError) {
                            scriptPartsMap[update.partIndex] = update.text
                        } else {
                            val currentPartText = scriptPartsMap[update.partIndex] ?: ""
                            scriptPartsMap[update.partIndex] = currentPartText + update.text
                        }
                        finalScript = scriptPartsMap.entries.sortedBy { it.key }.joinToString("\n\n") { it.value.trim() }
                        generatedScript = finalScript
                        if (!update.clearFirst && update.text.isNotBlank()) {
                            coroutineScope.launch { persistProgressToDb() }
                        }
                    }
                    is GenerationUpdate.Error -> {
                        generationLogs.add("ERROR: ${update.message}")
                        finalScript += "\nError: ${update.message}"
                        errorDialogCountdown = 15
                        errorMessageDialog = update.message
                    }
                    is GenerationUpdate.ProfileFatalError -> {
                        hadFatalProfileError = true
                        errorDialogCountdown = 15
                        errorMessageDialog = update.message
                    }
                    is GenerationUpdate.RequireRetryDecision -> {
                        showRetryDialog = update.failedParts
                        currentMissingParts = update.failedParts
                        retryCountdown = 15
                    }
                    is GenerationUpdate.RequireCharacterConfirmation -> {
                        val recIdx = update.recommendedIndex.coerceIn(0, (update.characters.size - 1).coerceAtLeast(0))
                        selectedCharacterDialogIndex = recIdx
                        val defaultChar = update.characters.getOrNull(recIdx)
                        customCharacterDialogInput = if (defaultChar != null) {
                            val relPart = if (defaultChar.relationshipStatus.isNotBlank()) " [${defaultChar.relationshipStatus}]" else ""
                            "${defaultChar.nameAndAppearance}$relPart - ${defaultChar.pronoun} နာမ်စားသုံးရန်"
                        } else {
                            characterHint
                        }
                        isEditingCharacterNames = false
                        customCharacterNamesMap.clear()
                        characterConfirmCountdown = 10
                        showCharacterConfirmDialog = update
                    }
                    is GenerationUpdate.CharacterProfileSaved -> {
                        localCharacterProfile = update.profileText
                        localConfirmedMainChar = update.confirmedMainCharacter
                        coroutineScope.launch { persistProgressToDb() }
                    }
                    is GenerationUpdate.RequireNewKeyForRpd -> {
                        val missing = scriptPartsMap.filter { entry ->
                            entry.value.isBlank() || 
                            entry.value.contains("[အပိုင်း (") || 
                            entry.value.contains("နေရာလွတ်") ||
                            entry.value.contains("Error")
                        }.keys.toList()
                        currentMissingParts = if (update.failedParts.isNotEmpty()) update.failedParts else missing
                        editMainKeyInput = apiKey
                        editSpareKeyInput = spareApiKey
                        rpdCountdown = 10
                        showRpdKeyDialog = true
                    }
                    is GenerationUpdate.ApiCallResult -> {
                        localTotalApiCalls++
                        if (update.success) localSuccessApiCalls++ else localFailedApiCalls++
                        localMainApiCalls = update.mainTotal
                        localMainSuccessCalls = update.mainSuccess
                        localMainFailedCalls = update.mainFailed
                        localSpareApiCalls = update.spareTotal
                        localSpareSuccessCalls = update.spareSuccess
                        localSpareFailedCalls = update.spareFailed
                        localUsedSpareKey = update.usedSpareKey
                    }
                    is GenerationUpdate.Complete -> {
                        generationLogs.add("Generation Complete!")
                        activeDecisionChannel = null
                    }
                }
            }
            
            // Save to database
            if (finalScript.isNotBlank() && !finalScript.contains("Error generating script") && !finalScript.contains("Error:")) {
                val savedEntity = persistProgressToDb()
                isEditingScript = false
                if (!hadFatalProfileError && savedEntity != null) {
                    viewingHistoryScript = savedEntity
                }
            }
            
            isGenerating = false
            saveCurrentProgressAndStop = null
            autoSaveCurrentProgress = null
        }
    }

    val launchGeneration: (com.example.db.PrGtScript?, List<Int>?) -> Unit = { existingScript, retryParts ->
        launchGenerationWithUri(existingScript, retryParts, null)
    }

    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            if (selectedMediaUri != uri) {
                cachedUploadedGeminiUri = null
                cachedUploadedDurationMs = null
                cachedUploadedMimeType = null
                cachedUploadedApiKey = null
            }
            selectedMediaUri = uri
            selectedMediaMimeType = context.contentResolver.getType(uri)
            selectedMediaName = getMediaFileName(context, uri, "Video selected")
            try {
                val retriever = android.media.MediaMetadataRetriever()
                retriever.setDataSource(context, uri)
                val timeString = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                selectedMediaDurationMs = timeString?.toLongOrNull() ?: 0L
                retriever.release()
            } catch (e: Exception) {
                selectedMediaDurationMs = 0L
            }
        }
    }

    val audioPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            if (selectedMediaUri != uri) {
                cachedUploadedGeminiUri = null
                cachedUploadedDurationMs = null
                cachedUploadedMimeType = null
                cachedUploadedApiKey = null
            }
            selectedMediaUri = uri
            selectedMediaMimeType = context.contentResolver.getType(uri)
            selectedMediaName = getMediaFileName(context, uri, "Audio selected")
            try {
                val retriever = android.media.MediaMetadataRetriever()
                retriever.setDataSource(context, uri)
                val timeString = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                selectedMediaDurationMs = timeString?.toLongOrNull() ?: 0L
                retriever.release()
            } catch (e: Exception) {
                selectedMediaDurationMs = 0L
            }
        }
    }

    val retryMediaPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        val script = pendingRetryScript
        val partsToRun = pendingRetryParts
        pendingRetryScript = null
        pendingRetryParts = null
        if (uri != null && script != null) {
            selectedMediaUri = uri
            selectedMediaMimeType = context.contentResolver.getType(uri) ?: (script.mimeType ?: "video/mp4")
            selectedMediaName = getMediaFileName(context, uri, script.fileName)
            try {
                val retriever = android.media.MediaMetadataRetriever()
                retriever.setDataSource(context, uri)
                val timeString = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                selectedMediaDurationMs = timeString?.toLongOrNull() ?: 0L
                retriever.release()
            } catch (_: Exception) {
                selectedMediaDurationMs = 0L
            }
            launchGenerationWithUri(script, partsToRun, uri)
        }
    }

    val triggerScriptAction: (com.example.db.PrGtScript, List<Int>?) -> Unit = { targetScript, targetParts ->
        val timeSinceSaved = System.currentTimeMillis() - targetScript.timestamp
        val isExpired48h = timeSinceSaved >= (48L * 60 * 60 * 1000)

        var recordedApiKey = ""
        if (targetScript.rpdInfoJson.isNotBlank()) {
            try {
                val rObj = org.json.JSONObject(targetScript.rpdInfoJson)
                recordedApiKey = rObj.optString("uploadApiKey", "")
            } catch (_: Exception) {}
        }

        coroutineScope.launch {
            var isAccessible = false
            if (!isExpired48h && !targetScript.geminiFileUri.isNullOrBlank()) {
                if (recordedApiKey.isNotBlank() && recordedApiKey != apiKey) {
                    isAccessible = false
                } else {
                    val fileId = targetScript.geminiFileUri.substringAfterLast("/")
                    isAccessible = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        try {
                            geminiApiClient.checkFileState(apiKey, "files/$fileId") == "ACTIVE"
                        } catch (_: Exception) {
                            false
                        }
                    }
                }
            }

            if (isAccessible) {
                launchGenerationWithUri(targetScript, targetParts, null)
            } else {
                pendingRetryScript = targetScript
                pendingRetryParts = targetParts
                val mime = targetScript.mimeType ?: "video/*"
                if (mime.startsWith("audio")) {
                    retryMediaPickerLauncher.launch("audio/*")
                } else {
                    retryMediaPickerLauncher.launch("video/*")
                }
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
        var showClearAllConfirmDialog by remember { mutableStateOf(false) }

        if (showClearAllConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showClearAllConfirmDialog = false },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "⚠️ Clear All Cache & History",
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                },
                text = {
                    Text(
                        text = "သိမ်းဆည်းထားသော Script မှတ်တမ်းအားလုံး (History)၊ Character Profile Cache များနှင့် Uploaded Media Cache အားလုံးကို အပြီးတိုင် ဖျက်ပစ်ပါမည်။\n\nသေချာပါသလား? (API Keys နှင့် RPD မှတ်တမ်းများ ပျက်မသွားပါ)",
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                dao.deleteAllScripts()
                                GeminiFileKeyCache.clearAllCache(context)
                                cachedUploadedGeminiUri = null
                                cachedUploadedDurationMs = null
                                cachedUploadedMimeType = null
                                cachedUploadedApiKey = null
                            }
                            showClearAllConfirmDialog = false
                            android.widget.Toast.makeText(
                                context,
                                "✅ History နှင့် Cache အားလုံး ရှင်းလင်းပြီးပါပြီ။",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("⚠️ အကုန်ဖျက်မည်", color = MaterialTheme.colorScheme.onError, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    OutlinedButton(onClick = { showClearAllConfirmDialog = false }) {
                        Text("မလုပ်ပါ")
                    }
                }
            )
        }

        // Top Bar
        if (showHistory) {
            CenterAlignedTopAppBar(
                navigationIcon = {
                    OutlinedButton(
                        onClick = { showClearAllConfirmDialog = true },
                        modifier = Modifier.padding(start = 8.dp),
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.08f),
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "⚠️ Clear All",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                title = {
                    Text("History", fontWeight = FontWeight.Bold)
                },
                actions = {
                    IconButton(onClick = { showHistory = !showHistory }) {
                        Icon(Icons.Filled.Close, contentDescription = "Close History")
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    actionIconContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        } else {
            TopAppBar(
                title = { Text("PrGt's Script", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { showHistory = !showHistory }) {
                        Icon(Icons.Filled.History, contentDescription = "History")
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    actionIconContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        }

        if (showHistory) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp),
                contentPadding = PaddingValues(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (historyScripts.isEmpty()) {
                    item {
                        Text(
                            text = "No history available.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                items(historyScripts, key = { it.id }) { script ->
                    val previewText = remember(script.scriptText) {
                        if (script.scriptText.length > 500) script.scriptText.take(500) + "..." else script.scriptText
                    }

                    val timeRemainingMs = remember(script.timestamp) {
                        val timeSinceSaved = System.currentTimeMillis() - script.timestamp
                        (48L * 60 * 60 * 1000) - timeSinceSaved
                    }
                    val timeStr = remember(timeRemainingMs) {
                        if (timeRemainingMs > 0) {
                            val remainingHours = java.util.concurrent.TimeUnit.MILLISECONDS.toHours(timeRemainingMs)
                            val remainingMins = java.util.concurrent.TimeUnit.MILLISECONDS.toMinutes(timeRemainingMs) % 60
                            "${remainingHours}h ${remainingMins}m"
                        } else "Expired"
                    }

                    val failedParts = remember(script.id, script.partsCount, script.scriptPartsJson, script.scriptText) {
                        val list = mutableListOf<Int>()
                        val partsMap = try {
                            if (script.scriptPartsJson.isNotBlank()) {
                                val obj = org.json.JSONObject(script.scriptPartsJson)
                                obj.keys().asSequence().associate { it.toInt() to obj.getString(it) }
                            } else emptyMap()
                        } catch (_: Exception) {
                            emptyMap()
                        }

                        for (i in 0 until script.partsCount) {
                            val partContent = partsMap[i]
                            val isMissingInJson = partsMap.isNotEmpty() && (partContent == null || partContent.isBlank())
                            val isPlaceholderInJson = partContent?.contains("နေရာလွတ်") == true || partContent?.contains("[အပိုင်း (") == true || partContent?.contains("Error") == true
                            val isMarkerInText = script.scriptText.contains("[အပိုင်း (${i + 1})") || 
                                script.scriptText.contains("[Final Retry Error in Part ${i + 1}:") ||
                                script.scriptText.contains("[Error parsing final output for Part ${i + 1}:") ||
                                script.scriptText.contains("[Network Error in Final Retry Part ${i + 1}:") ||
                                script.scriptText.contains("[အပိုင်း ${i + 1} - နေရာလွတ်...]") ||
                                script.scriptText.contains("Part ${i + 1}") && (script.scriptText.contains("Error") || script.scriptText.contains("failed"))

                            if (isMissingInJson || isPlaceholderInJson || isMarkerInText) {
                                list.add(i)
                            }
                        }
                        list
                    }

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 2.dp
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = script.fileName,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f)
                                )
                                Row {
                                    IconButton(onClick = {
                                        showScriptInfo = script
                                    }) {
                                        Icon(Icons.Filled.Info, contentDescription = "Script Info", modifier = Modifier.size(20.dp))
                                    }
                                    IconButton(onClick = {
                                        scriptToRename = script
                                        newNameInput = script.fileName
                                    }) {
                                        Icon(Icons.Filled.Edit, contentDescription = "Edit Name", modifier = Modifier.size(20.dp))
                                    }
                                    IconButton(onClick = {
                                        scriptToDelete = script
                                    }) {
                                        Icon(Icons.Filled.Delete, contentDescription = "Delete Script", modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Config: ${script.language} | ${script.modelId} | ${script.pov}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = previewText,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 5
                            )

                            if (failedParts.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(10.dp))
                                Surface(
                                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Warning,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "⚠️ မပြည့်စုံသေးသောအပိုင်း (${failedParts.size}) ပိုင်း ကျန်ရှိနေပါသည်: ${failedParts.map { it + 1 }.joinToString(", ")}",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onErrorContainer
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // Row 1: [View Full Script] [Regenerate All]
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = { viewingHistoryScript = script },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("View Full Script", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                androidx.compose.material3.OutlinedButton(
                                    onClick = { scriptToRegenerate = script },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        if (timeRemainingMs > 0) "Regenerate All ($timeStr)" else "Regenerate All",
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // Row 2: [Retry Special Parts] [Retry Failed Parts (if failed)]
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                androidx.compose.material3.OutlinedButton(
                                    onClick = { scriptForSpecialParts = script },
                                    modifier = if (failedParts.isNotEmpty()) Modifier.weight(1f) else Modifier.fillMaxWidth()
                                ) {
                                    Text("Retry Special Parts", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                if (failedParts.isNotEmpty()) {
                                    Button(
                                        onClick = { scriptToConfigureRetry = Pair(script, failedParts) },
                                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.error,
                                            contentColor = MaterialTheme.colorScheme.onError
                                        ),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(
                                            "Retry Failed (${failedParts.size})",
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                Spacer(modifier = Modifier.height(8.dp))
            
            if (!isGenerating) {
                // Video / Audio Picker
                PickerSection(
                    selectedFileName = selectedMediaName,
                    onSelectVideo = { videoPickerLauncher.launch("video/*") },
                    onSelectAudio = { audioPickerLauncher.launch("audio/*") }
                )

                // Number of parts
                SliderSection(
                    title = "Number of parts the video",
                    value = parts,
                    onValueChange = {
                        parts = it
                        sharedPrefs.edit().putFloat("preset_parts", it).apply()
                    },
                    valueRange = 5f..50f
                )
                if (selectedMediaDurationMs > 0) {
                    val segmentDurationSecs = (selectedMediaDurationMs / parts.toInt()) / 1000
                    Text(
                        text = "တစ်ပိုင်းလျှင် ခန့်မှန်း $segmentDurationSecs စက္ကန့် (Estimated: $segmentDurationSecs seconds/part)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                } else {
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Parallel Key (1 to 10)
                SliderSection(
                    title = "Parallel Key",
                    value = parallelKeys,
                    onValueChange = {
                        parallelKeys = it
                        sharedPrefs.edit().putFloat("preset_parallel_keys", it).apply()
                    },
                    valueRange = 1f..10f
                )
                val pCount = parts.toInt()
                val kCount = parallelKeys.toInt().coerceIn(1, 10)
                val minPartsPerKey = pCount / kCount
                val maxPartsPerKey = if (pCount % kCount == 0) minPartsPerKey else minPartsPerKey + 1
                val partsRangeStr = if (minPartsPerKey == maxPartsPerKey) "$minPartsPerKey ပိုင်းစီ" else "$minPartsPerKey–$maxPartsPerKey ပိုင်းစီ"
                Text(
                    text = if (kCount > 1) {
                        "Key $kCount ခုဖြင့် တစ်ပြိုင်နက် ခွဲဝေထုတ်လုပ်ပါမည် (၁ ခုလျှင် $partsRangeStr)"
                    } else {
                        "Key 1 ခုချင်းစီ အစဉ်လိုက် ထုတ်လုပ်ပါမည် (Sequential Mode)"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                // Script Length
                DropdownSection(
                    title = "Script Length",
                    options = scriptLengths,
                    selectedOption = selectedLength,
                    onOptionSelected = {
                        selectedLength = it
                        sharedPrefs.edit().putString("preset_length", it).apply()
                    }
                )

                // Narrator POV
                DropdownSection(
                    title = "Narrator POV",
                    options = narratorPOVs,
                    selectedOption = selectedPOV,
                    onOptionSelected = {
                        selectedPOV = it
                        sharedPrefs.edit().putString("preset_pov", it).apply()
                    }
                )

                if (selectedPOV.contains("Main Character", ignoreCase = true)) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "Main Character Hint (Optional - မထည့်လည်း AI က အလိုအလျောက် စစ်ဆေးပေးပါမည်)",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        OutlinedTextField(
                            value = characterHint,
                            onValueChange = {
                                characterHint = it
                                sharedPrefs.edit().putString("preset_character_hint", it).apply()
                            },
                            placeholder = {
                                Text(
                                    text = "ဥပမာ - အင်္ကျီအနက်ဝတ်ထားသော လူငယ် (ဂျွန်) - ကျွန်တော် နာမ်စားသုံးရန်",
                                    fontSize = 13.sp
                                )
                            },
                            trailingIcon = {
                                if (characterHint.isNotBlank()) {
                                    IconButton(onClick = {
                                        characterHint = ""
                                        sharedPrefs.edit().putString("preset_character_hint", "").apply()
                                    }) {
                                        Icon(
                                            imageVector = Icons.Filled.Close,
                                            contentDescription = "Clear Character Hint"
                                        )
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            singleLine = false,
                            maxLines = 2
                        )
                    }
                }

                // Script Tone
                DropdownSection(
                    title = "Script Tone",
                    options = scriptTones,
                    selectedOption = selectedTone,
                    onOptionSelected = {
                        selectedTone = it
                        sharedPrefs.edit().putString("preset_tone", it).apply()
                    }
                )

                // Output Language
                DropdownSection(
                    title = "Output Language",
                    options = outputLanguages,
                    selectedOption = selectedLanguage,
                    onOptionSelected = {
                        selectedLanguage = it
                        sharedPrefs.edit().putString("preset_language", it).apply()
                    }
                )
                
                Spacer(modifier = Modifier.height(32.dp))
                
                Button(
                    onClick = { launchGeneration(null, null) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text("Generate Script", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
        }
    } // End of else block for showHistory
        if (isGenerating) {
            Spacer(modifier = Modifier.height(200.dp))
        }
    } // End of main Column

    if (isGenerating) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
        ) {
            ProcessingBlock(
                mediaName = activeGenerationMediaName,
                progress = generationProgress,
                logs = generationLogs,
                elapsedTime = elapsedTime,
                onStopClick = { showStopDialog = true }
            )
        }
    }
    } // End of root Box

    var previousVersionToShow by remember { mutableStateOf<String?>(null) }

    if (viewingHistoryScript != null) {
        val script = viewingHistoryScript!!
        var isEditingInViewer by remember(script.id) { mutableStateOf(false) }
        var viewerText by remember(script.id) { mutableStateOf(script.scriptText) }
        
        val wordCount = viewerText.split("\\s+".toRegex()).filter { it.isNotBlank() }.size
        val charCount = viewerText.length
        val formattedDate = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(script.timestamp))

        val partsMap = remember(script.id, script.scriptPartsJson) {
            try {
                if (script.scriptPartsJson.isNotBlank()) {
                    val obj = org.json.JSONObject(script.scriptPartsJson)
                    obj.keys().asSequence().associate { it.toInt() to obj.getString(it) }
                } else emptyMap()
            } catch (_: Exception) {
                emptyMap()
            }
        }
        val totalParts = remember(script.partsCount, partsMap.size) {
            if (script.partsCount > 0) script.partsCount else if (partsMap.isNotEmpty()) partsMap.size else 1
        }
        val midPart = remember(totalParts) {
            if (totalParts <= 1) 1 else (totalParts + 1) / 2
        }
        val part1RangeStr = if (totalParts > 1) "Part 1-$midPart" else "Part 1"
        val part2RangeStr = if (totalParts > 1) "Part ${midPart + 1}-$totalParts" else "Part 1"

        AlertDialog(
            onDismissRequest = { viewingHistoryScript = null },
            properties = androidx.compose.ui.window.DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = false
            ),
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.92f),
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = script.fileName,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                        Text(
                            text = "$formattedDate • ${script.language} • ${script.modelId}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(onClick = { viewingHistoryScript = null }) {
                        Icon(Icons.Filled.Close, contentDescription = "Close")
                    }
                }
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxSize()
                ) {
                    // Statistics & Metadata chip row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "စာလုံးရေ: $wordCount",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "အက္ခရာ: $charCount",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                        Surface(
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "POV: ${script.pov}",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    val viewerMissingParts = remember(viewerText, script.partsCount, script.scriptPartsJson) {
                        val list = mutableListOf<Int>()
                        val partsMap = try {
                            if (script.scriptPartsJson.isNotBlank()) {
                                val obj = org.json.JSONObject(script.scriptPartsJson)
                                obj.keys().asSequence().associate { it.toInt() to obj.getString(it) }
                            } else emptyMap()
                        } catch (_: Exception) {
                            emptyMap()
                        }

                        for (i in 0 until script.partsCount) {
                            val partContent = partsMap[i]
                            val isMissingInJson = partsMap.isNotEmpty() && (partContent == null || partContent.isBlank())
                            val isPlaceholderInJson = partContent?.contains("နေရာလွတ်") == true || partContent?.contains("[အပိုင်း (") == true || partContent?.contains("Error") == true
                            val isMarkerInText = viewerText.contains("[အပိုင်း (${i + 1})") || 
                                viewerText.contains("[Final Retry Error in Part ${i + 1}:") ||
                                viewerText.contains("[Error parsing final output for Part ${i + 1}:") ||
                                viewerText.contains("[Network Error in Final Retry Part ${i + 1}:") ||
                                viewerText.contains("[အပိုင်း ${i + 1} - နေရာလွတ်...]") ||
                                viewerText.contains("Part ${i + 1}") && (viewerText.contains("Error") || viewerText.contains("failed"))

                            if (isMissingInJson || isPlaceholderInJson || isMarkerInText) {
                                list.add(i)
                            }
                        }
                        list
                    }

                    if (viewerMissingParts.isNotEmpty()) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Warning,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "⚠️ မပြည့်စုံသေးသောအပိုင်း (${viewerMissingParts.size}) ပိုင်း ကျန်ရှိနေပါသည်: ${viewerMissingParts.map { it + 1 }.joinToString(", ")}",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                                Button(
                                    onClick = {
                                        val partsToRun = viewerMissingParts.toList()
                                        viewingHistoryScript = null
                                        scriptToConfigureRetry = Pair(script, partsToRun)
                                    },
                                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.error,
                                        contentColor = MaterialTheme.colorScheme.onError
                                    ),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    modifier = Modifier.padding(start = 8.dp)
                                ) {
                                    Text("Retry Failed Parts", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }

                    // Main script scroll area
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        if (isEditingInViewer) {
                            OutlinedTextField(
                                value = viewerText,
                                onValueChange = { viewerText = it },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(8.dp),
                                textStyle = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp)
                            )
                        } else {
                            SelectionContainer {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .verticalScroll(rememberScrollState())
                                        .padding(16.dp)
                                ) {
                                    Text(
                                        text = viewerText,
                                        style = MaterialTheme.typography.bodyLarge,
                                        lineHeight = 26.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (totalParts > 1) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = {
                                    val textToCopy = if (viewerText == script.scriptText && partsMap.isNotEmpty()) {
                                        val selected = (0 until midPart).mapNotNull { partsMap[it] }.filter { it.isNotBlank() }
                                        if (selected.isNotEmpty()) selected.joinToString("\n\n") { it.trim() }
                                        else viewerText.split("\n\n").let { paras -> paras.take((paras.size + 1) / 2).joinToString("\n\n") }
                                    } else {
                                        val paras = viewerText.split("\n\n").filter { it.isNotBlank() }
                                        if (paras.size > 1) paras.take((paras.size + 1) / 2).joinToString("\n\n")
                                        else viewerText.take(viewerText.length / 2)
                                    }
                                    clipboardManager.setText(AnnotatedString(textToCopy))
                                    android.widget.Toast.makeText(context, "Copy 1 ($part1RangeStr) ကူးယူပြီးပါပြီ", android.widget.Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Copy 1 ($part1RangeStr)", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                            }

                            OutlinedButton(
                                onClick = {
                                    val textToCopy = if (viewerText == script.scriptText && partsMap.isNotEmpty()) {
                                        val selected = (midPart until totalParts).mapNotNull { partsMap[it] }.filter { it.isNotBlank() }
                                        if (selected.isNotEmpty()) selected.joinToString("\n\n") { it.trim() }
                                        else viewerText.split("\n\n").let { paras -> paras.drop((paras.size + 1) / 2).joinToString("\n\n") }
                                    } else {
                                        val paras = viewerText.split("\n\n").filter { it.isNotBlank() }
                                        if (paras.size > 1) paras.drop((paras.size + 1) / 2).joinToString("\n\n")
                                        else viewerText.drop(viewerText.length / 2)
                                    }
                                    clipboardManager.setText(AnnotatedString(textToCopy))
                                    android.widget.Toast.makeText(context, "Copy 2 ($part2RangeStr) ကူးယူပြီးပါပြီ", android.widget.Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Copy 2 ($part2RangeStr)", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            IconButton(onClick = {
                                clipboardManager.setText(AnnotatedString(viewerText))
                                android.widget.Toast.makeText(context, "ဇာတ်ညွှန်းတစ်ခုလုံး (Full Script) Copy ကူးပြီးပါပြီ", android.widget.Toast.LENGTH_SHORT).show()
                            }) {
                                Icon(Icons.Filled.ContentCopy, contentDescription = "Copy")
                            }
                            if (isEditingInViewer) {
                                IconButton(onClick = {
                                    isEditingInViewer = false
                                    coroutineScope.launch {
                                        dao.updateScriptText(script.id, viewerText)
                                    }
                                }) {
                                    Icon(Icons.Filled.Save, contentDescription = "Save", tint = MaterialTheme.colorScheme.primary)
                                }
                            } else {
                                IconButton(onClick = {
                                    isEditingInViewer = true
                                }) {
                                    Icon(Icons.Filled.Edit, contentDescription = "Edit")
                                }
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { viewingHistoryScript = null }) {
                                Text("ပိတ်မည်")
                            }
                        }
                    }
                }
            }
        )
    }

    if (showScriptInfo != null) {
        val totalCalls = showScriptInfo?.totalApiCalls ?: 0
        val successCalls = showScriptInfo?.successApiCalls ?: 0
        val failedCalls = showScriptInfo?.failedApiCalls ?: 0
        val prevVersionsStr = showScriptInfo?.previousVersionsJson ?: "[]"
        val prevVersions = try {
            org.json.JSONArray(prevVersionsStr)
        } catch (e: Exception) {
            org.json.JSONArray()
        }

        AlertDialog(
            onDismissRequest = { showScriptInfo = null },
            title = { Text("Script Information") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text("Settings:", fontWeight = FontWeight.Bold)
                    Text("Model: ${showScriptInfo?.modelId}")
                    Text("Language: ${showScriptInfo?.language}")
                    Text("Length: ${showScriptInfo?.targetLength}")
                    Text("POV: ${showScriptInfo?.pov}")
                    Text("Tone: ${showScriptInfo?.tone}")
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("API Calls & RPD Status:", fontWeight = FontWeight.Bold)
                    val rpdObj = try {
                        if (!showScriptInfo?.rpdInfoJson.isNullOrBlank()) org.json.JSONObject(showScriptInfo!!.rpdInfoJson) else null
                    } catch (_: Exception) {
                        null
                    }
                    if (rpdObj != null) {
                        val isRpdOn = rpdObj.optBoolean("rpdEnabled", false)
                        val usedSpare = rpdObj.optBoolean("usedSpareKey", false)
                        val mTot = rpdObj.optInt("mainTotal", totalCalls)
                        val mSuc = rpdObj.optInt("mainSuccess", successCalls)
                        val mFai = rpdObj.optInt("mainFailed", failedCalls)
                        val sTot = rpdObj.optInt("spareTotal", 0)
                        val sSuc = rpdObj.optInt("spareSuccess", 0)
                        val sFai = rpdObj.optInt("spareFailed", 0)

                        Text("RPD Mode: ${if (isRpdOn) "ON (Max 20 Requests)" else "OFF"}")
                        Text("Main API Key Calls: $mTot (Success: $mSuc, Failed: $mFai)")
                        if (usedSpare || sTot > 0) {
                            Text("Spare API Key Used: Yes", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                            Text("Spare API Key Calls: $sTot (Success: $sSuc, Failed: $sFai)")
                        } else {
                            Text("Spare API Key Used: No")
                        }
                        Text("Total Calls (Main + Spare): $totalCalls (Success: $successCalls, Failed: $failedCalls)")

                        val savedMainChar = rpdObj.optString("confirmedMainCharacter", "")
                        val savedCharList = rpdObj.optString("characterProfile", "")
                        if (savedMainChar.isNotBlank() || savedCharList.isNotBlank()) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("Character List & Relationship Status:", fontWeight = FontWeight.Bold)
                            if (savedMainChar.isNotBlank()) {
                                Text(
                                    text = "⭐ Main Character: $savedMainChar",
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                            if (savedCharList.isNotBlank()) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                                ) {
                                    Text(
                                        text = savedCharList,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(10.dp)
                                    )
                                }
                            }
                        }
                    } else {
                        Text("Total Calls: $totalCalls")
                        Text("Success Calls: $successCalls", color = MaterialTheme.colorScheme.primary)
                        Text("Failed Calls: $failedCalls", color = MaterialTheme.colorScheme.error)
                    }
                    
                    if (prevVersions.length() > 0) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Previous Versions:", fontWeight = FontWeight.Bold)
                        for (i in 0 until prevVersions.length()) {
                            val v = prevVersions.getJSONObject(i)
                            val vTime = java.text.SimpleDateFormat("MMM dd, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(v.getLong("timestamp")))
                            val vText = v.getString("scriptText")
                            OutlinedButton(
                                onClick = { previousVersionToShow = vText },
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            ) {
                                Text("Version ${i + 1} ($vTime)")
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showScriptInfo = null }) {
                    Text("OK")
                }
            }
        )
    }

    if (previousVersionToShow != null) {
        AlertDialog(
            onDismissRequest = { previousVersionToShow = null },
            title = { Text("Previous Version") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(previousVersionToShow ?: "")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    clipboardManager.setText(AnnotatedString(previousVersionToShow ?: ""))
                }) {
                    Text("Copy")
                }
            },
            dismissButton = {
                TextButton(onClick = { previousVersionToShow = null }) {
                    Text("Close")
                }
            }
        )
    }

    if (scriptToRename != null) {
        AlertDialog(
            onDismissRequest = { scriptToRename = null },
            title = { Text("မှတ်တမ်းအမည် ပြင်ဆင်ရန်") },
            text = {
                OutlinedTextField(
                    value = newNameInput,
                    onValueChange = { newNameInput = it },
                    label = { Text("အမည်သစ်") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    coroutineScope.launch {
                        scriptToRename?.let { dao.updateFileName(it.id, newNameInput) }
                        scriptToRename = null
                    }
                }) {
                    Text("ပြင်ဆင်မည်")
                }
            },
            dismissButton = {
                TextButton(onClick = { scriptToRename = null }) { Text("မလုပ်ပါ") }
            }
        )
    }

    if (scriptToDelete != null) {
        AlertDialog(
            onDismissRequest = { scriptToDelete = null },
            title = { Text("ဖျက်မည်လား") },
            text = { Text("ဤမှတ်တမ်းကို ဖျက်ပစ်ချင်တာ သေချာပါသလား။") },
            confirmButton = {
                TextButton(onClick = {
                    coroutineScope.launch {
                        scriptToDelete?.let {
                            GeminiFileKeyCache.deleteSeriesPartCharacterProfile(
                                context = context,
                                fileName = it.fileName,
                                geminiFileUri = it.geminiFileUri
                            )
                            dao.deleteScriptById(it.id)
                        }
                        scriptToDelete = null
                    }
                }) {
                    Text("သေချာသည် (Delete)")
                }
            },
            dismissButton = {
                TextButton(onClick = { scriptToDelete = null }) { Text("မလုပ်ပါ") }
            }
        )
    }

    if (scriptToRegenerate != null) {
        var localModelKey by remember(scriptToRegenerate) { 
            mutableStateOf(scriptToRegenerate?.modelId?.ifBlank { selectedModelKey } ?: selectedModelKey) 
        }
        var localParts by remember(scriptToRegenerate) { mutableStateOf(scriptToRegenerate!!.partsCount.toFloat()) }
        var localParallelKeys by remember(scriptToRegenerate) { mutableStateOf(parallelKeys) }
        var localLength by remember(scriptToRegenerate) { mutableStateOf(scriptToRegenerate!!.targetLength) }
        var localPOV by remember(scriptToRegenerate) { mutableStateOf(scriptToRegenerate!!.pov) }
        var localTone by remember(scriptToRegenerate) { mutableStateOf(scriptToRegenerate!!.tone) }
        var localLanguage by remember(scriptToRegenerate) { mutableStateOf(scriptToRegenerate!!.language) }

        AlertDialog(
            onDismissRequest = { scriptToRegenerate = null },
            title = { Text("Settings ပြင်ဆင်ရန်") },
            text = {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(8.dp)
                ) {
                    DropdownSection(
                        title = "Gemini Model",
                        options = modelOptions.map { it.second },
                        selectedOption = modelKeyToDisplay(localModelKey),
                        onOptionSelected = { localModelKey = modelDisplayToKey(it) }
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    SliderSection(
                        title = "Number of parts the video",
                        value = localParts,
                        onValueChange = { localParts = it },
                        valueRange = 5f..50f
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    SliderSection(
                        title = "Parallel Key",
                        value = localParallelKeys,
                        onValueChange = { localParallelKeys = it },
                        valueRange = 1f..10f
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    DropdownSection(
                        title = "Script Length",
                        options = scriptLengths,
                        selectedOption = localLength,
                        onOptionSelected = { localLength = it }
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    DropdownSection(
                        title = "Narrator POV",
                        options = narratorPOVs,
                        selectedOption = localPOV,
                        onOptionSelected = { localPOV = it }
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    DropdownSection(
                        title = "Script Tone",
                        options = scriptTones,
                        selectedOption = localTone,
                        onOptionSelected = { localTone = it }
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    DropdownSection(
                        title = "Output Language",
                        options = outputLanguages,
                        selectedOption = localLanguage,
                        onOptionSelected = { localLanguage = it }
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    parallelKeys = localParallelKeys
                    sharedPrefs.edit().putFloat("preset_parallel_keys", localParallelKeys).apply()
                    val updatedScript = scriptToRegenerate!!.copy(
                        modelId = localModelKey,
                        partsCount = localParts.toInt(),
                        targetLength = localLength,
                        pov = localPOV,
                        tone = localTone,
                        language = localLanguage
                    )
                    scriptToRegenerate = null
                    triggerScriptAction(updatedScript, null)
                }) {
                    Text("Start Generation")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { scriptToRegenerate = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (scriptForSpecialParts != null) {
        val targetScript = scriptForSpecialParts!!
        val totalParts = targetScript.partsCount
        val selectedPartIndices = remember(targetScript.id) { mutableStateListOf<Int>() }

        val partsMap = remember(targetScript.id) {
            try {
                if (targetScript.scriptPartsJson.isNotBlank()) {
                    val obj = org.json.JSONObject(targetScript.scriptPartsJson)
                    obj.keys().asSequence().associate { it.toInt() to obj.getString(it) }
                } else emptyMap()
            } catch (_: Exception) {
                emptyMap()
            }
        }

        AlertDialog(
            onDismissRequest = { scriptForSpecialParts = null },
            title = {
                Column {
                    Text(
                        text = "Retry Special Parts",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${targetScript.fileName} (စုစုပေါင်း $totalParts ပိုင်း)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = "ပြန်လည်ထုတ်လုပ်လိုသော အပိုင်းနံပါတ်များကို ရွေးချယ်ပါ-",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                selectedPartIndices.clear()
                                for (i in 0 until totalParts) {
                                    selectedPartIndices.add(i)
                                }
                            },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text("Select All", style = MaterialTheme.typography.labelMedium)
                        }
                        OutlinedButton(
                            onClick = {
                                selectedPartIndices.clear()
                            },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text("Clear All", style = MaterialTheme.typography.labelMedium)
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    val chunkedParts = (0 until totalParts).chunked(5)
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        chunkedParts.forEach { rowIndices ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                rowIndices.forEach { partIndex ->
                                    val isSelected = selectedPartIndices.contains(partIndex)
                                    val partContent = partsMap[partIndex]
                                    val isFailedOrMissing = partContent.isNullOrBlank() || 
                                        partContent.contains("နေရာလွတ်") || 
                                        partContent.contains("Error")

                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .aspectRatio(1f)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(
                                                if (isSelected) MaterialTheme.colorScheme.primary
                                                else if (isFailedOrMissing) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                                                else MaterialTheme.colorScheme.surfaceVariant
                                            )
                                            .border(
                                                width = if (isSelected) 2.dp else 1.dp,
                                                color = if (isSelected) MaterialTheme.colorScheme.primary
                                                else if (isFailedOrMissing) MaterialTheme.colorScheme.error.copy(alpha = 0.6f)
                                                else MaterialTheme.colorScheme.outlineVariant,
                                                shape = RoundedCornerShape(8.dp)
                                            )
                                            .clickable {
                                                if (isSelected) {
                                                    selectedPartIndices.remove(partIndex)
                                                } else {
                                                    selectedPartIndices.add(partIndex)
                                                }
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.Center
                                        ) {
                                            Text(
                                                text = "${partIndex + 1}",
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                                                color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                                else if (isFailedOrMissing) MaterialTheme.colorScheme.error
                                                else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            if (isFailedOrMissing && !isSelected) {
                                                Text(
                                                    text = "⚠️",
                                                    style = MaterialTheme.typography.labelSmall
                                                )
                                            }
                                        }
                                    }
                                }
                                for (emptySlot in 0 until (5 - rowIndices.size)) {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }

                    if (selectedPartIndices.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "ရွေးချယ်ထားသောအပိုင်း (${selectedPartIndices.size} ပိုင်း): ${selectedPartIndices.sorted().map { it + 1 }.joinToString(", ")}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val partsToRun = selectedPartIndices.sorted().toList()
                        val scriptToRun = targetScript
                        scriptForSpecialParts = null
                        scriptToConfigureRetry = Pair(scriptToRun, partsToRun)
                    },
                    enabled = selectedPartIndices.isNotEmpty()
                ) {
                    Text(
                        if (selectedPartIndices.isEmpty()) "Select at least 1 part" 
                        else "Continue (${selectedPartIndices.size} Parts)"
                    )
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { scriptForSpecialParts = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (scriptToConfigureRetry != null) {
        val targetScript = scriptToConfigureRetry!!.first
        val partsToRun = scriptToConfigureRetry!!.second
        var localModelKey by remember(scriptToConfigureRetry) { 
            mutableStateOf(targetScript.modelId.ifBlank { selectedModelKey }) 
        }
        val maxAllowedParallel = maxOf(1f, minOf(10f, partsToRun.size.toFloat()))
        var localParallelKeys by remember(scriptToConfigureRetry) { 
            mutableStateOf(minOf(parallelKeys, maxAllowedParallel)) 
        }
        var localLength by remember(scriptToConfigureRetry) { mutableStateOf(targetScript.targetLength) }
        var localPOV by remember(scriptToConfigureRetry) { mutableStateOf(targetScript.pov) }
        var localTone by remember(scriptToConfigureRetry) { mutableStateOf(targetScript.tone) }
        var localLanguage by remember(scriptToConfigureRetry) { mutableStateOf(targetScript.language) }

        AlertDialog(
            onDismissRequest = { scriptToConfigureRetry = null },
            title = { Text("Retry Settings ပြင်ဆင်ရန် (${partsToRun.size} ပိုင်း)") },
            text = {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(8.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "ထုတ်လုပ်မည့် အပိုင်းများ: Part ${partsToRun.map { it + 1 }.joinToString(", ")}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                    DropdownSection(
                        title = "Gemini Model",
                        options = modelOptions.map { it.second },
                        selectedOption = modelKeyToDisplay(localModelKey),
                        onOptionSelected = { localModelKey = modelDisplayToKey(it) }
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    if (maxAllowedParallel > 1f) {
                        SliderSection(
                            title = "Parallel Key (အများဆုံး ${maxAllowedParallel.toInt()} ခု)",
                            value = localParallelKeys.coerceIn(1f, maxAllowedParallel),
                            onValueChange = { localParallelKeys = it.coerceIn(1f, maxAllowedParallel) },
                            valueRange = 1f..maxAllowedParallel
                        )
                    } else {
                        Column {
                            Text(
                                text = "Parallel Key",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onBackground,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Key ၁ ခု (အပိုင်း ၁ ပိုင်းသာဖြစ်သဖြင့် ၁ ခုသာ အသုံးပြုနိုင်ပါသည်)",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    DropdownSection(
                        title = "Script Length",
                        options = scriptLengths,
                        selectedOption = localLength,
                        onOptionSelected = { localLength = it }
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    DropdownSection(
                        title = "Narrator POV",
                        options = narratorPOVs,
                        selectedOption = localPOV,
                        onOptionSelected = { localPOV = it }
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    DropdownSection(
                        title = "Script Tone",
                        options = scriptTones,
                        selectedOption = localTone,
                        onOptionSelected = { localTone = it }
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    DropdownSection(
                        title = "Output Language",
                        options = outputLanguages,
                        selectedOption = localLanguage,
                        onOptionSelected = { localLanguage = it }
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    parallelKeys = localParallelKeys
                    sharedPrefs.edit().putFloat("preset_parallel_keys", localParallelKeys).apply()
                    val updatedScript = targetScript.copy(
                        modelId = localModelKey,
                        targetLength = localLength,
                        pov = localPOV,
                        tone = localTone,
                        language = localLanguage
                    )
                    scriptToConfigureRetry = null
                    triggerScriptAction(updatedScript, partsToRun)
                }) {
                    Text("Start Retry (${partsToRun.size} Parts)")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { scriptToConfigureRetry = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showStopDialog) {
        AlertDialog(
            onDismissRequest = { showStopDialog = false },
            title = { Text("Script ထုတ်လုပ်မှု ရပ်တန့်မည်လား") },
            text = { 
                Text("တွက်ချက်နေမှုကို ရပ်တန့်ချင်တာ သေချာပါသလား။\n\n• 'ပြီးသလောက်ယူမည်' ကို ရွေးပါက လက်ရှိထွက်ထားသမျှကို မှတ်တမ်းတင်သိမ်းဆည်းပေးမည်ဖြစ်ပြီး မပြီးသေးသောအပိုင်းများကို နောက်မှ ဆက်လက် ထုတ်ယူနိုင်ပါမည်။\n• 'အကုန်ဖျက်မည်' ကို ရွေးပါက လက်ရှိ လုပ်ဆောင်ချက်အားလုံး ပျက်ပြယ်သွားပါမည်။") 
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            showStopDialog = false
                            if (saveCurrentProgressAndStop != null) {
                                saveCurrentProgressAndStop?.invoke()
                            } else {
                                generationJob?.cancel()
                                isGenerating = false
                                generationLogs.add("User stopped generation.")
                            }
                        }
                    ) {
                        Text("ပြီးသလောက်ယူမည် (Save)")
                    }
                    OutlinedButton(
                        onClick = {
                            generationJob?.cancel()
                            isGenerating = false
                            saveCurrentProgressAndStop = null
                            showStopDialog = false
                            generationLogs.add("User cancelled generation (Discarded).")
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("အကုန်ဖျက်မည်")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showStopDialog = false }) {
                    Text("မလုပ်ပါ (Cancel)")
                }
            }
        )
    }

    if (showCharacterConfirmDialog != null) {
        val confirmData = showCharacterConfirmDialog!!
        val isMainPovSelected = selectedPOV.contains("Main Character", ignoreCase = true)

        val buildConfirmationPayload: () -> Pair<String, String> = {
            val selectedChar = confirmData.characters.getOrNull(selectedCharacterDialogIndex)
            val selectedCustomName = customCharacterNamesMap[selectedCharacterDialogIndex]?.trim() ?: ""
            val baseSelection = customCharacterDialogInput.trim().ifBlank {
                if (selectedChar != null) {
                    val relPart = if (selectedChar.relationshipStatus.isNotBlank()) " [${selectedChar.relationshipStatus}]" else ""
                    "${selectedChar.nameAndAppearance}$relPart - ${selectedChar.pronoun} နာမ်စားသုံးရန်"
                } else ""
            }
            val finalMainSelection = if (selectedCustomName.isNotBlank() && !baseSelection.contains("ပြောင်းရေးရန်")) {
                "$baseSelection (နာမည်ကို '$selectedCustomName' အဖြစ် ပြောင်းရေးရန်)"
            } else {
                baseSelection
            }

            val customRulesArray = org.json.JSONArray()
            val updatedLines = confirmData.characters.mapIndexed { idx, c ->
                val roleStr = if (isMainPovSelected) {
                    if (idx == selectedCharacterDialogIndex) "MAIN" else "SUPPORT"
                } else {
                    if (c.isRecommendedMain) "MAIN" else "SUPPORT"
                }
                val cCustomName = customCharacterNamesMap[idx]?.trim() ?: ""
                val relPart = if (c.relationshipStatus.isNotBlank()) " [${c.relationshipStatus}]" else ""
                if (cCustomName.isNotBlank()) {
                    customRulesArray.put(
                        "${c.nameAndAppearance}$relPart (နာမ်စား: ${c.pronoun}) ၏ နာမည်ကို ဗီဒီယိုထဲတွင် မူရင်းအမည်ဖြင့် ခေါ်ဝေါ်နေသော်လည်း သင်ရေးသားမည့် Script ထဲတွင် '$cCustomName' အဖြစ် အစမှအဆုံး ပြောင်းလဲခေါ်ဝေါ်ရေးသားရန်"
                    )
                }
                val descWithCustom = if (cCustomName.isNotBlank()) {
                    "${c.nameAndAppearance} 👉 [နာမည်ကို '$cCustomName' အဖြစ် ပြောင်းရေးရန်]"
                } else {
                    c.nameAndAppearance
                }
                "CHARACTER_ITEM: $roleStr | $descWithCustom | ${c.relationshipStatus} | ${c.pronoun}"
            }
            val updatedProfileStr = if (updatedLines.isNotEmpty()) {
                updatedLines.joinToString("\n")
            } else {
                confirmData.rawProfileText
            }

            val payloadJson = org.json.JSONObject().apply {
                put("selectedMainSummary", finalMainSelection)
                put("updatedProfileText", updatedProfileStr)
                put("customNameRules", customRulesArray)
            }.toString()

            Pair(finalMainSelection, payloadJson)
        }

        LaunchedEffect(confirmData, isEditingCharacterNames) {
            if (!isEditingCharacterNames) {
                while (characterConfirmCountdown > 0 && !isEditingCharacterNames) {
                    kotlinx.coroutines.delay(1000L)
                    if (!isEditingCharacterNames) {
                        characterConfirmCountdown--
                    }
                }
                if (!isEditingCharacterNames && characterConfirmCountdown <= 0 && showCharacterConfirmDialog != null) {
                    val (finalSelection, payloadJson) = buildConfirmationPayload()
                    if (isMainPovSelected && finalSelection.isNotBlank()) {
                        characterHint = finalSelection
                        sharedPrefs.edit().putString("preset_character_hint", finalSelection).apply()
                    }
                    showCharacterConfirmDialog = null
                    activeCharacterConfirmChannel?.send(payloadJson)
                }
            }
        }

        AlertDialog(
            onDismissRequest = { /* Prevent outside click dismiss */ },
            title = {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isMainPovSelected) "ပင်မဇာတ်ဆောင်နှင့် နာမည် အတည်ပြုရန်" else "ဇာတ်ကောင်စာရင်းနှင့် နာမည် အတည်ပြုရန်",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = { isEditingCharacterNames = !isEditingCharacterNames },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Edit,
                                contentDescription = "Edit Character Names",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (isEditingCharacterNames) "Done" else "Edit",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (isEditingCharacterNames) {
                            "✏️ နာမည်ပြင်ဆင်နေစဉ် Countdown ရပ်ထားပေးပါသည်။ ဇာတ်ကောင်ဘေးရှိ အကွက်တွင် ပြောင်းလိုသော နာမည်သစ် ထည့်သွင်းနိုင်ပါသည်။"
                        } else {
                            "⏱️ $characterConfirmCountdown စက္ကန့်အတွင်း မရွေးချယ်ပါက AI သတ်မှတ်ထားသည့်အတိုင်း အလိုအလျောက် ဆက်လက်လုပ်ဆောင်ပါမည်။ (နာမည်ပြင်လိုပါက Edit ကိုနှိပ်ပါ)"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isEditingCharacterNames) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
                    )
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = if (isMainPovSelected) {
                            "ဗီဒီယိုထဲတွင် တွေ့ရှိရသော ဇာတ်ကောင်များ (ပင်မဇာတ်ဆောင် ရွေးချယ်ရန် / နာမည်ပြင်ရန်):"
                        } else {
                            "ဗီဒီယိုထဲတွင် တွေ့ရှိရသော ဇာတ်ကောင်များနှင့် တော်စပ်ပုံ:"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    confirmData.characters.forEachIndexed { idx, charItem ->
                        val isSelected = selectedCharacterDialogIndex == idx
                        val customNameVal = customCharacterNamesMap[idx] ?: ""
                        val formattedSummary = buildString {
                            append(charItem.nameAndAppearance)
                            if (charItem.relationshipStatus.isNotBlank()) {
                                append(" [${charItem.relationshipStatus}]")
                            }
                            append(" - ${charItem.pronoun} နာမ်စားသုံးရန်")
                            if (customNameVal.isNotBlank()) {
                                append(" (နာမည်ကို '${customNameVal.trim()}' အဖြစ် ပြောင်းရေးရန်)")
                            }
                        }
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (isMainPovSelected) {
                                        selectedCharacterDialogIndex = idx
                                        customCharacterDialogInput = formattedSummary
                                    }
                                },
                            shape = RoundedCornerShape(10.dp),
                            color = if (isMainPovSelected && isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            border = if (isMainPovSelected && isSelected) androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isMainPovSelected) {
                                    androidx.compose.material3.RadioButton(
                                        selected = isSelected,
                                        onClick = {
                                            selectedCharacterDialogIndex = idx
                                            customCharacterDialogInput = formattedSummary
                                        }
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    if (charItem.isRecommendedMain) {
                                        Text(
                                            text = "⭐ AI သတ်မှတ်ထားသော ပင်မဇာတ်ဆောင်",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    Text(
                                        text = charItem.nameAndAppearance,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (isMainPovSelected && isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                    if (charItem.relationshipStatus.isNotBlank()) {
                                        Text(
                                            text = "ဆက်ဆံရေး/တော်စပ်ပုံ: ${charItem.relationshipStatus}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.secondary,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                    Text(
                                        text = "နာမ်စား: ${charItem.pronoun}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    if (customNameVal.isNotBlank()) {
                                        Text(
                                            text = "👉 နာမည်ကို '${customNameVal.trim()}' အဖြစ် ပြောင်းရေးမည်",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                if (isEditingCharacterNames) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    OutlinedTextField(
                                        value = customNameVal,
                                        onValueChange = { newCustom ->
                                            customCharacterNamesMap[idx] = newCustom
                                            if (isMainPovSelected && selectedCharacterDialogIndex == idx) {
                                                val relPart = if (charItem.relationshipStatus.isNotBlank()) " [${charItem.relationshipStatus}]" else ""
                                                val suffix = if (newCustom.trim().isNotBlank()) " (နာမည်ကို '${newCustom.trim()}' အဖြစ် ပြောင်းရေးရန်)" else ""
                                                customCharacterDialogInput = "${charItem.nameAndAppearance}$relPart - ${charItem.pronoun} နာမ်စားသုံးရန်$suffix"
                                            }
                                        },
                                        label = { Text("နာမည်သစ်", fontSize = 11.sp) },
                                        placeholder = { Text("ဥပမာ-သီရိ", fontSize = 11.sp) },
                                        singleLine = true,
                                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                                        modifier = Modifier.width(120.dp),
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                }
                            }
                        }
                    }

                    if (isMainPovSelected) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "သတ်မှတ်မည့် Main Character Hint (လိုအပ်ပါက ပြင်ဆင်နိုင်သည်):",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = customCharacterDialogInput,
                            onValueChange = { customCharacterDialogInput = it },
                            trailingIcon = {
                                if (customCharacterDialogInput.isNotBlank()) {
                                    IconButton(onClick = { customCharacterDialogInput = "" }) {
                                        Icon(Icons.Filled.Close, contentDescription = "Clear")
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            maxLines = 2
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val (finalSelection, payloadJson) = buildConfirmationPayload()
                        if (isMainPovSelected && finalSelection.isNotBlank()) {
                            characterHint = finalSelection
                            sharedPrefs.edit().putString("preset_character_hint", finalSelection).apply()
                        }
                        showCharacterConfirmDialog = null
                        coroutineScope.launch {
                            activeCharacterConfirmChannel?.send(payloadJson)
                        }
                    }
                ) {
                    Text(
                        if (isEditingCharacterNames) "အတည်ပြုမည်" else "အတည်ပြုမည် (${characterConfirmCountdown}s)"
                    )
                }
            }
        )
    }

    if (showRpdKeyDialog) {
        LaunchedEffect(showRpdKeyDialog) {
            while (rpdCountdown > 0) {
                kotlinx.coroutines.delay(1000)
                rpdCountdown--
            }
            if (showRpdKeyDialog) {
                // Auto-save and accept current progress when timer expires
                showRpdKeyDialog = false
                generationLogs.add("⏱️ RPD Timeout: လက်ရှိရရှိထားသမျှ အပိုင်းများကို အလိုအလျောက် သိမ်းဆည်းလိုက်ပါပြီ။")
                if (saveCurrentProgressAndStop != null) {
                    saveCurrentProgressAndStop?.invoke()
                } else {
                    activeNewKeyChannel?.send("")
                    activeDecisionChannel?.send(false)
                }
            }
        }

        var dialogSelectedModelKey by remember(showRpdKeyDialog) {
            mutableStateOf(activeRunningModel.ifBlank { selectedModelKey })
        }
        val currentDialogModel = dialogSelectedModelKey
        val mainKeyStatus = remember(editMainKeyInput, rpdEnabled, currentDialogModel) { RpdTracker.getStatus(context, editMainKeyInput, model = currentDialogModel) }
        val spareKeyStatus = remember(editSpareKeyInput, rpdEnabled, currentDialogModel) { RpdTracker.getStatus(context, editSpareKeyInput, model = currentDialogModel) }

        val isModelChanged = dialogSelectedModelKey != (activeRunningModel.ifBlank { selectedModelKey })
        val hasQuotaOnCurrentKeys = !mainKeyStatus.isLimitReached || (editSpareKeyInput.isNotBlank() && !spareKeyStatus.isLimitReached)
        val canProceed = remember(editMainKeyInput, editSpareKeyInput, apiKey, spareApiKey, isModelChanged, hasQuotaOnCurrentKeys) {
            val mainChangedAndValid = editMainKeyInput.trim().isNotBlank() && editMainKeyInput.trim() != apiKey.trim()
            val spareChangedAndValid = editSpareKeyInput.trim().isNotBlank() && editSpareKeyInput.trim() != spareApiKey.trim()
            mainChangedAndValid || spareChangedAndValid || (isModelChanged && hasQuotaOnCurrentKeys)
        }

        AlertDialog(
            onDismissRequest = { /* Prevent dismiss */ },
            title = { 
                Text("⚠️ RPD Limit (${mainKeyStatus.maxLimit} ကြိမ်) ပြည့်သွားပါပြီ ($currentDialogModel)", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error) 
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "လက်ရှိ အသုံးပြုနေသော API Keys များ၏ Daily Request Limit (RPD - $currentDialogModel) ကုန်ဆုံးသွားပါပြီ။\n\nကျန်ရှိနေသော အပိုင်းများကို ဆက်လက်ထုတ်ယူရန် အခြား Gemini Model တစ်ခုသို့ ပြောင်းလဲအသုံးပြုနိုင်သလို (သို့မဟုတ်) Main API Key / Spare API Key အသစ် လဲလှယ်ထည့်သွင်းပေးနိုင်ပါသည် ($rpdCountdown စက္ကန့်အတွင်း လက်ရှိရသမျှကို Auto-Save လုပ်ပေးပါမည်)။",
                        style = MaterialTheme.typography.bodyMedium
                    )

                    // Model Selection Section
                    DropdownSection(
                        title = "Gemini Model ပြောင်းလဲရန်",
                        options = modelOptions.map { it.second },
                        selectedOption = modelKeyToDisplay(dialogSelectedModelKey),
                        onOptionSelected = { dialogSelectedModelKey = modelDisplayToKey(it) }
                    )

                    // Main Key Section
                    if (editMainKeyInput.isNotBlank()) {
                        if (mainKeyStatus.isLimitReached) {
                            Card(
                                shape = RoundedCornerShape(10.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Main Key RPD: ${mainKeyStatus.usedCount}/${mainKeyStatus.maxLimit} ပြည့်ပြီး (Reset: ${mainKeyStatus.hoursUntilReset}h ${mainKeyStatus.minutesUntilReset}m)",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        } else {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "Main Key RPD: ${mainKeyStatus.usedCount}/${mainKeyStatus.maxLimit} ကြိမ် (Available: ${mainKeyStatus.remainingCount} ကြိမ်)",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = editMainKeyInput,
                        onValueChange = { editMainKeyInput = it },
                        label = { Text("Main API Key") },
                        placeholder = { Text("AIzaSy...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )

                    // Spare Key Section
                    if (editSpareKeyInput.isNotBlank()) {
                        if (spareKeyStatus.isLimitReached) {
                            Card(
                                shape = RoundedCornerShape(10.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Spare Key RPD: ${spareKeyStatus.usedCount}/${spareKeyStatus.maxLimit} ပြည့်ပြီး (Reset: ${spareKeyStatus.hoursUntilReset}h ${spareKeyStatus.minutesUntilReset}m)",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        } else {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "Spare Key RPD: ${spareKeyStatus.usedCount}/${spareKeyStatus.maxLimit} ကြိမ် (Available: ${spareKeyStatus.remainingCount} ကြိမ်)",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = editSpareKeyInput,
                        onValueChange = { editSpareKeyInput = it },
                        label = { Text("Spare API Key") },
                        placeholder = { Text("Enter spare API key (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmedMain = editMainKeyInput.trim()
                        val trimmedSpare = editSpareKeyInput.trim()
                        
                        // Save both updated keys directly to settings / preferences
                        if (trimmedMain.isNotBlank()) {
                            apiKey = trimmedMain
                            mainKeyEnabled = true
                            sharedPrefs.edit()
                                .putString("gemini_api_key", trimmedMain)
                                .putBoolean("gemini_main_key_enabled", true)
                                .apply()
                        }
                        
                        if (trimmedSpare.isNotBlank()) {
                            val updatedSlots = spareKeySlots.toMutableList()
                            if (updatedSlots.isEmpty()) {
                                updatedSlots.add(SpareKeySlot(key = trimmedSpare, enabled = true))
                            } else {
                                updatedSlots[0] = SpareKeySlot(key = trimmedSpare, enabled = true)
                            }
                            saveSpareKeySlots(updatedSlots)
                        }

                        val isModelSwitched = dialogSelectedModelKey != (activeRunningModel.ifBlank { selectedModelKey })
                        if (isModelSwitched) {
                            selectedModelKey = dialogSelectedModelKey
                            activeRunningModel = dialogSelectedModelKey
                            sharedPrefs.edit().putString("gemini_model", dialogSelectedModelKey).apply()
                        }
                        
                        // Decide which key to feed into active generation channel
                        val nextKeyToUse = when {
                            trimmedSpare.isNotBlank() && trimmedSpare != spareApiKey.trim() -> trimmedSpare
                            trimmedMain.isNotBlank() && trimmedMain != apiKey.trim() -> trimmedMain
                            trimmedSpare.isNotBlank() && !spareKeyStatus.isLimitReached -> trimmedSpare
                            !mainKeyStatus.isLimitReached -> trimmedMain
                            else -> trimmedMain
                        }

                        val messageToSend = if (isModelSwitched) {
                            "MODEL:$dialogSelectedModelKey:$nextKeyToUse"
                        } else {
                            nextKeyToUse
                        }

                        if (nextKeyToUse.isNotBlank() || isModelSwitched) {
                            coroutineScope.launch {
                                activeNewKeyChannel?.send(messageToSend)
                            }
                            showRpdKeyDialog = false
                        }
                    },
                    enabled = canProceed
                ) {
                    Text("သိမ်းဆည်းပြီး ဆက်လုပ်မည်")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        showRpdKeyDialog = false
                        if (saveCurrentProgressAndStop != null) {
                            saveCurrentProgressAndStop?.invoke()
                        } else {
                            coroutineScope.launch {
                                activeNewKeyChannel?.send("") // Signal to cancel/stop
                                activeDecisionChannel?.send(false)
                            }
                        }
                    }
                ) {
                    Text("လက်ရှိအတိုင်း သိမ်းဆည်းမည် (${rpdCountdown}s)")
                }
            }
        )
    }

    if (showSettings) {
        SettingsDialog(
            apiKey = apiKey,
            onApiKeyChange = { newKey ->
                apiKey = newKey
                sharedPrefs.edit().putString("gemini_api_key", newKey).apply()
            },
            mainKeyEnabled = mainKeyEnabled,
            onMainKeyEnabledChange = { enabled ->
                mainKeyEnabled = enabled
                sharedPrefs.edit().putBoolean("gemini_main_key_enabled", enabled).apply()
            },
            spareKeySlots = spareKeySlots,
            onSpareKeySlotsChange = { updatedSlots ->
                saveSpareKeySlots(updatedSlots)
            },
            rpdEnabled = rpdEnabled,
            onRpdEnabledChange = { newEnabled ->
                rpdEnabled = newEnabled
                sharedPrefs.edit().putBoolean("gemini_rpd_enabled", newEnabled).apply()
            },
            selectedModel = modelKeyToDisplay(selectedModelKey),
            onModelSelected = { selectedDisplay ->
                val newKey = modelDisplayToKey(selectedDisplay)
                selectedModelKey = newKey
                sharedPrefs.edit().putString("gemini_model", newKey).apply()
            },
            models = modelOptions.map { it.second },
            rpm = rpmSetting,
            onRpmChange = { newRpm ->
                rpmSetting = newRpm
                sharedPrefs.edit().putFloat("gemini_rpm", newRpm).apply()
            },
            onDismiss = { showSettings = false }
        )
    }

    if (errorMessageDialog != null) {
        LaunchedEffect(errorMessageDialog) {
            errorDialogCountdown = 15
            while (errorDialogCountdown > 0) {
                kotlinx.coroutines.delay(1000L)
                errorDialogCountdown--
            }
            errorMessageDialog = null
        }
        AlertDialog(
            onDismissRequest = { errorMessageDialog = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Info,
                        contentDescription = "Error",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text("Error ဖြစ်ပေါ်ပါသည်", color = MaterialTheme.colorScheme.error)
                }
            },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        text = errorMessageDialog ?: "အမည်မသိ အမှားတစ်ခု ဖြစ်ပေါ်ပါသည်။",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "အကြံပြုချက်: မှတ်တမ်းအား History ထဲတွင် သိမ်းဆည်းထားပြီး ဖြစ်ပါသည်။ ခေတ္တစောင့်ဆိုင်းပြီးမှ History ထဲမှနေ၍ ပြန်လည်ကြိုးစားနိုင်ပါသည် (${errorDialogCountdown}s အတွင်း အလိုအလျောက် ပိတ်ပါမည်)။",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(onClick = { errorMessageDialog = null }) {
                    Text("နားလည်ပါပြီ (${errorDialogCountdown}s)")
                }
            }
        )
    }
}

@Composable
fun PickerSection(
    selectedFileName: String,
    onSelectVideo: () -> Unit,
    onSelectAudio: () -> Unit
) {
    Column {
        Text(
            text = "Media Source",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            MediaButton(
                title = "Select Video",
                icon = Icons.Rounded.Movie,
                onClick = onSelectVideo,
                modifier = Modifier.weight(1f)
            )
            MediaButton(
                title = "Select Audio",
                icon = Icons.Rounded.AudioFile,
                onClick = onSelectAudio,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = selectedFileName,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Maximum size 2GB • Maximum length 30 minutes",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
        )
    }
}

@Composable
fun MediaButton(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .height(100.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable { onClick() },
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp
    ) {
        Column(
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))
            Text(title, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
fun SliderSection(
    title: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "${value.toInt()}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        if (valueRange.endInclusive > valueRange.start) {
            Slider(
                value = value.coerceIn(valueRange.start, valueRange.endInclusive),
                onValueChange = onValueChange,
                valueRange = valueRange,
                steps = maxOf(0, (valueRange.endInclusive - valueRange.start).toInt() - 1),
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DropdownSection(
    title: String,
    options: List<String>,
    selectedOption: String,
    onOptionSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(8.dp))
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded }
        ) {
            OutlinedTextField(
                value = selectedOption,
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .menuAnchor()
                    .fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.2f)
                ),
                shape = RoundedCornerShape(12.dp)
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = {
                            onOptionSelected(option)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun SettingsDialog(
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    mainKeyEnabled: Boolean,
    onMainKeyEnabledChange: (Boolean) -> Unit,
    spareKeySlots: List<SpareKeySlot>,
    onSpareKeySlotsChange: (List<SpareKeySlot>) -> Unit,
    rpdEnabled: Boolean,
    onRpdEnabledChange: (Boolean) -> Unit,
    selectedModel: String,
    onModelSelected: (String) -> Unit,
    models: List<String>,
    rpm: Float,
    onRpmChange: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            val context = LocalContext.current
            val headerResetStatus = remember(rpdEnabled, selectedModel) { RpdTracker.getStatus(context, apiKey, model = selectedModel) }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Settings", fontWeight = FontWeight.Bold)
                    val totalKeyCount = 1 + spareKeySlots.size
                    val activeCount = (if (mainKeyEnabled && apiKey.isNotBlank()) 1 else 0) +
                        spareKeySlots.count { it.enabled && it.key.isNotBlank() }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = "Active Keys: $activeCount/$totalKeyCount (Max 20)",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
                if (rpdEnabled) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "🕒 Daily Quota Reset: ${headerResetStatus.resetTimeDescription.split(" [").first()} (${headerResetStatus.hoursUntilReset}h ${headerResetStatus.minutesUntilReset}m အကြာ)",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                val context = LocalContext.current
                val mainKeyStatus = remember(apiKey, rpdEnabled, selectedModel) { RpdTracker.getStatus(context, apiKey, model = selectedModel) }
                val allKeysEnabled = mainKeyEnabled && spareKeySlots.all { it.enabled }

                // Master Switch: All API Keys ON/OFF
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "All API Keys (Master Switch)",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = if (allKeysEnabled) "API Key အားလုံး ဖွင့်ထားသည် (All ON)" else "အားလုံး တစ်ပြိုင်နက် ဖွင့်/ပိတ် ရန်",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = allKeysEnabled,
                            onCheckedChange = { turnAllOn ->
                                onMainKeyEnabledChange(turnAllOn)
                                val updatedList = spareKeySlots.map { it.copy(enabled = turnAllOn) }
                                onSpareKeySlotsChange(updatedList)
                            }
                        )
                    }
                }

                // Main API Key Card with ON/OFF Toggle
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (mainKeyEnabled) 0.45f else 0.2f),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (mainKeyEnabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Key #1 (Main API Key)",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = if (mainKeyEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (mainKeyEnabled) "[ON]" else "[OFF]",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (mainKeyEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                )
                            }
                            Switch(
                                checked = mainKeyEnabled,
                                onCheckedChange = onMainKeyEnabledChange
                            )
                        }

                        if (rpdEnabled && apiKey.isNotBlank()) {
                            if (mainKeyStatus.isLimitReached) {
                                Card(
                                    shape = RoundedCornerShape(8.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Warning,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "RPD (${mainKeyStatus.modelName}): ${mainKeyStatus.usedCount}/${mainKeyStatus.maxLimit} ပြည့်သွားပါပြီ",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onErrorContainer
                                        )
                                    }
                                }
                            } else {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = "RPD (${mainKeyStatus.modelName}): ${mainKeyStatus.usedCount}/${mainKeyStatus.maxLimit} ကြိမ် (Available: ${mainKeyStatus.remainingCount} ကြိမ်)",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                    )
                                }
                            }
                        }

                        OutlinedTextField(
                            value = apiKey,
                            onValueChange = onApiKeyChange,
                            label = { Text("Main API Key") },
                            placeholder = { Text("AIzaSy...") },
                            singleLine = true,
                            enabled = mainKeyEnabled,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp)
                        )
                    }
                }

                // Dynamic Spare API Key Slots (up to 19 Spare Keys => Total 20 Keys max)
                spareKeySlots.forEachIndexed { index, slot ->
                    val slotKeyStatus = remember(slot.key, rpdEnabled, selectedModel) { RpdTracker.getStatus(context, slot.key, model = selectedModel) }
                    val keyDisplayNum = index + 2 // Key #2 .. Key #20
                    val spareNum = index + 1     // Spare #1 .. Spare #19

                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (slot.enabled) 0.45f else 0.2f),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (slot.enabled) MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "Key #$keyDisplayNum (Spare Key #$spareNum)",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = if (slot.enabled) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (slot.enabled) "[ON]" else "[OFF]",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = if (slot.enabled) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error
                                    )
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Switch(
                                        checked = slot.enabled,
                                        onCheckedChange = { newEnabled ->
                                            val list = spareKeySlots.toMutableList()
                                            list[index] = slot.copy(enabled = newEnabled)
                                            onSpareKeySlotsChange(list)
                                        }
                                    )
                                    if (spareKeySlots.size > 1 || slot.key.isNotBlank()) {
                                        IconButton(
                                            onClick = {
                                                val list = spareKeySlots.toMutableList()
                                                if (list.size > 1) {
                                                    list.removeAt(index)
                                                } else {
                                                    list[0] = SpareKeySlot(key = "", enabled = true)
                                                }
                                                onSpareKeySlotsChange(list)
                                            }
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.Delete,
                                                contentDescription = "Remove Spare Key Slot",
                                                tint = MaterialTheme.colorScheme.error
                                            )
                                        }
                                    }
                                }
                            }

                            if (rpdEnabled && slot.key.isNotBlank()) {
                                if (slotKeyStatus.isLimitReached) {
                                    Card(
                                        shape = RoundedCornerShape(8.dp),
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.Warning,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = "RPD (${slotKeyStatus.modelName}): ${slotKeyStatus.usedCount}/${slotKeyStatus.maxLimit} ပြည့်သွားပါပြီ",
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onErrorContainer
                                            )
                                        }
                                    }
                                } else {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = "RPD (${slotKeyStatus.modelName}): ${slotKeyStatus.usedCount}/${slotKeyStatus.maxLimit} ကြိမ် (Available: ${slotKeyStatus.remainingCount} ကြိမ်)",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.secondary,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                        )
                                    }
                                }
                            }

                            OutlinedTextField(
                                value = slot.key,
                                onValueChange = { updatedText ->
                                    val list = spareKeySlots.toMutableList()
                                    list[index] = slot.copy(key = updatedText)
                                    onSpareKeySlotsChange(list)
                                },
                                label = { Text("Spare API Key #$spareNum") },
                                placeholder = { Text("Enter spare API key #$spareNum") },
                                singleLine = true,
                                enabled = slot.enabled,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp)
                            )
                        }
                    }
                }

                // Add Spare API Key Button (up to 19 Spare Keys = 20 Total Keys)
                val totalKeysNow = 1 + spareKeySlots.size
                if (totalKeysNow < 20) {
                    OutlinedButton(
                        onClick = {
                            val list = spareKeySlots.toMutableList()
                            list.add(SpareKeySlot(key = "", enabled = true))
                            onSpareKeySlotsChange(list)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("+ Add Spare API Key (${totalKeysNow}/20)")
                    }
                } else {
                    Text(
                        text = "✅ အများဆုံး API Key (၂၀) ခု ပြည့်သွားပါပြီ။",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "RPD Limit (Requests Per Day)",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = if (rpdEnabled) "Maximum: 20 Requests (On)" else "Off (ကန့်သတ်ချက်မရှိ)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (rpdEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = rpdEnabled,
                                onCheckedChange = onRpdEnabledChange
                            )
                        }
                        if (rpdEnabled) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Main Key ဖြင့် သတ်မှတ် RPD Limit (${mainKeyStatus.maxLimit} ကြိမ်) ပြည့်ပါက Spare Key သို့ အလိုအလျောက် ဆက်လက် run ပေးပါမည်။ Google Quota သည် Pacific Time ညဉ့်သန်းခေါင် (00:00 PST/PDT)၊ မြန်မာစံတော်ချိန် ${mainKeyStatus.resetTimeDescription.split(" [").first()} တွင် အလိုအလျောက် Reset ဖြစ်ပါမည်။",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                DropdownSection(
                    title = "Gemini Model",
                    options = models,
                    selectedOption = selectedModel,
                    onOptionSelected = onModelSelected
                )

                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Generation Speed (RPM)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "${rpm.toInt()} RPM",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Slider(
                        value = rpm,
                        onValueChange = onRpmChange,
                        valueRange = 4f..40f,
                        steps = 35,
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary
                        )
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Min: 4 (Safe)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Default: 20", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Max: 40 (Fast)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Save", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        shape = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.surface
    )
}
