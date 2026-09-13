package com.liam.joshymc.item.impl

import com.liam.joshymc.item.CustomItem
import com.liam.joshymc.util.LoreBuilder
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material

/**
 * The 6 Trainee Mode hotbar tools. Every tool is either read-only or
 * self-only — behavior lives in TraineeModeManager, which never exposes
 * punishment, inventory-editing, or player-teleport capability here.
 */
private fun toolName(text: String, color: TextColor): Component =
    Component.text(text, color).decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false)

class TraineeInspector : CustomItem() {
    override val id = "trainee_inspector"
    override val material = Material.BOOK
    override val displayName = toolName("Player Inspector", TextColor.color(0x55FFFF))
    override val lore = LoreBuilder.build(
        type = "Trainee Mode Tool",
        description = listOf("Right-click a player to view", "read-only moderation info."),
        usage = "Information only — nothing can be edited."
    )
}

class TraineeInvsee : CustomItem() {
    override val id = "trainee_invsee"
    override val material = Material.CHEST
    override val displayName = toolName("Inventory Inspector", TextColor.color(0xFFAA55))
    override val lore = LoreBuilder.build(
        type = "Trainee Mode Tool",
        description = listOf("Right-click a player to view their", "inventory, armor, and offhand."),
        usage = "Completely read-only — nothing can be taken."
    )
}

class TraineeTeleport : CustomItem() {
    override val id = "trainee_tp"
    override val material = Material.COMPASS
    override val displayName = toolName("Teleport to Player", TextColor.color(0xDD55FF))
    override val lore = LoreBuilder.build(
        type = "Trainee Mode Tool",
        description = listOf("Right-click to open a list of", "online players."),
        usage = "Teleports only you — never another player."
    )
}

class TraineeHistory : CustomItem() {
    override val id = "trainee_history"
    override val material = Material.PAPER
    override val displayName = toolName("Punishment History", TextColor.color(0xFFFF55))
    override val lore = LoreBuilder.build(
        type = "Trainee Mode Tool",
        description = listOf("Right-click a player to view their", "punishment history."),
        usage = "View-only — you cannot ban, mute, or warn."
    )
}

class TraineeStaffChat : CustomItem() {
    override val id = "trainee_staffchat"
    override val material = Material.STICK
    override val displayName = toolName("Staff Chat", TextColor.color(0x55FF55))
    override val lore = LoreBuilder.build(
        type = "Trainee Mode Tool",
        description = listOf("Right-click to toggle the existing", "Staff Chat system."),
        usage = "Same as /staffchat or /sc."
    )
}

class TraineeReports : CustomItem() {
    override val id = "trainee_reports"
    override val material = Material.REDSTONE_TORCH
    override val displayName = toolName("Reports", TextColor.color(0xFF5555))
    override val lore = LoreBuilder.build(
        type = "Trainee Mode Tool",
        description = listOf("Right-click to view recent player", "reports and teleport to the reported player."),
        usage = "View-only — you cannot close or delete reports."
    )
}
