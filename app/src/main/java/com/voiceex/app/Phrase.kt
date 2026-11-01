package com.voiceex.app

import java.util.Locale

data class Phrase(val prompt: String, val translation: String)

object PhraseParser {
    fun parse(content: String): List<Phrase> {
        val lines = content.removePrefix("\uFEFF").lines()
        return lines.mapIndexedNotNull { index, line ->
            val tabIndex = line.indexOf('\t')
            val separatorIndex = if (tabIndex >= 0) tabIndex else line.indexOf('|')
            if (separatorIndex < 0) {
                if (line.isBlank()) null
                else throw IllegalArgumentException("В строке ${index + 1} нет разделителя | или Tab")
            } else {
                val prompt = line.substring(0, separatorIndex).trim()
                val translation = line.substring(separatorIndex + 1).trim()
                if (prompt.isEmpty() || translation.isEmpty()) {
                    throw IllegalArgumentException("Проверьте исходную фразу и перевод в строке ${index + 1}")
                }
                Phrase(prompt, translation)
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

fun isRepeatCommand(value: String): Boolean =
    normalizeSpeech(value) in setOf(
        "повтори фразу",
        "повтори",
        "повторить фразу",
        "повторить",
        "repeat phrase",
        "repeat",
    )
