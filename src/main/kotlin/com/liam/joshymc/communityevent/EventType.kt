package com.liam.joshymc.communityevent

import org.bukkit.Material

/**
 * The hosted community event types (issue #1180). [id] is the one identifier used by
 * `/event create <id>`, tab completion, config keys and session state.
 */
enum class EventType(
    val id: String,
    val displayName: String,
    val icon: Material,
    val description: String,
    /** Other names `/event start <type>` accepts (issue #1248), already normalized (see [normalize]). */
    val aliases: List<String> = emptyList(),
) {
    SPLEEF("spleef", "Spleef", Material.DIAMOND_SHOVEL, "Break the floor under your opponents."),
    SHARKS("sharks", "Sharks and Minnows", Material.TROPICAL_FISH, "Minnows cross, sharks tag them.", listOf("sharksandminnows", "sharksminnows")),
    SUMO("sumo", "Sumo", Material.SLIME_BALL, "Knock your opponent off the platform."),
    CPVP("cpvp", "Crystal PvP", Material.END_CRYSTAL, "Crystal PvP matches.", listOf("crystalpvp")),
    MOBS("mobs", "Mob Waves", Material.ZOMBIE_HEAD, "Survive wave after wave of mobs.", listOf("mobwaves"));

    /**
     * Every normalized name of this type, most specific first: id, display name, aliases.
     * `/event start` matches arena names against these (issue #1248).
     */
    val names: List<String> get() = (listOf(normalize(id), normalize(displayName)) + aliases).distinct()

    companion object {
        val ids: List<String> = entries.map { it.id }

        fun fromId(id: String): EventType? = entries.firstOrNull { it.id.equals(id, ignoreCase = true) }

        /** [fromId], or any display name/alias, ignoring case, spaces, `_` and `-` (issue #1248). */
        fun fromName(name: String): EventType? {
            val key = normalize(name)
            if (key.isEmpty()) return null
            return entries.firstOrNull { key in it.names }
        }

        /** Lowercase letters and digits only, so "Mob_Waves", "mob-waves" and "Mob Waves" compare equal. */
        fun normalize(name: String): String = name.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }
    }
}
