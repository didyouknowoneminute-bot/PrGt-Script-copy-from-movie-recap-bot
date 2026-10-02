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
}
