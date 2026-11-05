package com.voiceex.app

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import androidx.compose.material3.Slider
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
import androidx.lifecycle.lifecycleScope
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener as VoskRecognitionListener
import org.vosk.android.SpeechService

private val Paper = Color(0xFFF4F6F1)
private val Ink = Color(0xFF1C2926)
private val Muted = Color(0xFF74817B)
private val Green = Color(0xFF15765F)
private val GreenDark = Color(0xFF105B49)
private val Coral = Color(0xFFC85C47)
private val Line = Color(0xFFDCE3DC)
private const val MAX_ANSWER_CHECK_DELAY_HALF_SECONDS = 10
private const val ANSWER_CHECK_DELAY_STEP_MILLIS = 500L
private const val DEFAULT_ANSWER_CHECK_DELAY_HALF_SECONDS = 0
private const val VOSK_SAMPLE_RATE = 16_000f
private const val VOSK_LISTENING_TIMEOUT_MILLIS = 30_000

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
    val recognitionModelStatus: String = "Preparing offline English model...",
    val voiceStatus: String = "Loading voices...",
    val answerCheckDelayHalfSeconds: Int = DEFAULT_ANSWER_CHECK_DELAY_HALF_SECONDS,
)

class MainActivity : androidx.activity.ComponentActivity() {
    private var uiState by mutableStateOf(PracticeUiState())
    private val mainHandler = Handler(Looper.getMainLooper())
    private var voskModel: Model? = null
    private var voskRecognizer: Recognizer? = null
    private var speechService: SpeechService? = null
    private var modelLoadJob: Job? = null
    private var startAfterModelLoad = false
    private var textToSpeech: TextToSpeech? = null
    private var isListening = false
    private var pendingRecognitionRestart: Runnable? = null
    private var pendingAnswerCheck: Runnable? = null
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
        loadSavedAnswerCheckDelay()
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
                        onAnswerCheckDelayChange = ::saveAnswerCheckDelay,
                    )
                }
            }
        }
    }

    private fun initializeSpeech() {
        loadVoskModel()

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

    private fun loadVoskModel() {
        if (voskModel != null || modelLoadJob?.isActive == true) return
        updateState {
            copy(
                recognitionModelStatus = "Preparing the offline English model...",
                recognitionStatus = if (isActive) "Preparing offline speech recognition..." else recognitionStatus,
            )
        }
        modelLoadJob = lifecycleScope.launch {
            try {
                val modelDirectory = withContext(Dispatchers.IO) {
                    VoskModelInstaller.install(applicationContext)
                }
                voskModel = withContext(Dispatchers.IO) { Model(modelDirectory.absolutePath) }
                updateState { copy(recognitionModelStatus = "Offline English model ready.") }
                if (startAfterModelLoad) {
                    startAfterModelLoad = false
                    requestMicrophoneAndStart()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val shouldReportStartFailure = startAfterModelLoad
                startAfterModelLoad = false
                val message = error.message ?: "download or model error"
                updateState {
                    copy(
                        recognitionModelStatus = "Could not load the offline English model: $message",
                        recognitionStatus = if (shouldReportStartFailure) {
                            "Could not prepare offline speech recognition: $message. Check your internet connection and tap Start Practice to retry."
                        } else {
                            recognitionStatus
                        },
                    )
                }
            }
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
                            firstTryRating = loadSavedFirstTryRating(phrases.size),
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
        if (voskModel == null) {
            startAfterModelLoad = true
            loadVoskModel()
            updateState { copy(recognitionStatus = "Preparing offline speech recognition...") }
            return
        }
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
                feedback = "Say the English translation. Voice commands: “Repeat phrase”, “Skip”, “Previous phrase”, “First phrase”, “Rating”, or “Clear rating”.",
                recognitionStatus = "Listen to the source phrase...",
            )
        }
        speak(phrase.prompt, "ru-RU") { startListening() }
    }

    private fun startListening() {
        if (!uiState.isActive) return
        val model = voskModel ?: run {
            loadVoskModel()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            updateState { copy(recognitionStatus = "Microphone permission is not granted.") }
            return
        }
        speechService?.let { service ->
            if (!isListening) {
                service.setPause(false)
                isListening = true
                updateState { copy(recognitionStatus = "Listening for the English translation offline. Speak now.") }
            }
            return
        }
        try {
            val recognizer = voskRecognizer ?: Recognizer(model, VOSK_SAMPLE_RATE).also { voskRecognizer = it }
            val service = SpeechService(recognizer, VOSK_SAMPLE_RATE)
            speechService = service
            isListening = true
            updateState { copy(recognitionStatus = "Listening for the English translation offline. Speak now.") }
            val started = service.startListening(object : VoskRecognitionListener {
                override fun onPartialResult(hypothesis: String) {
                    val partial = runCatching { JSONObject(hypothesis).optString("partial").trim() }
                        .getOrDefault("")
                    if (uiState.isActive && partial.isNotEmpty()) {
                        updateState { copy(recognitionStatus = "Recognizing: $partial") }
                    }
                }

                override fun onResult(hypothesis: String) = handleVoskResult(hypothesis)

                override fun onFinalResult(hypothesis: String) = Unit

                override fun onError(error: Exception) {
                    if (!uiState.isActive) return
                    isListening = false
                    disposeSpeechService()
                    runCatching { voskRecognizer?.reset() }
                    updateState {
                        copy(recognitionStatus = "Offline speech recognition failed: ${error.message ?: "audio error"}. Retrying...")
                    }
                    listenAgain(delayMillis = 1_000)
                }

                override fun onTimeout() {
                    if (!uiState.isActive) return
                    isListening = false
                    disposeSpeechService()
                    runCatching { voskRecognizer?.reset() }
                    updateState {
                        copy(recognitionStatus = "No speech detected. Check the microphone and try again; restarting listening...")
                    }
                    listenAgain(delayMillis = 1_000)
                }
            }, VOSK_LISTENING_TIMEOUT_MILLIS)
            if (!started) {
                isListening = false
                disposeSpeechService()
                updateState { copy(recognitionStatus = "Could not start offline speech recognition. Retrying...") }
                listenAgain(delayMillis = 1_000)
            }
        } catch (error: Exception) {
            isListening = false
            disposeSpeechService()
            updateState { copy(recognitionStatus = "Could not start offline speech recognition: ${error.message ?: "audio error"}. Retrying...") }
            listenAgain(delayMillis = 1_000)
        }
    }

    private fun handleVoskResult(hypothesis: String) {
        if (!uiState.isActive) return
        isListening = false
        speechService?.setPause(true)
        val transcript = runCatching { JSONObject(hypothesis).optString("text").trim() }
            .getOrDefault("")
        if (transcript.isEmpty()) {
            retryAfterUnrecognizedSpeech()
        } else {
            handleRecognition(listOf(transcript))
        }
    }

    private fun disposeSpeechService() {
        val service = speechService ?: return
        speechService = null
        runCatching { service.stop() }
        runCatching { service.shutdown() }
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
        checkAnswerAfterDelay(alternatives)
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
        speak(spokenReport, "en-US") { startListening() }
    }

    private fun clearRating() {
        updateState {
            copy(
                firstTryRating = FirstTryRating(),
                feedback = "Rating cleared. Start a new first-try rating.",
                recognitionStatus = "",
            )
        }
        speak("Rating cleared.", "en-US") { startListening() }
    }

    private fun checkAnswer(alternatives: List<String>) {
        val phrase = uiState.phrases.getOrNull(uiState.currentIndex) ?: return
        val correct = normalizeAnswer(phrase.translation)
        if (alternatives.any { normalizeAnswer(it) == correct }) {
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
            val recognized = formatRecognizedAnswer(alternatives.firstOrNull().orEmpty())
            updateState {
                copy(
                    reveal = AnswerReveal(recognized, phrase.translation),
                    feedback = "Incorrect. Listen to the recognized answer and the correct translation, then try again.",
                    recognitionStatus = "",
                    firstTryRating = firstTryRating.recordAnswer(currentIndex, isCorrect = false),
                )
            }
            speak(recognized, "en-US") {
                speak(phrase.translation, "en-US") { startListening() }
            }
        }
    }

    private fun listenAgain(delayMillis: Long = 250) {
        pendingRecognitionRestart?.let(mainHandler::removeCallbacks)
        pendingRecognitionRestart = Runnable {
            pendingRecognitionRestart = null
            if (uiState.isActive && !isListening) {
                startListening()
            }
        }.also { mainHandler.postDelayed(it, delayMillis) }
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
        pendingRecognitionRestart = null
        pendingAnswerCheck = null
        isListening = false
        disposeSpeechService()
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

    private fun loadSavedFirstTryRating(phraseCount: Int): FirstTryRating {
        val preferences = getPreferences(MODE_PRIVATE)
        val validIndexes = 0 until phraseCount
        val attempted = preferences.getStringSet(FIRST_TRY_ATTEMPTED_KEY, emptySet())
            .orEmpty()
            .mapNotNull(String::toIntOrNull)
            .filter { it in validIndexes }
            .toSet()
        val correctFirstTry = preferences.getStringSet(FIRST_TRY_CORRECT_KEY, emptySet())
            .orEmpty()
            .mapNotNull(String::toIntOrNull)
            .filter { it in validIndexes }
            .toSet()
        return FirstTryRating.fromIndexes(attempted, correctFirstTry)
    }

    private fun updateState(update: PracticeUiState.() -> PracticeUiState) {
        runOnUiThread {
            val updatedState = uiState.update()
            val preferences = getPreferences(MODE_PRIVATE)
            val editor = preferences.edit()
            var hasPersistenceChanges = false
            if (updatedState.currentIndex != uiState.currentIndex) {
                editor.putInt(CURRENT_INDEX_KEY, updatedState.currentIndex)
                hasPersistenceChanges = true
            }
            if (updatedState.firstTryRating !== uiState.firstTryRating) {
                editor
                    .putStringSet(
                        FIRST_TRY_ATTEMPTED_KEY,
                        updatedState.firstTryRating.attemptedPhraseIndexes.map(Int::toString).toSet(),
                    )
                    .putStringSet(
                        FIRST_TRY_CORRECT_KEY,
                        updatedState.firstTryRating.correctFirstTryPhraseIndexes.map(Int::toString).toSet(),
                    )
                hasPersistenceChanges = true
            }
            if (hasPersistenceChanges) editor.apply()
            uiState = updatedState
        }
    }

    override fun onDestroy() {
        modelLoadJob?.cancel()
        cancelRecognition()
        cancelSpeech()
        stopPracticeService()
        voskRecognizer?.close()
        voskRecognizer = null
        voskModel?.close()
        voskModel = null
        textToSpeech?.shutdown()
        textToSpeech = null
        super.onDestroy()
    }

    companion object {
        private const val PHRASES_KEY = "voicePractice.phrases"
        private const val CURRENT_INDEX_KEY = "voicePractice.currentIndex"
        private const val FIRST_TRY_ATTEMPTED_KEY = "voicePractice.firstTryRating.attempted"
        private const val FIRST_TRY_CORRECT_KEY = "voicePractice.firstTryRating.correct"
        private const val RUSSIAN_VOICE_KEY = "voicePractice.russianVoice"
        private const val ENGLISH_VOICE_KEY = "voicePractice.englishVoice"
        private const val LEGACY_RUSSIAN_VOICE_KEY = "russianVoice"
        private const val LEGACY_ENGLISH_VOICE_KEY = "englishVoice"
        private const val ANSWER_CHECK_DELAY_KEY = "voicePractice.answerCheckDelayHalfSeconds"
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
    onAnswerCheckDelayChange: (Int) -> Unit,
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
                    Text("English recognition · Vosk offline model", color = Ink, fontSize = 12.sp)
                    Text(
                        "The English (US) model downloads once (about 41 MB). Recognition then works without internet.",
                        color = Muted,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                    )
                    Text(state.recognitionModelStatus, color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
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
                "Phrase lists and settings are stored on this device. Speech recognition uses the downloaded Vosk English (US) model and works offline.",
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
                        Column(
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(progress, color = Muted, fontSize = 12.sp)
                            Text(
                                "${state.firstTryRating.percentage}%",
                                color = GreenDark,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
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
