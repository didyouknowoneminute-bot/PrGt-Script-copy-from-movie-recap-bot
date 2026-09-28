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
    val minutesUntilReset: Long
)

object RpdTracker {
    private const val PREFS_NAME = "rpd_tracker_cache"
    private const val PACIFIC_TIMEZONE_ID = "America/Los_Angeles"
    const val DEFAULT_RPD_LIMIT = 20

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

    /**
     * Get the current RPD usage and status for an API key.
     * Automatically resets to 0 if the US Pacific date has changed!
     */
    fun getStatus(context: Context, apiKey: String, limit: Int = DEFAULT_RPD_LIMIT): RpdStatus {
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

        if (trimmed.isEmpty()) {
            return RpdStatus(
                usedCount = 0,
                maxLimit = limit,
                remainingCount = limit,
                isLimitReached = false,
                resetTimeDescription = resetDescription,
                hoursUntilReset = hoursUntilReset,
                minutesUntilReset = minutesUntilReset
            )
        }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val keyHash = hashKey(trimmed)
        val todayDate = getPacificDateKey()

        val savedDate = prefs.getString("date_$keyHash", null)
        val used = if (savedDate == todayDate) {
            prefs.getInt("count_$keyHash", 0)
        } else {
            0 // Automatically reset when Pacific date changes!
        }

        val remaining = (limit - used).coerceAtLeast(0)
        val isReached = used >= limit

        return RpdStatus(
            usedCount = used,
            maxLimit = limit,
            remainingCount = remaining,
            isLimitReached = isReached,
            resetTimeDescription = resetDescription,
            hoursUntilReset = hoursUntilReset,
            minutesUntilReset = minutesUntilReset
        )
    }

    /**
     * Record an API call for a key. Increments usage count for today's Pacific Date.
     */
    @Synchronized
    fun recordCall(context: Context, apiKey: String): Int {
        val trimmed = apiKey.trim()
        if (trimmed.isEmpty()) return 0
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val keyHash = hashKey(trimmed)
        val todayDate = getPacificDateKey()

        val savedDate = prefs.getString("date_$keyHash", null)
        val currentCount = if (savedDate == todayDate) {
            prefs.getInt("count_$keyHash", 0)
        } else {
            0
        }
        val newCount = currentCount + 1
        prefs.edit()
            .putString("date_$keyHash", todayDate)
            .putInt("count_$keyHash", newCount)
            .apply()
        return newCount
    }
}
