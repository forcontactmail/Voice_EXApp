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
    fun parsesMultipleAcceptedTranslations() {
        val phrase = PhraseParser.parse("Source phrase | First answer | Second answer").single()

        assertEquals("First answer", phrase.translation)
        assertEquals(listOf("First answer", "Second answer"), phrase.acceptedTranslations)
        assertTrue(phrase.acceptsTranslation("First answer."))
        assertTrue(phrase.acceptsTranslation("Second answer"))
        assertFalse(phrase.acceptsTranslation("Unlisted answer"))
    }

    @Test
    fun parsesTabSeparatedAcceptedTranslations() {
        val phrase = PhraseParser.parse("Source phrase\tFirst answer\tSecond answer").single()

        assertEquals(listOf("First answer", "Second answer"), phrase.acceptedTranslations)
        assertTrue(phrase.acceptsTranslation("Second answer"))
    }

    @Test
    fun parsesWordDictionaryWithCefrLevels() {
        val words = WordParser.parse(
            "дом | house | A1\n" +
                "окружающая среда | environment | B1\n" +
                "слово\tword\tA1",
        )

        assertEquals(3, words.size)
        assertEquals("house", words[0].translation)
        assertEquals("A1", words[0].cefr)
        assertTrue(words.all { it.isWord })
        assertEquals(
            "дом | house | A1\nокружающая среда | environment | B1\nслово | word | A1",
            WordParser.format(words),
        )
        assertFalse(PhraseParser.parse("Доброе утро | Good morning").single().isWord)
    }

    @Test
    fun includesOnlyQuestionsFromSelectedAssets() {
        val phrases = listOf(Phrase("phrase", "translation"))
        val words = listOf(Phrase("word", "translation", isWord = true))

        assertEquals(
            listOf(words.single()),
            questionsForSelectedAssets(phrases, words, setOf("vocabulary/Words.txt")),
        )
        assertEquals(
            listOf(phrases.single()),
            questionsForSelectedAssets(phrases, words, setOf("phrases.txt")),
        )
        assertTrue(questionsForSelectedAssets(phrases, words, emptySet()).isEmpty())
    }

    @Test
    fun rejectsInvalidWordDictionaryRows() {
        assertTrue(runCatching { WordParser.parse("дом | house") }.isFailure)
        assertTrue(runCatching { WordParser.parse("дом | house | C7") }.isFailure)
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
        assertTrue(isRepeatCommand("Repeat question."))
        assertTrue(isRepeatCommand("Повтори вопрос"))
        assertFalse(isRepeatCommand("Repeat phrase"))
        assertFalse(isRepeatCommand("Repeat this question"))
    }

    @Test
    fun detectsPracticeModeCommands() {
        assertTrue(isStartPhrasesCommand("Start Phrases"))
        assertTrue(isStartPhrasesCommand("Start phrases."))
        assertFalse(isStartPhrasesCommand("Start Words"))
        assertTrue(isStartWordsCommand("Start Words"))
        assertTrue(isStartWordsCommand("Start words."))
        assertFalse(isStartWordsCommand("Start Phrases"))
    }

    @Test
    fun answerComparisonIgnoresOptionalOClock() {
        assertEquals(
            "Don't call me at 9, I'll be watching the match.",
            formatRecognizedAnswer("Don't call me at 9:00, I'll be watching the match."),
        )
        assertEquals("The meeting is at 9:30.", formatRecognizedAnswer("The meeting is at 9:30."))
        assertEquals(
            normalizeAnswer("Don't call me at nine"),
            normalizeAnswer("Don't call me at nine o'clock"),
        )
        assertEquals(
            normalizeAnswer("Don't call me at 9, I'll be watching the match."),
            normalizeAnswer("Don't call me at 9:00, I'll be watching the match."),
        )
        assertEquals("don't call me at nine o'clock", normalizeSpeech("Don't call me at nine o'clock"))
    }

    @Test
    fun detectsRatingCommand() {
        assertTrue(isRatingCommand("Rating"))
        assertTrue(isRatingCommand("Rating."))
        assertTrue(isRatingCommand("What is my rating?"))
        assertTrue(isRatingCommand("Show me my rating"))
        assertTrue(isRatingCommand("What's my rating"))
        assertFalse(isRatingCommand("Raiting"))
        assertFalse(isRatingCommand("The rating is excellent"))
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
    fun answerCheckDelayUsesHalfSecondStepsWithinRange() {
        assertEquals(0L, answerCheckDelayMillis(0))
        assertEquals(500L, answerCheckDelayMillis(1))
        assertEquals(2_500L, answerCheckDelayMillis(5))
        assertEquals(5_000L, answerCheckDelayMillis(10))
        assertEquals(0L, answerCheckDelayMillis(-1))
        assertEquals(5_000L, answerCheckDelayMillis(11))
    }

    @Test
    fun checksExactInstalledOnDeviceLanguage() {
        assertTrue(isOnDeviceLanguageAvailable("en-US", setOf("en-US", "ru-RU")))
        assertFalse(isOnDeviceLanguageAvailable("en-US", setOf("en-GB", "ru-RU")))
        assertFalse(isOnDeviceLanguageAvailable("en-US", emptySet()))
    }

    @Test
    fun estimatesCefrFromPhraseComplexityAndSupportsAllLevels() {
        assertEquals("A1", CefrEstimator.estimate("Open the door."))
        assertEquals("A2", CefrEstimator.estimate("I usually get up at seven in the morning."))
        assertEquals("B1", CefrEstimator.estimate("She has been working here for three years."))
        assertEquals("B1", CefrEstimator.estimate("This house was built in 1961."))
        assertEquals("C1", CefrEstimator.estimate("If I had known about it yesterday, I would have helped you."))
        assertEquals(listOf("A1", "A2", "B1", "B2", "C1", "C2"), CefrEstimator.levels)
    }

    @Test
    fun computesFirstTryPercentageOnlyFromCorrectAnswers() {
        val history = PracticeSessionHistory(
            id = 1,
            startedAt = 0,
            endedAt = 10_000,
            durationSeconds = 10,
            correctCount = 3,
            firstTryCount = 2,
            retryCount = 1,
        )

        assertEquals(67, history.firstTryPercentage)
        assertEquals(0, history.copy(correctCount = 0, firstTryCount = 0).firstTryPercentage)
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
    fun detectsNavigationToPreviousQuestions() {
        assertEquals(PhraseNavigationCommand.PreviousPhrases(1), parsePhraseNavigationCommand("Previous question"))
        assertEquals(PhraseNavigationCommand.PreviousPhrases(3), parsePhraseNavigationCommand("Go back three questions"))
        assertEquals(PhraseNavigationCommand.PreviousPhrases(4), parsePhraseNavigationCommand("Go back 4 questions"))
        assertEquals(PhraseNavigationCommand.PreviousPhrases(2), parsePhraseNavigationCommand("Previous two questions"))
        assertEquals(null, parsePhraseNavigationCommand("Go back zero questions"))
        assertEquals(null, parsePhraseNavigationCommand("Previous this question"))
        assertEquals(null, parsePhraseNavigationCommand("Previous phrase"))
        assertEquals(null, parsePhraseNavigationCommand("Назад на 4 фразы"))
    }

    @Test
    fun detectsNavigationToFirstQuestion() {
        assertEquals(PhraseNavigationCommand.FirstPhrase, parsePhraseNavigationCommand("First question"))
        assertEquals(PhraseNavigationCommand.FirstPhrase, parsePhraseNavigationCommand("Start from the beginning"))
        assertEquals(null, parsePhraseNavigationCommand("First phrase"))
    }
}
