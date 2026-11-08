package com.voiceex.app

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.res.AssetManager
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteException
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.window.Dialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.util.Locale
import java.text.SimpleDateFormat
import java.util.Date
import java.util.UUID
import kotlin.math.roundToInt

private val Paper = Color(0xFFF4F6F1)
private val Ink = Color(0xFF1C2926)
private val Muted = Color(0xFF74817B)
private val Green = Color(0xFF15765F)
private val GreenDark = Color(0xFF105B49)
private val Coral = Color(0xFFC85C47)
private val Line = Color(0xFFDCE3DC)
private const val DEFAULT_RECOGNITION_LANGUAGE = "en-US"
private const val RECOGNITION_POSSIBLY_COMPLETE_SILENCE_MILLIS = 2_000
private const val RECOGNITION_COMPLETE_SILENCE_MILLIS = 3_000
private const val MAX_ANSWER_CHECK_DELAY_HALF_SECONDS = 10
private const val ANSWER_CHECK_DELAY_STEP_MILLIS = 500L
private const val DEFAULT_ANSWER_CHECK_DELAY_HALF_SECONDS = 0
private val COMMON_ENGLISH_RECOGNITION_LANGUAGES = listOf(
    "en-US",
    "en-GB",
    "en-AU",
    "en-CA",
    "en-IN",
    "en-IE",
    "en-NZ",
    "en-ZA",
    "en-SG",
)

private data class AnswerReveal(val recognized: String, val expected: String)

private data class PracticeUiState(
    val sourceText: String = "",
    val phrases: List<Phrase> = emptyList(),
    val wordQuestions: List<Phrase> = emptyList(),
    val isPracticingWords: Boolean = false,
    val currentIndex: Int = 0,
    val isActive: Boolean = false,
    val isPaused: Boolean = false,
    val feedback: String = "Choose Start to begin.",
    val recognitionStatus: String = "",
    val loadStatus: String = "Loading sample...",
    val firstTryRating: FirstTryRating = FirstTryRating(),
    val reveal: AnswerReveal? = null,
    val russianVoices: List<String> = emptyList(),
    val englishVoices: List<String> = emptyList(),
    val selectedRussianVoice: String = "",
    val selectedEnglishVoice: String = "",
    val recognitionLanguages: List<String> = listOf(DEFAULT_RECOGNITION_LANGUAGE),
    val selectedRecognitionLanguage: String = DEFAULT_RECOGNITION_LANGUAGE,
    val recognitionLanguageStatus: String = "Loading recognition languages...",
    val installedOnDeviceLanguages: Set<String> = emptySet(),
    val recognitionSupportChecked: Boolean = false,
    val recognitionSupportCheckFailed: Boolean = false,
    val offlineRecognitionEnabled: Boolean = false,
    val offlineRecognitionStatus: String = "Checking on-device recognition...",
    val voiceStatus: String = "Loading voices...",
    val answerCheckDelayHalfSeconds: Int = DEFAULT_ANSWER_CHECK_DELAY_HALF_SECONDS,
    val historySessions: List<PracticeSessionHistory> = emptyList(),
    val historyDetails: List<SessionQuestionDetail> = emptyList(),
    val historyDetailTitle: String = "",
    val historyStatus: String = "",
    val dictionaryStatus: String = "",
    val assetHierarchy: List<HierarchyNode> = emptyList(),
    val assetQuestions: Map<String, List<Phrase>> = emptyMap(),
) {
    val activeQuestions: List<Phrase>
        get() = questionsFromSelectedAssets(assetQuestions, selectedAssetPaths(assetHierarchy))
}

class MainActivity : androidx.activity.ComponentActivity() {
    private var uiState by mutableStateOf(PracticeUiState())
    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null
    private var speechRecognizerIsOnDevice: Boolean? = null
    private var textToSpeech: TextToSpeech? = null
    private var isListening = false
    private var awaitingRecognitionResult = false
    private var recognitionAttemptId = 0L
    private var recognitionResultTimeout: Runnable? = null
    private var recognitionStartTimeout: Runnable? = null
    private var recognitionSupportTimeout: Runnable? = null
    private var recognitionSupportCheckId = 0L
    private var pendingRecognitionRestart: Runnable? = null
    private var pendingAnswerCheck: Runnable? = null
    private var utteranceId: String? = null
    private var speechContinuation: (() -> Unit)? = null
    private var practiceServiceStarted = false
    private lateinit var practiceDatabase: PracticeDatabase
    private var currentSessionId: Long? = null

    private val microphonePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) beginPrompt()
        else updateState {
            copy(recognitionStatus = "Allow microphone access in Android settings to speak your translation.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        practiceDatabase = PracticeDatabase(this)
        practiceDatabase.finishOpenSessions()
        getPreferences(MODE_PRIVATE).edit()
            .remove(FIRST_TRY_ATTEMPTED_KEY)
            .remove(FIRST_TRY_CORRECT_KEY)
            .apply()
        loadSavedAnswerCheckDelay()
        loadSavedRecognitionLanguage()
        loadSavedOfflineRecognition()
        loadAssetDictionaries()
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(
                primary = Green,
                onPrimary = Color.White,
                secondary = GreenDark,
                background = Paper,
                surface = Color.White,
                onSurface = Ink,
                error = Coral,
            )) {
                Surface(modifier = Modifier.fillMaxSize(), color = Paper) {
                    VoicePracticeScreen(
                        state = uiState,
                        onHierarchyChanged = ::saveAssetHierarchySelection,
                        onStartPractice = {
                            when {
                                uiState.isPaused -> resumePractice()
                                uiState.isActive -> resetPractice()
                                else -> requestMicrophoneAndStart()
                            }
                        },
                        onPausePractice = {
                            when {
                                uiState.isPaused -> resumePractice()
                                uiState.isActive -> pausePractice()
                            }
                        },
                        onImport = ::importPhrases,
                        onSavePhrases = ::savePhrases,
                        onChooseRussianVoice = { saveVoice(it, true) },
                        onChooseEnglishVoice = { saveVoice(it, false) },
                        onChooseRecognitionLanguage = ::saveRecognitionLanguage,
                        onOfflineRecognitionChange = ::saveOfflineRecognition,
                        onAnswerCheckDelayChange = ::saveAnswerCheckDelay,
                        onOpenHistory = ::loadHistory,
                        onSaveDictionaryQuestion = ::saveDictionaryQuestion,
                        onShowHistoryDetails = ::showHistoryDetails,
                    )
                }
            }
        }
        mainHandler.post(::initializeSpeech)
    }

    private fun initializeSpeech() {
        if (ensureSpeechRecognizer(forceOnline = true)) loadRecognitionLanguages()

        textToSpeech = TextToSpeech(this) { result ->
            if (result == TextToSpeech.SUCCESS) {
                textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) = Unit
                    override fun onDone(id: String?) = runOnUiThread { finishSpeech(id) }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) = runOnUiThread {
                        updateState { copy(recognitionStatus = "Could not speak the phrase. Check the installed voices in Android settings.") }
                        finishSpeech(id)
                    }
                    override fun onError(id: String?, errorCode: Int) = onError(id)
                })
                loadVoices()
            } else {
                updateState { copy(voiceStatus = "Text-to-speech is unavailable. Install a text-to-speech service in Android settings.") }
            }
        }
    }

    private fun ensureSpeechRecognizer(forceOnline: Boolean = false): Boolean {
        val useOnDevice = uiState.offlineRecognitionEnabled && !forceOnline
        val wasActive = uiState.isActive
        if (speechRecognizer != null && speechRecognizerIsOnDevice == useOnDevice) return true
        speechRecognizer?.destroy()
        speechRecognizer = null
        speechRecognizerIsOnDevice = null
        val recognizerAvailable = if (useOnDevice) {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        } else {
            SpeechRecognizer.isRecognitionAvailable(this)
        }
        if (!recognizerAvailable) {
            updateState {
                copy(
                    isActive = false,
                    recognitionStatus = if (useOnDevice) {
                        "On-device speech recognition is unavailable on this device. Turn off Offline recognition or install an on-device recognition service."
                    } else {
                        "Speech recognition is unavailable. Install or enable a speech recognition service in Android settings."
                    },
                )
            }
            if (wasActive) {
                stopPracticeService()
                finishHistorySession()
            }
            return false
        }
        return try {
            speechRecognizer = if (useOnDevice) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
            } else {
                SpeechRecognizer.createSpeechRecognizer(this)
            }
            speechRecognizerIsOnDevice = useOnDevice
            true
        } catch (error: RuntimeException) {
            updateState {
                copy(
                    isActive = false,
                    recognitionStatus = "Could not connect to the speech recognition service: ${error.message ?: "service error"}.",
                )
            }
            if (wasActive) {
                stopPracticeService()
                finishHistorySession()
            }
            false
        }
    }

    private fun loadSavedRecognitionLanguage() {
        val language = getPreferences(MODE_PRIVATE)
            .getString(RECOGNITION_LANGUAGE_KEY, DEFAULT_RECOGNITION_LANGUAGE)
            ?: DEFAULT_RECOGNITION_LANGUAGE
        uiState = uiState.copy(
            recognitionLanguages = (COMMON_ENGLISH_RECOGNITION_LANGUAGES + language).distinct(),
            selectedRecognitionLanguage = language,
        )
    }

    private fun loadSavedOfflineRecognition() {
        uiState = uiState.copy(
            offlineRecognitionEnabled = getPreferences(MODE_PRIVATE)
                .getBoolean(OFFLINE_RECOGNITION_KEY, false),
            offlineRecognitionStatus = offlineRecognitionStatus(
                language = uiState.selectedRecognitionLanguage,
                installedLanguages = uiState.installedOnDeviceLanguages,
                supportChecked = uiState.recognitionSupportChecked,
                supportCheckFailed = uiState.recognitionSupportCheckFailed,
                sdkInt = Build.VERSION.SDK_INT,
                recognizerAvailable = isOnDeviceRecognizerAvailable(),
            ),
        )
    }

    private fun isOnDeviceRecognizerAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    private fun offlineRecognitionStatus(
        language: String,
        installedLanguages: Set<String>,
        supportChecked: Boolean,
        supportCheckFailed: Boolean,
        sdkInt: Int,
        recognizerAvailable: Boolean,
    ): String = when {
        sdkInt < Build.VERSION_CODES.TIRAMISU ->
            "Checking installed offline languages requires Android 13 or later."
        !recognizerAvailable ->
            "On-device speech recognition is unavailable on this device."
        supportCheckFailed ->
            "Could not verify installed offline languages. Check the Android speech service or turn off Offline recognition."
        !supportChecked ->
            "Checking installed on-device languages..."
        isOnDeviceLanguageAvailable(language, installedLanguages) ->
            "Offline recognition is ready for $language."
        else ->
            "No offline speech pack for $language is installed. Download it in Android language or speech settings."
    }

    private fun saveOfflineRecognition(enabled: Boolean) {
        getPreferences(MODE_PRIVATE).edit().putBoolean(OFFLINE_RECOGNITION_KEY, enabled).apply()
        if (uiState.isActive) stopPractice()
        updateState {
            copy(
                offlineRecognitionEnabled = enabled,
                installedOnDeviceLanguages = if (enabled) emptySet() else installedOnDeviceLanguages,
                recognitionSupportChecked = if (enabled) false else recognitionSupportChecked,
                recognitionSupportCheckFailed = false,
                offlineRecognitionStatus = offlineRecognitionStatus(
                    language = selectedRecognitionLanguage,
                    installedLanguages = if (enabled) emptySet() else installedOnDeviceLanguages,
                    supportChecked = if (enabled) false else recognitionSupportChecked,
                    supportCheckFailed = false,
                    sdkInt = Build.VERSION.SDK_INT,
                    recognizerAvailable = isOnDeviceRecognizerAvailable(),
                ),
            )
        }
        if (speechRecognizer == null) {
            updateState { copy(offlineRecognitionStatus = "Checking installed on-device languages...") }
        }
    }

    private fun loadSavedAnswerCheckDelay() {
        val savedSteps = getPreferences(MODE_PRIVATE)
            .getInt(ANSWER_CHECK_DELAY_KEY, DEFAULT_ANSWER_CHECK_DELAY_HALF_SECONDS)
            .coerceIn(0, MAX_ANSWER_CHECK_DELAY_HALF_SECONDS)
        uiState = uiState.copy(answerCheckDelayHalfSeconds = savedSteps)
    }

    private fun saveAnswerCheckDelay(halfSeconds: Int) {
        val boundedSteps = halfSeconds.coerceIn(0, MAX_ANSWER_CHECK_DELAY_HALF_SECONDS)
        getPreferences(MODE_PRIVATE).edit().putInt(ANSWER_CHECK_DELAY_KEY, boundedSteps).apply()
        updateState { copy(answerCheckDelayHalfSeconds = boundedSteps) }
    }

    private fun loadRecognitionLanguages() {
        val recognizer = speechRecognizer ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            recognitionSupportTimeout?.let(mainHandler::removeCallbacks)
            val checkId = ++recognitionSupportCheckId
            recognitionSupportTimeout = Runnable {
                if (checkId != recognitionSupportCheckId) return@Runnable
                recognitionSupportTimeout = null
                setRecognitionLanguages(
                    COMMON_ENGLISH_RECOGNITION_LANGUAGES,
                    emptySet(),
                    supportChecked = true,
                    supportCheckFailed = true,
                )
            }.also { mainHandler.postDelayed(it, RECOGNITION_SUPPORT_TIMEOUT_MILLIS) }
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            try {
                recognizer.checkRecognitionSupport(
                    intent,
                    ContextCompat.getMainExecutor(this),
                    object : RecognitionSupportCallback {
                        override fun onSupportResult(recognitionSupport: RecognitionSupport) {
                            if (checkId != recognitionSupportCheckId) return
                            finishRecognitionSupportCheck()
                            val onDeviceLanguages =
                                recognitionSupport.installedOnDeviceLanguages
                                    .filterEnglishRecognitionLanguages()
                            val languages = (
                                recognitionSupport.onlineLanguages +
                                    onDeviceLanguages +
                                    recognitionSupport.supportedOnDeviceLanguages
                                ).filterEnglishRecognitionLanguages()
                            if (languages.isEmpty()) {
                                loadRecognitionLanguagesFromBroadcast(supportChecked = true)
                            } else {
                                setRecognitionLanguages(languages, onDeviceLanguages.toSet(), supportChecked = true)
                            }
                        }

                        override fun onError(error: Int) {
                            if (checkId != recognitionSupportCheckId) return
                            finishRecognitionSupportCheck()
                            setRecognitionLanguages(
                                COMMON_ENGLISH_RECOGNITION_LANGUAGES,
                                emptySet(),
                                supportChecked = true,
                                supportCheckFailed = true,
                            )
                        }
                    },
                )
            } catch (_: UnsupportedOperationException) {
                finishRecognitionSupportCheck()
                setRecognitionLanguages(
                    COMMON_ENGLISH_RECOGNITION_LANGUAGES,
                    emptySet(),
                    supportChecked = true,
                    supportCheckFailed = true,
                )
            } catch (_: RuntimeException) {
                finishRecognitionSupportCheck()
                setRecognitionLanguages(
                    COMMON_ENGLISH_RECOGNITION_LANGUAGES,
                    emptySet(),
                    supportChecked = true,
                    supportCheckFailed = true,
                )
            }
        } else {
            loadRecognitionLanguagesFromBroadcast(supportChecked = true)
        }
    }

    private fun finishRecognitionSupportCheck() {
        recognitionSupportTimeout?.let(mainHandler::removeCallbacks)
        recognitionSupportTimeout = null
        recognitionSupportCheckId++
    }

    private fun loadRecognitionLanguagesFromBroadcast(
        supportChecked: Boolean = false,
        supportCheckFailed: Boolean = false,
    ) {
        val request = Intent(RecognizerIntent.ACTION_GET_LANGUAGE_DETAILS)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val languages = getResultExtras(true)
                    ?.getStringArrayList(RecognizerIntent.EXTRA_SUPPORTED_LANGUAGES)
                    .orEmpty()
                    .filterEnglishRecognitionLanguages()
                setRecognitionLanguages(languages, emptySet(), supportChecked, supportCheckFailed)
            }
        }
        try {
            sendOrderedBroadcast(
                request,
                null,
                receiver,
                mainHandler,
                Activity.RESULT_OK,
                null,
                null,
            )
        } catch (_: RuntimeException) {
            setRecognitionLanguages(emptyList(), emptySet(), supportChecked, supportCheckFailed)
        }
    }

    private fun List<String>.filterEnglishRecognitionLanguages(): List<String> = distinct()
        .filter { Locale.forLanguageTag(it).language == "en" }
        .sorted()

    private fun setRecognitionLanguages(
        languages: List<String>,
        installedOnDeviceLanguages: Set<String> = emptySet(),
        supportChecked: Boolean = false,
        supportCheckFailed: Boolean = false,
    ) {
        val savedLanguage = getPreferences(MODE_PRIVATE)
            .getString(RECOGNITION_LANGUAGE_KEY, DEFAULT_RECOGNITION_LANGUAGE)
        val availableLanguages = (
            COMMON_ENGLISH_RECOGNITION_LANGUAGES +
                languages +
                listOfNotNull(savedLanguage)
            ).distinct()
        val selectedLanguage = savedLanguage?.takeIf { it in availableLanguages }
            ?: DEFAULT_RECOGNITION_LANGUAGE.takeIf { it in availableLanguages }
            ?: availableLanguages.first()
        getPreferences(MODE_PRIVATE).edit().putString(RECOGNITION_LANGUAGE_KEY, selectedLanguage).apply()
        updateState {
            copy(
                recognitionLanguages = availableLanguages,
                selectedRecognitionLanguage = selectedLanguage,
                installedOnDeviceLanguages = installedOnDeviceLanguages,
                recognitionSupportChecked = supportChecked,
                recognitionSupportCheckFailed = supportCheckFailed,
                offlineRecognitionStatus = offlineRecognitionStatus(
                    language = selectedLanguage,
                    installedLanguages = installedOnDeviceLanguages,
                    supportChecked = supportChecked,
                    supportCheckFailed = supportCheckFailed,
                    sdkInt = Build.VERSION.SDK_INT,
                    recognizerAvailable = isOnDeviceRecognizerAvailable(),
                ),
                recognitionLanguageStatus = if (languages.isEmpty()) {
                    "Choose an English locale. Availability depends on the Android recognition service and installed speech packs."
                } else {
                    "Choose an English locale. Support depends on the Android recognition service."
                },
            )
        }
        if (uiState.isActive && uiState.offlineRecognitionEnabled) {
            offlineRecognitionBlockReason(uiState.selectedRecognitionLanguage)?.let { reason ->
                updateState { copy(recognitionStatus = reason) }
            } ?: startListening(uiState.selectedRecognitionLanguage)
        }
    }

    private fun saveRecognitionLanguage(language: String) {
        if (language !in uiState.recognitionLanguages) return
        getPreferences(MODE_PRIVATE).edit().putString(RECOGNITION_LANGUAGE_KEY, language).apply()
        updateState {
            copy(
                selectedRecognitionLanguage = language,
                offlineRecognitionStatus = offlineRecognitionStatus(
                    language = language,
                    installedLanguages = installedOnDeviceLanguages,
                    supportChecked = recognitionSupportChecked,
                    supportCheckFailed = recognitionSupportCheckFailed,
                    sdkInt = Build.VERSION.SDK_INT,
                    recognizerAvailable = isOnDeviceRecognizerAvailable(),
                ),
            )
        }
        if (uiState.isActive) {
            cancelRecognition()
            startListening(language)
        }
    }

    private fun loadAssetDictionaries() {
        runCatching {
            val storedSelection = getPreferences(MODE_PRIVATE)
                .getStringSet(SELECTED_ASSET_PATHS_KEY, null)
                ?.toSet()
            val fileTree = buildAssetHierarchyTree(assets)
            val initialSelection = storedSelection ?: allAssetFilePaths(fileTree)
            val parsedFiles = allAssetFiles(fileTree)
                .filter { isDictionaryAsset(it.name) }
                .associate { file ->
                    val content = assets.open(file.assetPath)
                        .bufferedReader(Charsets.UTF_8)
                        .use { it.readText() }
                    file.assetPath to parseAssetQuestions(file.assetPath, content)
                }
            val hierarchy = practiceDatabase.syncHierarchyTree(
                applyAssetSelection(fileTree, initialSelection),
            )
            val loadedQuestions = linkedMapOf<String, List<Phrase>>()
            allAssetFiles(hierarchy)
                .filter { isDictionaryAsset(it.name) }
                .forEach { file ->
                val hierarchyId = file.id ?: error("Hierarchy ID was not created for ${file.assetPath}.")
                val questions = practiceDatabase.replaceQuestionsForHierarchy(
                    hierarchyId,
                    parsedFiles.getValue(file.assetPath),
                )
                loadedQuestions[file.assetPath] = questions
            }
            updateState {
                copy(
                    assetHierarchy = hierarchy,
                    assetQuestions = loadedQuestions,
                    phrases = loadedQuestions
                        .filterKeys { !it.substringAfterLast('/').startsWith("word", ignoreCase = true) }
                        .values
                        .flatten(),
                    wordQuestions = loadedQuestions
                        .filterKeys { it.substringAfterLast('/').startsWith("word", ignoreCase = true) }
                        .values
                        .flatten(),
                    sourceText = PhraseParser.format(
                        loadedQuestions
                            .filterKeys { !it.substringAfterLast('/').startsWith("word", ignoreCase = true) }
                            .values
                            .flatten(),
                    ),
                    loadStatus = "Loaded ${loadedQuestions.values.sumOf { it.size }} questions from ${loadedQuestions.size} asset files.",
                )
            }
        }.onFailure { error ->
            updateState {
                copy(dictionaryStatus = "Could not load asset dictionaries: ${error.message}")
            }
        }
    }

    private fun saveAssetHierarchySelection(nodes: List<HierarchyNode>) {
        val selectedPaths = selectedAssetPaths(nodes)
        getPreferences(MODE_PRIVATE).edit()
            .putStringSet(SELECTED_ASSET_PATHS_KEY, selectedPaths)
            .apply()
        updateState { copy(assetHierarchy = nodes) }
    }

    private fun loadVoices() {
        val engine = textToSpeech ?: return
        val voices = engine.voices.orEmpty()
        val russian = voices.filter { it.locale.language == "ru" }
        val english = voices
            .filter { it.locale.language == "en" }
            .sortedWith(
                compareBy<android.speech.tts.Voice> { it.locale.country != "US" }
                    .thenBy { it.isNetworkConnectionRequired }
                    .thenByDescending { it.quality }
                    .thenBy { it.name },
            )
        val preferences = getPreferences(MODE_PRIVATE)
        val savedRussian = loadSavedVoice(preferences, RUSSIAN_VOICE_KEY, LEGACY_RUSSIAN_VOICE_KEY)
        val savedEnglish = loadSavedVoice(preferences, ENGLISH_VOICE_KEY, LEGACY_ENGLISH_VOICE_KEY)
        val russianNames = (russian.map { it.name } + listOfNotNull(savedRussian)).distinct()
        val englishNames = (english.map { it.name } + listOfNotNull(savedEnglish)).distinct()
        val selectedRussian = savedRussian ?: russian.firstOrNull()?.name.orEmpty()
        val selectedEnglish = savedEnglish ?: english.firstOrNull()?.name.orEmpty()
        updateState {
            copy(
                russianVoices = russianNames,
                englishVoices = englishNames,
                selectedRussianVoice = selectedRussian,
                selectedEnglishVoice = selectedEnglish,
                voiceStatus = if (
                    russian.isEmpty() || english.isEmpty() ||
                    (savedRussian != null && savedRussian !in russian.map { it.name }) ||
                    (savedEnglish != null && savedEnglish !in english.map { it.name })
                ) {
                    "Some saved voices are unavailable. Install the required speech pack; Android is temporarily using the default voice."
                } else {
                    "Text-to-speech voices loaded."
                },
            )
        }
    }

    private fun loadSavedVoice(
        preferences: SharedPreferences,
        key: String,
        legacyKey: String,
    ): String? {
        val saved = preferences.getString(key, null)
        val legacy = preferences.getString(legacyKey, null)
        if (legacy != null) {
            preferences.edit().apply {
                if (saved == null) putString(key, legacy)
                remove(legacyKey)
            }.apply()
        }
        return saved ?: legacy
    }

    private fun saveVoice(name: String, russian: Boolean) {
        val key = if (russian) RUSSIAN_VOICE_KEY else ENGLISH_VOICE_KEY
        val legacyKey = if (russian) LEGACY_RUSSIAN_VOICE_KEY else LEGACY_ENGLISH_VOICE_KEY
        getPreferences(MODE_PRIVATE).edit()
            .putString(key, name)
            .remove(legacyKey)
            .apply()
        updateState {
            if (russian) copy(selectedRussianVoice = name, voiceStatus = "Russian voice selection saved.")
            else copy(selectedEnglishVoice = name, voiceStatus = "English voice selection saved.")
        }
    }

    private fun requestMicrophoneAndStart() {
        val selectedAssetPaths = selectedAssetPaths(uiState.assetHierarchy)
        if (selectedAssetPaths.isEmpty()) {
            updateState { copy(recognitionStatus = "No asset nodes are selected for testing.") }
            return
        }
        val questions = uiState.activeQuestions
        if (questions.isEmpty()) {
            updateState { copy(recognitionStatus = "The selected assets contain no practice questions.") }
            return
        }
        offlineRecognitionBlockReason(uiState.selectedRecognitionLanguage)?.let { reason ->
            updateState { copy(recognitionStatus = reason) }
            return
        }
        if (!ensureSpeechRecognizer()) return
        if (uiState.isActive || uiState.isPaused) {
            resetPractice()
            return
        }
        updateState {
            copy(
                currentIndex = 0,
                isActive = false,
                isPaused = false,
                feedback = "Starting practice.",
                recognitionStatus = "",
                reveal = null,
            )
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            beginPrompt()
        } else {
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun pausePractice() {
        if (!uiState.isActive) return
        updateState {
            copy(
                isActive = false,
                isPaused = true,
                recognitionStatus = "Practice paused.",
                feedback = "Practice paused. Tap Resumption to continue.",
            )
        }
        cancelRecognition()
        cancelSpeech()
    }

    private fun resumePractice() {
        if (!uiState.isPaused) return
        updateState {
            copy(
                isActive = true,
                isPaused = false,
                recognitionStatus = "Listen to the source phrase...",
                feedback = "Say the English translation.",
            )
        }
        beginPrompt()
    }

    private fun resetPractice() {
        updateState {
            copy(
                currentIndex = 0,
                isActive = false,
                isPaused = false,
                firstTryRating = FirstTryRating(),
                reveal = null,
                feedback = "Choose Start to begin.",
                recognitionStatus = "Practice reset.",
            )
        }
        cancelRecognition()
        cancelSpeech()
        stopPracticeService()
        finishHistorySession()
    }

    private fun beginPrompt() {
        if (uiState.activeQuestions.any { it.questionId == null }) {
            runCatching { practiceDatabase.replaceActiveQuestions(uiState.activeQuestions) }
                .onSuccess { questions ->
                    uiState = if (uiState.isPracticingWords) {
                        uiState.copy(wordQuestions = questions)
                    } else {
                        uiState.copy(
                            phrases = questions,
                            sourceText = PhraseParser.format(questions),
                        )
                    }
                }
                .onFailure { error ->
                    updateState { copy(recognitionStatus = "Could not prepare questions for session history: ${error.message}") }
                    return
                }
        }
        val phrase = uiState.activeQuestions.getOrNull(uiState.currentIndex) ?: return
        if (!startPracticeService()) return
        if (!uiState.isActive && currentSessionId == null) {
            try {
                currentSessionId = practiceDatabase.startSession()
            } catch (error: SQLiteException) {
                stopPracticeService()
                updateState { copy(recognitionStatus = "Could not start session history: ${error.message}") }
                return
            }
        }
        cancelRecognition()
        cancelSpeech()
        updateState {
            copy(
                isActive = true,
                isPaused = false,
                reveal = null,
                feedback = "Say the English translation. Repeat question, skip question, previous question, first question, rating, or clear rating are available.",
                recognitionStatus = "Listen to the source phrase...",
            )
        }
        speak(phrase.prompt, "ru-RU") { startListening(uiState.selectedRecognitionLanguage) }
    }

    private fun startListening(language: String) {
        if (!uiState.isActive) return
        offlineRecognitionBlockReason(language)?.let { reason ->
            updateState { copy(recognitionStatus = reason) }
            return
        }
        if (!ensureSpeechRecognizer()) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            updateState { copy(recognitionStatus = "Microphone permission is not granted.") }
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                RECOGNITION_POSSIBLY_COMPLETE_SILENCE_MILLIS,
            )
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                RECOGNITION_COMPLETE_SILENCE_MILLIS,
            )
        }
        try {
            val attemptId = ++recognitionAttemptId
            speechRecognizer?.setRecognitionListener(createRecognitionListener(attemptId))
            isListening = true
            awaitingRecognitionResult = false
            updateState { copy(recognitionStatus = "Starting speech recognition...") }
            speechRecognizer?.startListening(intent)
            recognitionStartTimeout?.let(mainHandler::removeCallbacks)
            recognitionStartTimeout = Runnable {
                if (attemptId != recognitionAttemptId || !isListening || !uiState.isActive) return@Runnable
                recognitionAttemptId++
                recognitionStartTimeout = null
                isListening = false
                speechRecognizer?.cancel()
                if (uiState.offlineRecognitionEnabled) {
                    updateState {
                        copy(
                            recognitionStatus = "The on-device speech service did not respond. Check the installed speech pack or turn off Offline recognition.",
                        )
                    }
                } else {
                    updateState { copy(recognitionStatus = "Speech service did not respond. Restarting recognition...") }
                    recreateSpeechRecognizer()
                    listenAgain(delayMillis = 1_000)
                }
            }.also { mainHandler.postDelayed(it, RECOGNITION_START_TIMEOUT_MILLIS) }
        } catch (error: RuntimeException) {
            isListening = false
            updateState { copy(recognitionStatus = "Could not start the microphone. Restarting speech recognition...") }
            recreateSpeechRecognizer()
            listenAgain(delayMillis = 1_000)
        }
    }

    private fun createRecognitionListener(attemptId: Long) = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            if (attemptId != recognitionAttemptId) return
            recognitionStartTimeout?.let(mainHandler::removeCallbacks)
            recognitionStartTimeout = null
            isListening = true
            updateState { copy(recognitionStatus = "Listening for the English translation...") }
        }

        override fun onBeginningOfSpeech() {
            if (attemptId != recognitionAttemptId) return
            updateState { copy(recognitionStatus = "Speech detected. Recognizing the translation...") }
        }
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() {
            if (attemptId != recognitionAttemptId) return
            isListening = false
            awaitingRecognitionResult = true
            if (uiState.isActive) updateState { copy(recognitionStatus = "Processing the translation...") }
            scheduleRecognitionResultTimeout(attemptId)
        }

        override fun onError(error: Int) {
            if (!finishRecognitionAttempt(attemptId)) return
            isListening = false
            awaitingRecognitionResult = false
            if (!uiState.isActive) return
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    retryAfterUnrecognizedSpeech()
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    updateState { copy(recognitionStatus = "Allow microphone access for this app in Android settings.") }
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> {
                    if (uiState.offlineRecognitionEnabled) {
                        updateState {
                            copy(recognitionStatus = "The on-device recognizer reported a network error. No online recognition was used.")
                        }
                    } else {
                        updateState { copy(recognitionStatus = "No connection to the speech recognition service. Retrying...") }
                        listenAgain(delayMillis = 2_000)
                    }
                }
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                    updateState {
                        copy(recognitionStatus = "The service does not support ${uiState.selectedRecognitionLanguage}. Choose another recognition language or install its speech pack in Android settings.")
                    }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    updateState { copy(recognitionStatus = "Speech service is busy. Retrying...") }
                    listenAgain(delayMillis = 500)
                }
                else -> {
                    updateState { copy(recognitionStatus = "Speech recognition stopped (error $error). Restarting the microphone...") }
                    recreateSpeechRecognizer()
                    listenAgain(delayMillis = 1_000)
                }
            }
        }

        override fun onResults(results: Bundle?) {
            if (!finishRecognitionAttempt(attemptId)) return
            isListening = false
            awaitingRecognitionResult = false
            val alternatives = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                .orEmpty()
            handleRecognition(alternatives)
        }

        override fun onPartialResults(partialResults: Bundle?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun finishRecognitionAttempt(attemptId: Long): Boolean {
        if (attemptId != recognitionAttemptId) return false
        recognitionAttemptId++
        recognitionStartTimeout?.let(mainHandler::removeCallbacks)
        recognitionStartTimeout = null
        recognitionResultTimeout?.let(mainHandler::removeCallbacks)
        recognitionResultTimeout = null
        return true
    }

    private fun scheduleRecognitionResultTimeout(attemptId: Long) {
        recognitionResultTimeout?.let(mainHandler::removeCallbacks)
        recognitionResultTimeout = Runnable {
            if (attemptId != recognitionAttemptId || !awaitingRecognitionResult || !uiState.isActive) return@Runnable
            finishRecognitionAttempt(attemptId)
            awaitingRecognitionResult = false
            isListening = false
            speechRecognizer?.cancel()
            recreateSpeechRecognizer()
            updateState {
                copy(recognitionStatus = "Speech service returned no result. Restarting listening...")
            }
            listenAgain(delayMillis = 500)
        }.also { mainHandler.postDelayed(it, RECOGNITION_RESULT_TIMEOUT_MILLIS) }
    }

    private fun handleRecognition(alternatives: List<String>) {
        if (!uiState.isActive) return
        if (alternatives.isEmpty()) {
            retryAfterUnrecognizedSpeech()
            return
        }
        if (alternatives.any(::isStartPhrasesCommand) || alternatives.any(::isStartWordsCommand)) {
            requestMicrophoneAndStart()
            return
        }
        if (alternatives.any(::isRepeatCommand)) {
            beginPrompt()
            return
        }
        if (alternatives.any(::isSkipCommand)) {
            skipQuestion()
            return
        }
        if (alternatives.any(::isClearRatingCommand)) {
            clearRating()
            return
        }
        if (alternatives.any(::isRatingCommand)) {
            reportRating()
            return
        }
        val navigationCommand = alternatives.firstNotNullOfOrNull(::parsePhraseNavigationCommand)
        when (navigationCommand) {
            PhraseNavigationCommand.FirstPhrase -> {
                updateState { copy(currentIndex = 0, reveal = null) }
                beginPrompt()
                return
            }
            is PhraseNavigationCommand.PreviousPhrases -> {
                updateState {
                    copy(
                        currentIndex = (currentIndex - navigationCommand.count).coerceAtLeast(0),
                        reveal = null,
                    )
                }
                beginPrompt()
                return
            }
            null -> Unit
        }
        checkAnswerAfterDelay(alternatives)
    }

    private fun skipQuestion() {
        val phrase = uiState.activeQuestions.getOrNull(uiState.currentIndex)
        if (phrase != null && phrase.questionId != null) {
            currentSessionId?.let { sessionId ->
                recordHistoryChange { practiceDatabase.recordSkippedQuestion(sessionId, phrase.questionId) }
            }
        }
        val nextIndex = uiState.currentIndex + 1
        val isComplete = nextIndex >= uiState.activeQuestions.size
        updateState {
            copy(
                currentIndex = nextIndex,
                isActive = !isComplete,
                reveal = null,
                feedback = if (isComplete) {
                    "Question skipped. Practice complete."
                } else {
                    "Question skipped."
                },
                recognitionStatus = "",
            )
        }
        if (isComplete) {
            finishHistorySession()
            speak("Practice complete.", "en-US", ::stopPracticeService)
        } else {
            speak("Skipping question.", "en-US") { beginPrompt() }
        }
    }

    private fun offlineRecognitionBlockReason(language: String): String? {
        if (!uiState.offlineRecognitionEnabled) return null
        return when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ->
                "Offline language checking requires Android 13 or later. Turn off Offline recognition to continue."
            !isOnDeviceRecognizerAvailable() ->
                "On-device speech recognition is unavailable on this device. Turn off Offline recognition to continue."
            uiState.recognitionSupportCheckFailed ->
                "Could not verify installed offline languages. Check the Android speech service or turn off Offline recognition."
            !uiState.recognitionSupportChecked ->
                "Still checking installed offline languages. Try again in a moment."
            !isOnDeviceLanguageAvailable(language, uiState.installedOnDeviceLanguages) ->
                "No offline speech pack for $language is installed. Download it in Android language or speech settings, or turn off Offline recognition."
            else -> null
        }
    }

    private fun retryAfterUnrecognizedSpeech() {
        updateState { copy(recognitionStatus = "Speech not recognized. Listening again...") }
        listenAgain()
    }

    private fun checkAnswerAfterDelay(alternatives: List<String>) {
        val delayMillis = answerCheckDelayMillis(uiState.answerCheckDelayHalfSeconds)
        if (delayMillis == 0L) {
            checkAnswer(alternatives)
            return
        }

        val phraseIndex = uiState.currentIndex
        updateState { copy(recognitionStatus = "Waiting before checking the translation...") }
        pendingAnswerCheck = Runnable {
            pendingAnswerCheck = null
            if (uiState.isActive && uiState.currentIndex == phraseIndex) {
                checkAnswer(alternatives)
            }
        }.also { mainHandler.postDelayed(it, delayMillis) }
    }

    private fun reportRating() {
        val rating = uiState.firstTryRating
        val feedback = if (rating.phraseCount == 0) {
            "No questions answered yet. First-try rating: 0%."
        } else {
            "${rating.phraseCount} questions evaluated; ${rating.correctPhraseCount} correct on the first try (${rating.percentage}%)."
        }
        val spokenReport = if (rating.phraseCount == 0) {
            "No questions answered yet. Your first-try rating is 0 percent."
        } else {
            val questionWord = if (rating.phraseCount == 1) "question" else "questions"
            "You have answered ${rating.phraseCount} $questionWord. Your first-try rating is ${rating.percentage} percent."
        }
        updateState { copy(feedback = feedback, recognitionStatus = "") }
        speak(spokenReport, "en-US") { startListening(uiState.selectedRecognitionLanguage) }
    }

    private fun clearRating() {
        updateState {
            copy(
                firstTryRating = FirstTryRating(),
                feedback = "Rating cleared. Start a new first-try rating.",
                recognitionStatus = "",
            )
        }
        speak("Rating cleared.", "en-US") { startListening(uiState.selectedRecognitionLanguage) }
    }

    private fun checkAnswer(alternatives: List<String>) {
        val phrase = uiState.activeQuestions.getOrNull(uiState.currentIndex) ?: return
        if (alternatives.any(phrase::acceptsTranslation)) {
            phrase.questionId?.let { questionId ->
                currentSessionId?.let { sessionId ->
                    recordHistoryChange { practiceDatabase.recordCorrectAnswer(sessionId, questionId) }
                }
            }
            updateState {
                copy(
                    feedback = "Correct.",
                    recognitionStatus = "",
                    reveal = null,
                    firstTryRating = firstTryRating.recordAnswer(currentIndex, isCorrect = true),
                )
            }
            speak("Correct.", "en-US") {
                val nextIndex = uiState.currentIndex + 1
                if (nextIndex >= uiState.activeQuestions.size) {
                    updateState {
                        copy(
                            currentIndex = nextIndex,
                            isActive = false,
                            feedback = "You completed all questions. Great job!",
                            recognitionStatus = "",
                        )
                    }
                    finishHistorySession()
                    speak("Practice complete.", "en-US", ::stopPracticeService)
                } else {
                    updateState { copy(currentIndex = nextIndex, reveal = null) }
                    beginPrompt()
                }
            }
        } else {
            phrase.questionId?.let { questionId ->
                currentSessionId?.let { sessionId ->
                    recordHistoryChange { practiceDatabase.recordWrongAnswer(sessionId, questionId) }
                }
            }
            val recognized = formatRecognizedAnswer(alternatives.firstOrNull().orEmpty())
            updateState {
                copy(
                    reveal = AnswerReveal(recognized, phrase.acceptedTranslations.joinToString(" / ")),
                    feedback = "Incorrect. Listen to the recognized answer and the correct translation, then try again.",
                    recognitionStatus = "",
                    firstTryRating = firstTryRating.recordAnswer(currentIndex, isCorrect = false),
                )
            }
            speak(recognized, "en-US") {
                speak(phrase.translation, "en-US") { startListening(uiState.selectedRecognitionLanguage) }
            }
        }
    }

    private fun listenAgain(delayMillis: Long = 250) {
        pendingRecognitionRestart?.let(mainHandler::removeCallbacks)
        pendingRecognitionRestart = Runnable {
            pendingRecognitionRestart = null
            if (uiState.isActive && !isListening && !awaitingRecognitionResult) {
                startListening(uiState.selectedRecognitionLanguage)
            }
        }.also { mainHandler.postDelayed(it, delayMillis) }
    }

    private fun recreateSpeechRecognizer() {
        speechRecognizer?.destroy()
        speechRecognizer = null
        ensureSpeechRecognizer()
    }

    private fun speak(text: String, language: String, onFinished: (() -> Unit)? = null) {
        val engine = textToSpeech
        if (engine == null) {
            updateState { copy(recognitionStatus = "Text-to-speech is unavailable or not ready. Check the Android text-to-speech service.") }
            return
        }
        cancelSpeech()
        val selected = if (language.startsWith("ru")) uiState.selectedRussianVoice else uiState.selectedEnglishVoice
        val voice = engine.voices.orEmpty().firstOrNull { it.name == selected }
        if (voice != null) engine.voice = voice else engine.language = Locale.forLanguageTag(language)
        val id = UUID.randomUUID().toString()
        utteranceId = id
        speechContinuation = onFinished
        val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        if (result == TextToSpeech.ERROR) {
            updateState { copy(recognitionStatus = "Could not start text-to-speech.") }
            finishSpeech(id)
        }
    }

    private fun finishSpeech(id: String?) {
        if (id == null || id != utteranceId) return
        utteranceId = null
        val continuation = speechContinuation
        speechContinuation = null
        continuation?.invoke()
    }

    private fun cancelSpeech() {
        utteranceId = null
        speechContinuation = null
        textToSpeech?.stop()
    }

    private fun cancelRecognition() {
        mainHandler.removeCallbacksAndMessages(null)
        recognitionStartTimeout = null
        recognitionResultTimeout = null
        finishRecognitionSupportCheck()
        pendingRecognitionRestart = null
        pendingAnswerCheck = null
        recognitionAttemptId++
        if (isListening || awaitingRecognitionResult) speechRecognizer?.cancel()
        isListening = false
        awaitingRecognitionResult = false
    }

    private fun stopPractice() {
        updateState {
            copy(
                isActive = false,
                isPaused = false,
                recognitionStatus = "Practice stopped.",
                feedback = "Choose Start to begin.",
            )
        }
        cancelRecognition()
        cancelSpeech()
        stopPracticeService()
        finishHistorySession()
    }

    private fun startPracticeService(): Boolean {
        if (practiceServiceStarted) return true
        return try {
            ContextCompat.startForegroundService(
                this,
                Intent(this, VoicePracticeService::class.java),
            )
            practiceServiceStarted = true
            true
        } catch (error: RuntimeException) {
            updateState {
                copy(recognitionStatus = "Could not start background voice practice: ${error.message}")
            }
            false
        }
    }

    private fun stopPracticeService() {
        if (!practiceServiceStarted) return
        stopService(Intent(this, VoicePracticeService::class.java))
        practiceServiceStarted = false
    }

    private fun importPhrases() {
        openDocument.launch(arrayOf("text/*", "application/octet-stream"))
    }

    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: error("Could not open the selected file.")
        }.onSuccess { content ->
            runCatching { PhraseParser.parse(content) }
                .onSuccess { phrases ->
                    if (phrases.isEmpty()) {
                        updateState { copy(loadStatus = "The file contains no phrase pairs.") }
                    } else {
                        stopPractice()
                        updateState {
                            copy(
                                sourceText = content,
                                phrases = phrases,
                                currentIndex = 0,
                                firstTryRating = FirstTryRating(),
                                reveal = null,
                                feedback = "Choose Start to begin.",
                                loadStatus = "File loaded. Phrases: ${phrases.size}. Tap Save List to use it next time.",
                            )
                        }
                    }
                }
                .onFailure { error ->
                    updateState { copy(loadStatus = "Could not load the list: ${error.message}") }
                }
        }.onFailure { error ->
            updateState { copy(loadStatus = "Could not read the file: ${error.message}") }
        }
    }

    private fun savePhrases(content: String) {
        runCatching { PhraseParser.parse(content) }
            .onSuccess { phrases ->
                if (phrases.isEmpty()) {
                    updateState { copy(loadStatus = "Add at least one phrase pair.") }
                    return
                }
                stopPractice()
                runCatching { practiceDatabase.replaceActiveQuestions(phrases) }
                    .onFailure { error ->
                        updateState { copy(loadStatus = "Could not save questions to the database: ${error.message}") }
                        return@onSuccess
                    }
                val savedQuestions = practiceDatabase.loadActiveQuestions(isWord = false)
                getPreferences(MODE_PRIVATE).edit().remove(PHRASES_KEY).apply()
                updateState {
                    copy(
                        sourceText = PhraseParser.format(savedQuestions),
                        phrases = savedQuestions,
                        currentIndex = 0,
                        firstTryRating = FirstTryRating(),
                        reveal = null,
                        loadStatus = "Questions saved to the database. Phrases: ${savedQuestions.size}.",
                        feedback = "Choose Start to begin.",
                    )
                }
            }
            .onFailure { error ->
                updateState { copy(loadStatus = "Could not save the list: ${error.message}") }
            }
    }

    private fun saveDictionaryQuestion(
        assetPath: String,
        hierarchyId: Long,
        questionId: Long?,
        prompt: String,
        translation: String,
        cefr: String,
        isWord: Boolean,
    ): Boolean {
        return runCatching {
            if (questionId == null) {
                practiceDatabase.addQuestion(prompt, translation, cefr, isWord, hierarchyId)
            } else {
                check(practiceDatabase.updateQuestion(questionId, prompt, translation, cefr, hierarchyId)) {
                    "Question $questionId no longer exists."
                }
            }
            practiceDatabase.loadQuestionsForHierarchy(hierarchyId)
        }.onSuccess { questions ->
            updateState {
                copy(
                    assetQuestions = assetQuestions + (assetPath to questions),
                    dictionaryStatus = "${if (questionId == null) "Added" else "Saved"} question in $assetPath.",
                )
            }
        }.onFailure { error ->
            updateState { copy(dictionaryStatus = "Could not save question in $assetPath: ${error.message}") }
        }.isSuccess
    }

    private fun loadHistory() {
        runCatching { practiceDatabase.loadHistory() }
            .onSuccess { sessions ->
                updateState {
                    copy(
                        historySessions = sessions,
                        historyDetails = emptyList(),
                        historyDetailTitle = "",
                        historyStatus = "",
                    )
                }
            }
            .onFailure { error ->
                updateState { copy(historyStatus = "Could not load history: ${error.message}") }
            }
    }

    private fun showHistoryDetails(sessionId: Long, firstTry: Boolean) {
        runCatching { practiceDatabase.loadCorrectQuestionDetails(sessionId, firstTry) }
            .onSuccess { details ->
                updateState {
                    copy(
                        historyDetails = details,
                        historyDetailTitle = if (firstTry) {
                            "Correct on the first try"
                        } else {
                            "Correct after one or more hints"
                        },
                    )
                }
            }
            .onFailure { error ->
                updateState { copy(historyStatus = "Could not load session details: ${error.message}") }
            }
    }

    private fun recordHistoryChange(action: () -> Unit) {
        try {
            action()
        } catch (error: SQLiteException) {
            updateState { copy(recognitionStatus = "History could not be saved: ${error.message}") }
        }
    }

    private fun finishHistorySession() {
        val sessionId = currentSessionId ?: return
        currentSessionId = null
        recordHistoryChange { practiceDatabase.finishSession(sessionId) }
    }

    private fun updateState(update: PracticeUiState.() -> PracticeUiState) {
        runOnUiThread {
            val updatedState = uiState.update()
            if (updatedState.currentIndex != uiState.currentIndex) {
                getPreferences(MODE_PRIVATE).edit()
                    .putInt(CURRENT_INDEX_KEY, updatedState.currentIndex)
                    .apply()
            }
            uiState = updatedState
        }
    }

    override fun onDestroy() {
        finishHistorySession()
        cancelRecognition()
        cancelSpeech()
        stopPracticeService()
        speechRecognizer?.destroy()
        speechRecognizer = null
        textToSpeech?.shutdown()
        textToSpeech = null
        practiceDatabase.close()
        super.onDestroy()
    }

    companion object {
        private const val RECOGNITION_START_TIMEOUT_MILLIS = 10_000L
        private const val RECOGNITION_SUPPORT_TIMEOUT_MILLIS = 10_000L
        private const val RECOGNITION_RESULT_TIMEOUT_MILLIS = 10_000L
        private const val PHRASES_KEY = "voicePractice.phrases"
        private const val CURRENT_INDEX_KEY = "voicePractice.currentIndex"
        private const val FIRST_TRY_ATTEMPTED_KEY = "voicePractice.firstTryRating.attempted"
        private const val FIRST_TRY_CORRECT_KEY = "voicePractice.firstTryRating.correct"
        private const val RUSSIAN_VOICE_KEY = "voicePractice.russianVoice"
        private const val ENGLISH_VOICE_KEY = "voicePractice.englishVoice"
        private const val LEGACY_RUSSIAN_VOICE_KEY = "russianVoice"
        private const val LEGACY_ENGLISH_VOICE_KEY = "englishVoice"
        private const val RECOGNITION_LANGUAGE_KEY = "voicePractice.recognitionLanguage"
        private const val OFFLINE_RECOGNITION_KEY = "voicePractice.offlineRecognition"
        private const val ANSWER_CHECK_DELAY_KEY = "voicePractice.answerCheckDelayHalfSeconds"
        private const val SELECTED_ASSET_PATHS_KEY = "voicePractice.selectedAssetPaths"
    }
}

@Composable
private fun VoicePracticeScreen(
    state: PracticeUiState,
    onHierarchyChanged: (List<HierarchyNode>) -> Unit,
    onStartPractice: () -> Unit,
    onPausePractice: () -> Unit,
    onImport: () -> Unit,
    onSavePhrases: (String) -> Unit,
    onChooseRussianVoice: (String) -> Unit,
    onChooseEnglishVoice: (String) -> Unit,
    onChooseRecognitionLanguage: (String) -> Unit,
    onOfflineRecognitionChange: (Boolean) -> Unit,
    onAnswerCheckDelayChange: (Int) -> Unit,
    onOpenHistory: () -> Unit,
    onSaveDictionaryQuestion: (String, Long, Long?, String, String, String, Boolean) -> Boolean,
    onShowHistoryDetails: (Long, Boolean) -> Unit,
) {
    var editorOpen by remember { mutableStateOf(false) }
    var editorContent by remember(state.sourceText) { mutableStateOf(state.sourceText) }
    var menuExpanded by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var historyOpen by remember { mutableStateOf(false) }
    var historyDetailOpen by remember { mutableStateOf(false) }
    var dictionaryOpen by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (settingsOpen || historyOpen || dictionaryOpen || historyDetailOpen) {
                    TextButton(onClick = {
                        when {
                            historyDetailOpen -> historyDetailOpen = false
                            dictionaryOpen -> dictionaryOpen = false
                            else -> {
                                historyOpen = false
                                settingsOpen = false
                            }
                        }
                    }) {
                        Text("Back", color = GreenDark)
                    }
                } else {
                    Box {
                        TextButton(onClick = { menuExpanded = true }) {
                            Text("☰", color = GreenDark)
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Settings") },
                                onClick = {
                                    menuExpanded = false
                                    settingsOpen = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("History") },
                                onClick = {
                                    menuExpanded = false
                                    onOpenHistory()
                                    historyOpen = true
                                    historyDetailOpen = false
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Dictionary") },
                                onClick = {
                                    menuExpanded = false
                                    dictionaryOpen = true
                                    historyOpen = false
                                    historyDetailOpen = false
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.width(6.dp))
                Text("Voice Practice", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                text = if (state.isActive) "MICROPHONE ACTIVE" else "RU  →  EN",
                color = if (state.isActive) GreenDark else Muted,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        if (settingsOpen) {
            Text("Settings", color = Ink, fontSize = 28.sp, fontFamily = FontFamily.Serif)
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.White),
                shape = RoundedCornerShape(18.dp),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Kicker("TRAINING")
                    Text("Practice list", color = Ink, fontSize = 22.sp, fontFamily = FontFamily.Serif)
                    Text(
                        "Add one Russian phrase per line, then | and its English translation. Add more | separated English translations to accept equivalent answers. Tabs can also separate fields.",
                        color = Muted,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                    )
                    Text(
                        state.loadStatus,
                        color = if (
                            state.loadStatus.startsWith("Could not") ||
                            state.loadStatus.startsWith("Phrase list error") ||
                            state.loadStatus.startsWith("The file contains no") ||
                            state.loadStatus.startsWith("Add at least")
                        ) Coral else GreenDark,
                        fontSize = 12.sp,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = onImport, modifier = Modifier.weight(1f)) {
                            Text("Import .txt")
                        }
                        Button(
                            onClick = { editorContent = state.sourceText; editorOpen = true },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Green),
                        ) {
                            Text("Edit list")
                        }
                    }
                }
            }
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.White),
                shape = RoundedCornerShape(18.dp),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Kicker("VOICE")
                    Text("Speech settings", color = Ink, fontSize = 22.sp, fontFamily = FontFamily.Serif)
                    VoicePicker(
                        label = "Russian source phrase voice",
                        voices = state.russianVoices,
                        selected = state.selectedRussianVoice,
                        onSelect = onChooseRussianVoice,
                    )
                    VoicePicker(
                        label = "English translation voice",
                        voices = state.englishVoices,
                        selected = state.selectedEnglishVoice,
                        onSelect = onChooseEnglishVoice,
                    )
                    RecognitionLanguagePicker(
                        languages = state.recognitionLanguages,
                        selected = state.selectedRecognitionLanguage,
                        enabled = true,
                        onSelect = onChooseRecognitionLanguage,
                    )
                    Text(state.recognitionLanguageStatus, color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Offline recognition", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                "Use only an installed on-device speech model.",
                                color = Muted,
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                            )
                        }
                        Switch(
                            checked = state.offlineRecognitionEnabled,
                            onCheckedChange = onOfflineRecognitionChange,
                        )
                    }
                    Text(
                        state.offlineRecognitionStatus,
                        color = if (state.offlineRecognitionStatus.startsWith("Offline recognition is ready")) GreenDark else Coral,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                    )
                    Text(state.voiceStatus, color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
                }
            }
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.White),
                shape = RoundedCornerShape(18.dp),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Kicker("PRACTICE")
                    Text("Answer checking", color = Ink, fontSize = 22.sp, fontFamily = FontFamily.Serif)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Delay before checking", color = Ink, fontSize = 13.sp)
                        Text(
                            text = String.format(
                                Locale.US,
                                "%.1f sec",
                                state.answerCheckDelayHalfSeconds / 2f,
                            ),
                            color = GreenDark,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Slider(
                        value = state.answerCheckDelayHalfSeconds.toFloat(),
                        onValueChange = { onAnswerCheckDelayChange(it.roundToInt()) },
                        valueRange = 0f..MAX_ANSWER_CHECK_DELAY_HALF_SECONDS.toFloat(),
                        steps = MAX_ANSWER_CHECK_DELAY_HALF_SECONDS - 1,
                    )
                    Text(
                        "Choose 0–5 seconds in 0.5-second steps. The setting is saved on this device.",
                        color = Muted,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                    )
                }
            }
            Text(
                "Phrase lists and voice settings are stored on this device. Online speech recognition may require an internet connection. Offline recognition requires Android 13 or later and an installed speech pack for the selected language. Text-to-speech may also require downloaded voices.",
                modifier = Modifier.padding(horizontal = 2.dp, vertical = 4.dp),
                color = Muted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        } else if (dictionaryOpen) {
            AssetDictionaryScreen(
                assetHierarchy = state.assetHierarchy,
                assetQuestions = state.assetQuestions,
                status = state.dictionaryStatus,
                onHierarchyChanged = onHierarchyChanged,
                onSaveQuestion = onSaveDictionaryQuestion,
            )
        } else if (historyOpen) {
            if (historyDetailOpen) {
                HistoryQuestionDetails(
                    title = state.historyDetailTitle,
                    questions = state.historyDetails,
                )
            } else {
                PracticeHistoryTable(
                    sessions = state.historySessions,
                    status = state.loadStatus,
                    onShowDetails = { sessionId, firstTry ->
                        onShowHistoryDetails(sessionId, firstTry)
                        historyDetailOpen = true
                    },
                )
            }
        } else {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.White),
                shape = RoundedCornerShape(18.dp),
            ) {
                Column(
                    modifier = Modifier.padding(22.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        val questionProgress = state.activeQuestions.size.takeIf { it > 0 }?.let {
                            "${(state.currentIndex + 1).coerceAtMost(it)} / $it"
                        } ?: "0"
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                "SELECTED QUESTIONS",
                                color = Muted,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(questionProgress, color = Ink, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text("FIRST-TRY CORRECT", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Text(
                                "${state.firstTryRating.percentage}%",
                                color = GreenDark,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                    LinearProgressIndicator(
                        progress = if (state.activeQuestions.isEmpty()) 0f else {
                            (state.currentIndex.toFloat() / state.activeQuestions.size).coerceIn(0f, 1f)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        color = Green,
                        trackColor = Line,
                    )
                    Text(
                        text = state.activeQuestions.getOrNull(state.currentIndex)?.prompt
                            ?: if (state.activeQuestions.isNotEmpty()) "Practice complete"
                            else "Select questions in Dictionary",
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                        color = Ink,
                        fontSize = 26.sp,
                        lineHeight = 32.sp,
                        fontFamily = FontFamily.Serif,
                    )
                    state.reveal?.let { answer ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Paper, RoundedCornerShape(12.dp))
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Text("RECOGNIZED", color = Coral, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Text(answer.recognized.ifBlank { "Speech not recognized" }, color = Ink, fontSize = 16.sp)
                            Spacer(Modifier.height(4.dp))
                            Text("CORRECT TRANSLATION", color = Coral, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Text(answer.expected, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Text(
                        state.feedback,
                        color = when {
                            state.feedback.startsWith("Correct") || state.feedback.startsWith("You completed") -> GreenDark
                            state.reveal != null -> Coral
                            else -> Muted
                        },
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                    )
                    if (state.recognitionStatus.isNotBlank()) {
                        Text(state.recognitionStatus, color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Button(
                            onClick = onStartPractice,
                            enabled = state.activeQuestions.isNotEmpty(),
                            modifier = Modifier.weight(1f).height(50.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (state.isActive || state.isPaused) GreenDark else Green,
                            ),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text(
                                if (state.isActive || state.isPaused) "Reset" else "Start",
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        Button(
                            onClick = onPausePractice,
                            enabled = state.isActive || state.isPaused,
                            modifier = Modifier.weight(1f).height(50.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (state.isActive || state.isPaused) Green else Color(0xFFE7E9E5),
                                contentColor = if (state.isActive || state.isPaused) Color.White else Muted,
                            ),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text(
                                if (state.isPaused) "Resumption" else "Pause",
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        }
    }

    if (editorOpen) {
        AlertDialog(
            onDismissRequest = { editorOpen = false },
            title = { Text("Practice list") },
            text = {
                OutlinedTextField(
                    value = editorContent,
                    onValueChange = { editorContent = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 8,
                    maxLines = 14,
                    placeholder = { Text("Доброе утро. | Good morning.") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onSavePhrases(editorContent)
                    editorOpen = false
                }) { Text("Save list") }
            },
            dismissButton = {
                TextButton(onClick = { editorOpen = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun PracticeHistoryTable(
    sessions: List<PracticeSessionHistory>,
    status: String,
    onShowDetails: (Long, Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Practice history", color = Ink, fontSize = 28.sp, fontFamily = FontFamily.Serif)
        Text(
            "Correct answers exclude skipped and unanswered questions. Tap a first-try or retry count to inspect its questions.",
            color = Muted,
            fontSize = 13.sp,
            lineHeight = 19.sp,
        )
        if (status.isNotBlank()) Text(status, color = Coral, fontSize = 12.sp)
        if (sessions.isEmpty()) {
            Text("No completed practice sessions yet.", color = Muted, fontSize = 14.sp)
        } else {
            Column(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .background(Color.White, RoundedCornerShape(12.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    modifier = Modifier.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HistoryCell("Date", 92.dp, header = true)
                    HistoryCell("Start", 80.dp, header = true)
                    HistoryCell("End", 80.dp, header = true)
                    HistoryCell("Seconds", 76.dp, header = true)
                    HistoryCell("Correct", 72.dp, header = true)
                    HistoryCell("First try", 92.dp, header = true)
                    HistoryCell("With hints", 92.dp, header = true)
                    HistoryCell("First try %", 88.dp, header = true)
                }
                sessions.forEach { session ->
                    Row(
                        modifier = Modifier.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        HistoryCell(formatHistoryDate(session.startedAt, "yyyy-MM-dd"), 92.dp)
                        HistoryCell(formatHistoryDate(session.startedAt, "HH:mm:ss"), 80.dp)
                        HistoryCell(formatHistoryDate(session.endedAt, "HH:mm:ss"), 80.dp)
                        HistoryCell(session.durationSeconds.toString(), 76.dp)
                        HistoryCell(session.correctCount.toString(), 72.dp)
                        TextButton(
                            onClick = { onShowDetails(session.id, true) },
                            modifier = Modifier.width(92.dp),
                            contentPadding = PaddingValues(horizontal = 2.dp, vertical = 4.dp),
                        ) {
                            Text(session.firstTryCount.toString(), color = GreenDark, textAlign = TextAlign.Center)
                        }
                        TextButton(
                            onClick = { onShowDetails(session.id, false) },
                            modifier = Modifier.width(92.dp),
                            contentPadding = PaddingValues(horizontal = 2.dp, vertical = 4.dp),
                        ) {
                            Text(session.retryCount.toString(), color = GreenDark, textAlign = TextAlign.Center)
                        }
                        HistoryCell("${session.firstTryPercentage}%", 88.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryQuestionDetails(
    title: String,
    questions: List<SessionQuestionDetail>,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, color = Ink, fontSize = 26.sp, fontFamily = FontFamily.Serif)
        Text(
            "ID, question text, CEFR level and number of hints shown.",
            color = Muted,
            fontSize = 13.sp,
        )
        if (questions.isEmpty()) {
            Text("There are no questions in this category.", color = Muted, fontSize = 14.sp)
        } else {
            Column(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .background(Color.White, RoundedCornerShape(12.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    modifier = Modifier.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HistoryCell("Question ID", 84.dp, header = true)
                    HistoryCell("Question text", 250.dp, header = true)
                    HistoryCell("CEFR", 72.dp, header = true)
                    HistoryCell("Hints", 72.dp, header = true)
                }
                questions.forEach { question ->
                    Row(
                        modifier = Modifier.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        HistoryCell(question.id.toString(), 84.dp)
                        HistoryCell(question.text, 250.dp)
                        HistoryCell(question.cefr, 72.dp)
                        HistoryCell(question.tipsCount.toString(), 72.dp)
                    }
                }
            }
        }
    }
}

private fun buildAssetHierarchyTree(
    assetManager: AssetManager,
    baseDir: String = "",
    parentPath: String = "",
): List<HierarchyNode> {
    val roots = mutableListOf<HierarchyNode>()
    val entries = assetManager.list(baseDir).orEmpty()
    entries.sorted().forEach { name ->
        val path = if (baseDir.isEmpty()) name else "$baseDir/$name"
        val nested = assetManager.list(path).orEmpty()
        val assetPath = if (parentPath.isEmpty()) name else "$parentPath/$name"
        val node = if (nested.isNotEmpty()) {
            HierarchyNode(
                name = name,
                assetPath = assetPath,
                children = buildAssetHierarchyTree(assetManager, path, assetPath),
            )
        } else {
            HierarchyNode(name = name, assetPath = assetPath, isFile = true)
        }
        roots += node
    }
    return roots
}

private fun isDictionaryAsset(name: String): Boolean =
    name.endsWith(".txt", ignoreCase = true) || name.endsWith(".csv", ignoreCase = true)

private fun allAssetFiles(nodes: List<HierarchyNode>): List<HierarchyNode> =
    nodes.flatMap { node -> if (node.isFile) listOf(node) else allAssetFiles(node.children) }

private fun allAssetFilePaths(nodes: List<HierarchyNode>): Set<String> =
    allAssetFiles(nodes).mapTo(mutableSetOf()) { it.assetPath }

internal fun selectedAssetPaths(nodes: List<HierarchyNode>): Set<String> =
    allAssetFiles(nodes).filter { it.selected }.mapTo(mutableSetOf()) { it.assetPath }

private fun applyAssetSelection(nodes: List<HierarchyNode>, selectedPaths: Set<String>): List<HierarchyNode> =
    nodes.map { node ->
        val children = applyAssetSelection(node.children, selectedPaths)
        if (node.isFile) {
            node.copy(
                selected = node.assetPath in selectedPaths,
                indeterminate = false,
            )
        } else {
            val selectedCount = children.count { it.selected }
            val partialCount = children.count { it.indeterminate }
            val allSelected = children.isNotEmpty() && selectedCount == children.size
            node.copy(
                children = children,
                selected = allSelected,
                indeterminate = !allSelected && (selectedCount > 0 || partialCount > 0),
            )
        }
    }

internal fun setAssetNodeChecked(
    nodes: List<HierarchyNode>,
    assetPath: String,
    checked: Boolean,
): List<HierarchyNode> {
    fun findNode(items: List<HierarchyNode>): HierarchyNode? =
        items.firstNotNullOfOrNull { node ->
            if (node.assetPath == assetPath) node else findNode(node.children)
        }
    val changed = findNode(nodes)
    val subtreePaths = changed?.let { allAssetFilePaths(listOf(it)) }.orEmpty()
    val selectedPaths = selectedAssetPaths(nodes).toMutableSet()
    if (checked) selectedPaths += subtreePaths else selectedPaths -= subtreePaths
    return applyAssetSelection(nodes, selectedPaths)
}

@Composable
private fun HierarchyTreePicker(
    nodes: List<HierarchyNode>,
    onNodesChanged: (List<HierarchyNode>) -> Unit,
    onOpenFile: (HierarchyNode) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        nodes.forEach { node ->
            HierarchyNodeRow(
                node = node,
                depth = 0,
                rootNodes = nodes,
                onChange = onNodesChanged,
                onOpenFile = onOpenFile,
            )
        }
    }
}

@Composable
private fun HierarchyNodeRow(
    node: HierarchyNode,
    depth: Int,
    rootNodes: List<HierarchyNode>,
    onChange: (List<HierarchyNode>) -> Unit,
    onOpenFile: (HierarchyNode) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.width((depth * 16).dp))
            TriStateCheckbox(
                state = when {
                    node.indeterminate -> ToggleableState.Indeterminate
                    node.selected -> ToggleableState.On
                    else -> ToggleableState.Off
                },
                onClick = {
                    onChange(setAssetNodeChecked(rootNodes, node.assetPath, !node.selected))
                },
            )
            Text(
                node.name,
                color = if (node.isFile) GreenDark else Ink,
                fontSize = 12.sp,
                modifier = Modifier
                    .weight(1f)
                    .clickable(enabled = node.isFile) { onOpenFile(node) },
            )
        }
        if (node.children.isNotEmpty()) {
            node.children.forEach { child ->
                HierarchyNodeRow(child, depth + 1, rootNodes, onChange, onOpenFile)
            }
        }
    }
}

@Composable
private fun AssetDictionaryScreen(
    assetHierarchy: List<HierarchyNode>,
    assetQuestions: Map<String, List<Phrase>>,
    status: String,
    onHierarchyChanged: (List<HierarchyNode>) -> Unit,
    onSaveQuestion: (String, Long, Long?, String, String, String, Boolean) -> Boolean,
) {
    var openedFile by remember { mutableStateOf<HierarchyNode?>(null) }
    var questionBeingEdited by remember { mutableStateOf<Phrase?>(null) }
    var questionEditorOpen by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Dictionary", color = Ink, fontSize = 28.sp, fontFamily = FontFamily.Serif)
        Text(
            "Asset files: ${allAssetFiles(assetHierarchy).size}. Tap a file to open its dictionary.",
            color = Muted,
            fontSize = 13.sp,
            lineHeight = 19.sp,
        )
        if (status.isNotBlank()) {
            Text(
                status,
                color = if (status.startsWith("Could not")) Coral else GreenDark,
                fontSize = 12.sp,
            )
        }
        HierarchyTreePicker(
            nodes = assetHierarchy,
            onNodesChanged = onHierarchyChanged,
            onOpenFile = { openedFile = it },
        )
    }
    openedFile?.let { file ->
        val questions = assetQuestions[file.assetPath].orEmpty()
        Dialog(onDismissRequest = {
            openedFile = null
            questionEditorOpen = false
        }) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = Color.White,
            ) {
                Column(
                    modifier = Modifier
                        .heightIn(max = 680.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            file.assetPath,
                            color = Ink,
                            fontSize = 20.sp,
                            fontFamily = FontFamily.Serif,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { openedFile = null }) { Text("Close") }
                    }
                    Text(
                        "Edit questions from this asset file.",
                        color = Muted,
                        fontSize = 12.sp,
                    )
                    Button(
                        onClick = {
                            questionBeingEdited = null
                            questionEditorOpen = true
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Green),
                    ) { Text("Add") }
                    if (questions.isEmpty()) {
                        Text("This file contains no questions.", color = Muted, fontSize = 14.sp)
                    }
                    questions.forEach { question ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Paper),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    questionBeingEdited = question
                                    questionEditorOpen = true
                                },
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(question.prompt, color = Ink, fontSize = 14.sp)
                                Text(question.translation, color = Muted, fontSize = 13.sp)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(question.cefr, color = GreenDark, fontSize = 12.sp)
                                    TextButton(onClick = {
                                        questionBeingEdited = question
                                        questionEditorOpen = true
                                    }) { Text("Edit") }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (questionEditorOpen) {
            DictionaryQuestionDialog(
                question = questionBeingEdited,
                isWord = file.name.startsWith("word", ignoreCase = true),
                onDismiss = { questionEditorOpen = false },
                onSave = { prompt, translation, cefr ->
                    val hierarchyId = file.id
                    if (hierarchyId == null) return@DictionaryQuestionDialog false
                    val saved = onSaveQuestion(
                        file.assetPath,
                        hierarchyId,
                        questionBeingEdited?.questionId,
                        prompt,
                        translation,
                        cefr,
                        file.name.startsWith("word", ignoreCase = true),
                    )
                    if (saved) questionEditorOpen = false
                    saved
                },
            )
        }
    }
}

@Composable
private fun DictionaryQuestionDialog(
    question: Phrase?,
    isWord: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, String, String) -> Boolean,
) {
    var prompt by remember(question?.questionId, isWord) { mutableStateOf(question?.prompt.orEmpty()) }
    var translation by remember(question?.questionId, isWord) { mutableStateOf(question?.translation.orEmpty()) }
    var cefr by remember(question?.questionId, isWord) { mutableStateOf(question?.cefr ?: "A1") }
    var cefrManuallySelected by remember(question?.questionId, isWord) { mutableStateOf(question != null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (question == null) "Add ${if (isWord) "word" else "phrase"}" else "Edit ${if (isWord) "word" else "phrase"}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text(if (isWord) "Word" else "Phrase") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = translation,
                    onValueChange = {
                        translation = it
                        if (question == null && !isWord && !cefrManuallySelected) {
                            cefr = CefrEstimator.estimate(it)
                        }
                    },
                    label = { Text("English translation") },
                    singleLine = true,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("CEFR", color = Muted, modifier = Modifier.padding(end = 8.dp))
                    CEFRCefrPicker(
                        selected = cefr,
                        enabled = true,
                        onSelect = {
                            cefr = it
                            cefrManuallySelected = true
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = prompt.isNotBlank() && translation.isNotBlank(),
                onClick = { onSave(prompt, translation, cefr) },
            ) {
                Text(if (question == null) "Add" else "Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun CEFRCefrPicker(
    selected: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = enabled,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
        ) {
            Text(selected)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            CefrEstimator.levels.forEach { level ->
                DropdownMenuItem(
                    text = { Text(level) },
                    onClick = {
                        onSelect(level)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun HistoryCell(text: String, width: androidx.compose.ui.unit.Dp, header: Boolean = false) {
    Text(
        text = text,
        modifier = Modifier.width(width).padding(horizontal = 3.dp, vertical = 4.dp),
        color = if (header) GreenDark else Ink,
        fontSize = if (header) 11.sp else 12.sp,
        fontWeight = if (header) FontWeight.Bold else FontWeight.Normal,
        textAlign = TextAlign.Center,
    )
}

private fun formatHistoryDate(timestamp: Long, pattern: String): String =
    SimpleDateFormat(pattern, Locale.getDefault()).format(Date(timestamp))

internal fun answerCheckDelayMillis(halfSecondSteps: Int): Long =
    halfSecondSteps.coerceIn(0, MAX_ANSWER_CHECK_DELAY_HALF_SECONDS) * ANSWER_CHECK_DELAY_STEP_MILLIS

@Composable
private fun Kicker(text: String) {
    Text(text, color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
}

@Composable
private fun VoicePicker(
    label: String,
    voices: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = Ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
                enabled = voices.isNotEmpty(),
            ) {
                Text(
                    text = selected.ifEmpty { "Android default voice" },
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Start,
                    maxLines = 1,
                )
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                voices.forEach { voice ->
                    DropdownMenuItem(
                        text = { Text(voice) },
                        onClick = {
                            onSelect(voice)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun RecognitionLanguagePicker(
    languages: List<String>,
    selected: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("English recognition language", color = Ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled && languages.isNotEmpty(),
            ) {
                Text(
                    text = recognitionLanguageLabel(selected),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Start,
                    maxLines = 1,
                )
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                languages.forEach { language ->
                    DropdownMenuItem(
                        text = { Text(recognitionLanguageLabel(language)) },
                        onClick = {
                            onSelect(language)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

private fun recognitionLanguageLabel(languageTag: String): String {
    val displayName = Locale.forLanguageTag(languageTag)
        .getDisplayName(Locale.ENGLISH)
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ENGLISH) else it.toString() }
    return "$displayName · $languageTag"
}
