package com.voiceex.app

import java.util.Locale
import kotlin.math.roundToInt

data class Phrase(
    val prompt: String,
    val translation: String,
    val acceptedTranslations: List<String> = listOf(translation),
    val questionId: Long? = null,
    val cefr: String = CefrEstimator.estimate(translation),
    val isWord: Boolean = false,
) {
    fun acceptsTranslation(answer: String): Boolean {
        val normalizedAnswer = normalizeAnswer(answer)
        return acceptedTranslations.any { normalizeAnswer(it) == normalizedAnswer }
    }
}

fun questionsFromSelectedAssets(
    assetQuestions: Map<String, List<Phrase>>,
    selectedAssetPaths: Set<String>,
): List<Phrase> = assetQuestions.entries
    .filter { it.key in selectedAssetPaths }
    .flatMap { it.value }

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

    fun format(phrases: List<Phrase>): String = phrases.joinToString("\n") { phrase ->
        (listOf(phrase.prompt) + phrase.acceptedTranslations).joinToString(" | ")
    }
}

object WordParser {
    fun parse(content: String): List<Phrase> =
        content.removePrefix("\uFEFF").lines().mapIndexedNotNull { index, line ->
            if (line.isBlank()) return@mapIndexedNotNull null
            val delimiter = if ('\t' in line) '\t' else '|'
            val fields = line.split(delimiter).map(String::trim)
            if (fields.size != 3 || fields.any(String::isEmpty)) {
                throw IllegalArgumentException("Line ${index + 1} must contain a Russian word, translation, and CEFR level")
            }
            val cefr = fields[2].uppercase(Locale.US)
            if (cefr !in CefrEstimator.levels) {
                throw IllegalArgumentException("Line ${index + 1} has an invalid CEFR level")
            }
            Phrase(
                prompt = fields[0],
                translation = fields[1],
                cefr = cefr,
                isWord = true,
            )
        }

    fun format(words: List<Phrase>): String = words.joinToString("\n") { word ->
        "${word.prompt} | ${word.translation} | ${word.cefr}"
    }
}

object CefrEstimator {
    val levels = listOf("A1", "A2", "B1", "B2", "C1", "C2")
    private val irregularParticiples =
        "built|made|sent|written|given|known|seen|done|taken|told|found|bought|brought|caught|held|left|lost|paid|sold|thought|won|put|read|cut"
    private val passivePattern =
        Regex("\\b(?:am|is|are|was|were|be|been|being)\\s+(?:\\w+(?:ed|en)|$irregularParticiples)\\b")
    private val perfectPattern =
        Regex("\\b(?:have|has|had)\\s+(?:been\\s+\\w+ing|\\w+(?:ed|en)|$irregularParticiples)\\b")
    private val modalPerfectPattern =
        Regex("\\b(?:could|would|should|might|must|can't|cannot)\\s+have\\s+\\w+")

    fun estimate(translation: String): String {
        val text = translation.lowercase(Locale.US)
        val words = normalizeSpeech(text).split(" ").filter(String::isNotEmpty)
        val baseLevel = when {
            words.size <= 5 -> 0
            words.size <= 9 -> 1
            words.size <= 15 -> 2
            words.size <= 22 -> 3
            else -> 4
        }
        var level = baseLevel
        if (perfectPattern.containsMatchIn(text) ||
            Regex("\\b(?:because|although|while|before|after|unless|since|whereas|when)\\b").containsMatchIn(text) ||
            Regex("\\b(?:who|which|that)\\b").containsMatchIn(text) ||
            passivePattern.containsMatchIn(text)
        ) {
            level = maxOf(level, 2)
        }
        if (modalPerfectPattern.containsMatchIn(text) ||
            Regex("\\bif\\b.*\\b(?:would|could|might)\\b").containsMatchIn(text) ||
            Regex("\\b(?:looking forward to|used to|in order to)\\b").containsMatchIn(text)
        ) {
            level = maxOf(level, 3)
        }
        if (Regex("\\b(?:wish|if)\\b.*\\b(?:had|were)\\b.*\\b(?:would|could|might|wish)\\b").containsMatchIn(text) ||
            Regex("\\b(?:would|could|should|might) have been \\w+ing\\b").containsMatchIn(text)
        ) {
            level = maxOf(level, 4)
        }
        val longWordRatio = words.count { it.length >= 10 }.toFloat() / words.size.coerceAtLeast(1)
        if (words.size >= 30 && longWordRatio >= 0.2f &&
            (passivePattern.containsMatchIn(text) || perfectPattern.containsMatchIn(text))
        ) {
            level = 5
        }
        return levels[level]
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
        "повтори вопрос",
        "повтори",
        "повторить вопрос",
        "повторить",
        "repeat question",
        "repeat",
    )

fun isStartPhrasesCommand(value: String): Boolean =
    normalizeSpeech(value) == "start phrases"

fun isStartWordsCommand(value: String): Boolean =
    normalizeSpeech(value) == "start words"

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
    Regex("^(?:(?:go )?back|previous|go to previous|return to previous) (.+) questions?$")

fun parsePhraseNavigationCommand(value: String): PhraseNavigationCommand? {
    val normalized = normalizeSpeech(value)
    if (normalized in setOf(
            "first question",
            "first question",
            "go to first question",
            "go to the first question",
            "start from the beginning",
        )
    ) {
        return PhraseNavigationCommand.FirstPhrase
    }

    if (normalized in setOf(
            "previous",
            "previous question",
            "previous question",
            "go back",
            "go back one question",
            "back",
            "back one question",
        )
    ) {
        return PhraseNavigationCommand.PreviousPhrases(1)
    }

    val countText = englishPreviousPhrasesPattern.matchEntire(normalized)?.groupValues?.get(1)
        ?: return null
    val count = countText.toIntOrNull() ?: spokenPhraseCounts[countText] ?: return null
    return count.takeIf { it > 0 }?.let(PhraseNavigationCommand::PreviousPhrases)
}
