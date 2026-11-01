package com.voiceex.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhraseTest {
    @Test
    fun parsesPipeAndTabSeparatedPhrasesAndBom() {
        assertEquals(
            listOf(
                Phrase("Доброе утро.", "Good morning."),
                Phrase("Как дела?", "How are you?"),
            ),
            PhraseParser.parse("\uFEFFДоброе утро. | Good morning.\nКак дела?\tHow are you?\n"),
        )
    }

    @Test
    fun reportsInvalidLinesAndMissingSides() {
        val missingSeparator = runCatching { PhraseParser.parse("Привет") }.exceptionOrNull()
        val missingTranslation = runCatching { PhraseParser.parse("Привет | ") }.exceptionOrNull()

        assertEquals("В строке 1 нет разделителя | или Tab", missingSeparator?.message)
        assertEquals("Проверьте исходную фразу и перевод в строке 1", missingTranslation?.message)
    }

    @Test
    fun normalizesRecognizedSpeechAndDetectsRepeatCommands() {
        assertEquals("i'd like coffee", normalizeSpeech("I’d like, coffee!"))
        assertTrue(isRepeatCommand("Repeat phrase."))
        assertTrue(isRepeatCommand("Повтори фразу"))
        assertFalse(isRepeatCommand("Repeat this phrase"))
    }
}
