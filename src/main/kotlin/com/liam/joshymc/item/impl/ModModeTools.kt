package com.liam.joshymc.item.impl

import com.liam.joshymc.Joshymc
import com.liam.joshymc.item.CustomItem
import com.liam.joshymc.util.LoreBuilder
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material

/**
 * The 9 Moderator Mode hotbar tools. Materials/names are configurable via
 * `config.yml > modmode.tools.<key>` so the server owner can re-skin the
 * loadout without touching code; behavior always lives in ModModeManager.
 */
private fun toolMaterial(plugin: Joshymc, key: String, default: Material): Material {
    val raw = plugin.config.getString("modmode.tools.$key.material") ?: return default
    return try { Material.valueOf(raw.uppercase()) } catch (_: IllegalArgumentException) { default }
}

private fun toolName(plugin: Joshymc, key: String, default: String, color: TextColor): Component {
    val raw = plugin.config.getString("modmode.tools.$key.name")
    val base = if (raw != null) plugin.commsManager.parseLegacy(raw) else Component.text(default, color)
    return base.decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false)
}

fun vanishLore(vanished: Boolean): List<Component> {
    val stateLine = if (vanished) {
        Component.text("Vanish: ", NamedTextColor.GRAY).append(Component.text("ON", NamedTextColor.GREEN)).decoration(TextDecoration.ITALIC, false)
    } else {
        Component.text("Vanish: ", NamedTextColor.GRAY).append(Component.text("OFF", NamedTextColor.RED)).decoration(TextDecoration.ITALIC, false)
    }
    return listOf(
        Component.empty(),
        stateLine,
        Component.empty(),
        Component.text("Right-click to toggle your vanish state.", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false),
        Component.text("Moderator Mode stays active either way.", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)
    )
}

fun spectatorLore(active: Boolean): List<Component> {
    val stateLine = if (active) {
        Component.text("Spectator: ", NamedTextColor.GRAY).append(Component.text("ON", NamedTextColor.GREEN)).decoration(TextDecoration.ITALIC, false)
    } else {
        Component.text("Spectator: ", NamedTextColor.GRAY).append(Component.text("OFF", NamedTextColor.RED)).decoration(TextDecoration.ITALIC, false)
    }
    return listOf(
        Component.empty(),
        stateLine,
        Component.empty(),
        Component.text("Right-click to toggle Spectator Mode.", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false)
    )
}

class ModModePunish(plugin: Joshymc) : CustomItem() {
    override val id = "modmode_punish"
    override val material = toolMaterial(plugin, "punish", Material.MACE)
    override val displayName = toolName(plugin, "punish", "Punish", TextColor.color(0xFF5555))
    override val lore = LoreBuilder.build(
        type = "Moderator Mode Tool",
        description = listOf("Right-click a player to open the", "punishment panel for that player."),
        usage = "Respects your existing punishment permissions."
    )
}

class ModModeRandomTp(plugin: Joshymc) : CustomItem() {
    override val id = "modmode_rtp"
    override val material = toolMaterial(plugin, "rtp", Material.ENDER_PEARL)
    override val displayName = toolName(plugin, "rtp", "Random TP", TextColor.color(0xDD55FF))
    override val lore = LoreBuilder.build(
        type = "Moderator Mode Tool",
        description = listOf("Right-click to teleport to a", "random eligible online player."),
        usage = "You remain vanished after teleporting."
    )
}

class ModModeFreeze(plugin: Joshymc) : CustomItem() {
    override val id = "modmode_freeze"
    override val material = toolMaterial(plugin, "freeze", Material.PACKED_ICE)
    override val displayName = toolName(plugin, "freeze", "Freeze / Unfreeze", TextColor.color(0x55FFFF))
    override val lore = LoreBuilder.build(
        type = "Moderator Mode Tool",
        description = listOf("Right-click a player to toggle", "their frozen state."),
        usage = "Requires moderation permission."
    )
}

class ModModeTotemGuard(plugin: Joshymc) : CustomItem() {
    override val id = "modmode_totemguard"
    override val material = toolMaterial(plugin, "totemguard", Material.TOTEM_OF_UNDYING)
    override val displayName = toolName(plugin, "totemguard", "Totem Guard", TextColor.color(0xFFAA00))
    override val hasGlint = true
    override val lore = LoreBuilder.build(
        type = "Moderator Mode Tool",
        description = listOf("Right-click a player to view their", "anticheat flags and held items."),
        usage = "Never triggers real totem-save effects."
    )
}

class ModModeVanish(plugin: Joshymc) : CustomItem() {
    override val id = "modmode_vanish"
    override val material = toolMaterial(plugin, "vanish", Material.SLIME_BALL)
    override val displayName = toolName(plugin, "vanish", "Vanish", TextColor.color(0x55FF55))
    override val lore = vanishLore(false)
}

class ModModeInvsee(plugin: Joshymc) : CustomItem() {
    override val id = "modmode_invsee"
    override val material = toolMaterial(plugin, "invsee", Material.ENDER_CHEST)
    override val displayName = toolName(plugin, "invsee", "Invsee", TextColor.color(0xAA55FF))
    override val lore = LoreBuilder.build(
        type = "Moderator Mode Tool",
        description = listOf("Right-click a player to inspect", "their inventory."),
        usage = "Read-only unless you can edit inventories."
    )
}

class ModModeSpectator(plugin: Joshymc) : CustomItem() {
    override val id = "modmode_spectator"
    override val material = toolMaterial(plugin, "spectator", Material.ENDER_EYE)
    override val displayName = toolName(plugin, "spectator", "Spectator", TextColor.color(0x55AAFF))
    override val lore = spectatorLore(false)
}

class ModModeEcsee(plugin: Joshymc) : CustomItem() {
    override val id = "modmode_ecsee"
    override val material = toolMaterial(plugin, "ecsee", Material.CHEST)
    override val displayName = toolName(plugin, "ecsee", "ECSee", TextColor.color(0xFFAA55))
    override val lore = LoreBuilder.build(
        type = "Moderator Mode Tool",
        description = listOf("Right-click a player to inspect", "their Ender Chest."),
        usage = "Read-only unless you can edit inventories."
    )
}

class ModModeVault(plugin: Joshymc) : CustomItem() {
    override val id = "modmode_vault"
    override val material = toolMaterial(plugin, "vault", Material.BARREL)
    override val displayName = toolName(plugin, "vault", "Player Vault", TextColor.color(0x55FFAA))
    override val lore = LoreBuilder.build(
        type = "Moderator Mode Tool",
        description = listOf("Right-click a player to inspect", "their player vault(s)."),
        usage = "Read-only unless you can edit inventories."
    )
}
