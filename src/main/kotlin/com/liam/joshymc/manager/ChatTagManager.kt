package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
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

        plugin.logger.info("[ChatTags] Loaded ${tags.size} tags in ${categories.size} categories, ${playerTags.size} player selections.")
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
        persistVoucherTag(id, display, permission)
        return tag
    }

    private fun persistVoucherTag(id: String, display: String, permission: String) {
        val file = plugin.configFile("tags.yml")
        val config = YamlConfiguration.loadConfiguration(file)
        config.set("tags.$VOUCHER_CATEGORY.$id.display", display)
        config.set("tags.$VOUCHER_CATEGORY.$id.permission", permission)
        try {
            config.save(file)
        } catch (e: Exception) {
            plugin.logger.warning("[ChatTags] Failed to persist voucher tag '$id': ${e.message}")
        }
    }

    // ── GUI ──────────────────────────────────────────

    /** "Vibe" for a normal category, "Special Chat Tags" for the voucher-only category (issue #602). */
    private fun categoryDisplayName(category: String): String =
        if (category == VOUCHER_CATEGORY) "Special Chat Tags" else category.replaceFirstChar { it.uppercase() }

    fun openCategoryMenu(player: Player) {
        val size = 54
        val gui = CustomGui(
            Component.text("Chat Tags", NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false),
            size
        )

        // Fill + border
        val filler = ItemStack(Material.BLACK_STAINED_GLASS_PANE).apply { editMeta { it.displayName(Component.empty()) } }
        val border = ItemStack(Material.YELLOW_STAINED_GLASS_PANE).apply { editMeta { it.displayName(Component.empty()) } }
        for (i in 0 until size) gui.inventory.setItem(i, filler)
        for (i in 0..8) { gui.inventory.setItem(i, border); gui.inventory.setItem(size - 9 + i, border) }

        // Category icons
        val catMaterials = mapOf(
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
            VOUCHER_CATEGORY to Material.NAME_TAG
        )

        val slots = mutableListOf<Int>()
        for (row in 1..3) for (col in 1..7) slots.add(row * 9 + col)

        for ((index, category) in categories.withIndex()) {
            if (index >= slots.size) break
            val slot = slots[index]
            val tagCount = getTagsByCategory(category).size
            val mat = catMaterials[category] ?: Material.BOOK

            val item = ItemStack(mat)
            item.editMeta { meta ->
                meta.displayName(
                    Component.text(categoryDisplayName(category), NamedTextColor.GOLD)
                        .decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false)
                )
                meta.lore(listOf(
                    Component.empty(),
                    Component.text("  $tagCount tags", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                    Component.empty(),
                    Component.text("  Click to browse", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false)
                ))
            }

            val capturedCat = category
            gui.setItem(slot, item) { p, _ -> openTagList(p, capturedCat, 0) }
        }

        // Remove tag button
        val removeItem = ItemStack(Material.BARRIER)
        removeItem.editMeta { meta ->
            meta.displayName(Component.text("Remove Tag", NamedTextColor.RED).decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false))
        }
        gui.setItem(49, removeItem) { p, _ ->
            setPlayerTag(p.uniqueId, null)
            plugin.commsManager.send(p, Component.text("Chat tag removed.", NamedTextColor.GREEN))
            p.playSound(p.location, Sound.ENTITY_ITEM_PICKUP, 0.5f, 1.0f)
            p.closeInventory()
        }

        plugin.guiManager.open(player, gui)
        player.playSound(player.location, Sound.BLOCK_CHEST_OPEN, 0.5f, 1.2f)
    }

    fun openTagList(player: Player, category: String, page: Int) {
        val categoryTags = getTagsByCategory(category)
        val pageSize = 28
        val startIndex = page * pageSize
        val pageTags = categoryTags.drop(startIndex).take(pageSize)
        val totalPages = maxOf(1, (categoryTags.size + pageSize - 1) / pageSize)

        val currentTag = getPlayerTag(player)?.id
        val size = 54
        val gui = CustomGui(
            Component.text(categoryDisplayName(category), NamedTextColor.GOLD)
                .decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false),
            size
        )

        val filler = ItemStack(Material.BLACK_STAINED_GLASS_PANE).apply { editMeta { it.displayName(Component.empty()) } }
        val border = ItemStack(Material.YELLOW_STAINED_GLASS_PANE).apply { editMeta { it.displayName(Component.empty()) } }
        for (i in 0 until size) gui.inventory.setItem(i, filler)
        for (i in 0..8) { gui.inventory.setItem(i, border); gui.inventory.setItem(size - 9 + i, border) }

        val slots = mutableListOf<Int>()
        for (row in 1..4) for (col in 1..7) slots.add(row * 9 + col)

        for ((index, tag) in pageTags.withIndex()) {
            if (index >= slots.size) break
            val slot = slots[index]
            val canUse = canUse(player, tag)
            val isEquipped = tag.id == currentTag

            val mat = when {
                isEquipped -> Material.LIME_DYE
                canUse -> Material.GRAY_DYE
                else -> Material.RED_DYE
            }

            val item = ItemStack(mat)
            item.editMeta { meta ->
                meta.displayName(plugin.commsManager.parseLegacy(tag.display.trimEnd())
                    .decoration(TextDecoration.ITALIC, false))
                val lore = mutableListOf<Component>()
                lore.add(Component.empty())
                val isSpecial = category == VOUCHER_CATEGORY
                when {
                    isEquipped -> {
                        if (isSpecial) lore.add(Component.text("  Owned", NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false))
                        lore.add(Component.text("  Equipped!", NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false))
                    }
                    canUse -> {
                        if (isSpecial) lore.add(Component.text("  Owned", NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false))
                        lore.add(Component.text("  Click to equip", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false))
                    }
                    else -> {
                        lore.add(Component.text("  Locked", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false))
                        if (isSpecial) lore.add(Component.text("  Redeem the corresponding voucher to unlock", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false))
                    }
                }
                meta.lore(lore)
            }

            val capturedTag = tag
            gui.setItem(slot, item) { p, _ ->
                if (!canUse(p, capturedTag)) {
                    plugin.commsManager.send(p, Component.text("You don't have permission to use this tag.", NamedTextColor.RED))
                    return@setItem
                }
                setPlayerTag(p.uniqueId, capturedTag.id)
                p.playSound(p.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 1.5f)
                plugin.commsManager.send(p,
                    Component.text("Tag set to ", NamedTextColor.GREEN)
                        .append(plugin.commsManager.parseLegacy(capturedTag.display.trimEnd()))
                )
                p.closeInventory()
            }
        }

        // Back button
        val back = ItemStack(Material.ARROW)
        back.editMeta { it.displayName(Component.text("Back", NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false)) }
        gui.setItem(49, back) { p, _ -> openCategoryMenu(p) }

        // Pagination
        if (page > 0) {
            val prev = ItemStack(Material.ARROW)
            prev.editMeta { it.displayName(Component.text("Previous", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false)) }
            gui.setItem(46, prev) { p, _ -> openTagList(p, category, page - 1) }
        }
        if (page < totalPages - 1) {
            val next = ItemStack(Material.ARROW)
            next.editMeta { it.displayName(Component.text("Next", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false)) }
            gui.setItem(52, next) { p, _ -> openTagList(p, category, page + 1) }
        }

        plugin.guiManager.open(player, gui)
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

        val defaultsSection = defaults.getConfigurationSection("tags") ?: return
        val userSection = userCfg.getConfigurationSection("tags")
            ?: userCfg.createSection("tags")

        var categoriesAdded = 0
        var tagsAdded = 0
        for (categoryId in defaultsSection.getKeys(false)) {
            val defaultCat = defaultsSection.getConfigurationSection(categoryId) ?: continue
            val userCat = userSection.getConfigurationSection(categoryId)
            if (userCat == null) {
                userSection.set(categoryId, defaultsSection.get(categoryId))
                categoriesAdded++
                continue
            }
            for (tagId in defaultCat.getKeys(false)) {
                if (!userCat.contains(tagId)) {
                    userCat.set(tagId, defaultCat.get(tagId))
                    tagsAdded++
                }
            }
        }
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
        private const val VOUCHER_CATEGORY = "voucher"
        private const val VOUCHER_PERMISSION_PREFIX = "joshymc.tag.voucher."

        // Normal (non-Special) Chat Tag categories approved for the shop — issue #602.
        private val APPROVED_CATEGORIES = setOf(
            "vibe", "nature", "animal", "music", "pirate",
            "skill", "military", "meme", "mythical", "cyberpunk", "cosmic"
        )
    }
}
