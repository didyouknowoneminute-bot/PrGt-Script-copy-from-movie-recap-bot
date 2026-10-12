package com.example

import android.content.Context
import android.net.Uri
import java.security.MessageDigest

/**
 * Cache for Gemini uploaded file URIs tied strictly to (Media Hash + API Key Hash).
 * Ensures that if an API Key changes, the file is re-uploaded specifically to that API key's Gemini storage.
 */
object GeminiFileKeyCache {
    private const val PREFS_NAME = "gemini_file_key_cache"

    private fun hashString(input: String): String {
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            val bytes = md.digest(input.toByteArray())
            bytes.joinToString("") { "%02x".format(it) }.take(20)
        } catch (e: Exception) {
            input.hashCode().toString()
        }
    }

    private fun getMediaIdentifier(context: Context, mediaUri: Uri?): String {
        if (mediaUri == null) return "unknown_media"
        val path = mediaUri.toString()
        var size = -1L
        try {
            context.contentResolver.openFileDescriptor(mediaUri, "r")?.use { pfd ->
                size = pfd.statSize
            }
        } catch (e: Exception) {
            // Ignore
        }
        return "$path-$size"
    }

    private fun getCacheKey(context: Context, mediaUri: Uri?, apiKey: String): String {
        val mediaId = getMediaIdentifier(context, mediaUri)
        val keyPart = hashString(apiKey.trim())
        val mediaPart = hashString(mediaId)
        return "cache_${mediaPart}_$keyPart"
    }

    fun getCachedFile(context: Context, mediaUri: Uri?, apiKey: String): Pair<String, String>? {
        if (apiKey.isBlank() || mediaUri == null) return null
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val cacheKey = getCacheKey(context, mediaUri, apiKey)
        val uri = prefs.getString("${cacheKey}_uri", null)
        val name = prefs.getString("${cacheKey}_name", null)
        val timestamp = prefs.getLong("${cacheKey}_time", 0L)

        // Gemini files expire after 48 hours (we use 46 hours for safety)
        val maxAgeMs = 46L * 60 * 60 * 1000
        if (!uri.isNullOrEmpty() && !name.isNullOrEmpty() && (System.currentTimeMillis() - timestamp < maxAgeMs)) {
            return Pair(uri, name)
        }
        return null
    }

    fun saveCachedFile(context: Context, mediaUri: Uri?, apiKey: String, fileUri: String, fileName: String) {
        if (apiKey.isBlank() || mediaUri == null) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val cacheKey = getCacheKey(context, mediaUri, apiKey)
        prefs.edit()
            .putString("${cacheKey}_uri", fileUri)
            .putString("${cacheKey}_name", fileName)
            .putLong("${cacheKey}_time", System.currentTimeMillis())
            .apply()
    }

    private fun getCharacterProfileCacheKey(context: Context, mediaUri: Uri?, geminiFileUri: String?, characterHint: String, language: String, fileName: String = ""): String {
        val mediaId = when {
            fileName.isNotBlank() -> fileName.trim().lowercase()
            mediaUri != null -> getMediaIdentifier(context, mediaUri)
            else -> geminiFileUri ?: "unknown_file"
        }
        val hintHash = hashString(characterHint.trim() + "_" + language.trim())
        val mediaHash = hashString(mediaId)
        return "char_profile_${mediaHash}_$hintHash"
    }

    fun getCachedCharacterProfile(context: Context, mediaUri: Uri?, geminiFileUri: String?, characterHint: String, language: String, fileName: String = ""): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = getCharacterProfileCacheKey(context, mediaUri, geminiFileUri, characterHint, language, fileName)
        val profile = prefs.getString("${key}_text", null)
        val timestamp = prefs.getLong("${key}_time", 0L)
        val maxAgeMs = 46L * 60 * 60 * 1000
        if (!profile.isNullOrBlank() && (System.currentTimeMillis() - timestamp < maxAgeMs)) {
            return profile
        }
        return null
    }

    fun saveCachedCharacterProfile(context: Context, mediaUri: Uri?, geminiFileUri: String?, characterHint: String, language: String, profileText: String, fileName: String = "") {
        if (profileText.isBlank()) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = getCharacterProfileCacheKey(context, mediaUri, geminiFileUri, characterHint, language, fileName)
        prefs.edit()
            .putString("${key}_text", profileText)
            .putLong("${key}_time", System.currentTimeMillis())
            .apply()
    }

    data class SeriesPartInfo(
        val baseTitle: String,
        val partNumber: Int?
    )

    private fun normalizeDigits(input: String): String {
        val sb = StringBuilder(input.length)
        for (ch in input) {
            when (ch) {
                in '\u1040'..'\u1049' -> sb.append((ch.code - '\u1040'.code + '0'.code).toChar())
                in '\uFF10'..'\uFF19' -> sb.append((ch.code - '\uFF10'.code + '0'.code).toChar())
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun cleanSeriesBaseTitle(raw: String): String {
        var s = raw.trim()
        val extRegex = Regex("""\.(?:mp4|mkv|mov|avi|webm|mp3|m4a|wav|flac|aac|ogg|3gp|ts|m4v)$""", RegexOption.IGNORE_CASE)
        s = s.replace(extRegex, "").trim()
        val resRegex = Regex("""\s*[\(\[（【]\s*(?:\d{3,4}\s*p|4k|2k|8k|hd|fhd|uhd)\s*[\)\]）】]$""", RegexOption.IGNORE_CASE)
        s = s.replace(resRegex, "").trim()
        return s
    }

    fun parseSeriesInfo(fileName: String): SeriesPartInfo {
        val trimmed = normalizeDigits(fileName.trim())
        val cleanedFull = cleanSeriesBaseTitle(trimmed)

        // Match prefix patterns like: "Part 1 - Title.mp4", "Part 13 - Title(1080p).mp4", "[Part 10] Title", "EP 10 - Title", "အပိုင်း ၁၀ - Title"
        val prefixRegex = Regex(
            """^(?:[\(\[\{【（]\s*)?(?:part|ep|episode|အပိုင်း|ပိုင်း|pt)\.?\s*(\d+)\s*(?:[\)\]\}】）])?\s*(?:[-_:–—~|/]+\s*|\s+)(.+)$""",
            RegexOption.IGNORE_CASE
        )
        val prefixMatch = prefixRegex.find(cleanedFull)
        if (prefixMatch != null) {
            val partNum = prefixMatch.groupValues[1].toIntOrNull()
            val rest = cleanSeriesBaseTitle(prefixMatch.groupValues[2])
            if (rest.isNotBlank() && partNum != null) {
                return SeriesPartInfo(baseTitle = rest, partNumber = partNum)
            }
        }

        // Match suffix patterns like: "Title - Part 10.mp4", "Title_Part_12.mp4", "Title (Part 10).mp4"
        val suffixRegex = Regex(
            """^(.+?)\s*(?:[-_:–—~|/\s]+|[\(\[\{【（]\s*)(?:part|ep|episode|အပိုင်း|ပိုင်း|pt)\.?\s*(\d+)\s*(?:[\)\]\}】）])?$""",
            RegexOption.IGNORE_CASE
        )
        val suffixMatch = suffixRegex.find(cleanedFull)
        if (suffixMatch != null) {
            val base = cleanSeriesBaseTitle(suffixMatch.groupValues[1])
            val partNum = suffixMatch.groupValues[2].toIntOrNull()
            if (base.isNotBlank() && partNum != null) {
                return SeriesPartInfo(baseTitle = base, partNumber = partNum)
            }
        }

        return SeriesPartInfo(baseTitle = cleanedFull.ifBlank { trimmed }, partNumber = null)
    }

    fun isSameSeries(baseTitle1: String, baseTitle2: String): Boolean {
        val c1 = cleanSeriesBaseTitle(baseTitle1).replace("…", "...")
        val c2 = cleanSeriesBaseTitle(baseTitle2).replace("…", "...")
        if (c1.isBlank() || c2.isBlank()) return false
        if (c1.equals(c2, ignoreCase = true)) return true

        // Handle middle-truncated filenames with "..." where 1-digit ("Part 1") vs 2-digit ("Part 10") shifts truncation by 1-4 chars
        if (c1.contains("...") && c2.contains("...")) {
            val p1 = c1.substringBefore("...").trim()
            val s1 = c1.substringAfterLast("...").trim()
            val p2 = c2.substringBefore("...").trim()
            val s2 = c2.substringAfterLast("...").trim()
            val minPrefix = minOf(p1.length, p2.length)
            val minSuffix = minOf(s1.length, s2.length)
            if (minPrefix >= 4 && minSuffix >= 4) {
                val checkPrefixLen = (minPrefix - 3).coerceAtLeast(minPrefix.coerceAtMost(6))
                val checkSuffixLen = (minSuffix - 3).coerceAtLeast(minSuffix.coerceAtMost(6))
                val prefixMatches = p1.take(checkPrefixLen).equals(p2.take(checkPrefixLen), ignoreCase = true)
                val suffixMatches = s1.takeLast(checkSuffixLen).equals(s2.takeLast(checkSuffixLen), ignoreCase = true)
                if (prefixMatches && suffixMatches) return true
            }
        } else if (c1.contains("...") || c2.contains("...")) {
            val (truncated, full) = if (c1.contains("...")) c1 to c2 else c2 to c1
            val p = truncated.substringBefore("...").trim()
            val s = truncated.substringAfterLast("...").trim()
            if (p.length >= 4 && s.length >= 4) {
                val checkPrefixLen = (p.length - 3).coerceAtLeast(p.length.coerceAtMost(6))
                val checkSuffixLen = (s.length - 3).coerceAtLeast(s.length.coerceAtMost(6))
                if (full.take(checkPrefixLen).equals(p.take(checkPrefixLen), ignoreCase = true) &&
                    full.takeLast(checkSuffixLen).equals(s.takeLast(checkSuffixLen), ignoreCase = true)
                ) {
                    return true
                }
            }
        }

        // Handle end-truncated long titles where 1-digit vs 2-digit shifts total length by up to 4 chars
        val minLen = minOf(c1.length, c2.length)
        if (minLen >= 20 && kotlin.math.abs(c1.length - c2.length) <= 4) {
            var commonPrefix = 0
            while (commonPrefix < minLen && c1[commonPrefix].equals(c2[commonPrefix], ignoreCase = true)) {
                commonPrefix++
            }
            var commonSuffix = 0
            while (commonSuffix < (minLen - commonPrefix) &&
                c1[c1.length - 1 - commonSuffix].equals(c2[c2.length - 1 - commonSuffix], ignoreCase = true)
            ) {
                commonSuffix++
            }
            if (commonPrefix + commonSuffix >= minLen - 4) {
                return true
            }
        }

        return false
    }

    fun deleteSeriesPartCharacterProfile(context: Context, fileName: String, geminiFileUri: String? = null) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()

        if (fileName.isNotBlank()) {
            val fileHash = hashString(fileName.trim().lowercase())
            val filePrefix = "char_profile_${fileHash}_"
            prefs.all.keys.filter { it.startsWith(filePrefix) }.forEach { k ->
                editor.remove(k)
            }
        }

        if (!geminiFileUri.isNullOrBlank()) {
            val mediaHash = hashString(geminiFileUri)
            val prefix = "char_profile_${mediaHash}_"
            prefs.all.keys.filter { it.startsWith(prefix) }.forEach { k ->
                editor.remove(k)
            }
        }

        editor.apply()
    }

    fun clearAllCache(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
    }
}
