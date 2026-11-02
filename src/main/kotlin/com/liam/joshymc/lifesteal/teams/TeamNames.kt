package com.liam.joshymc.lifesteal.teams

import java.util.Locale

/**
 * Lifesteal team names (issue #1346), Bukkit-free.
 *
 * A name is letters, digits and `_` (no spaces, so `/team accept <team>` and `/team info <team>`
 * take it as one argument and Bedrock players can type it). The player's capitalization is kept
 * for display; every comparison uses [canonical] (lower case), and the database keeps the
 * canonical form UNIQUE among active teams.
 *
 * Protected identities: a name may not be, or (for the longer words) contain, a staff or server
 * identity, also when spelled with `_` or look-alike digits (`Adm1n`, `St4ff`, `J0shy_MC`).
 */
object TeamNames {

    private val ALLOWED = Regex("[A-Za-z0-9_]+")

    /** Never allowed anywhere in a name (after [normalize]). */
    val PROTECTED_WORDS: Set<String> = setOf(
        "admin", "staff", "moderator", "joshy", "console", "official", "operator", "developer", "mojang", "anticheat",
    )

    /** Not allowed as the whole name (after [normalize]); words that are fine inside others ("observer"). */
    val PROTECTED_NAMES: Set<String> = setOf(
        "owner", "owners", "helper", "helpers", "server", "system", "support", "manager", "managers", "minecraft", "microsoft",
        "mod", "mods", "op", "ops", "dev", "devs", "srmod", "jrmod", "trainee", "lifesteal", "survival",
        "team", "teams", "none", "null", "everyone", "here", "all", "nobody", "unknown", "notch", "herobrine",
    )

    sealed interface Check {
        /** Valid: [display] as typed (trimmed), [canonical] for comparisons. */
        data class Valid(val display: String, val canonical: String) : Check
        data class Invalid(val reason: String) : Check
    }

    fun canonical(name: String): String = name.trim().lowercase(Locale.ROOT)

    /** Lower case, `_` removed, look-alike digits read as the letters they imitate. */
    fun normalize(name: String): String = buildString {
        for (ch in name.lowercase(Locale.ROOT)) {
            when (ch) {
                '_' -> {}
                '0' -> append('o')
                '1' -> append('i')
                '3' -> append('e')
                '4' -> append('a')
                '5' -> append('s')
                '7' -> append('t')
                '8' -> append('b')
                else -> append(ch)
            }
        }
    }

    /**
     * Whether [name] impersonates a protected identity, or one of [extraReserved] (config,
     * matched as a whole name the same way).
     */
    fun isProtected(name: String, extraReserved: Collection<String> = emptyList()): Boolean {
        val normalized = normalize(name)
        // "1" also imitates "l" (Ju1y, 0fficia1): check both readings.
        val readings = setOf(normalized, normalize(name.replace('1', 'l')))
        for (reading in readings) {
            if (reading in PROTECTED_NAMES) return true
            if (PROTECTED_WORDS.any { it in reading }) return true
            if (extraReserved.any { normalize(it) == reading }) return true
        }
        return false
    }

    /**
     * Validates a requested name. [isProfane] is the server's banned-word filter
     * (`ProfanityFilter.contains`), passed in so this stays Bukkit-free.
     */
    fun validate(
        raw: String,
        minLength: Int,
        maxLength: Int,
        extraReserved: Collection<String> = emptyList(),
        isProfane: (String) -> Boolean = { false },
    ): Check {
        val name = raw.trim()
        if (name.isEmpty()) return Check.Invalid("Choose a team name.")
        if (name.length < minLength || name.length > maxLength) {
            return Check.Invalid("Team names must be $minLength to $maxLength characters long.")
        }
        if (!ALLOWED.matches(name)) return Check.Invalid("Team names may only use letters, numbers and _.")
        if (name.all { it == '_' }) return Check.Invalid("Team names need at least one letter or number.")
        if (isProtected(name, extraReserved)) return Check.Invalid("That name is reserved. Choose another one.")
        // The filter matches whole words; also try the name with _ as spaces and with no _ at all.
        if (isProfane(name) || isProfane(name.replace('_', ' ')) || isProfane(name.replace("_", ""))) {
            return Check.Invalid("That team name isn't allowed. Choose another one.")
        }
        return Check.Valid(name, canonical(name))
    }
}
