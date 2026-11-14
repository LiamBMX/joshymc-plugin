package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gamemode.ServerGamemode
import com.liam.joshymc.gui.GuiStyle
import com.liam.joshymc.gui.CustomGui
import com.liam.joshymc.lifesteal.ranks.LifestealRankConfig
import com.liam.joshymc.lifesteal.ranks.LifestealRankRules
import com.liam.joshymc.util.giveItemSafely
import com.liam.joshymc.util.freshVoucherCopy
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import java.io.File
import java.util.UUID

class KitManager(private val plugin: Joshymc) {

    companion object {
        val KIT_GUI_TITLE: Component = Component.text("Kits", TextColor.color(0x55FFFF))
            .decoration(TextDecoration.BOLD, true)
            .decoration(TextDecoration.ITALIC, false)

        private const val CREATE_KIT_TITLE_PREFIX = "Creating Kit: "
        private const val EDIT_KIT_TITLE_PREFIX = "Editing Kit: "

        private val FILLER = ItemStack(Material.BLACK_STAINED_GLASS_PANE).apply {
            editMeta { it.displayName(Component.empty()) }
        }

        // /editkit GUI layout: content area mirrors the 36-slot /createkit inventory
        // (so slot keys stay identical), plus a bottom control row.
        const val EDIT_GUI_SIZE = 45
        val EDIT_CONTENT_SLOTS = 0..35
        val EDIT_CONTROL_SLOTS = 36..44
        const val EDIT_INFO_SLOT = 36
        const val EDIT_RELOAD_SLOT = 40
        const val EDIT_SAVE_SLOT = 42
        const val EDIT_CANCEL_SLOT = 44

        /** Capitalizes each word of a kit's internal id for display only (e.g. "trail_blazer" -> "Trail Blazer"). */
        fun displayName(kitName: String): String =
            kitName.split('_', ' ').filter { it.isNotEmpty() }
                .joinToString(" ") { it.lowercase().replaceFirstChar(Char::uppercase) }
    }

    data class KitDef(
        val name: String,
        val icon: Material,
        val cooldownHours: Int,
        val permission: String,
        val items: Map<Int, ItemStack>,
        /** Raw kits.yml `gamemode` (null = missing = Survival), see [KitCatalog]. */
        val gamemode: String? = null,
        /** Raw kits.yml `category` (null = inferred from the name), see [KitCatalog]. */
        val category: String? = null,
        /** kits.yml `enabled` (missing = true). Issue #1428: only Lifesteal kits honor it (hidden and unclaimable while false). */
        val enabled: Boolean = true,
    ) {
        val entry: KitCatalog.Entry get() = KitCatalog.Entry(name, gamemode, category)
        val isLifesteal: Boolean get() = ServerGamemode.fromId(gamemode) == ServerGamemode.LIFESTEAL
        /** False only for a Lifesteal kit turned off in kits.yml; Survival kits are never affected. */
        val available: Boolean get() = !isLifesteal || enabled
    }

    data class EditSession(
        val kitName: String,
        val editorId: UUID,
        val inventory: Inventory,
        val originalItems: Map<Int, ItemStack>,
        val overflowItems: Map<Int, ItemStack>
    )

    private val editSessions = mutableMapOf<UUID, EditSession>()
    private val editLocks = mutableMapOf<String, UUID>()

    private var kitsFile: File = plugin.configFile("kits.yml")
    private var kitsConfig: YamlConfiguration = YamlConfiguration()
    private val kits = mutableMapOf<String, KitDef>()

    fun start() {
        // Create cooldown table
        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS kit_cooldowns (
                uuid TEXT NOT NULL,
                kit TEXT NOT NULL,
                claimed_at INTEGER NOT NULL,
                PRIMARY KEY (uuid, kit)
            )
        """.trimIndent())

        // Load kits from YAML
        loadKits()

        plugin.logger.info("[KitManager] Started with ${kits.size} kit(s).")
    }

    /** Force-closes any open /editkit sessions and releases their locks. Called on disable/reload. */
    fun stop() {
        for (session in editSessions.values.toList()) {
            val editor = Bukkit.getPlayer(session.editorId) ?: continue
            if (editor.openInventory.topInventory == session.inventory) {
                plugin.commsManager.send(editor, Component.text("Kit editor closed (server reloading). No changes were saved.", NamedTextColor.YELLOW))
                editor.closeInventory()
            }
        }
        editSessions.clear()
        editLocks.clear()
    }

    private fun loadKits() {
        kits.clear()

        // First-run: extract the bundled kits.yml from the jar.
        if (!kitsFile.exists()) {
            try {
                plugin.saveResource("kits.yml", false)
            } catch (_: IllegalArgumentException) {
                kitsFile.parentFile.mkdirs()
                kitsFile.createNewFile()
            }
            kitsFile = plugin.configFile("kits.yml")
        }
        parseKitsFile()

        // Self-heal: if the on-disk file is missing a `kits:` section or contains
        // zero kits (empty file, corrupted edit, legacy install from before we
        // shipped defaults), overwrite it from the bundled resource and retry.
        if (kits.isEmpty()) {
            plugin.logger.warning(
                "[KitManager] No kits parsed from ${kitsFile.path} (size=${kitsFile.length()}). " +
                "Re-extracting bundled kits.yml from the jar."
            )
            try {
                plugin.saveResource("kits.yml", true)
                kitsFile = plugin.configFile("kits.yml")
                parseKitsFile()
            } catch (e: Exception) {
                plugin.logger.warning("[KitManager] Failed to re-extract kits.yml: ${e.message}")
            }
        }
    }

    private fun parseKitsFile() {
        kits.clear()
        kitsConfig = YamlConfiguration.loadConfiguration(kitsFile)

        val kitsSection = kitsConfig.getConfigurationSection("kits")
        if (kitsSection == null) {
            plugin.logger.warning("[KitManager] kits.yml has no 'kits:' root section (path=${kitsFile.path}).")
            return
        }
        for (name in kitsSection.getKeys(false)) {
            val section = kitsSection.getConfigurationSection(name) ?: continue
            val iconStr = section.getString("icon", "CHEST") ?: "CHEST"
            val icon = try { Material.valueOf(iconStr) } catch (_: Exception) { Material.CHEST }
            val cooldownHours = section.getInt("cooldown-hours", 72)
            val permission = section.getString("permission", "joshymc.kit.$name") ?: "joshymc.kit.$name"

            val items = mutableMapOf<Int, ItemStack>()
            val itemsSection = section.getConfigurationSection("items")
            if (itemsSection != null) {
                for (slotKey in itemsSection.getKeys(false)) {
                    val slot = slotKey.toIntOrNull() ?: continue
                    val itemStack = try {
                        itemsSection.getItemStack(slotKey)
                    } catch (e: Exception) {
                        plugin.logger.warning("[KitManager] Failed to deserialize $name slot $slotKey: ${e.message}")
                        null
                    } ?: continue
                    items[slot] = itemStack
                }
            }

            val gamemode = section.getString("gamemode")
            val category = section.getString("category")
            if (KitCatalog.invalidGamemode(gamemode)) {
                plugin.logger.warning("[KitManager] Kit '$name' has unknown gamemode '$gamemode' (survival or lifesteal); it is hidden from /kit.")
            }
            if (KitCatalog.invalidCategory(category)) {
                plugin.logger.warning("[KitManager] Kit '$name' has unknown category '$category' (starter, rank or special); using its name instead.")
            }

            kits[name] = KitDef(name, icon, cooldownHours, permission, items, gamemode, category, section.getBoolean("enabled", true))
        }
    }

    fun saveKit(
        name: String,
        icon: Material,
        cooldownHours: Int,
        permission: String,
        items: Map<Int, ItemStack>,
        gamemode: String? = null,
        category: String? = null,
    ) {
        val path = "kits.$name"
        kitsConfig.set("$path.icon", icon.name)
        kitsConfig.set("$path.cooldown-hours", cooldownHours)
        kitsConfig.set("$path.permission", permission)
        kitsConfig.set("$path.gamemode", gamemode)
        kitsConfig.set("$path.category", category)

        // Clear old items
        kitsConfig.set("$path.items", null)
        for ((slot, item) in items) {
            kitsConfig.set("$path.items.$slot", item)
        }

        kitsConfig.save(kitsFile)
        kits[name] = KitDef(name, icon, cooldownHours, permission, items, gamemode, category, kitsConfig.getBoolean("$path.enabled", true))
    }

    fun deleteKit(name: String): Boolean {
        if (!kits.containsKey(name)) return false
        kitsConfig.set("kits.$name", null)
        kitsConfig.save(kitsFile)
        kits.remove(name)
        return true
    }

    fun getKitNames(): List<String> = kits.keys.toList()

    fun getKit(name: String): KitDef? = kits[name]

    /** The gamemode whose kits [player] sees: the gamemode of the world they're in. */
    fun gamemodeOf(player: Player): ServerGamemode = plugin.gamemodeLockManager.gamemodeOf(player)

    /** [name] if it is a kit of [player]'s current gamemode, else null (Survival kits never resolve on Lifesteal and back). */
    fun getKitFor(player: Player, name: String): KitDef? =
        kits[name]?.takeIf { it.available && KitCatalog.visibleIn(it.entry, gamemodeOf(player)) }

    /** Kit names [player] can see in their current gamemode, in kits.yml order. */
    fun getKitNamesFor(player: Player): List<String> {
        val current = gamemodeOf(player)
        return kits.values.filter { it.available && KitCatalog.visibleIn(it.entry, current) }.map { it.name }
    }

    /**
     * The rank kit order for [gamemode] from `config.yml > kits.rank-order.<gamemode>`. Survival
     * falls back to the donor tiers when the key is missing; Lifesteal has no rank hierarchy in
     * JoshyMC, so it is empty until configured (its rank kits then keep kits.yml order).
     */
    fun rankOrder(gamemode: ServerGamemode): List<String> {
        val path = "kits.rank-order.${gamemode.id}"
        val configured = if (plugin.config.isList(path)) KitCatalog.normalizeOrder(plugin.config.getStringList(path)) else null
        return when (gamemode) {
            ServerGamemode.SURVIVAL -> configured ?: KitCatalog.DEFAULT_SURVIVAL_RANK_ORDER
            // Issue #1428: an empty Lifesteal order follows the Lifesteal rank hierarchy (lifesteal-ranks.yml `kits`).
            ServerGamemode.LIFESTEAL -> configured?.takeIf { it.isNotEmpty() } ?: lifestealRanks()?.let { LifestealRankRules.kitOrder(it) }.orEmpty()
        }
    }

    // ── Lifesteal rank kits (issue #1428) ───────────────────────────────

    private fun lifestealRanks(): LifestealRankConfig.Settings? = plugin.lifestealRanksOrNull()?.takeIf { it.hosted }?.settings

    /**
     * Whether [player] may claim [kit]. Survival kits (and Lifesteal kits no rank lists): the kit's
     * permission, as before. A Lifesteal rank kit: only the Lifesteal rank hierarchy decides (its rank
     * or any higher one, both enabled), never a permission node, so a shared or inherited LuckPerms
     * node can't unlock it.
     */
    fun canUse(player: Player, kit: KitDef): Boolean {
        if (!kit.isLifesteal) return player.hasPermission(kit.permission)
        val ranks = plugin.lifestealRanksOrNull()
        val settings = lifestealRanks()
        if (ranks == null || settings == null) return player.hasPermission(kit.permission)
        return when (LifestealRankRules.kitAccess(settings, ranks.ownedRanks(player.uniqueId), kit.name)) {
            LifestealRankRules.KitAccess.NOT_A_RANK_KIT -> player.hasPermission(kit.permission)
            LifestealRankRules.KitAccess.ALLOWED -> true
            else -> false
        }
    }

    /** The Lifesteal rank a kit belongs to, for its icon. */
    private fun lifestealRankFor(kit: KitDef): LifestealRankConfig.RankDef? =
        if (kit.isLifesteal) lifestealRanks()?.rankForKit(kit.name) else null

    /** [kit]'s cooldown for [player]: Lifesteal rank kits get the `kit-cooldown-reduction-percent` perk. */
    fun cooldownMillisFor(player: Player, kit: KitDef): Long {
        val base = kit.cooldownHours.toLong() * 3600_000L
        val ranks = plugin.lifestealRanksOrNull()
        if (!kit.isLifesteal || ranks == null || lifestealRankFor(kit) == null) return base
        val reduction = ranks.perk(player, LifestealRankConfig.Perk.KIT_COOLDOWN_REDUCTION_PERCENT)
        return LifestealRankRules.kitCooldownMillis(kit.cooldownHours, reduction)
    }

    /**
     * The duplication-safe claim used for every Lifesteal kit: refuses where the items could be
     * lost or rolled back (Mod Mode, a Ranked match, the event world, combat), refuses unless every
     * item fits (nothing is ever dropped or discarded), reserves the cooldown in one conditional
     * statement *before* handing anything out (a second click, alias or reconnect can't claim twice;
     * a crash can only lose a claim, never duplicate one), then gives and saves the player.
     */
    private fun claimLifestealKit(player: Player, kit: KitDef): Boolean {
        val ranks = plugin.lifestealRanksOrNull()
        fun refuse(message: String): Boolean {
            plugin.commsManager.send(player, Component.text(message, NamedTextColor.RED))
            player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 0.7f, 1.0f)
            return false
        }
        if (ranks == null) return refuse("Kits are unavailable right now.")
        if (player.isDead) return refuse("You can't claim kits while dead.")
        if (plugin.modModeManager.isModMode(player)) return refuse("Leave Mod Mode to claim kits.")
        if (plugin.lifestealRankedManager.holds(player)) return refuse("You can't claim kits during a Ranked match.")
        if (plugin.eventManager.isInEventWorld(player)) return refuse("You can't claim kits in the event world.")
        if ((ranks.settings?.refuseKitsWhileCombatTagged ?: true) && plugin.combatManager.isTagged(player)) {
            return refuse("You can't claim kits while in combat.")
        }

        val items = kit.items.toSortedMap().values.map { item ->
            item.clone().also { clone ->
                if (plugin.creditVoucherManager.isCreditVoucher(clone)) {
                    clone.editMeta { meta ->
                        meta.persistentDataContainer.set(plugin.creditVoucherManager.voucherUuidKey, PersistentDataType.STRING, UUID.randomUUID().toString())
                    }
                }
            }
        }
        // Simulate on a copy of the storage slots: refuse unless everything fits.
        val probe = Bukkit.createInventory(null, 36)
        val storage = player.inventory.storageContents
        for (i in storage.indices) if (i < probe.size) probe.setItem(i, storage[i]?.clone())
        if (probe.addItem(*items.map { it.clone() }.toTypedArray()).isNotEmpty()) {
            return refuse("Your inventory is full. Free up space for ${items.size} item stack(s) and claim the kit again; nothing was used up.")
        }

        val now = System.currentTimeMillis()
        val cooldown = if (hasBypass(player, kit.name)) 0L else cooldownMillisFor(player, kit)
        val previous = ranks.store.kitClaimedAt(player.uniqueId, kit.name)
        val reserved = try {
            ranks.store.reserveKitClaim(player.uniqueId, kit.name, now, cooldown)
        } catch (e: Exception) {
            plugin.logger.warning("[KitManager] Could not reserve Lifesteal kit ${kit.name} for ${player.name}: ${e.message}")
            return refuse("Kits are unavailable right now. Please try again.")
        }
        if (!reserved) return refuse("Kit '${kit.name}' is on cooldown: ${formatCooldown(getCooldownRemaining(player, kit.name))} remaining.")

        val failed = player.inventory.addItem(*items.toTypedArray())
        if (failed.isNotEmpty()) {
            // Can't happen after the simulation; never lose what didn't fit.
            if (failed.size == items.size) {
                ranks.store.releaseKitClaim(player.uniqueId, kit.name, now, previous)
                return refuse("Your inventory is full. Free up space and claim the kit again; nothing was used up.")
            }
            failed.values.forEach { plugin.giveItemSafely(player, it) }
            plugin.logger.warning("[KitManager] ${player.name}'s Lifesteal kit ${kit.name}: ${failed.size} stack(s) didn't fit and were dropped at their feet.")
        }
        try { player.saveData() } catch (e: Exception) {
            plugin.logger.warning("[KitManager] Could not save ${player.name}'s data after kit ${kit.name}: ${e.message}")
        }
        plugin.logger.info("[KitManager] ${player.name} (${player.uniqueId}) claimed Lifesteal kit ${kit.name} (${items.size} stack(s)).")
        plugin.commsManager.send(player, Component.text("Kit '${kit.name}' claimed!", NamedTextColor.GREEN))
        player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.2f)
        if (isStarterKit(player, kit)) plugin.tutorialSignal(player, com.liam.joshymc.tutorial.TutorialSignal.StarterKitClaimed)
        return true
    }

    private fun kitsIn(player: Player, category: KitCatalog.Category): List<KitDef> {
        val current = gamemodeOf(player)
        return KitCatalog.kitsFor(category, kits.values.filter { it.available }.map { it.entry }, current, rankOrder(current))
            .mapNotNull { kits[it] }
    }

    fun claimKit(player: Player, name: String): Boolean {
        // Revalidated on every claim (GUI clicks included): only kits of the player's current gamemode.
        val kit = getKitFor(player, name)
        if (kit == null) {
            plugin.commsManager.send(player, Component.text("Kit '$name' not found.", NamedTextColor.RED))
            return false
        }

        if (!canUse(player, kit)) {
            val rank = lifestealRankFor(kit)
            val message = if (rank != null) "This kit needs the ${rank.displayName} rank or higher (see /lsranks)." else "You don't have permission to use this kit."
            plugin.commsManager.send(player, Component.text(message, NamedTextColor.RED))
            return false
        }

        if (!canClaim(player, name)) {
            val remaining = getCooldownRemaining(player, name)
            val formatted = formatCooldown(remaining)
            plugin.commsManager.send(player, Component.text("Kit '$name' is on cooldown: $formatted remaining.", NamedTextColor.RED))
            player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 0.7f, 1.0f)
            return false
        }

        // Issue #1428: every Lifesteal kit goes through the reserved, fit-checked claim.
        if (kit.isLifesteal) return claimLifestealKit(player, kit)

        // Give items
        for ((slot, item) in kit.items) {
            // kit.items is a shared in-memory template reused for every claimer — handing
            // out item.clone() verbatim would give every claimer an identical voucher_uuid,
            // so only the first person to redeem it would succeed. Each delivery gets a fresh
            // id (issue #833) and, for signed vouchers, the signature for that id (issue #1529:
            // keeping the template's signature made the copy read as forged).
            val clone = when (val copy = plugin.freshVoucherCopy(item)) {
                com.liam.joshymc.util.VoucherCopy.NotVoucher -> item.clone()
                is com.liam.joshymc.util.VoucherCopy.Fresh -> copy.item
                is com.liam.joshymc.util.VoucherCopy.Refused -> {
                    plugin.logger.warning("[Kits] Kit '$name' slot $slot ${copy.reason}; not handed out.")
                    continue
                }
            }
            val staffMode = plugin.modModeManager.isModMode(player)
            if (!staffMode && slot < player.inventory.size && player.inventory.getItem(slot) == null) {
                player.inventory.setItem(slot, clone)
            } else {
                plugin.giveItemSafely(player, clone)
            }
        }

        // Set cooldown
        val now = System.currentTimeMillis()
        plugin.databaseManager.execute(
            "INSERT OR REPLACE INTO kit_cooldowns (uuid, kit, claimed_at) VALUES (?, ?, ?)",
            player.uniqueId.toString(), name, now
        )

        plugin.commsManager.send(player, Component.text("Kit '$name' claimed!", NamedTextColor.GREEN))
        player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.2f)
        // Issue #1387: the tutorial's Starter Kit objective counts only this confirmed claim.
        if (isStarterKit(player, kit)) plugin.tutorialSignal(player, com.liam.joshymc.tutorial.TutorialSignal.StarterKitClaimed)
        return true
    }

    /** Whether [kit] is a Starter Kit in [player]'s current gamemode (issue #1387). */
    fun isStarterKit(player: Player, kit: KitDef): Boolean {
        val current = gamemodeOf(player)
        return KitCatalog.visibleIn(kit.entry, current) && KitCatalog.categoryOf(kit.entry, rankOrder(current)) == KitCatalog.Category.STARTER
    }

    /** The Starter Kits of [player]'s current gamemode (issue #1387). */
    fun starterKitsFor(player: Player): List<KitDef> = kitsIn(player, KitCatalog.Category.STARTER)

    /** Whether [uuid] has ever claimed kit [name] (a cooldown row exists, expired or not). Read only. */
    fun hasClaimedBefore(uuid: UUID, name: String): Boolean =
        plugin.databaseManager.queryFirst(
            "SELECT 1 FROM kit_cooldowns WHERE uuid = ? AND kit = ?",
            uuid.toString(), name
        ) { true } ?: false

    fun getCooldownRemaining(player: Player, name: String): Long {
        // Bypass permissions: global or per-kit
        if (player.hasPermission("joshymc.kit.bypass.*") ||
            player.hasPermission("joshymc.kit.bypass.$name") ||
            player.hasPermission("joshymc.kit.cooldown.bypass")) {
            return 0L
        }

        val kit = kits[name] ?: return 0L
        val claimedAt = plugin.databaseManager.queryFirst(
            "SELECT claimed_at FROM kit_cooldowns WHERE uuid = ? AND kit = ?",
            player.uniqueId.toString(), name
        ) { rs -> rs.getLong("claimed_at") } ?: return 0L

        val cooldownMs = cooldownMillisFor(player, kit)
        val elapsed = System.currentTimeMillis() - claimedAt
        val remaining = cooldownMs - elapsed
        return if (remaining > 0) remaining else 0L
    }

    fun canClaim(player: Player, name: String): Boolean {
        return getCooldownRemaining(player, name) <= 0L
    }

    /** True if the player has any kit cooldown bypass permission for this kit. */
    fun hasBypass(player: Player, name: String): Boolean {
        return player.hasPermission("joshymc.kit.bypass.*") ||
                player.hasPermission("joshymc.kit.bypass.$name") ||
                player.hasPermission("joshymc.kit.cooldown.bypass")
    }

    /**
     * `/kit` main menu (issue #1379): a 27-slot chest with only the middle row used, Starter Kit
     * (slot 11), Rank Kits (13) and Special Kits (15). No filler, borders or other buttons.
     */
    fun openKitGui(player: Player) {
        if (kits.isEmpty()) {
            // Likely saveResource never ran (file existed but empty, e.g. legacy install).
            // Attempt one more load pass so `/kit` can self-recover without /joshymc reload.
            plugin.logger.warning("[KitManager] openKitGui: no kits in memory; forcing reload.")
            loadKits()
        }
        val gui = CustomGui(KIT_GUI_TITLE, KitCatalog.MAIN_SIZE)

        renderStarter(gui, player)
        renderCategory(
            gui, player, KitCatalog.RANK_SLOT, KitCatalog.Category.RANK, Material.NETHERITE_CHESTPLATE,
            "Rank Kits", "Kits unlocked by your rank."
        )
        renderCategory(
            gui, player, KitCatalog.SPECIAL_SLOT, KitCatalog.Category.SPECIAL, Material.NETHER_STAR,
            "Special Kits", "Media and Discord kits."
        )

        plugin.guiManager.open(player, gui)
        player.playSound(player.location, Sound.BLOCK_CHEST_OPEN, 0.5f, 1.2f)
    }

    private fun renderStarter(gui: CustomGui, player: Player) {
        val starters = kitsIn(player, KitCatalog.Category.STARTER)
        when (starters.size) {
            0 -> gui.setItem(
                KitCatalog.STARTER_SLOT,
                categoryItem(Material.CHEST, "Starter Kit", NamedTextColor.GRAY, listOf(
                    line("There is no starter kit here.", NamedTextColor.GRAY)
                ))
            ) { p, _ -> GuiStyle.errorSound(p) }
            1 -> {
                // The one starter kit sits on the main menu itself: same claim/preview clicks as any kit.
                val kit = starters.single()
                val item = kitIcon(player, kit, Material.CHEST, "Starter Kit")
                gui.setItem(KitCatalog.STARTER_SLOT, item, kitClick(kit.name) { openKitGui(it) })
            }
            else -> renderCategory(
                gui, player, KitCatalog.STARTER_SLOT, KitCatalog.Category.STARTER, Material.CHEST,
                "Starter Kits", "Free kits for new players."
            )
        }
    }

    private fun renderCategory(
        gui: CustomGui,
        player: Player,
        slot: Int,
        category: KitCatalog.Category,
        icon: Material,
        title: String,
        description: String,
    ) {
        val inCategory = kitsIn(player, category)
        val ready = inCategory.count { canUse(player, it) && canClaim(player, it.name) }
        val lore = mutableListOf(line(description, NamedTextColor.GRAY), Component.empty())
        if (inCategory.isEmpty()) {
            lore += line("No kits here yet.", NamedTextColor.GRAY)
        } else {
            lore += line("Kits: ", NamedTextColor.GRAY).append(Component.text("${inCategory.size}", NamedTextColor.WHITE))
            lore += line("Ready to claim: ", NamedTextColor.GRAY)
                .append(Component.text("$ready", if (ready > 0) NamedTextColor.GREEN else NamedTextColor.WHITE))
            lore += Component.empty()
            lore += line("Click to open", NamedTextColor.YELLOW)
        }
        val color = if (inCategory.isEmpty()) NamedTextColor.GRAY else NamedTextColor.AQUA
        gui.setItem(slot, categoryItem(icon, title, color, lore)) { p, _ ->
            GuiStyle.clickSound(p)
            openCategoryGui(p, category)
        }
    }

    /**
     * One category's kits (issue #1379), in [KitCatalog] order: centered rows under an empty top
     * row ([KitGuiLayout]), Back (to the main menu) and Close in the bottom row.
     */
    fun openCategoryGui(player: Player, category: KitCatalog.Category) {
        val list = kitsIn(player, category)
        val label = when (category) {
            KitCatalog.Category.STARTER -> "Starter Kits"
            KitCatalog.Category.RANK -> "Rank Kits"
            KitCatalog.Category.SPECIAL -> "Special Kits"
        }
        val title = Component.text(label, TextColor.color(0x55FFFF))
            .decoration(TextDecoration.BOLD, true)
            .decoration(TextDecoration.ITALIC, false)
        val size = KitGuiLayout.size(list.size)
        val gui = CustomGui(title, size)

        gui.setItem(GuiStyle.backSlot(size), GuiStyle.back("Back to Kits")) { p, _ ->
            GuiStyle.clickSound(p)
            openKitGui(p)
        }
        gui.setItem(KitGuiLayout.closeSlot(list.size), GuiStyle.close()) { p, _ -> p.closeInventory() }

        if (list.isEmpty()) {
            gui.setItem(
                KitGuiLayout.ROW + 4,
                categoryItem(Material.GRAY_DYE, "No kits", NamedTextColor.GRAY, listOf(
                    line("There are no ${label.lowercase()} here.", NamedTextColor.GRAY)
                ))
            )
        }

        val current = gamemodeOf(player)
        val order = rankOrder(current)
        val slots = KitGuiLayout.slots(list.size)
        for ((idx, kitDef) in list.withIndex()) {
            if (idx >= slots.size) {
                plugin.logger.warning("[KitManager] ${list.size} $label is more than the menu holds; '${kitDef.name}' and later aren't shown.")
                break
            }
            try {
                val rank = if (category == KitCatalog.Category.RANK) KitCatalog.rankFor(kitDef.name, order) else null
                val item = kitIcon(player, kitDef, kitDef.icon, displayName(kitDef.name), rank)
                gui.setItem(slots[idx], item, kitClick(kitDef.name) { openCategoryGui(it, category) })
            } catch (e: Exception) {
                plugin.logger.warning("[KitManager] Failed to render kit '${kitDef.name}' at slot ${slots[idx]}: ${e.message}")
            }
        }

        plugin.guiManager.open(player, gui)
    }

    /**
     * Left-click claims (closing the menu first, as before), right-click previews; the preview's
     * Back reopens [reopen]. The kit is looked up again on click, so a kit deleted or moved to
     * another gamemode meanwhile is refused by [claimKit] rather than claimed from a stale icon.
     */
    private fun kitClick(kitName: String, reopen: (Player) -> Unit): (Player, org.bukkit.event.inventory.InventoryClickEvent) -> Unit =
        { p, event ->
            if (event.click.isRightClick) {
                val kit = getKitFor(p, kitName)
                if (kit == null) {
                    GuiStyle.errorSound(p)
                    reopen(p)
                } else {
                    openKitPreviewGui(p, kit, reopen)
                }
            } else {
                p.closeInventory()
                claimKit(p, kitName)
            }
        }

    private fun line(text: String, color: NamedTextColor): Component =
        Component.text(text, color).decoration(TextDecoration.ITALIC, false)

    private fun categoryItem(material: Material, name: String, color: NamedTextColor, lore: List<Component>): ItemStack {
        val item = GuiStyle.item(material, Component.text(name, color).decoration(TextDecoration.BOLD, true), lore)
        item.editMeta { it.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS) }
        return item
    }

    /** A kit's icon: name colored by state, cooldown, status (Ready / remaining / Locked) and the click hints. */
    private fun kitIcon(player: Player, kitDef: KitDef, material: Material, name: String, rank: String? = null): ItemStack {
        val onCooldown = !canClaim(player, kitDef.name)
        val hasPermission = canUse(player, kitDef)
        val lifestealRank = lifestealRankFor(kitDef)
        val ready = hasPermission && !onCooldown
        val locked = !hasPermission

        // Issue #1412: the Discord and Media kits draw the shared custom icons (same item model as
        // everywhere else); name and lore below are set exactly as for any other kit.
        val item = KitCatalog.guiIconFor(kitDef.name)?.let { GuiStyle.icon(it) } ?: ItemStack(material)

        item.editMeta { meta ->
                val nameColor = when {
                    locked -> NamedTextColor.GRAY
                    onCooldown -> NamedTextColor.GOLD
                    else -> NamedTextColor.AQUA
                }
                meta.displayName(
                    Component.text(name, nameColor)
                        .decoration(TextDecoration.ITALIC, false)
                        .decoration(TextDecoration.BOLD, true)
                )

                val lore = mutableListOf<Component>()
                lore.add(Component.empty())

                lore.add(
                    Component.text("Cooldown: ", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false)
                        .append(Component.text(cooldownLabel(player, kitDef), NamedTextColor.WHITE))
                )

                val statusValue = when {
                    locked -> Component.text("Locked", NamedTextColor.RED)
                    onCooldown -> Component.text("${formatCooldown(getCooldownRemaining(player, kitDef.name))} remaining", NamedTextColor.GOLD)
                    else -> Component.text("Ready", NamedTextColor.GREEN)
                }
                lore.add(
                    Component.text("Status: ", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false)
                        .append(statusValue.decoration(TextDecoration.ITALIC, false))
                )
                if (lifestealRank != null) {
                    // Issue #1428: Lifesteal rank kits always name their rank; higher ranks can claim them too.
                    lore.add(line("Required rank: ${lifestealRank.displayName} or higher", if (locked) NamedTextColor.RED else NamedTextColor.GRAY))
                    lore.add(line("Contents: ${kitDef.items.size} item stack(s), right-click to preview", NamedTextColor.GRAY))
                } else if (locked && rank != null) {
                    lore.add(line("Requires the ${DonorTiers.displayName(rank)} rank", NamedTextColor.RED))
                }

                lore.add(Component.empty())
                if (ready) {
                    lore.add(Component.text("Left-Click to claim", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false))
                }
                lore.add(Component.text("Right-Click to preview", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false))

                meta.lore(lore)
                meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)

                // Subtle glint on ready kits only — no glint while on cooldown or locked.
                if (ready) {
                    meta.addEnchant(Enchantment.UNBREAKING, 1, true)
                    meta.addItemFlags(ItemFlag.HIDE_ENCHANTS)
                }
            }
        return item
    }

    /**
     * Read-only preview of a kit's contents — items can be hovered for full tooltips (enchants,
     * attributes, etc.) but not taken (GuiManager cancels every click on the menu). With [back],
     * a bottom row holds Back (to [back]) and Close.
     */
    fun openKitPreviewGui(player: Player, kitDef: KitDef, back: ((Player) -> Unit)? = null) {
        val sortedItems = kitDef.items.toSortedMap().values.toList()
        val itemRows = (((sortedItems.size - 1) / 9) + 1).coerceIn(1, if (back != null) 5 else 6)
        val rows = itemRows + if (back != null) 1 else 0
        val size = rows * 9

        val title = Component.text("Preview: ")
            .append(Component.text(displayName(kitDef.name), TextColor.color(0x55FFFF)))
            .decoration(TextDecoration.BOLD, true)
            .decoration(TextDecoration.ITALIC, false)
        val gui = CustomGui(title, size)

        for ((index, kitItem) in sortedItems.withIndex()) {
            if (index >= itemRows * 9) break
            gui.inventory.setItem(index, kitItem.clone())
        }

        if (back != null) {
            gui.setItem(GuiStyle.backSlot(size), GuiStyle.back()) { p, _ ->
                GuiStyle.clickSound(p)
                back(p)
            }
            gui.setItem(GuiStyle.closeSlot(size), GuiStyle.close()) { p, _ -> p.closeInventory() }
        }

        plugin.guiManager.open(player, gui)
        player.playSound(player.location, Sound.BLOCK_CHEST_OPEN, 0.4f, 1.4f)
    }

    fun openCreateKitGui(player: Player, kitName: String) {
        val title = Component.text(CREATE_KIT_TITLE_PREFIX)
            .append(Component.text(kitName, NamedTextColor.AQUA))
            .decoration(TextDecoration.ITALIC, false)

        // Create kit GUI uses a plain inventory (not GuiManager) so players can freely place items.
        // We use a Bukkit inventory directly and handle the close via a scheduled task.
        val inv = Bukkit.createInventory(null, 36, title)
        player.openInventory(inv)
        plugin.commsManager.send(player, Component.text("Place items in the chest, then close it to save the kit.", NamedTextColor.YELLOW))

        // Schedule a repeating check for when they close the inventory
        val capturedKitName = kitName
        val taskRef = arrayOfNulls<org.bukkit.scheduler.BukkitTask>(1)
        taskRef[0] = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            // Check if the player has closed the inventory
            if (player.openInventory.topInventory !== inv || !player.isOnline) {
                taskRef[0]?.cancel()

                // Collect items from the inventory
                val items = mutableMapOf<Int, ItemStack>()
                for (slot in 0 until inv.size) {
                    val item = inv.getItem(slot)
                    if (item != null && item.type != Material.AIR) {
                        items[slot] = item.clone()
                    }
                }

                if (items.isEmpty()) {
                    plugin.commsManager.send(player, Component.text("Kit creation cancelled (no items placed).", NamedTextColor.RED))
                    return@Runnable
                }

                // A new kit belongs to the gamemode it was made in (issue #1379).
                saveKit(capturedKitName, items.values.first().type, 72, "joshymc.kit.$capturedKitName", items, gamemodeOf(player).id)
                plugin.commsManager.send(player, Component.text("Kit '$capturedKitName' created with ${items.size} item(s)!", NamedTextColor.GREEN))
                player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.2f)
            }
        }, 5L, 5L)
    }

    fun getEditSession(player: Player): EditSession? = editSessions[player.uniqueId]

    /** Opens (or re-opens) the /editkit GUI for `name`. Handles the "already being edited" lock. */
    fun openEditKitGui(player: Player, name: String) {
        val kit = kits[name]
        if (kit == null) {
            plugin.commsManager.send(player, Component.text("Kit '$name' does not exist.", NamedTextColor.RED))
            return
        }

        val lockHolder = editLocks[name]
        if (lockHolder != null && lockHolder != player.uniqueId) {
            val holder = Bukkit.getPlayer(lockHolder)
            if (holder != null && holder.isOnline) {
                plugin.commsManager.send(
                    player,
                    Component.text("That kit is currently being edited by ${holder.name}.", NamedTextColor.RED)
                )
                return
            }
            // Holder went offline without their session getting cleaned up — release the stale lock.
            editLocks.remove(name)
            editSessions.remove(lockHolder)
        }

        // Editing a second kit replaces any edit session this player already had open (discarded, not saved).
        editSessions[player.uniqueId]?.let { discardEditSession(player, closeInventory = true) }

        val inv = Bukkit.createInventory(null, EDIT_GUI_SIZE, editGuiTitle(name))
        for ((slot, item) in kit.items) {
            if (slot in EDIT_CONTENT_SLOTS) inv.setItem(slot, item.clone())
        }
        renderEditControlRow(inv, name)

        val overflow = kit.items.filterKeys { it !in EDIT_CONTENT_SLOTS }
        editSessions[player.uniqueId] = EditSession(name, player.uniqueId, inv, kit.items, overflow)
        editLocks[name] = player.uniqueId

        player.openInventory(inv)
        player.playSound(player.location, Sound.BLOCK_CHEST_OPEN, 0.5f, 1.2f)
    }

    fun handleEditSave(player: Player) {
        val session = editSessions[player.uniqueId] ?: return
        val kit = kits[session.kitName]
        if (kit == null) {
            plugin.commsManager.send(player, Component.text("Kit '${session.kitName}' no longer exists.", NamedTextColor.RED))
            discardEditSession(player, closeInventory = true)
            return
        }

        val items = mutableMapOf<Int, ItemStack>()
        for (slot in EDIT_CONTENT_SLOTS) {
            val item = session.inventory.getItem(slot)
            if (item != null && item.type != Material.AIR) items[slot] = item.clone()
        }
        // Slots outside the editable area (shouldn't normally exist) are preserved untouched.
        items.putAll(session.overflowItems)

        saveKit(session.kitName, kit.icon, kit.cooldownHours, kit.permission, items, kit.gamemode, kit.category)

        editSessions.remove(player.uniqueId)
        editLocks.remove(session.kitName)

        plugin.commsManager.send(player, Component.text("Kit '${session.kitName}' has been updated.", NamedTextColor.GREEN))
        player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.2f)
        player.closeInventory()
    }

    fun handleEditCancel(player: Player) {
        if (!editSessions.containsKey(player.uniqueId)) return
        plugin.commsManager.send(player, Component.text("Kit edit cancelled.", NamedTextColor.YELLOW))
        discardEditSession(player, closeInventory = true)
    }

    fun handleEditReload(player: Player) {
        val session = editSessions[player.uniqueId] ?: return
        for (slot in EDIT_CONTENT_SLOTS) session.inventory.setItem(slot, null)
        for ((slot, item) in session.originalItems) {
            if (slot in EDIT_CONTENT_SLOTS) session.inventory.setItem(slot, item.clone())
        }
        plugin.commsManager.send(player, Component.text("Reloaded original kit contents.", NamedTextColor.YELLOW))
        player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.5f, 1.0f)
    }

    /** Ends a player's edit session without saving. Used for close-without-save, quit, cancel, and reload/shutdown. */
    fun discardEditSession(player: Player, closeInventory: Boolean) {
        val session = editSessions.remove(player.uniqueId) ?: return
        if (editLocks[session.kitName] == player.uniqueId) editLocks.remove(session.kitName)
        if (closeInventory && player.isOnline && player.openInventory.topInventory == session.inventory) {
            player.closeInventory()
        }
    }

    private fun renderEditControlRow(inv: Inventory, kitName: String) {
        for (slot in EDIT_CONTROL_SLOTS) inv.setItem(slot, FILLER.clone())

        val info = ItemStack(Material.PAPER)
        info.editMeta { meta ->
            meta.displayName(Component.text("Editing: $kitName", NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false))
            meta.lore(listOf(Component.text("Move items to edit this kit's contents.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)))
        }
        inv.setItem(EDIT_INFO_SLOT, info)

        val reload = ItemStack(Material.ORANGE_DYE)
        reload.editMeta { it.displayName(Component.text("Reload Original", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false)) }
        inv.setItem(EDIT_RELOAD_SLOT, reload)

        val save = ItemStack(Material.LIME_DYE)
        save.editMeta { it.displayName(Component.text("Save Changes", NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false)) }
        inv.setItem(EDIT_SAVE_SLOT, save)

        val cancel = ItemStack(Material.RED_DYE)
        cancel.editMeta { it.displayName(Component.text("Cancel", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false)) }
        inv.setItem(EDIT_CANCEL_SLOT, cancel)
    }

    private fun editGuiTitle(name: String): Component =
        Component.text(EDIT_KIT_TITLE_PREFIX)
            .append(Component.text(name, NamedTextColor.AQUA))
            .decoration(TextDecoration.ITALIC, false)

    /** The cooldown shown on a kit icon: whole hours as before, the reduced cooldown for Lifesteal rank kits. */
    private fun cooldownLabel(player: Player, kit: KitDef): String {
        if (lifestealRankFor(kit) == null) return "${kit.cooldownHours}h"
        val ms = cooldownMillisFor(player, kit)
        return if (ms <= 0L) "none" else formatCooldown(ms)
    }

    private fun formatCooldown(millis: Long): String {
        if (millis <= 0) return "Ready"
        val totalMinutes = millis / 60_000
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
    }
}
