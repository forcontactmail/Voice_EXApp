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

        assertEquals("Line 1 is missing a | or tab separator", missingSeparator?.message)
        assertEquals("Check the source phrase and translation on line 1", missingTranslation?.message)
    }

    @Test
    fun normalizesRecognizedSpeechAndDetectsRepeatCommands() {
        assertEquals("i'd like coffee", normalizeSpeech("I’d like, coffee!"))
        assertTrue(isRepeatCommand("Repeat phrase."))
        assertTrue(isRepeatCommand("Повтори фразу"))
        assertFalse(isRepeatCommand("Repeat this phrase"))
    }

    @Test
    fun detectsSkipQuestionCommands() {
        assertTrue(isSkipCommand("Skip question."))
        assertTrue(isSkipCommand("Skip"))
        assertFalse(isSkipCommand("Пропустить вопрос"))
        assertFalse(isSkipCommand("Пропусти вопрос"))
        assertFalse(isSkipCommand("Skip the question"))
    }
}
