package com.liam.joshymc.util

import org.bukkit.Material

object BlockUtil {

    val UNBREAKABLE = setOf(
        Material.BEDROCK,
        Material.BARRIER,
        Material.END_PORTAL_FRAME,
        Material.END_PORTAL,
        Material.NETHER_PORTAL,
        Material.COMMAND_BLOCK,
        Material.CHAIN_COMMAND_BLOCK,
        Material.REPEATING_COMMAND_BLOCK,
        Material.STRUCTURE_BLOCK,
        Material.STRUCTURE_VOID,
        Material.JIGSAW,
    )

    fun isMineable(material: Material): Boolean {
        if (material.isAir) return false
        if (UNBREAKABLE.contains(material)) return false
        return true
    }
}
