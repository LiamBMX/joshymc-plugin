package com.liam.joshymc.lifesteal.social

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import com.liam.joshymc.gui.GuiStyle
import com.liam.joshymc.lifesteal.social.SocialRules.Audience
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryClickEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.UUID

/**
 * The Friends menu (issue #1430). Pages are drawn from the database when opened; buttons only
 * carry the UUID (and, for requests, the creation time) of what they showed, and every click goes
 * back through [LifestealSocialManager], which re-checks the feature, world, permission and the
 * current rows, so a stale or copied button can't act on anything else. GuiManager already
 * cancels clicks on the menu and refuses shift/number-key/double-click moves into it.
 */
class FriendsMenu(private val plugin: Joshymc) {

    private val social get() = plugin.lifestealSocialManager

    enum class Filter(val label: String) { ALL("All friends"), ONLINE("Online"), OFFLINE("Offline") }

    private fun guard(player: Player): Boolean {
        val problem = social.friendProblem(player) ?: return true
        player.closeInventory()
        social.refuse(player, problem)
        return false
    }

    private fun text(s: String, color: NamedTextColor) = Component.text(s, color).decoration(TextDecoration.ITALIC, false)

    // ── Main page: the friend list ──────────────────────────

    fun openMain(player: Player, page: Int = 0, filter: Filter = Filter.ALL) {
        if (!guard(player)) return
        val now = System.currentTimeMillis()
        val all = social.store.friendsOf(player.uniqueId).map { it to social.showsOnline(player, it.friend) }
        val incoming = social.store.incoming(player.uniqueId, now)
        val outgoing = social.store.outgoing(player.uniqueId, now)
        val shown = all.filter {
            when (filter) {
                Filter.ALL -> true
                Filter.ONLINE -> it.second
                Filter.OFFLINE -> !it.second
            }
        }.sortedWith(compareByDescending<Pair<FriendStore.Friendship, Boolean>> { it.second }.thenBy { social.nameOf(it.first.friend).lowercase() })
        val pages = ((shown.size + PAGE - 1) / PAGE).coerceAtLeast(1)
        val current = page.coerceIn(0, pages - 1)
        val online = all.count { it.second }
        val gui = CustomGui(Component.text("Friends (${all.size}/${social.settings?.maxFriends ?: 0})"), 54)

        shown.drop(current * PAGE).take(PAGE).forEachIndexed { index, (friendship, isOnline) ->
            val uuid = friendship.friend
            val name = social.nameOf(uuid)
            val lore = listOf(
                if (isOnline) text("Online", NamedTextColor.GREEN) else text("Offline", NamedTextColor.GRAY),
                text("Friends since ${DATE.format(Date(friendship.since))}", NamedTextColor.GRAY),
                Component.empty(),
                text("Left-click: send a message", NamedTextColor.YELLOW),
                text("Right-click: view profile / remove", NamedTextColor.YELLOW),
            )
            gui.setItem(index, GuiStyle.skull(Bukkit.getOfflinePlayer(uuid), text(name, if (isOnline) NamedTextColor.GREEN else NamedTextColor.WHITE), lore)) { p, e ->
                if (!guard(p)) return@setItem
                // Stale button: the friendship must still exist.
                if (!social.store.areFriends(p.uniqueId, uuid)) {
                    GuiStyle.errorSound(p)
                    social.refuse(p, "You're no longer friends with $name.")
                    return@setItem openMain(p, current, filter)
                }
                if (e.isRightClick) openProfile(p, uuid) else suggestMessage(p, uuid)
            }
        }
        if (shown.isEmpty()) {
            gui.setItem(22, GuiStyle.item(Material.PAPER, text(if (all.isEmpty()) "No friends yet" else "Nobody here", NamedTextColor.GRAY),
                listOf(text("Add one with /friend add <player>", NamedTextColor.YELLOW))))
        }

        // Bottom row: requests, filter, paging, close, settings.
        gui.setItem(45, GuiStyle.item(Material.WRITABLE_BOOK, text("Incoming Requests (${incoming.size})", NamedTextColor.GOLD),
            listOf(text("Accept or deny friend requests", NamedTextColor.GRAY)))) { p, _ -> GuiStyle.clickSound(p); openRequests(p, incoming = true) }
        gui.setItem(46, GuiStyle.item(Material.PAPER, text("Outgoing Requests (${outgoing.size})", NamedTextColor.GOLD),
            listOf(text("Requests you sent", NamedTextColor.GRAY)))) { p, _ -> GuiStyle.clickSound(p); openRequests(p, incoming = false) }
        if (current > 0) gui.setItem(GuiStyle.prevSlot(54), GuiStyle.previousPage(current, pages)) { p, _ -> GuiStyle.pageSound(p); openMain(p, current - 1, filter) }
        gui.setItem(48, GuiStyle.item(Material.HOPPER, text("Showing: ${filter.label}", NamedTextColor.YELLOW),
            listOf(text("$online online, ${all.size - online} offline", NamedTextColor.GRAY), text("Click to change", NamedTextColor.GRAY)))) { p, _ ->
            GuiStyle.clickSound(p)
            openMain(p, 0, Filter.entries[(filter.ordinal + 1) % Filter.entries.size])
        }
        gui.setItem(GuiStyle.closeSlot(54), GuiStyle.close()) { p, _ -> p.closeInventory() }
        if (current < pages - 1) gui.setItem(GuiStyle.nextSlot(54), GuiStyle.nextPage(current, pages)) { p, _ -> GuiStyle.pageSound(p); openMain(p, current + 1, filter) }
        gui.setItem(53, GuiStyle.item(Material.COMPARATOR, text("Social Settings", NamedTextColor.AQUA),
            listOf(text("Privacy and notifications", NamedTextColor.GRAY)))) { p, _ -> GuiStyle.clickSound(p); openSettings(p) }
        plugin.guiManager.open(player, gui)
    }

    private fun suggestMessage(player: Player, friend: UUID) {
        player.closeInventory()
        val name = social.nameOf(friend)
        // Only suggests /msg: the message itself goes through the normal path (mutes, privacy, filters).
        social.send(player, Component.text("Click to message $name", NamedTextColor.YELLOW).clickEvent(ClickEvent.suggestCommand("/msg $name ")))
    }

    // ── Profile + removal ───────────────────────────────────

    private fun openProfile(player: Player, friend: UUID) {
        if (!guard(player)) return
        val name = social.nameOf(friend)
        val since = social.store.friendsOf(player.uniqueId).firstOrNull { it.friend == friend }?.since
            ?: return openMain(player)
        val isOnline = social.showsOnline(player, friend)
        val gui = CustomGui(Component.text("Friend: $name"), 27)
        gui.setItem(4, GuiStyle.skull(Bukkit.getOfflinePlayer(friend), text(name, NamedTextColor.GOLD), listOf(
            if (isOnline) text("Online", NamedTextColor.GREEN) else text("Offline", NamedTextColor.GRAY),
            text("Friends since ${DATE.format(Date(since))}", NamedTextColor.GRAY),
            text("Friends share no team, claim, teleport or storage access.", NamedTextColor.DARK_GRAY),
        )))
        gui.setItem(11, GuiStyle.item(Material.OAK_SIGN, text("Send Message", NamedTextColor.GREEN), listOf(text("Suggests /msg $name", NamedTextColor.GRAY)))) { p, _ ->
            if (guard(p)) suggestMessage(p, friend)
        }
        gui.setItem(15, GuiStyle.item(Material.RED_DYE, text("Remove Friend", NamedTextColor.RED), listOf(text("Asks for confirmation", NamedTextColor.GRAY)))) { p, _ ->
            GuiStyle.clickSound(p); openConfirmRemove(p, friend, since)
        }
        gui.setItem(GuiStyle.backSlot(27), GuiStyle.back()) { p, _ -> GuiStyle.clickSound(p); openMain(p) }
        gui.setItem(GuiStyle.closeSlot(27), GuiStyle.close()) { p, _ -> p.closeInventory() }
        plugin.guiManager.open(player, gui)
    }

    private fun openConfirmRemove(player: Player, friend: UUID, since: Long) {
        if (!guard(player)) return
        val name = social.nameOf(friend)
        val gui = CustomGui(Component.text("Remove $name?"), 27)
        gui.setItem(11, GuiStyle.item(Material.LIME_CONCRETE, text("Yes, remove $name", NamedTextColor.GREEN))) { p, _ ->
            if (!guard(p)) return@setItem
            // Only the same friendship the screen showed (not a newer one made after removing and re-adding).
            val current = social.store.friendsOf(p.uniqueId).firstOrNull { it.friend == friend }
            if (current == null || current.since != since) {
                GuiStyle.errorSound(p)
                social.refuse(p, "That friendship has already changed.")
                return@setItem openMain(p)
            }
            if (social.remove(p, friend)) GuiStyle.successSound(p) else GuiStyle.errorSound(p)
            openMain(p)
        }
        gui.setItem(15, GuiStyle.item(Material.RED_CONCRETE, text("Cancel", NamedTextColor.RED))) { p, _ -> GuiStyle.clickSound(p); openProfile(p, friend) }
        plugin.guiManager.open(player, gui)
    }

    // ── Requests ────────────────────────────────────────────

    fun openRequests(player: Player, incoming: Boolean, page: Int = 0) {
        if (!guard(player)) return
        val now = System.currentTimeMillis()
        val list = if (incoming) social.store.incoming(player.uniqueId, now) else social.store.outgoing(player.uniqueId, now)
        val pages = ((list.size + PAGE - 1) / PAGE).coerceAtLeast(1)
        val current = page.coerceIn(0, pages - 1)
        val gui = CustomGui(Component.text(if (incoming) "Incoming Requests" else "Outgoing Requests"), 54)
        list.drop(current * PAGE).take(PAGE).forEachIndexed { index, request ->
            val other = if (incoming) request.sender else request.target
            val name = social.nameOf(other)
            val left = ((request.expiresAt - now) / 1000).coerceAtLeast(0)
            val lore = buildList {
                add(text("Expires in ${LifestealSocialManager.formatSeconds(left)}", NamedTextColor.GRAY))
                add(Component.empty())
                if (incoming) {
                    add(text("Left-click: accept", NamedTextColor.GREEN))
                    add(text("Right-click: deny", NamedTextColor.RED))
                } else add(text("Click: cancel request", NamedTextColor.YELLOW))
            }
            gui.setItem(index, GuiStyle.skull(Bukkit.getOfflinePlayer(other), text(name, NamedTextColor.WHITE), lore)) { p, e ->
                if (!guard(p)) return@setItem
                // The store only acts on the request this button showed (same creation time, still pending).
                val ok = when {
                    !incoming -> social.store.pending(p.uniqueId, other, System.currentTimeMillis())?.createdAt == request.createdAt && social.cancel(p, other)
                    e.isRightClick -> social.deny(p, other, request.createdAt)
                    else -> social.accept(p, other, request.createdAt)
                }
                if (ok) GuiStyle.successSound(p) else GuiStyle.errorSound(p)
                openRequests(p, incoming, current)
            }
        }
        if (list.isEmpty()) gui.setItem(22, GuiStyle.item(Material.PAPER, text("No pending requests", NamedTextColor.GRAY)))
        gui.setItem(GuiStyle.backSlot(54), GuiStyle.back()) { p, _ -> GuiStyle.clickSound(p); openMain(p) }
        if (current > 0) gui.setItem(GuiStyle.prevSlot(54), GuiStyle.previousPage(current, pages)) { p, _ -> GuiStyle.pageSound(p); openRequests(p, incoming, current - 1) }
        gui.setItem(GuiStyle.closeSlot(54), GuiStyle.close()) { p, _ -> p.closeInventory() }
        if (current < pages - 1) gui.setItem(GuiStyle.nextSlot(54), GuiStyle.nextPage(current, pages)) { p, _ -> GuiStyle.pageSound(p); openRequests(p, incoming, current + 1) }
        plugin.guiManager.open(player, gui)
    }

    // ── Settings ────────────────────────────────────────────

    fun openSettings(player: Player) {
        if (!guard(player)) return
        val prefs = social.prefs(player.uniqueId)
        val gui = CustomGui(Component.text("Social Settings"), 27)

        fun audienceItem(
            slot: Int, material: Material, title: String, choices: List<Audience>,
            read: (SocialRules.Prefs) -> Audience, apply: (SocialRules.Prefs, Audience) -> SocialRules.Prefs,
        ) {
            val value = read(prefs)
            val lore = listOf(text("Current: ${value.label}", NamedTextColor.AQUA)) +
                choices.map { text((if (it == value) "> " else "  ") + it.label, if (it == value) NamedTextColor.GREEN else NamedTextColor.GRAY) } +
                listOf(Component.empty(), text("Click to change", NamedTextColor.YELLOW))
            gui.setItem(slot, GuiStyle.item(material, text(title, NamedTextColor.GOLD), lore)) { p, _ ->
                // Changes the clicking player's own settings, read fresh (never the value drawn here).
                if (social.updatePrefs(p) { current -> apply(current, SocialRules.next(read(current), choices)) } != null) GuiStyle.clickSound(p)
                else GuiStyle.errorSound(p)
                openSettings(p)
            }
        }
        audienceItem(10, Material.WRITABLE_BOOK, "Friend Requests From", SocialRules.REQUEST_CHOICES, { it.requestsFrom }) { p, a -> p.copy(requestsFrom = a) }
        audienceItem(11, Material.ENDER_EYE, "Online Status Visible To", SocialRules.STATUS_CHOICES, { it.statusVisibleTo }) { p, a -> p.copy(statusVisibleTo = a) }
        audienceItem(12, Material.OAK_SIGN, "Private Messages From", SocialRules.MESSAGE_CHOICES, { it.messagesFrom }) { p, a -> p.copy(messagesFrom = a) }
        gui.setItem(14, GuiStyle.toggle("Friend Join/Leave Notices", prefs.loginNotifications)) { p, _ ->
            if (social.updatePrefs(p) { it.copy(loginNotifications = !it.loginNotifications) } != null) GuiStyle.clickSound(p) else GuiStyle.errorSound(p)
            openSettings(p)
        }
        gui.setItem(15, GuiStyle.toggle("Friend Request Notices", prefs.requestNotifications)) { p, _ ->
            if (social.updatePrefs(p) { it.copy(requestNotifications = !it.requestNotifications) } != null) GuiStyle.clickSound(p) else GuiStyle.errorSound(p)
            openSettings(p)
        }
        gui.setItem(16, GuiStyle.item(Material.BOOK, text("Chat Channel: ${social.channelOf(player.uniqueId).label}", NamedTextColor.AQUA),
            listOf(text("Switch with /channel <global|team|alliance|local>", NamedTextColor.GRAY))))
        gui.setItem(GuiStyle.backSlot(27), GuiStyle.back()) { p, _ -> GuiStyle.clickSound(p); openMain(p) }
        gui.setItem(GuiStyle.closeSlot(27), GuiStyle.close()) { p, _ -> p.closeInventory() }
        plugin.guiManager.open(player, gui)
    }

    companion object {
        const val PAGE = 45
        private val DATE get() = SimpleDateFormat("MMM d, yyyy")
    }
}
