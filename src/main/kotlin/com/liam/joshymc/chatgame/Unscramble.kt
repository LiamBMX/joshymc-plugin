package com.liam.joshymc.chatgame

import java.util.Locale
import java.util.Random

/**
 * Bukkit-free rules for the Unscramble chat game (issue #1395): the word pool,
 * the scramble and the answer check. Used by `ChatGamesManager` and checked by
 * `scripts/ChatGameCheck.kt`.
 */
object Unscramble {

    const val MIN_LENGTH = 4
    const val MAX_LENGTH = 16

    /** How many shuffles [scramble] tries before giving a word up. */
    const val MAX_SCRAMBLE_ATTEMPTS = 50

    /** How many words [pick] tries before giving the round up. */
    const val MAX_PICK_ATTEMPTS = 20

    /** Words a missing or empty `chat-games.unscramble.words` falls back to. */
    val DEFAULT_WORDS: List<String> = listOf(
        "DIAMOND", "EMERALD", "NETHERITE", "CREEPER", "ENDERMAN", "REDSTONE", "OBSIDIAN",
        "PICKAXE", "VILLAGER", "ENCHANTING", "BEACON", "SHULKER", "ELYTRA", "CRAFTING",
        "FURNACE", "BLAZE", "WITHER", "GHAST", "COPPER", "AMETHYST",
    )

    /** The validated word pool. [problems] lists every skipped entry, for the console. */
    data class Pool(val words: List<String>, val problems: List<String>)

    /**
     * Validates config entries: trimmed, upper case, letters A-Z only (so every
     * Java and Bedrock keyboard can type the answer), [MIN_LENGTH]..[MAX_LENGTH]
     * letters, at least two different letters (so it can be scrambled), no
     * duplicates, and no anagram of an earlier word (an anagram would make the
     * scramble have two right answers while only one wins). [blocked] (the
     * chat profanity filter) refuses words chat would censor, since a censored
     * answer could never match.
     */
    fun parsePool(entries: List<Any?>, blocked: (String) -> Boolean = { false }): Pool {
        val words = ArrayList<String>()
        val problems = ArrayList<String>()
        val seenLetters = HashMap<String, String>()
        for (raw in entries) {
            val entry = raw?.toString()?.trim().orEmpty()
            if (entry.isEmpty()) {
                problems += "blank entry skipped"
                continue
            }
            val word = entry.uppercase(Locale.ROOT)
            when {
                !word.all { it in 'A'..'Z' } -> problems += "'$entry' skipped: letters A-Z only"
                word.length < MIN_LENGTH || word.length > MAX_LENGTH ->
                    problems += "'$entry' skipped: must be $MIN_LENGTH-$MAX_LENGTH letters"
                word.toSet().size < 2 -> problems += "'$entry' skipped: can't be scrambled"
                word in words -> problems += "'$entry' skipped: duplicate"
                blocked(word) -> problems += "'$entry' skipped: blocked by the chat filter"
                else -> {
                    val key = letterKey(word)
                    val earlier = seenLetters[key]
                    if (earlier != null) {
                        problems += "'$entry' skipped: anagram of $earlier (two right answers)"
                    } else {
                        seenLetters[key] = word
                        words += word
                    }
                }
            }
        }
        return Pool(words, problems)
    }

    /**
     * Shuffles [word]'s letters until the result differs from the word and is
     * not another pool word nor [blocked] (so a scramble never spells something
     * the chat filter refuses), at most [MAX_SCRAMBLE_ATTEMPTS] times. Returns
     * null when no such scramble was found.
     */
    fun scramble(
        word: String,
        random: Random,
        pool: Collection<String> = emptyList(),
        blocked: (String) -> Boolean = { false },
    ): String? {
        if (word.toSet().size < 2) return null
        val letters = word.toCharArray()
        repeat(MAX_SCRAMBLE_ATTEMPTS) {
            for (i in letters.indices.reversed()) {
                val j = random.nextInt(i + 1)
                val t = letters[i]; letters[i] = letters[j]; letters[j] = t
            }
            val result = String(letters)
            if (result != word && result !in pool && !blocked(result)) return result
        }
        return null
    }

    /** One round: the word players must type and the scramble they see. */
    data class Round(val answer: String, val scrambled: String)

    /**
     * Picks a random word other than [last] (when the pool has another one) and
     * scrambles it. A word that can't be scrambled is skipped for another one,
     * at most [MAX_PICK_ATTEMPTS] times. Null when the pool is empty or nothing
     * could be scrambled.
     */
    fun pick(pool: List<String>, last: String?, random: Random, blocked: (String) -> Boolean = { false }): Round? {
        if (pool.isEmpty()) return null
        val candidates = if (pool.size > 1 && last != null) pool.filter { it != last } else pool
        if (candidates.isEmpty()) return null
        repeat(MAX_PICK_ATTEMPTS) {
            val word = candidates[random.nextInt(candidates.size)]
            val scrambled = scramble(word, random, pool, blocked)
            if (scrambled != null) return Round(word, scrambled)
        }
        return null
    }

    /**
     * Whether a public chat [message] answers [answer]: leading/trailing
     * whitespace ignored, case ignored, the whole word only (no partial answers,
     * nothing extra).
     */
    fun matches(message: String, answer: String): Boolean =
        message.trim().equals(answer, ignoreCase = true)

    private fun letterKey(word: String): String = String(word.toCharArray().also { it.sort() })
}
