package com.voiceex.app

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
    val feedback: String = "Choose Start Phrases or Start Words to begin.",
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
    val questionLevelStatus: String = "",
    val dictionaryStatus: String = "",
) {
    val activeQuestions: List<Phrase>
        get() = if (isPracticingWords) wordQuestions else phrases
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
        loadSavedPhrases()
        loadSavedWords()
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
                        onStartPhrases = { requestMicrophoneAndStart(isWords = false) },
                        onStartWords = { requestMicrophoneAndStart(isWords = true) },
                        onStop = ::stopPractice,
                        onImport = ::importPhrases,
                        onSavePhrases = ::savePhrases,
                        onChooseRussianVoice = { saveVoice(it, true) },
                        onChooseEnglishVoice = { saveVoice(it, false) },
                        onChooseRecognitionLanguage = ::saveRecognitionLanguage,
                        onOfflineRecognitionChange = ::saveOfflineRecognition,
                        onAnswerCheckDelayChange = ::saveAnswerCheckDelay,
                        onOpenHistory = ::loadHistory,
                        onChooseQuestionCefr = ::saveQuestionCefr,
                        onShowHistoryDetails = ::showHistoryDetails,
                        onSaveWords = ::saveWords,
                        onImportWords = ::importWords,
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

    private fun loadSavedPhrases() {
        val saved = getPreferences(MODE_PRIVATE).getString(PHRASES_KEY, null)
        runCatching {
            var phrases = practiceDatabase.loadActiveQuestions()
            val loadedFromDatabase = phrases.isNotEmpty()
            if (!loadedFromDatabase) {
                val content = saved ?: assets.open("phrases.txt")
                    .bufferedReader(Charsets.UTF_8).use { it.readText() }
                phrases = practiceDatabase.replaceActiveQuestions(PhraseParser.parse(content))
            }
            if (phrases.isEmpty()) {
                updateState { copy(loadStatus = "The list contains no phrases.") }
                return
            }
            getPreferences(MODE_PRIVATE).edit().remove(PHRASES_KEY).apply()
            val content = PhraseParser.format(phrases)
            val savedIndex = getPreferences(MODE_PRIVATE)
                .getInt(CURRENT_INDEX_KEY, 0)
                .coerceIn(0, phrases.size)
            updateState {
                copy(
                    sourceText = content,
                    phrases = phrases,
                    currentIndex = savedIndex,
                    firstTryRating = FirstTryRating(),
                    loadStatus = if (loadedFromDatabase) {
                        "Question database loaded. Phrases: ${phrases.size}."
                    } else {
                        "Sample or saved list imported into the question database. Phrases: ${phrases.size}."
                    },
                )
            }
        }.onFailure { error ->
            updateState { copy(loadStatus = "Could not load the question database: ${error.message}") }
        }
    }

    private fun loadSavedWords() {
        runCatching {
            var words = practiceDatabase.loadActiveQuestions(isWord = true)
            if (words.isEmpty()) {
                val content = assets.open("Words.txt").bufferedReader(Charsets.UTF_8).use { it.readText() }
                words = practiceDatabase.replaceActiveQuestions(WordParser.parse(content))
            }
            updateState {
                copy(
                    wordQuestions = words,
                    dictionaryStatus = "Word dictionary loaded. Words: ${words.size}.",
                )
            }
        }.onFailure { error ->
            updateState { copy(dictionaryStatus = "Could not load Words.txt: ${error.message}") }
        }
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

    private fun requestMicrophoneAndStart(isWords: Boolean) {
        val questions = if (isWords) uiState.wordQuestions else uiState.phrases
        if (questions.isEmpty()) {
            updateState {
                copy(recognitionStatus = if (isWords) "The word dictionary is empty." else "The phrase list is empty.")
            }
            if (uiState.isActive) listenAgain()
            return
        }
        offlineRecognitionBlockReason(uiState.selectedRecognitionLanguage)?.let { reason ->
            updateState { copy(recognitionStatus = reason) }
            if (uiState.isActive) listenAgain()
            return
        }
        if (!ensureSpeechRecognizer()) return
        if (uiState.isActive) {
            cancelRecognition()
            cancelSpeech()
            finishHistorySession()
        }
        updateState {
            copy(
                isPracticingWords = isWords,
                currentIndex = 0,
                isActive = false,
                firstTryRating = if (isPracticingWords == isWords) firstTryRating else FirstTryRating(),
                feedback = if (isWords) "Starting word practice." else "Starting phrase practice.",
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
                reveal = null,
                feedback = "Say the English translation. Voice commands: “Start Phrases”, “Start Words”, “Repeat question”, “Skip question”, “Previous question”, “First question”, “Rating”, or “Clear rating”.",
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
        if (alternatives.any(::isStartPhrasesCommand)) {
            requestMicrophoneAndStart(isWords = false)
            return
        }
        if (alternatives.any(::isStartWordsCommand)) {
            requestMicrophoneAndStart(isWords = true)
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
                recognitionStatus = "Practice stopped.",
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
                                feedback = "Choose Start Phrases or Start Words to begin.",
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
                        feedback = "Choose Start Phrases or Start Words to begin.",
                    )
                }
            }
            .onFailure { error ->
                updateState { copy(loadStatus = "Could not save the list: ${error.message}") }
            }
    }

    private fun importWords() {
        openWordsDocument.launch(arrayOf("text/*", "application/octet-stream"))
    }

    private val openWordsDocument =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            runCatching {
                contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                    ?: error("Could not open the selected words file.")
            }.onSuccess(::saveWords)
                .onFailure { error ->
                    updateState { copy(dictionaryStatus = "Could not read words file: ${error.message}") }
                }
        }

    private fun saveWords(content: String) {
        runCatching { WordParser.parse(content) }
            .onSuccess { words ->
                if (words.isEmpty()) {
                    updateState { copy(dictionaryStatus = "Add at least one word and translation.") }
                    return
                }
                runCatching {
                    practiceDatabase.replaceActiveQuestions(words)
                    practiceDatabase.loadActiveQuestions(isWord = true)
                }.onSuccess { savedWords ->
                    updateState {
                        copy(
                            wordQuestions = savedWords,
                            dictionaryStatus = "Words saved to the dictionary. Words: ${savedWords.size}.",
                        )
                    }
                }.onFailure { error ->
                    updateState { copy(dictionaryStatus = "Could not save word dictionary: ${error.message}") }
                }
            }
            .onFailure { error ->
                updateState { copy(dictionaryStatus = "Could not parse word dictionary: ${error.message}") }
            }
    }

    private fun saveQuestionCefr(questionId: Long, cefr: String) {
        runCatching { practiceDatabase.updateQuestionCefr(questionId, cefr) }
            .onSuccess { saved ->
                if (saved) {
                    updateState {
                        copy(
                            phrases = phrases.map { phrase ->
                                if (phrase.questionId == questionId) phrase.copy(cefr = cefr) else phrase
                            },
                            wordQuestions = wordQuestions.map { word ->
                                if (word.questionId == questionId) word.copy(cefr = cefr) else word
                            },
                            questionLevelStatus = "CEFR level saved.",
                        )
                    }
                } else {
                    updateState { copy(questionLevelStatus = "Could not update CEFR level for question $questionId.") }
                }
            }
            .onFailure { error ->
                updateState { copy(questionLevelStatus = "Could not save CEFR level: ${error.message}") }
            }
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
    }
}

@Composable
private fun VoicePracticeScreen(
    state: PracticeUiState,
    onStartPhrases: () -> Unit,
    onStartWords: () -> Unit,
    onStop: () -> Unit,
    onImport: () -> Unit,
    onSavePhrases: (String) -> Unit,
    onChooseRussianVoice: (String) -> Unit,
    onChooseEnglishVoice: (String) -> Unit,
    onChooseRecognitionLanguage: (String) -> Unit,
    onOfflineRecognitionChange: (Boolean) -> Unit,
    onAnswerCheckDelayChange: (Int) -> Unit,
    onOpenHistory: () -> Unit,
    onChooseQuestionCefr: (Long, String) -> Unit,
    onShowHistoryDetails: (Long, Boolean) -> Unit,
    onSaveWords: (String) -> Unit,
    onImportWords: () -> Unit,
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
                                text = { Text("Dictionary / CEFR") },
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
            QuestionCefrEditor(
                phrases = state.phrases,
                words = state.wordQuestions,
                status = state.loadStatus,
                dictionaryStatus = state.dictionaryStatus,
                onChooseCefr = onChooseQuestionCefr,
                onSaveWords = onSaveWords,
                onImportWords = onImportWords,
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
                                if (state.isPracticingWords) "WORDS" else "PHRASES",
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
                            else if (state.isPracticingWords) "Add words in Dictionary / CEFR"
                            else "Add or import phrases in Settings",
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
                    if (state.isActive) {
                        Button(
                            onClick = onStop,
                            modifier = Modifier.fillMaxWidth().height(50.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Coral),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text("Stop", fontWeight = FontWeight.SemiBold)
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Button(
                                onClick = onStartPhrases,
                                enabled = state.phrases.isNotEmpty(),
                                modifier = Modifier.weight(1f).height(50.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Green),
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Text("Start Phrases", fontWeight = FontWeight.SemiBold)
                            }
                            Button(
                                onClick = onStartWords,
                                enabled = state.wordQuestions.isNotEmpty(),
                                modifier = Modifier.weight(1f).height(50.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Green),
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Text("Start Words", fontWeight = FontWeight.SemiBold)
                            }
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

@Composable
private fun QuestionCefrEditor(
    phrases: List<Phrase>,
    words: List<Phrase>,
    status: String,
    dictionaryStatus: String,
    onChooseCefr: (Long, String) -> Unit,
    onSaveWords: (String) -> Unit,
    onImportWords: () -> Unit,
) {
    var showWords by remember { mutableStateOf(false) }
    var wordsEditorOpen by remember { mutableStateOf(false) }
    var wordsEditorContent by remember(words) { mutableStateOf(WordParser.format(words)) }
    val questions = if (showWords) words else phrases
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Dictionary and CEFR", color = Ink, fontSize = 28.sp, fontFamily = FontFamily.Serif)
        Text(
            "Choose phrase questions or vocabulary words. Adjust CEFR levels, or edit and import the word dictionary.",
            color = Muted,
            fontSize = 13.sp,
            lineHeight = 19.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (showWords) {
                OutlinedButton(onClick = { showWords = false }) { Text("Phrase questions") }
                Button(
                    onClick = { showWords = true },
                    colors = ButtonDefaults.buttonColors(containerColor = Green),
                ) { Text("Words") }
            } else {
                Button(
                    onClick = { showWords = false },
                    colors = ButtonDefaults.buttonColors(containerColor = Green),
                ) { Text("Phrase questions") }
                OutlinedButton(onClick = { showWords = true }) { Text("Words") }
            }
        }
        if (showWords) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onImportWords) { Text("Import Words.txt") }
                Button(
                    onClick = {
                        wordsEditorContent = WordParser.format(words)
                        wordsEditorOpen = true
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Green),
                ) { Text("Edit words") }
            }
            if (dictionaryStatus.isNotBlank()) {
                Text(
                    dictionaryStatus,
                    color = if (dictionaryStatus.startsWith("Could not")) Coral else GreenDark,
                    fontSize = 12.sp,
                )
            }
        } else if (status.isNotBlank()) {
            Text(status, color = if (status.startsWith("Could not")) Coral else GreenDark, fontSize = 12.sp)
        }
        Text(
            "Initial levels are estimates based on phrase or word difficulty. Select a level to change it manually.",
            color = Muted,
            fontSize = 12.sp,
            lineHeight = 17.sp,
        )
        questions.forEach { phrase ->
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.White),
                shape = RoundedCornerShape(12.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = phrase.questionId?.toString() ?: "—",
                        color = Muted,
                        fontSize = 11.sp,
                        modifier = Modifier.width(32.dp),
                    )
                    Text(
                        text = "${phrase.prompt}  |  ${phrase.translation}",
                        color = Ink,
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f),
                        lineHeight = 17.sp,
                    )
                    CEFRCefrPicker(
                        selected = phrase.cefr,
                        enabled = phrase.questionId != null,
                        onSelect = { cefr -> phrase.questionId?.let { onChooseCefr(it, cefr) } },
                    )
                }
            }
        }
        if (wordsEditorOpen) {
            AlertDialog(
                onDismissRequest = { wordsEditorOpen = false },
                title = { Text("Edit word dictionary") },
                text = {
                    OutlinedTextField(
                        value = wordsEditorContent,
                        onValueChange = { wordsEditorContent = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 10,
                        maxLines = 16,
                        placeholder = { Text("дом | house | A1") },
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        onSaveWords(wordsEditorContent)
                        wordsEditorOpen = false
                    }) { Text("Save words") }
                },
                dismissButton = {
                    TextButton(onClick = { wordsEditorOpen = false }) { Text("Cancel") }
                },
            )
        }
    }
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
