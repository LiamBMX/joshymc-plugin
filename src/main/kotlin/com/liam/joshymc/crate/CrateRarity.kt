package com.liam.joshymc.crate

/**
 * Presentation-only reward rarity labels (issue #1514), kept Bukkit-free.
 *
 * A reward shows a rarity only when crates.yml gives it one (`rarity: <id>`); the tiers (label
 * and colour) come from `config.yml > crates.rarities`. A rarity never changes a weight, a draw
 * or a delivery, and is never computed from a reward's chance: an unset or unknown rarity just
 * shows no rarity line.
 */
object CrateRarity {

    data class Tier(val id: String, val label: String, val color: Int)

    /** One `crates.rarities.<id>` entry as read from the config (values not yet checked). */
    data class Raw(val id: String, val label: String?, val color: String?)

    data class Settings(
        val enabled: Boolean,
        /** Tier id -> tier, in config order. */
        val tiers: Map<String, Tier>,
        val problems: List<String>,
    ) {
        /** The tier a reward's `rarity:` value names, or null (unset, unknown or rarities off). */
        fun tier(id: String?): Tier? = if (!enabled || id == null) null else tiers[normalizeId(id)]

        /** True when [id] is set but names no tier, so the loader can warn about it once. */
        fun unknown(id: String?): Boolean = enabled && id != null && id.isNotBlank() && tiers[normalizeId(id)] == null
    }

    /** Suggested tiers from the issue: Common gray .. Mythic red. Only used when the section is missing. */
    val DEFAULTS: List<Tier> = listOf(
        Tier("common", "Common", 0xAAAAAA),
        Tier("uncommon", "Uncommon", 0x55FF55),
        Tier("rare", "Rare", 0x5599FF),
        Tier("epic", "Epic", 0xAA55FF),
        Tier("legendary", "Legendary", 0xFFAA00),
        Tier("mythic", "Mythic", 0xFF5555),
    )

    val DEFAULT_SETTINGS = Settings(true, DEFAULTS.associateBy { it.id }, emptyList())

    const val MAX_LABEL = 32
    private val ID = Regex("[a-z0-9_-]{1,32}")

    fun normalizeId(id: String): String = id.trim().lowercase()

    /** `#RRGGBB` or `RRGGBB` -> 0xRRGGBB, null otherwise. */
    fun parseColor(text: String?): Int? {
        val hex = text?.trim()?.removePrefix("#") ?: return null
        if (hex.length != 6 || !hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return hex.toInt(16)
    }

    /**
     * Reads the tiers. [raw] null = the section is missing, which uses [DEFAULTS]. A broken entry
     * (bad id, colour or label) is skipped and reported; the other tiers still load.
     */
    fun parse(enabled: Boolean, raw: List<Raw>?): Settings {
        if (raw == null) return Settings(enabled, DEFAULT_SETTINGS.tiers, emptyList())
        val problems = mutableListOf<String>()
        val tiers = LinkedHashMap<String, Tier>()
        for (entry in raw) {
            val id = normalizeId(entry.id)
            if (!ID.matches(id)) {
                problems += "rarities.${entry.id}: id must be 1-32 letters, digits, '_' or '-'"
                continue
            }
            if (id in tiers) {
                problems += "rarities.${entry.id}: duplicate of '$id'"
                continue
            }
            val color = parseColor(entry.color)
            if (color == null) {
                problems += "rarities.$id.color: '${entry.color}' is not a #RRGGBB colour"
                continue
            }
            val label = entry.label?.trim().orEmpty().ifEmpty { id.replaceFirstChar { it.uppercase() } }
            if (label.length > MAX_LABEL) {
                problems += "rarities.$id.label: longer than $MAX_LABEL characters"
                continue
            }
            tiers[id] = Tier(id, label, color)
        }
        return Settings(enabled, tiers, problems)
    }
}
