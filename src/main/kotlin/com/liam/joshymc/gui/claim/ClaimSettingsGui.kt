package com.liam.joshymc.gui.claim

import com.liam.joshymc.gui.GuiStyle
import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import com.liam.joshymc.manager.ClaimManager
import com.liam.joshymc.manager.ClaimManager.PublicFlag
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * Per-claim settings sub-GUI (issue #612), opened from the claim management
 * panel or `/claim settings`. The public permissions (issue #1106) decide what
 * players without claim access may do here; PvP, TNT and team sharing are the
 * older claim-wide toggles.
 */
object ClaimSettingsGui {

    private data class FlagEntry(val slot: Int, val flag: PublicFlag, val icon: Material, val name: String, val description: String)

    private val FLAGS = listOf(
        FlagEntry(11, PublicFlag.MOB_COMBAT, Material.ZOMBIE_HEAD, "Mob Combat", "Attack mobs. Villagers and pets stay protected."),
        FlagEntry(12, PublicFlag.BUILD, Material.BRICKS, "Build", "Place blocks."),
        FlagEntry(13, PublicFlag.BREAK, Material.CRACKED_STONE_BRICKS, "Break", "Break blocks."),
        FlagEntry(14, PublicFlag.PROJECTILES, Material.BOW, "Projectiles", "Shoot bows, crossbows, tridents and throwables."),
        FlagEntry(15, PublicFlag.FLY, Material.FEATHER, "Fly", "Fly here if they can already fly."),
        FlagEntry(16, PublicFlag.BUCKETS, Material.WATER_BUCKET, "Buckets", "Fill and empty buckets."),
        FlagEntry(20, PublicFlag.DOORS, Material.OAK_DOOR, "Doors", "Open doors, trapdoors and fence gates."),
        FlagEntry(21, PublicFlag.SWITCHES, Material.LEVER, "Buttons & Levers", "Press buttons and flip levers."),
        FlagEntry(22, PublicFlag.CONTAINERS, Material.CHEST, "Containers", "Open chests, barrels, furnaces and hoppers."),
        FlagEntry(23, PublicFlag.VILLAGER_TRADING, Material.EMERALD, "Villager Trading", "Trade with villagers."),
        FlagEntry(24, PublicFlag.VEHICLES, Material.OAK_BOAT, "Vehicles", "Ride boats and minecarts."),
        FlagEntry(25, PublicFlag.ENDER_PEARLS, Material.ENDER_PEARL, "Ender Pearls", "Teleport into or around here with Ender Pearls.")
    )

    fun open(plugin: Joshymc, player: Player, claimId: Int) {
        val claim = plugin.claimManager.getClaimById(claimId)
        if (claim == null || !plugin.claimManager.canManageClaim(player, claim)) {
            ClaimMainGui.open(plugin, player)
            return
        }

        val gui = CustomGui(Component.text("Claim Settings", NamedTextColor.GOLD), 54)
        for (slot in 0..53) gui.setItem(slot, GuiStyle.filler())

        gui.setItem(4, GuiStyle.item(
            Material.OAK_SIGN, Component.text("Public Permissions", NamedTextColor.GOLD),
            listOf(
                Component.empty(),
                Component.text("What players you haven't trusted", NamedTextColor.GRAY),
                Component.text("may do in claim #${claim.id}.", NamedTextColor.GRAY),
                Component.text("Trusted players keep full access.", NamedTextColor.DARK_GRAY)
            )
        ))

        gui.setItem(10, toggleItem(Material.SKELETON_SKULL, "PvP", "Players may damage each other here.", claim.pvpEnabled)) { p, _ ->
            withClaim(plugin, p, claimId) { plugin.claimManager.setClaimPvp(it.id, !it.pvpEnabled) }
        }

        for (entry in FLAGS) {
            val enabled = entry.flag in claim.publicFlags
            gui.setItem(entry.slot, toggleItem(entry.icon, entry.name, entry.description, enabled)) { p, _ ->
                withClaim(plugin, p, claimId) { plugin.claimManager.setPublicFlag(it.id, entry.flag, entry.flag !in it.publicFlags) }
            }
        }

        gui.setItem(30, toggleItem(Material.TNT, "TNT", "TNT lit here can damage this claim.", claim.tntEnabled)) { p, _ ->
            withClaim(plugin, p, claimId) { plugin.claimManager.setClaimTnt(it.id, !it.tntEnabled) }
        }

        val isOwner = claim.ownerUuid == player.uniqueId
        val onTeam = plugin.teamManager.getPlayerTeam(player.uniqueId) != null
        if (isOwner && onTeam) {
            gui.setItem(32, GuiStyle.item(
                if (claim.teamName != null) Material.LIME_DYE else Material.GRAY_DYE,
                Component.text("Team Sharing", NamedTextColor.YELLOW),
                listOf(
                    Component.empty(),
                    Component.text("Status: ", NamedTextColor.GRAY).append(
                        if (claim.teamName != null) Component.text("Shared with ${claim.teamName}", NamedTextColor.AQUA) else Component.text("Personal", NamedTextColor.GRAY)
                    ),
                    Component.text("Click to toggle.", NamedTextColor.GRAY)
                )
            )) { p, _ ->
                withClaim(plugin, p, claimId) {
                    if (it.teamName != null) plugin.claimManager.assignToPersonal(p, it) else plugin.claimManager.assignToTeam(p, it)
                }
            }
        } else {
            gui.setItem(32, GuiStyle.item(
                Material.GRAY_DYE, Component.text("Team Sharing", NamedTextColor.DARK_GRAY),
                listOf(Component.empty(), Component.text("Only the owner (while on a team) can toggle this.", NamedTextColor.RED))
            ))
        }

        gui.setItem(GuiStyle.backSlot(54), GuiStyle.back()) { p, _ -> ClaimManagementGui.open(plugin, p, claimId) }
        gui.setItem(GuiStyle.closeSlot(54), GuiStyle.close()) { p, _ -> p.closeInventory() }

        plugin.guiManager.open(player, gui)
    }

    /**
     * Re-resolves the claim on every click so a claim deleted (or handed to
     * someone else) while the menu is open is never edited through a stale copy.
     */
    private fun withClaim(plugin: Joshymc, player: Player, claimId: Int, action: (ClaimManager.Claim) -> Unit) {
        val claim = plugin.claimManager.getClaimById(claimId)
        if (claim == null || !plugin.claimManager.canManageClaim(player, claim)) {
            player.closeInventory()
            plugin.commsManager.send(player, Component.text("That claim no longer exists or isn't yours.", NamedTextColor.RED))
            return
        }
        action(claim)
        open(plugin, player, claimId)
    }

    private fun toggleItem(icon: Material, name: String, description: String, enabled: Boolean): ItemStack {
        val item = GuiStyle.item(
            icon,
            Component.text(name, GuiStyle.stateColor(enabled)),
            listOf(
                Component.empty(),
                Component.text(description, NamedTextColor.GRAY),
                Component.empty(),
                Component.text("Status: ", NamedTextColor.GRAY).append(GuiStyle.stateLine(enabled)),
                Component.text(if (enabled) "Click to disable." else "Click to enable.", NamedTextColor.YELLOW)
            )
        )
        item.editMeta { it.setEnchantmentGlintOverride(enabled) }
        return item
    }
}
