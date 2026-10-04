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

    fun parseSeriesInfo(fileName: String): SeriesPartInfo {
        val trimmed = fileName.trim()
        // Match prefix patterns like: "Part 1 - Title.mp4", "Part 2: Title.mp4", "EP 3 - Title", "အပိုင်း ၁ - Title"
        val prefixRegex = Regex("""^(?:part|ep|episode|အပိုင်း)\s*(\d+)\s*[-_:–—]+\s*(.+)$""", RegexOption.IGNORE_CASE)
        val prefixMatch = prefixRegex.find(trimmed)
        if (prefixMatch != null) {
            val partNum = prefixMatch.groupValues[1].toIntOrNull()
            val rest = prefixMatch.groupValues[2].trim()
            if (rest.isNotBlank()) {
                return SeriesPartInfo(baseTitle = rest, partNumber = partNum)
            }
        }

        // Match suffix patterns before extension like: "Title - Part 1.mp4", "Title_Part_2.mp4"
        val nameWithoutExt = trimmed.substringBeforeLast(".")
        val ext = if (trimmed.contains(".")) "." + trimmed.substringAfterLast(".") else ""
        val suffixRegex = Regex("""^(.+?)\s*[-_:–—\s]+\s*(?:part|ep|episode|အပိုင်း)\s*(\d+)$""", RegexOption.IGNORE_CASE)
        val suffixMatch = suffixRegex.find(nameWithoutExt)
        if (suffixMatch != null) {
            val base = suffixMatch.groupValues[1].trim() + ext
            val partNum = suffixMatch.groupValues[2].toIntOrNull()
            if (base.isNotBlank()) {
                return SeriesPartInfo(baseTitle = base, partNumber = partNum)
            }
        }

        return SeriesPartInfo(baseTitle = trimmed, partNumber = null)
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
}
