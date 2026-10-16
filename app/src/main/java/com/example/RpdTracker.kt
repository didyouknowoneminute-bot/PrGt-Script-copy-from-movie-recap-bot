package com.example

import android.content.Context
import java.security.MessageDigest
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

data class RpdStatus(
    val usedCount: Int,
    val maxLimit: Int = 20,
    val remainingCount: Int,
    val isLimitReached: Boolean,
    val resetTimeDescription: String,
    val hoursUntilReset: Long,
    val minutesUntilReset: Long,
    val modelName: String = ""
)

object RpdTracker {
    private const val PREFS_NAME = "rpd_tracker_cache"
    private const val PACIFIC_TIMEZONE_ID = "America/Los_Angeles"
    const val DEFAULT_RPD_LIMIT = 20
    const val FLASH_LITE_RPD_LIMIT = 150

    fun getLimitForModel(model: String): Int {
        val norm = normalizeModel(model)
        return if (norm.contains("lite")) FLASH_LITE_RPD_LIMIT else DEFAULT_RPD_LIMIT
    }

    /**
     * Map any UI display string or raw model ID to a canonical model ID without modifying model IDs.
     */
    fun normalizeModel(model: String): String {
        val trimmed = model.trim().lowercase(Locale.US)
        return when {
            trimmed.contains("3.8") -> "gemini-3.8-flash"
            trimmed.contains("3.7") -> "gemini-3.7-flash"
            trimmed.contains("3.6") -> "gemini-3.6-flash"
            trimmed.contains("3.5") -> "gemini-3.5-flash-lite"
            trimmed.contains("3.1") -> "gemini-3.1-flash-lite"
            trimmed.contains("3-flash") || trimmed.contains("3 flash") || trimmed.contains("gemini-3") || trimmed == "3" -> "gemini-3-flash"
            trimmed.startsWith("gemini-") -> trimmed
            trimmed.isBlank() -> "default"
            else -> trimmed
        }
    }

    /**
     * Get the current date in US Pacific Time (YYYY-MM-DD).
     * Google's daily quota resets at midnight (00:00:00 PST/PDT) in this timezone.
     */
    fun getPacificDateKey(): String {
        val tz = TimeZone.getTimeZone(PACIFIC_TIMEZONE_ID)
        val cal = Calendar.getInstance(tz)
        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH) + 1
        val day = cal.get(Calendar.DAY_OF_MONTH)
        return String.format(Locale.US, "%04d-%02d-%02d", year, month, day)
    }

    /**
     * Hash key to avoid plain text key collisions or sensitive data in key names
     */
    private fun hashKey(apiKey: String): String {
        val trimmed = apiKey.trim()
        if (trimmed.isEmpty()) return ""
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            val bytes = md.digest(trimmed.toByteArray())
            bytes.joinToString("") { "%02x".format(it) }.take(16)
        } catch (e: Exception) {
            trimmed.hashCode().toString()
        }
    }

    private fun getStorageKey(keyHash: String, normModel: String): String {
        return if (normModel == "default" || normModel.isBlank()) {
            keyHash
        } else {
            "${keyHash}_${normModel}"
        }
    }

    /**
     * Get the current RPD usage and status for an API key and model.
     * Automatically resets to 0 if the US Pacific date has changed!
     */
    fun getStatus(
        context: Context,
        apiKey: String,
        limit: Int = -1,
        model: String = ""
    ): RpdStatus {
        val trimmed = apiKey.trim()
        val tz = TimeZone.getTimeZone(PACIFIC_TIMEZONE_ID)
        val nowCal = Calendar.getInstance(tz)
        val isDst = tz.inDaylightTime(nowCal.time) // true during PDT, false during PST

        // Next midnight Pacific (00:00)
        val nextMidnight = Calendar.getInstance(tz).apply {
            add(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val msUntilReset = (nextMidnight.timeInMillis - System.currentTimeMillis()).coerceAtLeast(0L)
        val hoursUntilReset = msUntilReset / (1000 * 60 * 60)
        val minutesUntilReset = (msUntilReset / (1000 * 60)) % 60

        // Reset time description:
        // PDT (Summer): MMT 1:30 PM (13:30), ICT 2:00 PM (14:00)
        // PST (Winter): MMT 2:30 PM (14:30), ICT 3:00 PM (15:00)
        val resetDescription = if (isDst) {
            "မွန်းလွဲ ၁:၃၀ PM (MMT) / ၂:၀၀ PM (ICT) [PDT]"
        } else {
            "မွန်းလွဲ ၂:၃၀ PM (MMT) / ၃:၀၀ PM (ICT) [PST]"
        }

        val normModel = normalizeModel(model)
        val effectiveLimit = if (limit > 0) limit else getLimitForModel(normModel)

        if (trimmed.isEmpty()) {
            return RpdStatus(
                usedCount = 0,
                maxLimit = effectiveLimit,
                remainingCount = effectiveLimit,
                isLimitReached = false,
                resetTimeDescription = resetDescription,
                hoursUntilReset = hoursUntilReset,
                minutesUntilReset = minutesUntilReset,
                modelName = normModel
            )
        }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val keyHash = hashKey(trimmed)
        val todayDate = getPacificDateKey()

        val subKey = getStorageKey(keyHash, normModel)
        val savedDate = prefs.getString("date_$subKey", null)
        val used = if (savedDate == todayDate) {
            prefs.getInt("count_$subKey", 0)
        } else {
            // Fallback for legacy key storage if model was default or first transition
            if (normModel == "default" && prefs.getString("date_$keyHash", null) == todayDate) {
                prefs.getInt("count_$keyHash", 0)
            } else {
                0 // Automatically reset when Pacific date changes!
            }
        }

        val remaining = (effectiveLimit - used).coerceAtLeast(0)
        val isReached = used >= effectiveLimit

        return RpdStatus(
            usedCount = used,
            maxLimit = effectiveLimit,
            remainingCount = remaining,
            isLimitReached = isReached,
            resetTimeDescription = resetDescription,
            hoursUntilReset = hoursUntilReset,
            minutesUntilReset = minutesUntilReset,
            modelName = normModel
        )
    }

    /**
     * Record an API call for a key and model. Increments usage count for today's Pacific Date.
     */
    @Synchronized
    fun recordCall(context: Context, apiKey: String, model: String = ""): Int {
        val trimmed = apiKey.trim()
        if (trimmed.isEmpty()) return 0
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val keyHash = hashKey(trimmed)
        val normModel = normalizeModel(model)
        val todayDate = getPacificDateKey()

        val subKey = getStorageKey(keyHash, normModel)
        val savedDate = prefs.getString("date_$subKey", null)
        val currentCount = if (savedDate == todayDate) {
            prefs.getInt("count_$subKey", 0)
        } else {
            0
        }
        val newCount = currentCount + 1
        prefs.edit()
            .putString("date_$subKey", todayDate)
            .putInt("count_$subKey", newCount)
            .commit()
        return newCount
    }
}
