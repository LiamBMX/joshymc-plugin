package com.liam.joshymc.crate

/**
 * What every crate GUI says about one reward (issue #1514), kept Bukkit-free so the wording,
 * quantities, chances and hidden-reward rules are checked without a server.
 *
 * The manager turns these lines into Adventure components; the item itself (material, model,
 * custom name, its own lore) always comes from the reward's template, so custom items keep
 * their look.
 */
object CrateRewardText {

    /** Where the reward is shown. */
    enum class Context {
        /** The Preview GUI: every detail, chance included. */
        PREVIEW,
        /** A Select / Select 3 picker or its confirmation: the player chooses, so no chance. */
        PICK,
        /** A Spin / Pulse reel before the reveal. */
        REEL,
        /** The revealed / claimed reward. */
        RESULT,
    }

    enum class Kind { RARITY, QUANTITY, CHANCE, STATUS }

    data class Line(val kind: Kind, val label: String, val value: String, val good: Boolean = true)

    data class Info(
        val name: String,
        val amount: Int,
        val weight: Int,
        /** Sum of every reward's weight in the crate (hidden ones included). */
        val totalWeight: Int,
        val rarity: CrateRarity.Tier?,
        /** crates.yml `hidden: true`: shown as a mystery until it is won or picked. */
        val hidden: Boolean,
        /** Null when the reward can be won right now, else why not (shown to the player). */
        val unavailableReason: String?,
        /** Select / Select 3 crate: players choose, the weights only matter for mass-opening. */
        val pickCrate: Boolean,
    )

    const val MYSTERY_NAME = "Mystery Reward"

    /** True when [context] keeps a hidden reward's identity secret. Picking needs to see it. */
    fun conceals(info: Info, context: Context): Boolean =
        info.hidden && (context == Context.PREVIEW || context == Context.REEL)

    fun name(info: Info, context: Context): String = if (conceals(info, context)) MYSTERY_NAME else info.name

    fun lines(info: Info, context: Context): List<Line> {
        val concealed = conceals(info, context)
        val out = mutableListOf<Line>()
        info.rarity?.let { out += Line(Kind.RARITY, "Rarity", it.label) }
        out += Line(Kind.QUANTITY, "Quantity", if (concealed) "???" else "x${info.amount}")
        if (context == Context.PREVIEW) {
            val label = if (info.pickCrate) "Mass-open chance" else "Chance"
            out += Line(Kind.CHANCE, label, CrateOdds.describe(info.weight, info.totalWeight))
            out += if (info.unavailableReason == null) Line(Kind.STATUS, "Status", "Obtainable")
            else Line(Kind.STATUS, "Status", "Unavailable: ${info.unavailableReason}", good = false)
        }
        return out
    }
}
