package com.voiceex.app

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.Surface
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
import java.util.UUID

private val Paper = Color(0xFFF4F6F1)
private val Ink = Color(0xFF1C2926)
private val Muted = Color(0xFF74817B)
private val Green = Color(0xFF15765F)
private val GreenDark = Color(0xFF105B49)
private val Coral = Color(0xFFC85C47)
private val Line = Color(0xFFDCE3DC)
private const val DEFAULT_RECOGNITION_LANGUAGE = "en-US"
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
    val currentIndex: Int = 0,
    val isActive: Boolean = false,
    val feedback: String = "Tap Start Practice to begin.",
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
    val voiceStatus: String = "Loading voices...",
)

class MainActivity : androidx.activity.ComponentActivity() {
    private var uiState by mutableStateOf(PracticeUiState())
    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null
    private var isListening = false
    private var awaitingRecognitionResult = false
    private var recognitionAttemptId = 0L
    private var recognitionResultTimeout: Runnable? = null
    private var pendingRecognitionRestart: Runnable? = null
    private var utteranceId: String? = null
    private var speechContinuation: (() -> Unit)? = null
    private var practiceServiceStarted = false

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
        loadSavedRecognitionLanguage()
        initializeSpeech()
        loadSavedPhrases()
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
                        onStart = ::requestMicrophoneAndStart,
                        onStop = ::stopPractice,
                        onImport = ::importPhrases,
                        onSavePhrases = ::savePhrases,
                        onChooseRussianVoice = { saveVoice(it, true) },
                        onChooseEnglishVoice = { saveVoice(it, false) },
                        onChooseRecognitionLanguage = ::saveRecognitionLanguage,
                    )
                }
            }
        }
    }

    private fun initializeSpeech() {
        if (ensureSpeechRecognizer()) loadRecognitionLanguages()

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

    private fun ensureSpeechRecognizer(): Boolean {
        if (speechRecognizer != null) return true
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            updateState {
                copy(
                    isActive = false,
                    recognitionStatus = "Speech recognition is unavailable. Install or enable a speech recognition service in Android settings.",
                )
            }
            return false
        }
        return try {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            true
        } catch (error: RuntimeException) {
            updateState {
                copy(
                    isActive = false,
                    recognitionStatus = "Could not connect to the speech recognition service: ${error.message ?: "service error"}.",
                )
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

    private fun loadRecognitionLanguages() {
        val recognizer = speechRecognizer ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
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
                            val languages = (
                                recognitionSupport.onlineLanguages +
                                    recognitionSupport.installedOnDeviceLanguages +
                                    recognitionSupport.supportedOnDeviceLanguages
                                ).filterEnglishRecognitionLanguages()
                            if (languages.isEmpty()) loadRecognitionLanguagesFromBroadcast()
                            else setRecognitionLanguages(languages)
                        }

                        override fun onError(error: Int) {
                            loadRecognitionLanguagesFromBroadcast()
                        }
                    },
                )
            } catch (_: UnsupportedOperationException) {
                loadRecognitionLanguagesFromBroadcast()
            } catch (_: RuntimeException) {
                loadRecognitionLanguagesFromBroadcast()
            }
        } else {
            loadRecognitionLanguagesFromBroadcast()
        }
    }

    private fun loadRecognitionLanguagesFromBroadcast() {
        val request = Intent(RecognizerIntent.ACTION_GET_LANGUAGE_DETAILS)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val languages = getResultExtras(true)
                    ?.getStringArrayList(RecognizerIntent.EXTRA_SUPPORTED_LANGUAGES)
                    .orEmpty()
                    .filterEnglishRecognitionLanguages()
                setRecognitionLanguages(languages)
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
            setRecognitionLanguages(emptyList())
        }
    }

    private fun List<String>.filterEnglishRecognitionLanguages(): List<String> = distinct()
        .filter { Locale.forLanguageTag(it).language == "en" }
        .sorted()

    private fun setRecognitionLanguages(languages: List<String>) {
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
                recognitionLanguageStatus = if (languages.isEmpty()) {
                    "Choose an English locale. Availability depends on the Android recognition service and installed speech packs."
                } else {
                    "Choose an English locale. Support depends on the Android recognition service."
                },
            )
        }
    }

    private fun saveRecognitionLanguage(language: String) {
        if (language !in uiState.recognitionLanguages) return
        getPreferences(MODE_PRIVATE).edit().putString(RECOGNITION_LANGUAGE_KEY, language).apply()
        updateState { copy(selectedRecognitionLanguage = language) }
        if (uiState.isActive) {
            cancelRecognition()
            startListening(language)
        }
    }

    private fun loadSavedPhrases() {
        val saved = getPreferences(MODE_PRIVATE).getString(PHRASES_KEY, null)
        val content = saved ?: runCatching {
            assets.open("phrases.txt").bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrElse { error ->
            updateState { copy(loadStatus = "Could not load the sample: ${error.message}") }
            return
        }
        runCatching { PhraseParser.parse(content) }
            .onSuccess { phrases ->
                if (phrases.isEmpty()) {
                    updateState { copy(loadStatus = "The list contains no phrases.") }
                } else {
                    val savedIndex = getPreferences(MODE_PRIVATE)
                        .getInt(CURRENT_INDEX_KEY, 0)
                        .coerceIn(0, phrases.size)
                    updateState {
                        copy(
                            sourceText = content,
                            phrases = phrases,
                            currentIndex = savedIndex,
                            firstTryRating = FirstTryRating(),
                            loadStatus = if (saved == null) {
                                "Sample loaded. Phrases: ${phrases.size}."
                            } else {
                                "Saved list loaded. Phrases: ${phrases.size}."
                            },
                        )
                    }
                }
            }
            .onFailure { error ->
                updateState { copy(loadStatus = "Phrase list error: ${error.message}") }
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

    private fun requestMicrophoneAndStart() {
        if (!ensureSpeechRecognizer()) return
        if (uiState.currentIndex >= uiState.phrases.size) {
            updateState {
                copy(
                    currentIndex = 0,
                    feedback = "Starting the practice again.",
                    reveal = null,
                )
            }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            beginPrompt()
        } else {
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun beginPrompt() {
        val phrase = uiState.phrases.getOrNull(uiState.currentIndex) ?: return
        if (!startPracticeService()) return
        cancelRecognition()
        cancelSpeech()
        updateState {
            copy(
                isActive = true,
                reveal = null,
                feedback = "Say the English translation. Voice commands: “Repeat phrase”, “Skip”, “Previous phrase”, “First phrase”, “Raiting”, or “Clear raiting”.",
                recognitionStatus = "Listen to the source phrase...",
            )
        }
        speak(phrase.prompt, "ru-RU") { startListening(uiState.selectedRecognitionLanguage) }
    }

    private fun startListening(language: String) {
        if (!uiState.isActive) return
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
                    updateState { copy(recognitionStatus = "No connection to the speech recognition service. Retrying...") }
                    listenAgain(delayMillis = 2_000)
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
        checkAnswer(alternatives)
    }

    private fun skipQuestion() {
        val nextIndex = uiState.currentIndex + 1
        val isComplete = nextIndex >= uiState.phrases.size
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
            speak("Practice complete.", "en-US", ::stopPracticeService)
        } else {
            speak("Skipping question.", "en-US") { beginPrompt() }
        }
    }

    private fun retryAfterUnrecognizedSpeech() {
        updateState { copy(recognitionStatus = "Speech not recognized. Listening again...") }
        listenAgain()
    }

    private fun reportRating() {
        val rating = uiState.firstTryRating
        val feedback = if (rating.phraseCount == 0) {
            "No phrases answered yet. First-try rating: 0%."
        } else {
            "${rating.phraseCount} phrases evaluated; ${rating.correctPhraseCount} correct on the first try (${rating.percentage}%)."
        }
        val spokenReport = if (rating.phraseCount == 0) {
            "No phrases answered yet. Your first-try rating is 0 percent."
        } else {
            val phraseWord = if (rating.phraseCount == 1) "phrase" else "phrases"
            "You have answered ${rating.phraseCount} $phraseWord. Your first-try rating is ${rating.percentage} percent."
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
        val phrase = uiState.phrases.getOrNull(uiState.currentIndex) ?: return
        val correct = normalizeSpeech(phrase.translation)
        if (alternatives.any { normalizeSpeech(it) == correct }) {
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
                if (nextIndex >= uiState.phrases.size) {
                    updateState {
                        copy(
                            currentIndex = nextIndex,
                            isActive = false,
                            feedback = "You completed all phrases. Great job!",
                            recognitionStatus = "",
                        )
                    }
                    speak("Practice complete.", "en-US", ::stopPracticeService)
                } else {
                    updateState { copy(currentIndex = nextIndex, reveal = null) }
                    beginPrompt()
                }
            }
        } else {
            val recognized = alternatives.firstOrNull().orEmpty()
            updateState {
                copy(
                    reveal = AnswerReveal(recognized, phrase.translation),
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
        recognitionResultTimeout = null
        pendingRecognitionRestart = null
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
                                feedback = "Tap Start Practice to begin.",
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
                getPreferences(MODE_PRIVATE).edit().putString(PHRASES_KEY, content).apply()
                updateState {
                    copy(
                        sourceText = content,
                        phrases = phrases,
                        currentIndex = 0,
                        firstTryRating = FirstTryRating(),
                        reveal = null,
                        loadStatus = "List saved on this device. Phrases: ${phrases.size}.",
                        feedback = "Tap Start Practice to begin.",
                    )
                }
            }
            .onFailure { error ->
                updateState { copy(loadStatus = "Could not save the list: ${error.message}") }
            }
    }

    private fun updateState(update: PracticeUiState.() -> PracticeUiState) {
        runOnUiThread {
            val updatedState = uiState.update()
            if (updatedState.currentIndex != uiState.currentIndex) {
                getPreferences(MODE_PRIVATE)
                    .edit()
                    .putInt(CURRENT_INDEX_KEY, updatedState.currentIndex)
                    .apply()
            }
            uiState = updatedState
        }
    }

    override fun onDestroy() {
        cancelRecognition()
        cancelSpeech()
        stopPracticeService()
        speechRecognizer?.destroy()
        speechRecognizer = null
        textToSpeech?.shutdown()
        textToSpeech = null
        super.onDestroy()
    }

    companion object {
        private const val RECOGNITION_RESULT_TIMEOUT_MILLIS = 10_000L
        private const val PHRASES_KEY = "voicePractice.phrases"
        private const val CURRENT_INDEX_KEY = "voicePractice.currentIndex"
        private const val RUSSIAN_VOICE_KEY = "voicePractice.russianVoice"
        private const val ENGLISH_VOICE_KEY = "voicePractice.englishVoice"
        private const val LEGACY_RUSSIAN_VOICE_KEY = "russianVoice"
        private const val LEGACY_ENGLISH_VOICE_KEY = "englishVoice"
        private const val RECOGNITION_LANGUAGE_KEY = "voicePractice.recognitionLanguage"
    }
}

@Composable
private fun VoicePracticeScreen(
    state: PracticeUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onImport: () -> Unit,
    onSavePhrases: (String) -> Unit,
    onChooseRussianVoice: (String) -> Unit,
    onChooseEnglishVoice: (String) -> Unit,
    onChooseRecognitionLanguage: (String) -> Unit,
) {
    var editorOpen by remember { mutableStateOf(false) }
    var editorContent by remember(state.sourceText) { mutableStateOf(state.sourceText) }
    var menuExpanded by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
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
                if (settingsOpen) {
                    TextButton(onClick = { settingsOpen = false }) {
                        Text("← Practice", color = GreenDark)
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
                        "Add one pair per line: a Russian phrase, then | and its English translation. A tab can also be used as the separator.",
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
                    Text(state.voiceStatus, color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
                }
            }
            Text(
                "Phrase lists and voice settings are stored on this device. Speech recognition depends on the Android speech service and may require an internet connection.",
                modifier = Modifier.padding(horizontal = 2.dp, vertical = 4.dp),
                color = Muted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
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
                        Column {
                            Kicker("SPEAK")
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Translate aloud.",
                                color = Ink,
                                fontSize = 29.sp,
                                lineHeight = 34.sp,
                                fontWeight = FontWeight.Normal,
                                fontFamily = FontFamily.Serif,
                            )
                        }
                        val progress = state.phrases.size.takeIf { it > 0 }?.let {
                            "${(state.currentIndex + 1).coerceAtMost(it)} of $it"
                        } ?: "No phrases"
                        Text(progress, color = Muted, fontSize = 12.sp)
                    }
                    LinearProgressIndicator(
                        progress = if (state.phrases.isEmpty()) 0f else {
                            (state.currentIndex.toFloat() / state.phrases.size).coerceIn(0f, 1f)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        color = Green,
                        trackColor = Line,
                    )
                    Text(
                        text = state.phrases.getOrNull(state.currentIndex)?.prompt
                            ?: if (state.phrases.isNotEmpty()) "Practice complete" else "Add or import a phrase list in Settings",
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
                    Button(
                        onClick = if (state.isActive) onStop else onStart,
                        enabled = state.phrases.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = if (state.isActive) Coral else Green),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(if (state.isActive) "Stop" else "Start Practice", fontWeight = FontWeight.SemiBold)
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
