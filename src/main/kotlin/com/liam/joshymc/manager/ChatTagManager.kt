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

    fun start() {
        // Save default tags.yml
        val file = plugin.configFile("tags.yml")
        if (!file.exists()) {
            plugin.saveResource("tags.yml", false)
        }

        // Load tags
        val config = YamlConfiguration.loadConfiguration(file)
        tags.clear()
        categories.clear()

        val tagsSection = config.getConfigurationSection("tags") ?: return
        for (categoryId in tagsSection.getKeys(false)) {
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
        return player.hasPermission(tag.permission)
    }

    // ── GUI ──────────────────────────────────────────

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
            "prestige" to Material.GOLD_INGOT,
            "og" to Material.CLOCK,
            "skill" to Material.DIAMOND_SWORD,
            "vibe" to Material.NOTE_BLOCK,
            "nature" to Material.OAK_SAPLING,
            "cosmic" to Material.END_STONE,
            "gem" to Material.DIAMOND,
            "animal" to Material.BONE,
            "food" to Material.COOKIE,
            "meme" to Material.PAPER,
            "color" to Material.WHITE_WOOL,
            "role" to Material.IRON_PICKAXE,
            "season" to Material.SUNFLOWER,
            "rare" to Material.NETHER_STAR
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
                    Component.text(category.replaceFirstChar { it.uppercase() }, NamedTextColor.GOLD)
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
            Component.text("${category.replaceFirstChar { it.uppercase() }} Tags", NamedTextColor.GOLD)
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
                when {
                    isEquipped -> lore.add(Component.text("  Equipped!", NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false))
                    canUse -> lore.add(Component.text("  Click to equip", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false))
                    else -> lore.add(Component.text("  Locked", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false))
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
}
