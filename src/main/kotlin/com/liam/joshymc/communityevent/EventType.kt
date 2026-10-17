package com.liam.joshymc.communityevent

import org.bukkit.Material

/**
 * The hosted community event types (issue #1180). [id] is the one identifier used by
 * `/event create <id>`, tab completion, config keys and session state.
 */
enum class EventType(val id: String, val displayName: String, val icon: Material, val description: String) {
    SPLEEF("spleef", "Spleef", Material.DIAMOND_SHOVEL, "Break the floor under your opponents."),
    SHARKS("sharks", "Sharks and Minnows", Material.TROPICAL_FISH, "Minnows cross, sharks tag them."),
    SUMO("sumo", "Sumo", Material.SLIME_BALL, "Knock your opponent off the platform."),
    CPVP("cpvp", "Crystal PvP", Material.END_CRYSTAL, "Crystal PvP matches."),
    MOBS("mobs", "Mob Waves", Material.ZOMBIE_HEAD, "Survive wave after wave of mobs.");

    companion object {
        val ids: List<String> = entries.map { it.id }

        fun fromId(id: String): EventType? = entries.firstOrNull { it.id.equals(id, ignoreCase = true) }
    }
}
