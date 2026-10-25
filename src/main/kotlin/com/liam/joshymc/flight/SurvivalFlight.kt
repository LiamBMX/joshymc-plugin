package com.liam.joshymc.flight

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Rules for Survival temporary flight time and Fly Vouchers (issue #1304). Bukkit-free.
 *
 * Temporary flight runs on real time, the same way the LuckPerms timed `joshymc.fly` nodes
 * `/flytime` reads (issue #1303) do: the balance is one stored end time (epoch millis) and
 * time left is `end - now`, online or not. When it reaches zero a [GRACE_MILLIS] landing
 * grace follows. The grace is worked out from the same stored end time, so moving, toggling
 * flight, reconnecting or a restart can never start it again or make it longer, and it never
 * adds to the balance.
 */
object SurvivalFlight {

    const val GRACE_SECONDS = 30
    const val GRACE_MILLIS = GRACE_SECONDS * 1000L

    /** Longest single Fly Voucher. */
    const val MAX_VOUCHER_SECONDS = 365L * 86_400

    /** Most temporary flight time a player can hold at once. */
    const val MAX_BALANCE_MILLIS = 3_650L * 86_400 * 1000

    /** Grace seconds left at which a chat reminder is sent (the start of the grace has its own warning). */
    val CHAT_REMINDERS = setOf(15, 10, 5)

    /** Grace seconds left at which a countdown title is shown. */
    val TITLE_COUNTDOWN = setOf(5, 4, 3, 2, 1)

    /** Durations offered by tab completion; any other valid duration works too. */
    val SUGGESTED_DURATIONS = listOf("5m", "15m", "30m", "1h", "2h", "6h", "12h", "24h")

    sealed interface Phase {
        /** No temporary flight: never had any, or it ended and was already enforced. */
        data object None : Phase
        data class Active(val remainingMillis: Long) : Phase
        data class Grace(val graceLeftMillis: Long) : Phase
        /** Grace is over but the end hasn't been enforced yet (it ended offline or between ticks). */
        data object Expired : Phase
    }

    fun phase(endsAt: Long, ended: Boolean, now: Long): Phase = when {
        ended -> Phase.None
        now < endsAt -> Phase.Active(endsAt - now)
        now < endsAt + GRACE_MILLIS -> Phase.Grace(endsAt + GRACE_MILLIS - now)
        else -> Phase.Expired
    }

    /** Whole seconds, rounded up, so time that's still left never reads 0. */
    fun ceilSeconds(millis: Long): Long = if (millis <= 0) 0 else (millis + 999) / 1000

    /**
     * The new end time after adding [addSeconds] on top of whatever flight the player still
     * has: their own unexpired end time and [otherTemporaryEnd] (a LuckPerms timed fly node),
     * whichever lasts longer, or now. Time left in a grace period is never counted, so a
     * voucher redeemed during the grace adds its full duration from now. Null when the result
     * would pass [MAX_BALANCE_MILLIS] or overflow.
     */
    fun stackedEnd(currentEnd: Long?, ended: Boolean, otherTemporaryEnd: Long?, addSeconds: Long, now: Long): Long? {
        if (addSeconds <= 0 || addSeconds > MAX_VOUCHER_SECONDS) return null
        var base = now
        if (currentEnd != null && !ended && currentEnd > base) base = currentEnd
        if (otherTemporaryEnd != null && otherTemporaryEnd > base) base = otherTemporaryEnd
        return try {
            val end = Math.addExact(base, Math.multiplyExact(addSeconds, 1000L))
            if (end - now > MAX_BALANCE_MILLIS) null else end
        } catch (_: ArithmeticException) {
            null
        }
    }

    sealed interface DurationParse {
        data class Ok(val seconds: Long) : DurationParse
        data class Invalid(val reason: String) : DurationParse
    }

    private val DURATION_PART = Regex("(\\d+)([dhms])")

    /**
     * `30m`, `2h`, `1h30m`, `1d12h`, `90s` (units d/h/m/s, each at most once, largest first).
     * Plain numbers, signs, decimals, zero, and anything over [MAX_VOUCHER_SECONDS] are refused.
     */
    fun parseDuration(input: String): DurationParse {
        val text = input.trim().lowercase()
        if (text.isEmpty()) return DurationParse.Invalid("Duration is empty.")
        if (!Regex("(\\d+[dhms])+").matches(text)) {
            return DurationParse.Invalid("Use a duration like 30m, 2h, 1h30m or 1d.")
        }
        val order = "dhms"
        var lastUnit = -1
        var total = 0L
        for (match in DURATION_PART.findAll(text)) {
            val (digits, unit) = match.destructured
            val unitIndex = order.indexOf(unit)
            if (unitIndex <= lastUnit) return DurationParse.Invalid("Use each unit once, largest first (e.g. 1h30m).")
            lastUnit = unitIndex
            if (digits.length > 9) return DurationParse.Invalid("Duration is too long (max ${long(MAX_VOUCHER_SECONDS)}).")
            val perUnit = when (unit) { "d" -> 86_400L; "h" -> 3_600L; "m" -> 60L; else -> 1L }
            total += digits.toLong() * perUnit
            if (total > MAX_VOUCHER_SECONDS) return DurationParse.Invalid("Duration is too long (max ${long(MAX_VOUCHER_SECONDS)}).")
        }
        if (total <= 0) return DurationParse.Invalid("Duration must be longer than zero.")
        return DurationParse.Ok(total)
    }

    /** `2h`, `1h 30m`, `45s`: [FlightTime.format] for whole seconds. */
    fun short(seconds: Long): String = FlightTime.format(java.time.Duration.ofSeconds(seconds.coerceAtLeast(0)))

    /** `2 Hours`, `1 Hour 30 Minutes`, `1 Day 6 Hours`: every non-zero unit. */
    fun long(seconds: Long): String {
        var left = seconds.coerceAtLeast(0)
        val parts = mutableListOf<String>()
        for ((size, name) in listOf(86_400L to "Day", 3_600L to "Hour", 60L to "Minute", 1L to "Second")) {
            val n = left / size
            left %= size
            if (n > 0) parts += "$n $name${if (n == 1L) "" else "s"}"
        }
        return if (parts.isEmpty()) "0 Seconds" else parts.joinToString(" ")
    }

    /** What a voucher's signature covers: every field that decides what it's worth or whether it's been used. */
    fun signedPayload(version: Int, voucherUuid: String, seconds: Long): String = "fly|$version|$voucherUuid|$seconds"

    fun sign(secret: ByteArray, payload: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        return mac.doFinal(payload.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    /** Constant-time comparison so a forged signature can't be guessed byte by byte. */
    fun verify(secret: ByteArray, payload: String, signature: String?): Boolean {
        if (signature == null) return false
        return java.security.MessageDigest.isEqual(
            sign(secret, payload).toByteArray(Charsets.US_ASCII),
            signature.toByteArray(Charsets.US_ASCII)
        )
    }
}
