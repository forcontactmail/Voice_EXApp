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
    fun detectsRatingCommand() {
        assertTrue(isRatingCommand("Rating"))
        assertTrue(isRatingCommand("Rating."))
        assertFalse(isRatingCommand("Raiting"))
        assertFalse(isRatingCommand("My rating"))
    }

    @Test
    fun detectsClearRatingCommand() {
        assertTrue(isClearRatingCommand("Clear rating"))
        assertTrue(isClearRatingCommand("Clear rating."))
        assertFalse(isClearRatingCommand("Clear raiting"))
        assertFalse(isClearRatingCommand("Clear this rating"))
    }

    @Test
    fun ratesUniquePhrasesByFirstAttempt() {
        val rating = FirstTryRating()
            .recordAnswer(0, isCorrect = true)
            .recordAnswer(1, isCorrect = false)
            .recordAnswer(1, isCorrect = true)
            .recordAnswer(2, isCorrect = true)

        assertEquals(3, rating.phraseCount)
        assertEquals(2, rating.correctPhraseCount)
        assertEquals(67, rating.percentage)

        assertEquals(0, FirstTryRating().percentage)
    }

    @Test
    fun detectsSkipQuestionCommands() {
        assertTrue(isSkipCommand("Skip question."))
        assertTrue(isSkipCommand("Skip"))
        assertFalse(isSkipCommand("Пропустить вопрос"))
        assertFalse(isSkipCommand("Пропусти вопрос"))
        assertFalse(isSkipCommand("Skip the question"))
    }

    @Test
    fun detectsNavigationToPreviousPhrases() {
        assertEquals(PhraseNavigationCommand.PreviousPhrases(1), parsePhraseNavigationCommand("Previous phrase"))
        assertEquals(PhraseNavigationCommand.PreviousPhrases(3), parsePhraseNavigationCommand("Go back three phrases"))
        assertEquals(PhraseNavigationCommand.PreviousPhrases(4), parsePhraseNavigationCommand("Go back 4 phrases"))
        assertEquals(PhraseNavigationCommand.PreviousPhrases(2), parsePhraseNavigationCommand("Previous two phrases"))
        assertEquals(null, parsePhraseNavigationCommand("Go back zero phrases"))
        assertEquals(null, parsePhraseNavigationCommand("Previous this phrase"))
        assertEquals(null, parsePhraseNavigationCommand("Назад на 4 фразы"))
    }

    @Test
    fun detectsNavigationToFirstPhrase() {
        assertEquals(PhraseNavigationCommand.FirstPhrase, parsePhraseNavigationCommand("First phrase"))
        assertEquals(PhraseNavigationCommand.FirstPhrase, parsePhraseNavigationCommand("Start from the beginning"))
    }
}
