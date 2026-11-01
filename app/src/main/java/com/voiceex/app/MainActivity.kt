package com.voiceex.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
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
import androidx.compose.foundation.shape.CircleShape
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

private data class AnswerReveal(val recognized: String, val expected: String)

private data class PracticeUiState(
    val sourceText: String = "",
    val phrases: List<Phrase> = emptyList(),
    val currentIndex: Int = 0,
    val isActive: Boolean = false,
    val feedback: String = "Нажмите «Начать», чтобы запустить голосовую практику.",
    val recognitionStatus: String = "",
    val loadStatus: String = "Загружаю пример...",
    val reveal: AnswerReveal? = null,
    val russianVoices: List<String> = emptyList(),
    val englishVoices: List<String> = emptyList(),
    val selectedRussianVoice: String = "",
    val selectedEnglishVoice: String = "",
    val recognitionWait: RecognitionWait = RecognitionWait.TWO_SECONDS,
    val voiceStatus: String = "Голоса загружаются...",
)

private enum class RecognitionWait(val millis: Int, val label: String) {
    TWO_SECONDS(2_000, "2 секунды"),
    THREE_SECONDS(3_000, "3 секунды"),
    FOUR_SECONDS(4_000, "4 секунды"),
    FIVE_SECONDS(5_000, "5 секунд"),
}

class MainActivity : androidx.activity.ComponentActivity() {
    private var uiState by mutableStateOf(PracticeUiState())
    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null
    private var isListening = false
    private var utteranceId: String? = null
    private var speechContinuation: (() -> Unit)? = null

    private val microphonePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) beginPrompt()
        else updateState {
            copy(recognitionStatus = "Разрешите доступ к микрофону в настройках Android, чтобы говорить перевод.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadRecognitionWait()
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
                        onRepeat = ::requestMicrophoneAndStart,
                        onImport = ::importPhrases,
                        onSavePhrases = ::savePhrases,
                        onChooseRussianVoice = { saveVoice("russianVoice", it, true) },
                        onChooseEnglishVoice = { saveVoice("englishVoice", it, false) },
                        onChooseRecognitionWait = ::saveRecognitionWait,
                    )
                }
            }
        }
    }

    private fun initializeSpeech() {
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).also { recognizer ->
                recognizer.setRecognitionListener(recognitionListener)
            }
        } else {
            updateState {
                copy(recognitionStatus = "На устройстве не найден сервис распознавания речи. Установите или включите службу речи в Android.")
            }
        }

        textToSpeech = TextToSpeech(this) { result ->
            if (result == TextToSpeech.SUCCESS) {
                textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) = Unit
                    override fun onDone(id: String?) = runOnUiThread { finishSpeech(id) }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) = runOnUiThread {
                        updateState { copy(recognitionStatus = "Не удалось озвучить фразу. Проверьте установленные голоса в Android.") }
                        finishSpeech(id)
                    }
                    override fun onError(id: String?, errorCode: Int) = onError(id)
                })
                loadVoices()
            } else {
                updateState { copy(voiceStatus = "Синтез речи недоступен. Установите службу Text-to-Speech в Android.") }
            }
        }
    }

    private fun loadSavedPhrases() {
        val saved = getPreferences(MODE_PRIVATE).getString(PHRASES_KEY, null)
        val content = saved ?: runCatching {
            assets.open("phrases.txt").bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrElse { error ->
            updateState { copy(loadStatus = "Не удалось загрузить пример: ${error.message}") }
            return
        }
        runCatching { PhraseParser.parse(content) }
            .onSuccess { phrases ->
                if (phrases.isEmpty()) {
                    updateState { copy(loadStatus = "В списке нет фраз.") }
                } else {
                    updateState {
                        copy(
                            sourceText = content,
                            phrases = phrases,
                            currentIndex = 0,
                            loadStatus = if (saved == null) {
                                "Загружен пример. Фраз: ${phrases.size}."
                            } else {
                                "Загружен сохранённый список. Фраз: ${phrases.size}."
                            },
                        )
                    }
                }
            }
            .onFailure { error ->
                updateState { copy(loadStatus = "Ошибка списка фраз: ${error.message}") }
            }
    }

    private fun loadVoices() {
        val engine = textToSpeech ?: return
        val voices = engine.voices.orEmpty()
        val russian = voices.filter { it.locale.language == "ru" }
        val english = voices.filter { it.locale.language == "en" }
        val preferences = getPreferences(MODE_PRIVATE)
        val savedRussian = preferences.getString(RUSSIAN_VOICE_KEY, null)
        val savedEnglish = preferences.getString(ENGLISH_VOICE_KEY, null)
        val russianNames = russian.map { it.name }
        val englishNames = english.map { it.name }
        val selectedRussian = savedRussian?.takeIf { it in russianNames } ?: russian.firstOrNull()?.name.orEmpty()
        val selectedEnglish = savedEnglish?.takeIf { it in englishNames } ?: english.firstOrNull()?.name.orEmpty()
        updateState {
            copy(
                russianVoices = russianNames,
                englishVoices = englishNames,
                selectedRussianVoice = selectedRussian,
                selectedEnglishVoice = selectedEnglish,
                voiceStatus = if (russian.isEmpty() || english.isEmpty()) {
                    "Для выбора голосов установите русский и английский речевые пакеты в настройках Android."
                } else {
                    "Голоса озвучивания загружены."
                },
            )
        }
    }

    private fun saveVoice(key: String, name: String, russian: Boolean) {
        getPreferences(MODE_PRIVATE).edit().putString(key, name).apply()
        updateState {
            if (russian) copy(selectedRussianVoice = name, voiceStatus = "Выбор русского голоса сохранён.")
            else copy(selectedEnglishVoice = name, voiceStatus = "Выбор английского голоса сохранён.")
        }
    }

    private fun loadRecognitionWait() {
        val savedMillis = getPreferences(MODE_PRIVATE).getInt(RECOGNITION_WAIT_KEY, RecognitionWait.TWO_SECONDS.millis)
        val savedWait = RecognitionWait.entries.firstOrNull { it.millis == savedMillis } ?: RecognitionWait.TWO_SECONDS
        uiState = uiState.copy(recognitionWait = savedWait)
    }

    private fun saveRecognitionWait(wait: RecognitionWait) {
        getPreferences(MODE_PRIVATE).edit().putInt(RECOGNITION_WAIT_KEY, wait.millis).apply()
        updateState { copy(recognitionWait = wait) }
    }

    private fun requestMicrophoneAndStart() {
        if (speechRecognizer == null) {
            updateState { copy(recognitionStatus = "Распознавание речи недоступно на этом устройстве.") }
            return
        }
        if (uiState.currentIndex >= uiState.phrases.size) {
            updateState {
                copy(
                    currentIndex = 0,
                    feedback = "Начинаем тренировку заново.",
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
        cancelRecognition()
        cancelSpeech()
        updateState {
            copy(
                isActive = true,
                reveal = null,
                feedback = "Скажите перевод по-английски. Чтобы повторить исходную фразу, скажите “Repeat phrase”.",
                recognitionStatus = "Слушайте исходную фразу...",
            )
        }
        speak(phrase.prompt, "ru-RU") { startListening("en-US") }
    }

    private fun startListening(language: String) {
        if (!uiState.isActive || speechRecognizer == null) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            updateState { copy(recognitionStatus = "Нет разрешения на использование микрофона.") }
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, uiState.recognitionWait.millis)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, uiState.recognitionWait.millis / 2)
        }
        try {
            isListening = true
            speechRecognizer?.startListening(intent)
            updateState { copy(recognitionStatus = "Слушаю перевод по-английски...") }
        } catch (error: RuntimeException) {
            isListening = false
            updateState { copy(recognitionStatus = "Не удалось запустить микрофон: ${error.message ?: "ошибка службы речи"}.") }
        }
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            isListening = true
        }

        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() {
            isListening = false
        }

        override fun onError(error: Int) {
            isListening = false
            if (!uiState.isActive) return
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    updateState {
                        copy(recognitionStatus = "Речь не распознана. Попробуйте ещё раз или скажите “Repeat phrase”.")
                    }
                    listenAgain()
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    updateState { copy(recognitionStatus = "Разрешите приложению доступ к микрофону в настройках Android.") }
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                    updateState { copy(recognitionStatus = "Нет соединения со службой распознавания речи.") }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                    listenAgain(delayMillis = 500)
                else ->
                    updateState { copy(recognitionStatus = "Ошибка распознавания речи (код $error). Попробуйте ещё раз.") }
            }
        }

        override fun onResults(results: Bundle?) {
            isListening = false
            val alternatives = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                .orEmpty()
            handleRecognition(alternatives)
        }

        override fun onPartialResults(partialResults: Bundle?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun handleRecognition(alternatives: List<String>) {
        if (!uiState.isActive) return
        if (alternatives.isEmpty()) {
            updateState { copy(recognitionStatus = "Речь не распознана. Попробуйте ещё раз.") }
            listenAgain()
            return
        }
        if (alternatives.any(::isRepeatCommand)) {
            beginPrompt()
            return
        }
        checkAnswer(alternatives)
    }

    private fun checkAnswer(alternatives: List<String>) {
        val phrase = uiState.phrases.getOrNull(uiState.currentIndex) ?: return
        val correct = normalizeSpeech(phrase.translation)
        if (alternatives.any { normalizeSpeech(it) == correct }) {
            updateState {
                copy(feedback = "Правильно.", recognitionStatus = "", reveal = null)
            }
            speak("Правильно.", "ru-RU") {
                val nextIndex = uiState.currentIndex + 1
                if (nextIndex >= uiState.phrases.size) {
                    updateState {
                        copy(
                            currentIndex = nextIndex,
                            isActive = false,
                            feedback = "Вы прошли все фразы. Отличная работа!",
                            recognitionStatus = "",
                        )
                    }
                    speak("Тренировка завершена.", "ru-RU")
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
                    feedback = "Ошибка. Послушайте распознанный ответ и правильный перевод, затем повторите правильный перевод.",
                    recognitionStatus = "",
                )
            }
            speak(recognized, "en-US") {
                speak(phrase.translation, "en-US") { startListening("en-US") }
            }
        }
    }

    private fun listenAgain(delayMillis: Long = 250) {
        mainHandler.postDelayed({ startListening("en-US") }, delayMillis)
    }

    private fun speak(text: String, language: String, onFinished: (() -> Unit)? = null) {
        val engine = textToSpeech
        if (engine == null) {
            updateState { copy(recognitionStatus = "Синтез речи недоступен или ещё не готов. Проверьте службу Text-to-Speech Android.") }
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
            updateState { copy(recognitionStatus = "Не удалось запустить синтез речи.") }
            finishSpeech(id)
        }
    }

    private fun finishSpeech(id: String?) {
        if (id == null || id != utteranceId) return
        utteranceId = null
        val continuation = speechContinuation
        speechContinuation = null
        if (uiState.isActive) continuation?.invoke()
    }

    private fun cancelSpeech() {
        utteranceId = null
        speechContinuation = null
        textToSpeech?.stop()
    }

    private fun cancelRecognition() {
        mainHandler.removeCallbacksAndMessages(null)
        if (isListening) speechRecognizer?.cancel()
        isListening = false
    }

    private fun stopPractice() {
        updateState {
            copy(
                isActive = false,
                recognitionStatus = "Практика остановлена.",
            )
        }
        cancelRecognition()
        cancelSpeech()
    }

    private fun importPhrases() {
        openDocument.launch(arrayOf("text/*", "application/octet-stream"))
    }

    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: error("Не удалось открыть выбранный файл.")
        }.onSuccess { content ->
            runCatching { PhraseParser.parse(content) }
                .onSuccess { phrases ->
                    if (phrases.isEmpty()) {
                        updateState { copy(loadStatus = "В файле нет ни одной пары фраз.") }
                    } else {
                        stopPractice()
                        updateState {
                            copy(
                                sourceText = content,
                                phrases = phrases,
                                currentIndex = 0,
                                reveal = null,
                                feedback = "Нажмите «Начать», чтобы запустить голосовую практику.",
                                loadStatus = "Файл загружен. Фраз: ${phrases.size}. Нажмите «Сохранить список», чтобы использовать его при следующем запуске.",
                            )
                        }
                    }
                }
                .onFailure { error ->
                    updateState { copy(loadStatus = "Не удалось загрузить список: ${error.message}") }
                }
        }.onFailure { error ->
            updateState { copy(loadStatus = "Не удалось прочитать файл: ${error.message}") }
        }
    }

    private fun savePhrases(content: String) {
        runCatching { PhraseParser.parse(content) }
            .onSuccess { phrases ->
                if (phrases.isEmpty()) {
                    updateState { copy(loadStatus = "Добавьте хотя бы одну пару фраз.") }
                    return
                }
                stopPractice()
                getPreferences(MODE_PRIVATE).edit().putString(PHRASES_KEY, content).apply()
                updateState {
                    copy(
                        sourceText = content,
                        phrases = phrases,
                        currentIndex = 0,
                        reveal = null,
                        loadStatus = "Список сохранён на устройстве. Фраз: ${phrases.size}.",
                        feedback = "Нажмите «Начать», чтобы запустить голосовую практику.",
                    )
                }
            }
            .onFailure { error ->
                updateState { copy(loadStatus = "Не удалось сохранить список: ${error.message}") }
            }
    }

    private fun updateState(update: PracticeUiState.() -> PracticeUiState) {
        runOnUiThread { uiState = uiState.update() }
    }

    override fun onDestroy() {
        cancelRecognition()
        cancelSpeech()
        speechRecognizer?.destroy()
        speechRecognizer = null
        textToSpeech?.shutdown()
        textToSpeech = null
        super.onDestroy()
    }

    companion object {
        private const val PHRASES_KEY = "voicePractice.phrases"
        private const val RUSSIAN_VOICE_KEY = "voicePractice.russianVoice"
        private const val ENGLISH_VOICE_KEY = "voicePractice.englishVoice"
        private const val RECOGNITION_WAIT_KEY = "voicePractice.recognitionWait"
    }
}

@Composable
private fun VoicePracticeScreen(
    state: PracticeUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRepeat: () -> Unit,
    onImport: () -> Unit,
    onSavePhrases: (String) -> Unit,
    onChooseRussianVoice: (String) -> Unit,
    onChooseEnglishVoice: (String) -> Unit,
    onChooseRecognitionWait: (RecognitionWait) -> Unit,
) {
    var editorOpen by remember { mutableStateOf(false) }
    var editorContent by remember(state.sourceText) { mutableStateOf(state.sourceText) }
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
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .background(Green, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("V", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.width(10.dp))
                Text("voice practice", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                text = if (state.isActive) "МИКРОФОН АКТИВЕН" else "RU  →  EN",
                color = if (state.isActive) GreenDark else Muted,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
            )
        }

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
                        Kicker("01  ·  ГОВОРИТЕ")
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Переведите вслух.",
                            color = Ink,
                            fontSize = 29.sp,
                            lineHeight = 34.sp,
                            fontWeight = FontWeight.Normal,
                            fontFamily = FontFamily.Serif,
                        )
                    }
                    val progress = state.phrases.size.takeIf { it > 0 }?.let {
                        "${(state.currentIndex + 1).coerceAtMost(it)} из $it"
                    } ?: "Нет фраз"
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
                        ?: if (state.phrases.isNotEmpty()) "Тренировка завершена" else "Загрузите или добавьте список фраз",
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                    color = Ink,
                    fontSize = 35.sp,
                    lineHeight = 42.sp,
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
                        Text("РАСПОЗНАНО", color = Coral, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Text(answer.recognized.ifBlank { "Речь не распознана" }, color = Ink, fontSize = 16.sp)
                        Spacer(Modifier.height(4.dp))
                        Text("ПРАВИЛЬНЫЙ ПЕРЕВОД", color = Coral, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Text(answer.expected, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                Text(
                    state.feedback,
                    color = when {
                        state.feedback.startsWith("Правильно") || state.feedback.startsWith("Вы прошли") -> GreenDark
                        state.reveal != null -> Coral
                        else -> Muted
                    },
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                )
                if (state.recognitionStatus.isNotBlank()) {
                    Text(state.recognitionStatus, color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = if (state.isActive) onStop else onStart,
                        enabled = state.phrases.isNotEmpty(),
                        modifier = Modifier.weight(1f).height(50.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = if (state.isActive) Coral else Green),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(if (state.isActive) "Остановить" else "Начать практику", fontWeight = FontWeight.SemiBold)
                    }
                    OutlinedButton(
                        onClick = onRepeat,
                        enabled = state.phrases.getOrNull(state.currentIndex) != null,
                        modifier = Modifier.height(50.dp),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text("Повторить", color = GreenDark)
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
                Kicker("02  ·  ВАШИ ФРАЗЫ")
                Text("Список для тренировки", color = Ink, fontSize = 22.sp, fontFamily = FontFamily.Serif)
                Text(
                    "По одной паре на строку: фраза на русском, затем | и перевод на английском. Поддерживается разделитель Tab.",
                    color = Muted,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                )
                Text(state.loadStatus, color = if (state.loadStatus.startsWith("Не удалось") || state.loadStatus.startsWith("Ошибка")) Coral else GreenDark, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = onImport, modifier = Modifier.weight(1f)) {
                        Text("Загрузить .txt")
                    }
                    Button(
                        onClick = { editorContent = state.sourceText; editorOpen = true },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Green),
                    ) {
                        Text("Редактировать")
                    }
                }
                VoicePicker(
                    label = "Русская фраза",
                    voices = state.russianVoices,
                    selected = state.selectedRussianVoice,
                    onSelect = onChooseRussianVoice,
                )
                VoicePicker(
                    label = "Английский перевод",
                    voices = state.englishVoices,
                    selected = state.selectedEnglishVoice,
                    onSelect = onChooseEnglishVoice,
                )
                RecognitionWaitPicker(
                    selected = state.recognitionWait,
                    onSelect = onChooseRecognitionWait,
                )
                Text(
                    "Если служба речи завершает ответ слишком рано, увеличьте время ожидания. Настройка применяется при следующем запуске распознавания.",
                    color = Muted,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                )
                Text(state.voiceStatus, color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
            }
        }

        Text(
            "Фразы и настройки голосов хранятся только на этом устройстве. Распознавание зависит от установленной в Android службы речи и может требовать интернет.",
            modifier = Modifier.padding(horizontal = 2.dp, vertical = 4.dp),
            color = Muted,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )
    }

    if (editorOpen) {
        AlertDialog(
            onDismissRequest = { editorOpen = false },
            title = { Text("Список фраз") },
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
                }) { Text("Сохранить список") }
            },
            dismissButton = {
                TextButton(onClick = { editorOpen = false }) { Text("Отмена") }
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
                    text = selected.ifEmpty { "Голос Android по умолчанию" },
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
private fun RecognitionWaitPicker(
    selected: RecognitionWait,
    onSelect: (RecognitionWait) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Ожидание перевода", color = Ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = selected.label,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Start,
                )
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                RecognitionWait.entries.forEach { wait ->
                    DropdownMenuItem(
                        text = { Text(wait.label) },
                        onClick = {
                            onSelect(wait)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}
