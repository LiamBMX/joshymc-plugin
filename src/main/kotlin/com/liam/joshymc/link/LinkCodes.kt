package com.liam.joshymc.link

import java.security.SecureRandom

/**
 * Account-link verification codes (issue #1155). Codes come from [SecureRandom] over an
 * alphabet without look-alike characters (no 0/O, 1/I/L), so a player can read one off
 * the GUI and type it into a DM without guessing which glyph it was.
 */
object LinkCodes {

    const val ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"
    const val MIN_LENGTH = 6
    const val MAX_LENGTH = 16

    private val random = SecureRandom()

    fun generate(length: Int): String =
        String(CharArray(length.coerceIn(MIN_LENGTH, MAX_LENGTH)) { ALPHABET[random.nextInt(ALPHABET.length)] })

    /** How the code is shown in-game: 8 characters read as `K7QM-4XPA`. */
    fun display(code: String): String = if (code.length >= 8) code.chunked(4).joinToString("-") else code

    /**
     * Canonical form of what a Discord user typed: spaces and dashes dropped, upper-cased.
     * `k7qm-4xpa`, `K7QM 4XPA` and `K7QM4XPA` are all the same guess, so reformatting a
     * message never earns extra attempts. Returns null when the message can't be a code
     * attempt at all (other punctuation, wrong length), which is answered with help text.
     */
    fun normalize(input: String, length: Int): String? {
        if (input.any { !(it.isWhitespace() || it == '-' || it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9') }) return null
        val code = input.filter { it.isLetterOrDigit() }.uppercase()
        return if (code.length == length) code else null
    }
}
