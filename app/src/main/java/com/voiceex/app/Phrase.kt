package com.voiceex.app

import java.util.Locale
import kotlin.math.roundToInt

data class Phrase(
    val prompt: String,
    val translation: String,
    val acceptedTranslations: List<String> = listOf(translation),
) {
    fun acceptsTranslation(answer: String): Boolean {
        val normalizedAnswer = normalizeAnswer(answer)
        return acceptedTranslations.any { normalizeAnswer(it) == normalizedAnswer }
    }
}

class FirstTryRating private constructor(
    val attemptedPhraseIndexes: Set<Int>,
    val correctFirstTryPhraseIndexes: Set<Int>,
) {
    constructor() : this(emptySet(), emptySet())

    val phraseCount: Int
        get() = attemptedPhraseIndexes.size

    val correctPhraseCount: Int
        get() = correctFirstTryPhraseIndexes.size

    val percentage: Int
        get() = if (phraseCount == 0) 0 else (correctPhraseCount * 100f / phraseCount).roundToInt()

    fun recordAnswer(phraseIndex: Int, isCorrect: Boolean): FirstTryRating {
        if (phraseIndex in attemptedPhraseIndexes) return this
        return FirstTryRating(
            attemptedPhraseIndexes = attemptedPhraseIndexes + phraseIndex,
            correctFirstTryPhraseIndexes = if (isCorrect) {
                correctFirstTryPhraseIndexes + phraseIndex
            } else {
                correctFirstTryPhraseIndexes
            },
        )
    }

}

object PhraseParser {
    fun parse(content: String): List<Phrase> {
        val lines = content.removePrefix("\uFEFF").lines()
        return lines.mapIndexedNotNull { index, line ->
            val delimiter = when {
                '\t' in line -> '\t'
                '|' in line -> '|'
                else -> null
            }
            if (delimiter == null) {
                if (line.isBlank()) null
                else throw IllegalArgumentException("Line ${index + 1} is missing a | or tab separator")
            } else {
                val fields = line.split(delimiter).map(String::trim)
                if (fields.size < 2 || fields.any(String::isEmpty)) {
                    throw IllegalArgumentException("Check the source phrase and translation on line ${index + 1}")
                }
                Phrase(fields.first(), fields[1], fields.drop(1))
            }
        }
    }
}

fun normalizeSpeech(value: String): String =
    value.lowercase(Locale.US)
        .replace(Regex("[’‘]"), "'")
        .replace(Regex("[^\\p{L}\\p{N}']+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

fun normalizeAnswer(value: String): String =
    normalizeSpeech(formatRecognizedAnswer(value))
        .split(" ")
        .filterNot { it == "o'clock" }
        .joinToString(" ")

fun formatRecognizedAnswer(value: String): String =
    value.replace(Regex("\\b(\\d{1,2})\\s*:\\s*00\\b"), "$1")

fun isRepeatCommand(value: String): Boolean =
    normalizeSpeech(value) in setOf(
        "повтори фразу",
        "повтори",
        "повторить фразу",
        "повторить",
        "repeat phrase",
        "repeat",
    )

fun isSkipCommand(value: String): Boolean =
    normalizeSpeech(value) in setOf(
        "skip question",
        "skip",
    )

private val ratingCommandWords = setOf("rating", "my", "the", "what", "what's", "is", "show", "me", "tell", "please")

fun isRatingCommand(value: String): Boolean =
    normalizeSpeech(value).split(" ").let { words ->
        "rating" in words &&
            words.size <= 5 &&
            words.all { it in ratingCommandWords }
    }

fun isClearRatingCommand(value: String): Boolean =
    normalizeSpeech(value) == "clear rating"

fun isOnDeviceLanguageAvailable(language: String, installedLanguages: Set<String>): Boolean {
    val selectedLanguage = Locale.forLanguageTag(language).toLanguageTag()
    return installedLanguages.any { installed ->
        Locale.forLanguageTag(installed).toLanguageTag().equals(selectedLanguage, ignoreCase = true)
    }
}

sealed interface PhraseNavigationCommand {
    data object FirstPhrase : PhraseNavigationCommand
    data class PreviousPhrases(val count: Int) : PhraseNavigationCommand
}

private val spokenPhraseCounts = mapOf(
    "one" to 1,
    "two" to 2,
    "three" to 3,
    "four" to 4,
    "five" to 5,
    "six" to 6,
    "seven" to 7,
    "eight" to 8,
    "nine" to 9,
    "ten" to 10,
)

private val englishPreviousPhrasesPattern =
    Regex("^(?:(?:go )?back|previous|go to previous|return to previous) (.+) (?:phrases?|questions?)$")

fun parsePhraseNavigationCommand(value: String): PhraseNavigationCommand? {
    val normalized = normalizeSpeech(value)
    if (normalized in setOf(
            "first phrase",
            "first question",
            "go to first phrase",
            "go to the first phrase",
            "start from the beginning",
        )
    ) {
        return PhraseNavigationCommand.FirstPhrase
    }

    if (normalized in setOf(
            "previous",
            "previous phrase",
            "previous question",
            "go back",
            "go back one phrase",
            "back",
            "back one phrase",
        )
    ) {
        return PhraseNavigationCommand.PreviousPhrases(1)
    }

    val countText = englishPreviousPhrasesPattern.matchEntire(normalized)?.groupValues?.get(1)
        ?: return null
    val count = countText.toIntOrNull() ?: spokenPhraseCounts[countText] ?: return null
    return count.takeIf { it > 0 }?.let(PhraseNavigationCommand::PreviousPhrases)
}
