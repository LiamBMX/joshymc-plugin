package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import com.liam.joshymc.gui.GuiStyle
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.permissions.Permission
import org.bukkit.permissions.PermissionDefault
import java.io.File
import java.util.UUID

class ChatTagManager(private val plugin: Joshymc) {

    data class ChatTag(
        val id: String,
        val category: String,
        val display: String,    // Legacy color coded display like "&6[MVP] "
        val permission: String? // null = free for all
    )

    private val tags = mutableMapOf<String, ChatTag>()
    private val categories = mutableListOf<String>()
    private val playerTags = mutableMapOf<UUID, String>() // UUID -> tag ID
    private val unlockedTags = mutableMapOf<UUID, MutableSet<String>>() // UUID -> unlocked tag IDs (issue #597)

    fun start() {
        // Save default tags.yml if missing; otherwise merge in any new
        // categories / tags from the bundled defaults that the user's
        // saved file is missing. Existing entries are left untouched so
        // admin tweaks (custom tags, perm renames) survive.
        val file = plugin.configFile("tags.yml")
        if (!file.exists()) {
            plugin.saveResource("tags.yml", false)
        } else {
            mergeMissingTagsFromDefaults(file)
        }

        // Load tags
        val config = YamlConfiguration.loadConfiguration(file)
        tags.clear()
        categories.clear()

        val tagsSection = config.getConfigurationSection("tags") ?: run {
            plugin.logger.warning("[ChatTags] tags.yml has no 'tags:' section — GUI will be empty.")
            return
        }
        for (categoryId in tagsSection.getKeys(false)) {
            // Only the approved normal categories (plus the dynamic voucher-only
            // "Special Chat Tags" category) may load — issue #602 trimmed the
            // category list, and this also prunes any leftover unapproved
            // categories still sitting in an admin's on-disk tags.yml.
            if (categoryId != VOUCHER_CATEGORY && categoryId !in APPROVED_CATEGORIES) continue
            categories.add(categoryId)
            val catSection = tagsSection.getConfigurationSection(categoryId) ?: continue
            for (tagId in catSection.getKeys(false)) {
                val tagSection = catSection.getConfigurationSection(tagId) ?: continue
                val display = tagSection.getString("display", "&7[$tagId] ") ?: "&7[$tagId] "
                val permission = tagSection.getString("permission")
                tags[tagId] = ChatTag(tagId, categoryId, display, permission)
            }
        }

        // Load player selections from DB
        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS player_tags (
                uuid TEXT PRIMARY KEY,
                tag_id TEXT NOT NULL
            )
        """.trimIndent())

        playerTags.clear()
        val rows = plugin.databaseManager.query("SELECT uuid, tag_id FROM player_tags") { rs ->
            UUID.fromString(rs.getString("uuid")) to rs.getString("tag_id")
        }
        for ((uuid, tagId) in rows) {
            if (tags.containsKey(tagId)) {
                playerTags[uuid] = tagId
            }
        }

        // Chat Tag voucher unlocks (issue #597) — per-player ownership of tags that
        // were redeemed from a physical voucher rather than granted via permission.
        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS chat_tag_unlocks (
                uuid TEXT NOT NULL,
                tag_id TEXT NOT NULL,
                PRIMARY KEY (uuid, tag_id)
            )
        """.trimIndent())

        unlockedTags.clear()
        val unlockRows = plugin.databaseManager.query("SELECT uuid, tag_id FROM chat_tag_unlocks") { rs ->
            UUID.fromString(rs.getString("uuid")) to rs.getString("tag_id")
        }
        for ((uuid, tagId) in unlockRows) {
            unlockedTags.getOrPut(uuid) { mutableSetOf() }.add(tagId)
        }

        // Voucher-tag permissions are dynamic (minted per tag id) and never live in
        // plugin.yml, so they must be (re-)registered with Bukkit's PluginManager on
        // every load. Without this, an unregistered permission node defaults to
        // PermissionDefault.OP, meaning any operator automatically "has" it and
        // canUse() reports the tag as already owned before it was ever redeemed —
        // the voucher then appears completely unclaimable to whoever is testing it.
        for (tag in tags.values) {
            if (isVoucherTag(tag)) registerVoucherPermission(tag.permission)
        }

        plugin.logger.info("[ChatTags] Loaded ${tags.size} tags in ${categories.size} categories, ${playerTags.size} player selections.")
    }

    private fun registerVoucherPermission(permission: String?) {
        if (permission == null) return
        val pm = Bukkit.getPluginManager()
        if (pm.getPermission(permission) == null) {
            pm.addPermission(Permission(permission, PermissionDefault.FALSE))
        }
    }

    // ── Public API ──────────────────────────────────

    fun getTag(id: String): ChatTag? = tags[id]
    fun getAllTags(): Collection<ChatTag> = tags.values
    fun getCategories(): List<String> = categories.toList()
    fun getTagsByCategory(category: String): List<ChatTag> = tags.values.filter { it.category == category }

    fun getPlayerTag(player: Player): ChatTag? {
        val tagId = playerTags[player.uniqueId] ?: return null
        return tags[tagId]
    }

    fun getPlayerTagDisplay(player: Player): String {
        val tag = getPlayerTag(player) ?: return ""
        return tag.display
    }

    fun setPlayerTag(uuid: UUID, tagId: String?) {
        if (tagId == null) {
            playerTags.remove(uuid)
            plugin.databaseManager.execute("DELETE FROM player_tags WHERE uuid = ?", uuid.toString())
        } else {
            playerTags[uuid] = tagId
            plugin.databaseManager.execute(
                "INSERT OR REPLACE INTO player_tags (uuid, tag_id) VALUES (?, ?)",
                uuid.toString(), tagId
            )
        }
    }

    fun canUse(player: Player, tag: ChatTag): Boolean {
        if (tag.permission == null) return true
        if (player.hasPermission(tag.permission)) return true
        return hasUnlocked(player.uniqueId, tag.id)
    }

    // ── Voucher unlocks (issue #597) ──────────────────

    fun hasUnlocked(uuid: UUID, tagId: String): Boolean = unlockedTags[uuid]?.contains(tagId) == true

    /** Permanently grants [tagId] to [uuid]. Returns true if this is a new unlock, false if already owned. */
    fun unlockTag(uuid: UUID, tagId: String): Boolean {
        if (hasUnlocked(uuid, tagId)) return false
        val rows = plugin.databaseManager.executeUpdate(
            "INSERT OR IGNORE INTO chat_tag_unlocks (uuid, tag_id) VALUES (?, ?)",
            uuid.toString(), tagId
        )
        if (rows <= 0) return false
        unlockedTags.getOrPut(uuid) { mutableSetOf() }.add(tagId)
        return true
    }

    /** True for tags created through `/voucher create` (issue #597) — the only tags physical Chat Tag vouchers may target. */
    fun isVoucherTag(tag: ChatTag): Boolean = tag.category == VOUCHER_CATEGORY

    fun getVoucherTagIds(): List<String> = tags.values.filter { isVoucherTag(it) }.map { it.id }

    fun getVoucherTag(id: String): ChatTag? = tags[id]?.takeIf { isVoucherTag(it) }

    /**
     * Creates a brand-new, voucher-only Chat Tag. It's locked behind a unique
     * per-tag permission node that nobody holds by default, so the only way to
     * obtain it is redeeming the matching physical voucher via
     * [ChatTagVoucherManager] (which calls [unlockTag]). Returns null if
     * [rawId] normalizes to nothing, the display is blank, or the id already
     * exists in any category.
     */
    fun createVoucherTag(rawId: String, rawDisplay: String): ChatTag? {
        val id = rawId.lowercase().replace(Regex("[^a-z0-9_]"), "")
        if (id.isBlank() || tags.containsKey(id)) return null

        val trimmedDisplay = rawDisplay.trim()
        if (trimmedDisplay.isBlank()) return null
        val display = "$trimmedDisplay "

        val permission = "$VOUCHER_PERMISSION_PREFIX$id"
        val tag = ChatTag(id, VOUCHER_CATEGORY, display, permission)

        tags[id] = tag
        if (VOUCHER_CATEGORY !in categories) categories.add(VOUCHER_CATEGORY)
        registerVoucherPermission(permission)
        persistVoucherTag(id, display, permission)
        return tag
    }

    private fun persistVoucherTag(id: String, display: String, permission: String) {
        val file = plugin.configFile("tags.yml")
        val config = YamlConfiguration.loadConfiguration(file)
        config.set("tags.$VOUCHER_CATEGORY.$id.display", display)
        config.set("tags.$VOUCHER_CATEGORY.$id.permission", permission)
        ChatTagDefaults.clearVoucherRemoved(config, id)
        try {
            config.save(file)
        } catch (e: Exception) {
            plugin.logger.warning("[ChatTags] Failed to persist voucher tag '$id': ${e.message}")
        }
    }

    enum class DeleteVoucherTagResult { NOT_FOUND, NOT_VOUCHER_TAG, DELETED }

    /**
     * Deletes a Chat Tag created via [createVoucherTag] (`/voucher tag delete`, issue #606).
     * Refuses to touch normal built-in tags — only [isVoucherTag] tags qualify. Any player
     * with the tag equipped is safely unequipped, and voucher ownership is revoked so
     * un-redeemed physical vouchers for this id fail safely (see [ChatTagVoucherManager.redeem],
     * which already treats an unknown [getTag] as an invalid voucher).
     */
    fun deleteVoucherTag(id: String): DeleteVoucherTagResult {
        val tag = tags[id] ?: return DeleteVoucherTagResult.NOT_FOUND
        if (!isVoucherTag(tag)) return DeleteVoucherTagResult.NOT_VOUCHER_TAG

        tags.remove(id)
        tag.permission?.let { Bukkit.getPluginManager().removePermission(it) }

        val equippedBy = playerTags.filterValues { it == id }.keys.toList()
        for (uuid in equippedBy) playerTags.remove(uuid)
        if (equippedBy.isNotEmpty()) {
            plugin.databaseManager.execute("DELETE FROM player_tags WHERE tag_id = ?", id)
        }

        for (unlocked in unlockedTags.values) unlocked.remove(id)
        plugin.databaseManager.execute("DELETE FROM chat_tag_unlocks WHERE tag_id = ?", id)

        removeVoucherTagFromFile(id)

        for (uuid in equippedBy) {
            val online = Bukkit.getPlayer(uuid) ?: continue
            plugin.commsManager.send(online, Component.text("Your equipped Chat Tag was removed by an admin.", NamedTextColor.YELLOW))
        }

        return DeleteVoucherTagResult.DELETED
    }

    private fun removeVoucherTagFromFile(id: String) {
        val file = plugin.configFile("tags.yml")
        val config = YamlConfiguration.loadConfiguration(file)
        config.set("tags.$VOUCHER_CATEGORY.$id", null)
        // A bundled voucher tag (issue #1391) would otherwise be merged back in on the next start.
        ChatTagDefaults.markVoucherRemoved(config, id)
        try {
            config.save(file)
        } catch (e: Exception) {
            plugin.logger.warning("[ChatTags] Failed to remove voucher tag '$id' from tags.yml: ${e.message}")
        }
    }

    // ── GUI (issue #1393) ────────────────────────────
    //
    // /tags → main menu (27 slots: Bundled Chat Tags 12, Special Chat Tags 14, nothing else)
    //       → Bundled Chat Tags (54 slots, one icon per bundle) → a bundle's tags (54 slots)
    //       → Special Chat Tags (54 slots, the voucher category's tags)
    // Layout and classification live in the Bukkit-free ChatTagMenus. Every click re-reads the
    // tag by id and re-checks canUse, so a reload, a deleted tag or a revoked permission between
    // opening a page and clicking it can never equip anything the player doesn't own.

    private fun plain(text: String, color: NamedTextColor): Component =
        Component.text(text, color).decoration(TextDecoration.ITALIC, false)

    private fun title(text: String): Component =
        Component.text(text, NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false)

    private fun countLine(label: String, value: String): Component =
        plain(label, NamedTextColor.GRAY).append(Component.text(value, NamedTextColor.WHITE))

    private fun unlockedCount(player: Player, tags: List<ChatTag>): Int = tags.count { canUse(player, it) }

    /** Same entry point as before (`/tags`, `/cosmetics`): the two-category main menu. */
    fun openCategoryMenu(player: Player) = openMainMenu(player)

    fun openMainMenu(player: Player) {
        val gui = CustomGui(title("Chat Tags"), ChatTagMenus.MAIN_SIZE)
        val equippedKind = getPlayerTag(player)?.let { ChatTagMenus.kindOf(it.category) }

        val bundles = ChatTagMenus.bundles(categories)
        val bundleTags = bundles.flatMap { getTagsByCategory(it) }
        val bundled = ItemStack(Material.BUNDLE)
        bundled.editMeta { meta ->
            meta.displayName(title("Bundled Chat Tags"))
            val lore = mutableListOf(
                plain("Browse themed collections of chat tags available", NamedTextColor.GRAY),
                plain("through the JoshyMC store.", NamedTextColor.GRAY),
                Component.empty(),
                countLine("Bundles: ", "${bundles.size}"),
                countLine("Unlocked: ", "${unlockedCount(player, bundleTags)}/${bundleTags.size}"),
            )
            if (equippedKind == ChatTagMenus.Kind.BUNDLED) {
                lore += plain("Your equipped tag is here", NamedTextColor.GREEN)
                meta.setEnchantmentGlintOverride(true)
            }
            lore += Component.empty()
            lore += plain("Click to browse", NamedTextColor.YELLOW)
            meta.lore(lore)
        }
        gui.setItem(ChatTagMenus.BUNDLED_SLOT, bundled) { p, _ ->
            GuiStyle.clickSound(p)
            openBundleMenu(p, 0)
        }

        val specialTags = getTagsByCategory(VOUCHER_CATEGORY)
        val special = ItemStack(Material.NAME_TAG)
        special.editMeta { meta ->
            meta.displayName(title("Special Chat Tags"))
            val lore = mutableListOf(
                plain("Browse individually unlockable and exclusive", NamedTextColor.GRAY),
                plain("chat tags.", NamedTextColor.GRAY),
                Component.empty(),
                countLine("Tags: ", "${specialTags.size}"),
                countLine("Unlocked: ", "${unlockedCount(player, specialTags)}/${specialTags.size}"),
            )
            if (equippedKind == ChatTagMenus.Kind.SPECIAL) {
                lore += plain("Your equipped tag is here", NamedTextColor.GREEN)
                meta.setEnchantmentGlintOverride(true)
            }
            lore += Component.empty()
            lore += plain("Click to browse", NamedTextColor.YELLOW)
            meta.lore(lore)
        }
        gui.setItem(ChatTagMenus.SPECIAL_SLOT, special) { p, _ ->
            GuiStyle.clickSound(p)
            openTagList(p, VOUCHER_CATEGORY, 0)
        }

        plugin.guiManager.open(player, gui)
        player.playSound(player.location, Sound.BLOCK_CHEST_OPEN, 0.5f, 1.2f)
    }

    /** Bundled Chat Tags: one icon per bundle category, loaded from tags.yml. */
    fun openBundleMenu(player: Player, page: Int) {
        val bundles = ChatTagMenus.bundles(categories)
        val currentPage = ChatTagMenus.clampPage(page, bundles.size)
        val totalPages = ChatTagMenus.totalPages(bundles.size)
        val shown = ChatTagMenus.pageOf(bundles, currentPage)
        val gui = CustomGui(title("Bundled Chat Tags"), ChatTagMenus.BROWSE_SIZE)
        val equippedCategory = getPlayerTag(player)?.category

        for ((slot, category) in ChatTagMenus.slots(shown.size).zip(shown)) {
            val bundleTags = getTagsByCategory(category)
            val name = ChatTagMenus.displayName(category)
            val item = ItemStack(BUNDLE_ICONS[category] ?: Material.BOOK)
            item.editMeta { meta ->
                meta.displayName(title(name))
                val lore = mutableListOf(
                    plain("A themed collection of $name chat tags.", NamedTextColor.GRAY),
                    Component.empty(),
                    countLine("Tags: ", "${bundleTags.size}"),
                    countLine("Unlocked: ", "${unlockedCount(player, bundleTags)}/${bundleTags.size}"),
                )
                if (category == equippedCategory) {
                    lore += plain("Your equipped tag is here", NamedTextColor.GREEN)
                    meta.setEnchantmentGlintOverride(true)
                }
                lore += Component.empty()
                lore += plain("Click to browse", NamedTextColor.YELLOW)
                meta.lore(lore)
            }
            gui.setItem(slot, item) { p, _ ->
                GuiStyle.clickSound(p)
                openTagList(p, category, 0)
            }
        }

        addNavigation(gui, player, currentPage, totalPages, "Back to Chat Tags", { openMainMenu(it) }) { p, to ->
            openBundleMenu(p, to)
        }
        plugin.guiManager.open(player, gui)
    }

    /** One bundle's tags, or Special Chat Tags for the voucher category. */
    fun openTagList(player: Player, category: String, page: Int) {
        val categoryTags = getTagsByCategory(category)
        val currentPage = ChatTagMenus.clampPage(page, categoryTags.size)
        val totalPages = ChatTagMenus.totalPages(categoryTags.size)
        val shown = ChatTagMenus.pageOf(categoryTags, currentPage)
        val kind = ChatTagMenus.kindOf(category)
        val gui = CustomGui(title(ChatTagMenus.displayName(category)), ChatTagMenus.BROWSE_SIZE)
        val currentTag = getPlayerTag(player)?.id

        for ((slot, tag) in ChatTagMenus.slots(shown.size).zip(shown)) {
            val tagId = tag.id
            gui.setItem(slot, tagIcon(player, tag, kind, tagId == currentTag)) { p, event ->
                onTagClick(p, tagId, category, currentPage, event.isRightClick)
            }
        }

        val backLabel: String
        val back: (Player) -> Unit
        if (kind == ChatTagMenus.Kind.SPECIAL) {
            backLabel = "Back to Chat Tags"
            back = { openMainMenu(it) }
        } else {
            // Back to the bundle page this bundle sits on.
            val bundlePage = ChatTagMenus.bundles(categories).indexOf(category).coerceAtLeast(0) / ChatTagMenus.PAGE_SIZE
            backLabel = "Back to Bundled Chat Tags"
            back = { openBundleMenu(it, bundlePage) }
        }
        addNavigation(gui, player, currentPage, totalPages, backLabel, back) { p, to -> openTagList(p, category, to) }
        plugin.guiManager.open(player, gui)
    }

    private fun tagIcon(player: Player, tag: ChatTag, kind: ChatTagMenus.Kind, isEquipped: Boolean): ItemStack {
        val owned = canUse(player, tag)
        val item = ItemStack(when {
            isEquipped -> Material.LIME_DYE
            owned -> Material.GRAY_DYE
            else -> Material.RED_DYE
        })
        item.editMeta { meta ->
            meta.displayName(plugin.commsManager.parseLegacy(tag.display.trimEnd()).decoration(TextDecoration.ITALIC, false))
            val lore = mutableListOf<Component>()
            lore += plain("Preview:", NamedTextColor.GRAY)
            lore += previewLine(player, tag).decoration(TextDecoration.ITALIC, false)
            lore += Component.empty()
            when {
                isEquipped -> {
                    lore += plain("Unlocked", NamedTextColor.GREEN)
                    lore += plain("Equipped", NamedTextColor.GREEN)
                }
                owned -> {
                    lore += plain("Unlocked", NamedTextColor.GREEN)
                    lore += plain("Click to equip", NamedTextColor.YELLOW)
                }
                else -> {
                    lore += plain("Locked", NamedTextColor.RED)
                    if (kind == ChatTagMenus.Kind.SPECIAL) {
                        lore += plain("Redeem this tag's voucher to unlock it.", NamedTextColor.GRAY)
                    }
                }
            }
            lore += plain("Right-click to preview in chat", NamedTextColor.DARK_GRAY)
            meta.lore(lore)
            if (isEquipped) meta.setEnchantmentGlintOverride(true)
        }
        return item
    }

    /** `[Tag] PlayerName: Hello!` with the tag's real configured formatting and the player's name. */
    private fun previewLine(player: Player, tag: ChatTag): Component =
        Component.empty().color(NamedTextColor.WHITE)
            .append(plugin.commsManager.parseLegacy(tag.display.trimEnd()))
            .append(Component.text(" ${player.name}", NamedTextColor.WHITE))
            .append(Component.text(": ", NamedTextColor.GRAY))
            .append(Component.text(ChatTagMenus.PREVIEW_MESSAGE, NamedTextColor.WHITE))

    /** A preview sent only to [player]: never broadcast and never equips. */
    private fun sendPreview(player: Player, tag: ChatTag) {
        plugin.commsManager.send(player, Component.text("Preview (only you can see this):", NamedTextColor.GRAY))
        player.sendMessage(previewLine(player, tag))
    }

    private fun onTagClick(player: Player, tagId: String, category: String, page: Int, rightClick: Boolean) {
        // Re-read by id: the page may predate a reload or a deleted tag.
        val tag = getTag(tagId)
        if (tag == null || tag.category != category) {
            GuiStyle.errorSound(player)
            plugin.commsManager.send(player, Component.text("That tag is no longer available.", NamedTextColor.RED))
            openTagList(player, category, page)
            return
        }
        if (rightClick) {
            GuiStyle.clickSound(player)
            sendPreview(player, tag)
            return
        }
        if (!canUse(player, tag)) {
            GuiStyle.errorSound(player)
            plugin.commsManager.send(player, Component.text("You haven't unlocked this tag yet.", NamedTextColor.RED))
            sendPreview(player, tag)
            return
        }
        if (getPlayerTag(player)?.id == tag.id) {
            GuiStyle.clickSound(player)
            return
        }
        setPlayerTag(player.uniqueId, tag.id)
        player.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 1.5f)
        plugin.commsManager.send(player,
            Component.text("Tag set to ", NamedTextColor.GREEN).append(plugin.commsManager.parseLegacy(tag.display.trimEnd()))
        )
        openTagList(player, category, page)
    }

    /** Bottom row of every 54-slot page: Previous 45, Back 49, Remove Current Tag 51, Next 53. */
    private fun addNavigation(
        gui: CustomGui,
        player: Player,
        page: Int,
        totalPages: Int,
        backLabel: String,
        back: (Player) -> Unit,
        turn: (Player, Int) -> Unit,
    ) {
        if (page > 0) {
            gui.setItem(ChatTagMenus.PREV_SLOT, GuiStyle.previousPage(page, totalPages)) { p, _ ->
                GuiStyle.pageSound(p)
                turn(p, page - 1)
            }
        }
        gui.setItem(ChatTagMenus.BACK_SLOT, GuiStyle.back(backLabel)) { p, _ ->
            GuiStyle.clickSound(p)
            back(p)
        }
        addRemoveTagButton(gui, player, ChatTagMenus.REMOVE_SLOT) { p -> turn(p, page) }
        if (page < totalPages - 1) {
            gui.setItem(ChatTagMenus.NEXT_SLOT, GuiStyle.nextPage(page, totalPages)) { p, _ ->
                GuiStyle.pageSound(p)
                turn(p, page + 1)
            }
        }
    }

    /** Unequips the current tag (its unlock stays) and shows what's equipped right now. */
    private fun addRemoveTagButton(gui: CustomGui, player: Player, slot: Int, refresh: (Player) -> Unit) {
        val current = getPlayerTag(player)
        val removeItem = ItemStack(Material.BARRIER)
        removeItem.editMeta { meta ->
            meta.displayName(
                Component.text("Remove Current Tag", NamedTextColor.RED)
                    .decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false)
            )
            val equippedLine = if (current != null) {
                Component.text("Equipped: ", NamedTextColor.GRAY).append(plugin.commsManager.parseLegacy(current.display.trimEnd()))
            } else {
                Component.text("No tag equipped", NamedTextColor.GRAY)
            }
            meta.lore(listOf(
                equippedLine.decoration(TextDecoration.ITALIC, false),
                plain("Your unlocked tags stay unlocked.", NamedTextColor.GRAY),
                Component.empty(),
                plain("Click to unequip your tag", NamedTextColor.YELLOW)
            ))
        }
        gui.setItem(slot, removeItem) { p, _ ->
            if (getPlayerTag(p) == null) {
                GuiStyle.errorSound(p)
                plugin.commsManager.send(p, Component.text("You don't have a tag equipped.", NamedTextColor.RED))
                return@setItem
            }
            setPlayerTag(p.uniqueId, null)
            plugin.commsManager.send(p, Component.text("Chat tag removed.", NamedTextColor.GREEN))
            p.playSound(p.location, Sound.ENTITY_ITEM_PICKUP, 0.5f, 1.0f)
            refresh(p)
        }
    }

    /**
     * Merge any tag categories / tag entries that exist in the bundled
     * `tags.yml` resource but are missing from the user's saved file.
     * Existing entries are left untouched so admin tweaks survive. Catches
     * the "GUI is completely empty" symptom on servers whose on-disk
     * `tags.yml` was generated by an older plugin version that had no
     * categories.
     */
    private fun mergeMissingTagsFromDefaults(userFile: File) {
        val defaultStream = plugin.getResource("tags.yml") ?: return
        val defaults = YamlConfiguration.loadConfiguration(defaultStream.bufferedReader())
        val userCfg = YamlConfiguration.loadConfiguration(userFile)

        if (com.liam.joshymc.util.ConfigUtil.looksLikeParseFailure(userFile, userCfg)) {
            plugin.logger.severe("[ChatTags] tags.yml failed to load (invalid YAML) — the existing file has been preserved and was NOT overwritten. Fix the syntax error and reload.")
            return
        }

        val merged = ChatTagDefaults.merge(defaults, userCfg)
        val categoriesAdded = merged.categoriesAdded
        val tagsAdded = merged.tagsAdded
        if (categoriesAdded > 0 || tagsAdded > 0) {
            try {
                com.liam.joshymc.util.ConfigUtil.backup(userFile, plugin.logger, "ChatTags")
                userCfg.save(userFile)
                plugin.logger.info("[ChatTags] Merged $categoriesAdded new categor${if (categoriesAdded == 1) "y" else "ies"} and $tagsAdded new tag${if (tagsAdded == 1) "" else "s"} from bundled defaults.")
            } catch (e: Exception) {
                plugin.logger.warning("[ChatTags] Failed to save merged tags.yml: ${e.message}")
            }
        }
    }

    companion object {
        private const val VOUCHER_CATEGORY = ChatTagDefaults.VOUCHER_CATEGORY
        private const val VOUCHER_PERMISSION_PREFIX = ChatTagDefaults.VOUCHER_PERMISSION_PREFIX

        // Icons for the bundles in the Bundled Chat Tags menu; unlisted bundles show a book.
        private val BUNDLE_ICONS = mapOf(
            "skill" to Material.DIAMOND_SWORD,
            "vibe" to Material.NOTE_BLOCK,
            "nature" to Material.OAK_SAPLING,
            "cosmic" to Material.END_STONE,
            "animal" to Material.BONE,
            "meme" to Material.PAPER,
            "music" to Material.JUKEBOX,
            "pirate" to Material.TRIDENT,
            "military" to Material.IRON_CHESTPLATE,
            "mythical" to Material.DRAGON_EGG,
            "cyberpunk" to Material.REDSTONE,
        )

        // Normal (non-Special) Chat Tag categories approved for the shop — issue #602.
        private val APPROVED_CATEGORIES = setOf(
            "vibe", "nature", "animal", "music", "pirate",
            "skill", "military", "meme", "mythical", "cyberpunk", "cosmic"
        )
    }
}
