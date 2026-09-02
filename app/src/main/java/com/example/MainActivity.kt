package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(modifier: Modifier = Modifier) {
    var showSettings by remember { mutableStateOf(false) }
    
    // States
    var parts by remember { mutableStateOf(20f) }
    
    val scriptLengths = listOf("Short", "Medium", "Long", "TTS match")
    var selectedLength by remember { mutableStateOf(scriptLengths[1]) }
    
    val narratorPOVs = listOf("Storyteller Script", "Main Character POV")
    var selectedPOV by remember { mutableStateOf(narratorPOVs[0]) }
    
    val scriptTones = listOf("Dramatic", "Comedic", "Suspenseful")
    var selectedTone by remember { mutableStateOf(scriptTones[0]) }

    val outputLanguages = listOf("Myanmar", "English")
    var selectedLanguage by remember { mutableStateOf(outputLanguages[0]) }

    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE) }

    // Settings State
    val models = listOf("Gemini 3.5 flash", "Gemini 3.6 flash")
    var apiKey by remember { mutableStateOf(sharedPrefs.getString("gemini_api_key", "") ?: "") }
    var selectedModel by remember { mutableStateOf(sharedPrefs.getString("gemini_model", models[0]) ?: models[0]) }

    // Generation State
    var generatedScript by remember { mutableStateOf("") }
    var isGenerating by remember { mutableStateOf(false) }
    var generationProgress by remember { mutableFloatStateOf(0f) }
    val generationLogs = remember { mutableStateListOf<String>() }
    var elapsedTime by remember { mutableLongStateOf(0L) }
    var showStopDialog by remember { mutableStateOf(false) }
    var generationJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    
    var activeDecisionChannel by remember { mutableStateOf<kotlinx.coroutines.channels.Channel<Boolean>?>(null) }
    var showRetryDialog by remember { mutableStateOf<List<Int>?>(null) }
    var retryCountdown by remember { mutableIntStateOf(15) }
    
    val coroutineScope = rememberCoroutineScope()


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
    
    var currentScriptId by remember { mutableStateOf<Int?>(null) }
    var isEditingScript by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current
    var showScriptInfo by remember { mutableStateOf<com.example.db.PrGtScript?>(null) }
    var errorMessageDialog by remember { mutableStateOf<String?>(null) }

    val launchGeneration: (com.example.db.PrGtScript?, List<Int>?) -> Unit = { existingScript, retryParts ->
        val generationStartTime = existingScript?.timestamp ?: System.currentTimeMillis()
        isGenerating = true
        generatedScript = ""
        currentScriptId = null
        generationProgress = 0f
        generationLogs.clear()
        showHistory = false // Close history if open
        
        val decisionChannel = kotlinx.coroutines.channels.Channel<Boolean>()
        activeDecisionChannel = decisionChannel
        
        generationJob = coroutineScope.launch {
            val scriptPartsMap = mutableMapOf<Int, String>()
            
            val activeModel = existingScript?.modelId ?: selectedModel
            val activeParts = existingScript?.partsCount?.toFloat() ?: parts
            val activeLength = existingScript?.targetLength ?: selectedLength
            val activePOV = existingScript?.pov ?: selectedPOV
            val activeTone = existingScript?.tone ?: selectedTone
            val activeLanguage = existingScript?.language ?: selectedLanguage
            
            var savedGeminiFileUri = existingScript?.geminiFileUri
            var savedMimeType = existingScript?.mimeType
            var savedDurationMs = (existingScript?.durationSec?.toLongOrNull() ?: 0L) * 1000
            var savedPartsCount = existingScript?.partsCount ?: parts.toInt()
            
            var localTotalApiCalls = existingScript?.totalApiCalls ?: 0
            var localSuccessApiCalls = existingScript?.successApiCalls ?: 0
            var localFailedApiCalls = existingScript?.failedApiCalls ?: 0
            var previousVersionsArray = org.json.JSONArray(existingScript?.previousVersionsJson ?: "[]")
            
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

            geminiApiClient.generateRecapScript(
                apiKey = apiKey,
                modelName = activeModel,
                partsCount = activeParts,
                length = activeLength,
                pov = activePOV,
                tone = activeTone,
                language = activeLanguage,
                mediaUri = selectedMediaUri, // This might be null if retrying, but existingGeminiFileUri will handle it
                mediaMimeType = savedMimeType ?: selectedMediaMimeType,
                retryDecisionChannel = decisionChannel,
                existingGeminiFileUri = savedGeminiFileUri,
                existingDurationMs = if (savedDurationMs > 0) savedDurationMs else null,
                specificPartsToGenerate = retryParts
            ).collect { update ->
                when (update) {
                    is GenerationUpdate.Log -> generationLogs.add(update.message)
                    is GenerationUpdate.Progress -> generationProgress = update.progress
                    is GenerationUpdate.FileInfo -> {
                        savedGeminiFileUri = update.geminiFileUri
                        savedMimeType = update.mimeType
                        savedDurationMs = update.durationMs
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
                    }
                    is GenerationUpdate.Error -> {
                        generationLogs.add("ERROR: ${update.message}")
                        finalScript += "\nError: ${update.message}"
                        errorMessageDialog = update.message
                    }
                    is GenerationUpdate.RequireRetryDecision -> {
                        showRetryDialog = update.failedParts
                        retryCountdown = 15
                    }
                    is GenerationUpdate.ApiCallResult -> {
                        localTotalApiCalls++
                        if (update.success) localSuccessApiCalls++ else localFailedApiCalls++
                    }
                    is GenerationUpdate.Complete -> {
                        generationLogs.add("Generation Complete!")
                        generationProgress = 1.0f
                        activeDecisionChannel = null
                    }
                }
            }
            
            // Save to database
            if (finalScript.isNotBlank() && !finalScript.contains("Error generating script") && !finalScript.contains("Error:")) {
                val partsJson = org.json.JSONObject()
                scriptPartsMap.forEach { (k, v) -> partsJson.put(k.toString(), v) }
                
                if (existingScript != null) {
                    val prevVersionObj = org.json.JSONObject()
                    prevVersionObj.put("timestamp", System.currentTimeMillis())
                    prevVersionObj.put("scriptText", existingScript.scriptText)
                    previousVersionsArray.put(prevVersionObj)
                }
                
                val scriptEntity = com.example.db.PrGtScript(
                    fileName = existingScript?.fileName ?: selectedMediaName,
                    fileSize = "N/A",
                    durationSec = if (savedDurationMs > 0) (savedDurationMs / 1000).toString() else "N/A",
                    scriptText = finalScript,
                    timestamp = generationStartTime,
                    language = activeLanguage,
                    modelId = activeModel,
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
                    previousVersionsJson = previousVersionsArray.toString()
                )
                // Delete older than 72 hours
                val cutoffTime = System.currentTimeMillis() - (72L * 60 * 60 * 1000)
                dao.deleteOlderThan(cutoffTime)
                if (existingScript != null) {
                    dao.deleteScriptById(existingScript.id)
                }
                val newId = dao.insertScript(scriptEntity)
                currentScriptId = newId.toInt()
                isEditingScript = false
                dao.keepLatestTen()
            }
            
            isGenerating = false
        }
    }

    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedMediaUri = uri
            selectedMediaMimeType = context.contentResolver.getType(uri)
            selectedMediaName = "Video selected"
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
            selectedMediaUri = uri
            selectedMediaMimeType = context.contentResolver.getType(uri)
            selectedMediaName = "Audio selected"
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

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
        // Top Bar
        TopAppBar(
            title = { Text(if (showHistory) "History" else "PrGt's Script", fontWeight = FontWeight.Bold) },
            actions = {
                IconButton(onClick = { showHistory = !showHistory }) {
                    Icon(if (showHistory) Icons.Filled.Close else Icons.Filled.History, contentDescription = "History")
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
                items(historyScripts) { script ->
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
                                text = script.scriptText,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 5
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = { 
                                    generatedScript = script.scriptText
                                    currentScriptId = script.id
                                    isEditingScript = false
                                    showHistory = false 
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("View Full Script")
                            }

                            val timeSinceSaved = System.currentTimeMillis() - script.timestamp
                            val timeRemainingMs = (48L * 60 * 60 * 1000) - timeSinceSaved

                            if (timeRemainingMs > 0 && script.geminiFileUri != null) {
                                val remainingHours = java.util.concurrent.TimeUnit.MILLISECONDS.toHours(timeRemainingMs)
                                val remainingMins = java.util.concurrent.TimeUnit.MILLISECONDS.toMinutes(timeRemainingMs) % 60
                                val timeStr = "${remainingHours}h ${remainingMins}m"

                                val failedParts = mutableListOf<Int>()
                                for (i in 0 until script.partsCount) {
                                    if (script.scriptText.contains("[အပိုင်း (${i + 1})") || 
                                        script.scriptText.contains("[Final Retry Error in Part ${i + 1}:") ||
                                        script.scriptText.contains("[Error parsing final output for Part ${i + 1}:") ||
                                        script.scriptText.contains("[Network Error in Final Retry Part ${i + 1}:")) {
                                        failedParts.add(i)
                                    }
                                }
                                
                                Spacer(modifier = Modifier.height(8.dp))
                                if (failedParts.isNotEmpty()) {
                                    androidx.compose.material3.OutlinedButton(
                                        onClick = { scriptToRegenerate = script },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("Regenerate All ($timeStr remaining)")
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Button(
                                        onClick = { launchGeneration(script, failedParts) },
                                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("Retry Failed Parts ($timeStr remaining)")
                                    }
                                } else {
                                    androidx.compose.material3.OutlinedButton(
                                        onClick = { scriptToRegenerate = script },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("Regenerate All ($timeStr remaining)")
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
                    onValueChange = { parts = it },
                    valueRange = 5f..50f
                )
                if (selectedMediaDurationMs > 0) {
                    val segmentDurationSecs = (selectedMediaDurationMs / parts.toInt()) / 1000
                    Text(
                        text = "တစ်ပိုင်းလျှင် ခန့်မှန်း $segmentDurationSecs စက္ကန့် (Estimated: $segmentDurationSecs seconds/part)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                } else {
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // Script Length
                DropdownSection(
                    title = "Script Length",
                    options = scriptLengths,
                    selectedOption = selectedLength,
                    onOptionSelected = { selectedLength = it }
                )

                // Narrator POV
                DropdownSection(
                    title = "Narrator POV",
                    options = narratorPOVs,
                    selectedOption = selectedPOV,
                    onOptionSelected = { selectedPOV = it }
                )

                // Script Tone
                DropdownSection(
                    title = "Script Tone",
                    options = scriptTones,
                    selectedOption = selectedTone,
                    onOptionSelected = { selectedTone = it }
                )

                // Output Language
                DropdownSection(
                    title = "Output Language",
                    options = outputLanguages,
                    selectedOption = selectedLanguage,
                    onOptionSelected = { selectedLanguage = it }
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
            
            Spacer(modifier = Modifier.height(16.dp))

            if (generatedScript.isNotEmpty() || isGenerating) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Generated Script",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Row {
                        if (!isGenerating && generatedScript.isNotEmpty()) {
                            IconButton(onClick = {
                                currentScriptId?.let { id ->
                                    historyScripts.find { it.id == id }?.let { script ->
                                        showScriptInfo = script
                                    }
                                }
                            }) {
                                Icon(Icons.Filled.Info, contentDescription = "Script Info")
                            }
                            IconButton(onClick = {
                                clipboardManager.setText(AnnotatedString(generatedScript))
                            }) {
                                Icon(Icons.Filled.ContentCopy, contentDescription = "Copy")
                            }
                            if (isEditingScript) {
                                IconButton(onClick = {
                                    isEditingScript = false
                                    coroutineScope.launch {
                                        currentScriptId?.let { id ->
                                            dao.updateScriptText(id, generatedScript)
                                        }
                                    }
                                }) {
                                    Icon(Icons.Filled.Save, contentDescription = "Save")
                                }
                            } else {
                                IconButton(onClick = {
                                    isEditingScript = true
                                }) {
                                    Icon(Icons.Filled.Edit, contentDescription = "Edit")
                                }
                            }
                        }
                    }
                }
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 2.dp
                ) {
                    if (isEditingScript) {
                        OutlinedTextField(
                            value = generatedScript,
                            onValueChange = { generatedScript = it },
                            modifier = Modifier.fillMaxWidth().padding(8.dp),
                            textStyle = MaterialTheme.typography.bodyLarge
                        )
                    } else {
                        SelectionContainer {
                            Text(
                                text = generatedScript,
                                modifier = Modifier.padding(16.dp),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
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
                .padding(16.dp)
        ) {
            ProcessingBlock(
                progress = generationProgress,
                logs = generationLogs,
                elapsedTime = elapsedTime,
                onStopClick = { showStopDialog = true }
            )
        }
    }
    } // End of root Box

    var previousVersionToShow by remember { mutableStateOf<String?>(null) }

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
                    Text("API Calls:", fontWeight = FontWeight.Bold)
                    Text("Total Calls: $totalCalls")
                    Text("Success Calls: $successCalls", color = MaterialTheme.colorScheme.primary)
                    Text("Failed Calls: $failedCalls", color = MaterialTheme.colorScheme.error)
                    
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
                        scriptToDelete?.let { dao.deleteScriptById(it.id) }
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
        var localParts by remember(scriptToRegenerate) { mutableStateOf(scriptToRegenerate!!.partsCount.toFloat()) }
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
                    SliderSection(
                        title = "Number of parts the video",
                        value = localParts,
                        onValueChange = { localParts = it },
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
                    val updatedScript = scriptToRegenerate!!.copy(
                        partsCount = localParts.toInt(),
                        targetLength = localLength,
                        pov = localPOV,
                        tone = localTone,
                        language = localLanguage
                    )
                    scriptToRegenerate = null
                    launchGeneration(updatedScript, null)
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

    if (showStopDialog) {
        AlertDialog(
            onDismissRequest = { showStopDialog = false },
            title = { Text("ရပ်တန့်မည်လား") },
            text = { Text("တွက်ချက်နေမှုကို ရပ်တန့်ချင်တာ သေချာပါသလား။") },
            confirmButton = {
                TextButton(onClick = {
                    generationJob?.cancel()
                    isGenerating = false
                    showStopDialog = false
                    generationLogs.add("User cancelled generation.")
                }) {
                    Text("သေချာသည် (Stop)")
                }
            },
            dismissButton = {
                TextButton(onClick = { showStopDialog = false }) {
                    Text("မလုပ်ပါ (Cancel)")
                }
            }
        )
    }

    if (showRetryDialog != null) {
        LaunchedEffect(showRetryDialog) {
            while (retryCountdown > 0) {
                kotlinx.coroutines.delay(1000)
                retryCountdown--
            }
            if (showRetryDialog != null) {
                activeDecisionChannel?.send(false)
                showRetryDialog = null
            }
        }

        AlertDialog(
            onDismissRequest = { /* Prevent dismiss */ },
            title = { Text("အပိုင်းတချို့ မအောင်မြင်ပါ") },
            text = { Text("အပိုင်း ${showRetryDialog!!.map { it + 1 }.joinToString(", ")} ကို ထပ်မံ ကြိုးစားသော်လည်း မအောင်မြင်ပါ။\n\n- လက်ရှိရသမျှကိုပဲ ယူမလား?\n- ထပ်ပြီး ကြိုးစားခိုင်းမလား?\n\n(အလိုအလျောက် ရွေးချယ်မှုအတွက် $retryCountdown စက္ကန့် ကျန်ပါသေးသည်)") },
            confirmButton = {
                Button(onClick = {
                    coroutineScope.launch { activeDecisionChannel?.send(true) }
                    showRetryDialog = null
                }) {
                    Text("ထပ်ကြိုးစားမည် (Retry)")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = {
                    coroutineScope.launch { activeDecisionChannel?.send(false) }
                    showRetryDialog = null
                }) {
                    Text("လက်ရှိအတိုင်းယူမည်")
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
            selectedModel = selectedModel,
            onModelSelected = { newModel ->
                selectedModel = newModel
                sharedPrefs.edit().putString("gemini_model", newModel).apply()
            },
            models = models,
            onDismiss = { showSettings = false }
        )
    }

    if (errorMessageDialog != null) {
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
                        text = "အကြံပြုချက်: API Key မှန်ကန်မှု ရှိ/မရှိ Settings တွင် စစ်ဆေးပါ သို့မဟုတ် အင်တာနက် လိုင်းကောင်းမွန်မှု ရှိ/မရှိ ပြန်လည်စစ်ဆေးပေးပါ။",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(onClick = { errorMessageDialog = null }) {
                    Text("နားလည်ပါပြီ")
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
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = (valueRange.endInclusive - valueRange.start).toInt() - 1,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary
            )
        )
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
    selectedModel: String,
    onModelSelected: (String) -> Unit,
    models: List<String>,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = onApiKeyChange,
                    label = { Text("API Key") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                DropdownSection(
                    title = "Gemini Model",
                    options = models,
                    selectedOption = selectedModel,
                    onOptionSelected = onModelSelected
                )
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
