package com.liam.joshymc.link

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.inventory.InventoryView
import org.bukkit.inventory.ItemStack

object LinkGui {

    const val GUI_TITLE_PREFIX = "Link Discord"
    const val CODE_SLOT = 13        // middle center
    const val STATUS_SLOT = 11      // middle left (opposite of cancel)
    const val CONFIRM_SLOT = 11     // replaces status when confirmed
    const val CANCEL_SLOT = 15      // middle right
    val CONFIRM_MATERIAL = Material.LIME_WOOL

    private val ACCENT = TextColor.color(0x55FFFF)
    private val SUCCESS = TextColor.color(0x55FF55)

    private fun filler(): ItemStack {
        val item = ItemStack(Material.BLACK_STAINED_GLASS_PANE)
        val meta = item.itemMeta!!
        meta.displayName(Component.empty())
        item.itemMeta = meta
        return item
    }

    private fun noItalic(c: Component): Component = c.decoration(TextDecoration.ITALIC, false)

    fun isLinkGui(view: InventoryView): Boolean =
        PlainTextComponentSerializer.plainText().serialize(view.title()).startsWith(GUI_TITLE_PREFIX)

    fun openCodeGui(player: Player, pending: PendingLink) {
        val code = LinkCodes.display(pending.code)
        val inv = Bukkit.createInventory(null, 27,
            noItalic(Component.text("Link Discord", ACCENT).decoration(TextDecoration.BOLD, true))
        )

        // Code item — top center
        val codeItem = ItemStack(Material.NAME_TAG)
        val codeMeta = codeItem.itemMeta!!
        codeMeta.displayName(noItalic(
            Component.text("Code: ", NamedTextColor.GRAY)
                .append(Component.text(code, ACCENT).decoration(TextDecoration.BOLD, true))
        ))
        codeMeta.lore(listOf(
            Component.empty(),
            noItalic(Component.text("DM this code to the bot.", NamedTextColor.GRAY)),
        ))
        codeItem.itemMeta = codeMeta
        inv.setItem(CODE_SLOT, codeItem)

        // Status item — middle center
        val statusItem = ItemStack(Material.CLOCK)
        val statusMeta = statusItem.itemMeta!!
        statusMeta.displayName(noItalic(
            Component.text("Waiting...", NamedTextColor.YELLOW)
        ))
        statusMeta.lore(listOf(
            Component.empty(),
            noItalic(Component.text("Send the code in the bot's DMs.", NamedTextColor.GRAY)),
            noItalic(Component.text("This page will update automatically.", NamedTextColor.DARK_GRAY)),
        ))
        statusItem.itemMeta = statusMeta
        inv.setItem(STATUS_SLOT, statusItem)

        // Cancel button — bottom right-center
        val cancelItem = ItemStack(Material.RED_WOOL)
        val cancelMeta = cancelItem.itemMeta!!
        cancelMeta.displayName(noItalic(
            Component.text("Cancel", NamedTextColor.RED).decoration(TextDecoration.BOLD, true)
        ))
        cancelItem.itemMeta = cancelMeta
        inv.setItem(CANCEL_SLOT, cancelItem)

        // Fill
        val fill = filler()
        for (i in 0 until 27) {
            if (inv.getItem(i) == null) inv.setItem(i, fill)
        }

        player.openInventory(inv)
        player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_PLING, 0.5f, 1.5f)
    }

    /**
     * Shows the Discord account that sent the code and the Confirm button. Main thread only.
     * Reopens the Link GUI if the player closed it, so it never writes into another inventory.
     */
    fun showConfirmation(player: Player, pending: PendingLink) {
        val claim = pending.claim ?: return
        if (!isLinkGui(player.openInventory)) openCodeGui(player, pending)
        val inv = player.openInventory.topInventory
        val code = LinkCodes.display(pending.code)

        // Update code item
        val codeItem = ItemStack(Material.NAME_TAG)
        val codeMeta = codeItem.itemMeta!!
        codeMeta.displayName(noItalic(
            Component.text("Code: ", NamedTextColor.GRAY)
                .append(Component.text(code, ACCENT).decoration(TextDecoration.BOLD, true))
        ))
        codeMeta.lore(listOf(
            Component.empty(),
            noItalic(Component.text("Code matched!", SUCCESS)),
        ))
        codeItem.itemMeta = codeMeta
        inv.setItem(CODE_SLOT, codeItem)

        // Update status → show Discord user
        val statusItem = ItemStack(Material.PLAYER_HEAD)
        val statusMeta = statusItem.itemMeta!!
        statusMeta.displayName(noItalic(
            Component.text("Account Found", SUCCESS).decoration(TextDecoration.BOLD, true)
        ))
        statusMeta.lore(listOf(
            Component.empty(),
            noItalic(Component.text("Discord: ", NamedTextColor.GRAY)
                .append(Component.text(claim.displayName, ACCENT))),
            noItalic(Component.text("Username: ", NamedTextColor.GRAY)
                .append(Component.text("@" + claim.username, ACCENT))),
            Component.empty(),
            noItalic(Component.text("Is this you?", NamedTextColor.YELLOW)),
        ))
        statusItem.itemMeta = statusMeta
        inv.setItem(STATUS_SLOT, statusItem)

        // Add confirm button — bottom left-center
        val confirmItem = ItemStack(CONFIRM_MATERIAL)
        val confirmMeta = confirmItem.itemMeta!!
        confirmMeta.displayName(noItalic(
            Component.text("Confirm", NamedTextColor.GREEN).decoration(TextDecoration.BOLD, true)
        ))
        confirmItem.itemMeta = confirmMeta
        inv.setItem(CONFIRM_SLOT, confirmItem)

        player.updateInventory()
        player.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.2f)
    }
}
