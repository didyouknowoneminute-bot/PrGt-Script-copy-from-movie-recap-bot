package com.example

import java.util.Locale

/**
 * Utility to parse SRT / VTT subtitle files and extract dialogue cues per timestamp window.
 */
object SrtParser {

    data class SubtitleCue(
        val index: Int,
        val startMs: Long,
        val endMs: Long,
        val text: String
    )

    fun parseTimestamp(timeStr: String): Long {
        val cleaned = timeStr.trim().replace(',', '.')
        val parts = cleaned.split(":")
        return try {
            when (parts.size) {
                3 -> {
                    val hours = parts[0].toLong()
                    val minutes = parts[1].toLong()
                    val secParts = parts[2].split(".")
                    val seconds = secParts[0].toLong()
                    val ms = if (secParts.size > 1) secParts[1].padEnd(3, '0').take(3).toLong() else 0L
                    hours * 3600000L + minutes * 60000L + seconds * 1000L + ms
                }
                2 -> {
                    val minutes = parts[0].toLong()
                    val secParts = parts[1].split(".")
                    val seconds = secParts[0].toLong()
                    val ms = if (secParts.size > 1) secParts[1].padEnd(3, '0').take(3).toLong() else 0L
                    minutes * 60000L + seconds * 1000L + ms
                }
                else -> 0L
            }
        } catch (_: Exception) {
            0L
        }
    }

    /**
     * Parses standard SRT or WebVTT content into a list of SubtitleCue items.
     */
    fun parseSrtContent(content: String): List<SubtitleCue> {
        val cues = mutableListOf<SubtitleCue>()
        if (content.isBlank()) return cues

        val normalized = content.replace("\r\n", "\n").replace("\r", "\n")
        val blocks = normalized.split(Regex("\n\\s*\n"))

        var cueIndex = 1
        for (block in blocks) {
            val lines = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) continue

            val arrowLineIdx = lines.indexOfFirst { it.contains("-->") }
            if (arrowLineIdx == -1) continue

            val timeLine = lines[arrowLineIdx]
            val timeParts = timeLine.split("-->")
            if (timeParts.size < 2) continue

            val startMs = parseTimestamp(timeParts[0])
            val endMsToken = timeParts[1].trim().split(Regex("\\s+"))[0]
            val endMs = parseTimestamp(endMsToken)

            val textLines = lines.subList(arrowLineIdx + 1, lines.size)
                .map { it.replace(Regex("<[^>]*>"), "").replace(Regex("\\{[^}]*\\}"), "").trim() }
                .filter { it.isNotEmpty() }

            if (textLines.isNotEmpty()) {
                val cleanText = textLines.joinToString(" ")
                cues.add(SubtitleCue(cueIndex++, startMs, endMs, cleanText))
            }
        }
        return cues
    }

    /**
     * Extracts subtitles that overlap with the specified segment [startMs, endMs].
     */
    fun getSubtitlesForSegment(cues: List<SubtitleCue>, startMs: Long, endMs: Long): String {
        if (cues.isEmpty()) return ""
        val relevant = cues.filter { cue ->
            cue.endMs >= startMs && cue.startMs <= endMs
        }
        if (relevant.isEmpty()) return ""
        return relevant.joinToString("\n") { cue ->
            val startMin = cue.startMs / 60000
            val startSec = (cue.startMs % 60000) / 1000
            val timeStamp = String.format(Locale.US, "%02d:%02d", startMin, startSec)
            "[$timeStamp] ${cue.text}"
        }
    }
}
