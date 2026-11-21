package com.liam.joshymc.lootbox

import com.liam.joshymc.Joshymc
import com.liam.joshymc.flight.SurvivalFlight
import com.liam.joshymc.gamemode.ServerGamemode
import com.liam.joshymc.lootbox.LootboxStore.Prize
import com.liam.joshymc.lootbox.LootboxStore.RewardRow
import com.liam.joshymc.lootbox.LootboxStore.RewardState
import com.liam.joshymc.manager.AntiDupeManager
import com.liam.joshymc.tutorial.TutorialRules
import com.liam.joshymc.util.VoucherCopy
import com.liam.joshymc.util.depositItemSafely
import com.liam.joshymc.util.freshVoucherCopy
import com.liam.joshymc.util.voucherTemplateProblem
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.bukkit.Sound
import org.bukkit.block.Crafter
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockDispenseEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.block.CrafterCraftEvent
import org.bukkit.event.inventory.FurnaceBurnEvent
import org.bukkit.event.inventory.PrepareItemCraftEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerItemConsumeEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.EnchantmentStorageMeta
import org.bukkit.persistence.PersistentDataType
import org.bukkit.scheduler.BukkitTask
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/**
 * Inventory Lootboxes (issue #1602): ten levels, each with its own loot pool in `lootboxes.yml`.
 *
 * - The item is any material carrying `joshymc:lootbox_level`, `lootbox_version` and an HMAC
 *   `lootbox_signature` over both (secret in `lootbox_secret`, never on an item or in a log), so
 *   names and lore prove nothing and same-level boxes still stack. Only `/lootbox give|giveall`
 *   and `/purchase fulfill ... lootbox` make them, through the purchase delivery queue.
 * - Right-click → pick 5 of 27 mystery slots ([LootboxMenus]) → confirm: the five prizes are drawn
 *   here ([LootboxDraw], SecureRandom; the slots never enter the draw), built and written as one
 *   `CONFIRMED` opening ([LootboxStore]), then exactly one Lootbox is taken, the player is saved and
 *   the opening becomes `REWARDS_COMMITTED`. The animation only shows those stored prizes.
 * - Prizes are delivered from the stored rows: items only into room the player has (never dropped,
 *   never in the event world or while a Ranked inventory is pending), Coins/Credits and chat tag
 *   unlocks in the same transaction as the row, commands once. What doesn't fit waits and is
 *   delivered on join and by a one-minute sweep.
 */
class LootboxManager(private val plugin: Joshymc) : Listener {

    val store = LootboxStore(plugin.databaseManager, { sql, params -> plugin.databaseManager.insertReturningId(sql, *params) })
    val menus = LootboxMenus(plugin, this)

    @Volatile var settings: LootboxSettings? = null
        private set
    @Volatile var problems: List<String> = emptyList()
        private set

    val levelKey = NamespacedKey(plugin, "lootbox_level")
    val versionKey = NamespacedKey(plugin, "lootbox_version")
    val signatureKey = NamespacedKey(plugin, "lootbox_signature")

    private var secret: ByteArray? = null
    private val random = SecureRandom()
    private var sweepTask: BukkitTask? = null
    private var started = false
    /** Players inside a delivery call (main thread), so a nested trigger can't start a second one. */
    private val delivering = HashSet<UUID>()
    /** Display icons per (level, reward id), rebuilt on every load. */
    private val iconCache = HashMap<String, ItemStack>()

    fun start() {
        store.createSchema()
        val fresh = ByteArray(32).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        secret = store.secret(fresh).chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        val recovery = store.recoverAtStartup()
        for (o in recovery.review) {
            plugin.logger.warning(
                "[Lootbox] Opening #${o.id} (Level ${o.level}) for ${o.name} (${o.uuid}) was not saved before the server stopped; now REVIEW. " +
                    "Check whether they still have the Lootbox, then /lootbox admin review ${o.id} grant|void confirm."
            )
        }
        for (r in recovery.interrupted) {
            plugin.logger.warning(
                "[Lootbox] Prize ${r.openingId}/${r.index} (${r.rewardId}) for ${r.uuid} was mid-delivery when the server stopped; now INTERRUPTED. " +
                    "Check their inventory, then /lootbox admin resolve ${r.openingId} ${r.index} resend|delivered confirm."
            )
        }
        loadSettings()
        Bukkit.getPluginManager().registerEvents(this, plugin)
        sweepTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { sweep() }, STARTUP_DELAY_TICKS, SWEEP_INTERVAL_TICKS)
        started = true
    }

    /** `/lootbox reload` and `/joshymc reload`. Committed openings never change: they carry their own prizes. */
    fun reload(): List<String> {
        if (!started) return emptyList()
        loadSettings()
        return problems
    }

    /**
     * `/joshymc reload`: it cancels every task and unregisters every listener before this runs, so
     * end the screens (their animation tasks are gone), listen and sweep again, and reread the file.
     */
    fun restartAfterPluginReload(): List<String> {
        if (!started) return emptyList()
        menus.closeAll()
        HandlerList.unregisterAll(this)
        Bukkit.getPluginManager().registerEvents(this, plugin)
        sweepTask?.cancel()
        sweepTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { sweep() }, SWEEP_INTERVAL_TICKS, SWEEP_INTERVAL_TICKS)
        loadSettings()
        // Prizes of an animation the reload cut short are owed already; hand them over now.
        Bukkit.getOnlinePlayers().forEach { deliverPending(it) }
        return problems
    }

    fun stop() {
        sweepTask?.cancel()
        sweepTask = null
        menus.closeAll()
        if (started) HandlerList.unregisterAll(this)
        delivering.clear()
        started = false
    }

    // ── Settings ─────────────────────────────────────────────────

    private fun loadSettings() {
        iconCache.clear()
        val result = try {
            val file = plugin.configFile(FILE_NAME)
            if (!file.exists()) plugin.saveResource(FILE_NAME, false)
            val yaml = YamlConfiguration()
            yaml.load(file)
            LootboxConfig.parse(object : LootboxConfig.Source {
                override fun get(path: String): Any? = yaml.get(path, null)?.takeUnless { it is ConfigurationSection }
                override fun keys(path: String): List<String>? = (yaml.get(path, null) as? ConfigurationSection)?.getKeys(false)?.toList()
            }, resolver)
        } catch (e: Exception) {
            LootboxConfig.Result(null, listOf("Could not read $FILE_NAME: ${e.message}"))
        }
        problems = result.problems
        settings = result.settings
        result.problems.forEach { plugin.logger.severe("[Lootbox] $FILE_NAME: $it") }
        val s = result.settings
        when {
            s == null -> plugin.logger.severe("[Lootbox] $FILE_NAME is invalid: Lootboxes can't be opened or given until it is fixed (committed openings are still delivered).")
            !s.enabled -> plugin.logger.info("[Lootbox] Disabled in $FILE_NAME (enabled: false). Committed openings are still delivered.")
            else -> {
                val ready = (1..LootboxConfig.LEVELS).filter { levelProblem(it) == null }
                plugin.logger.info("[Lootbox] Loaded $FILE_NAME: levels ready to open: ${if (ready.isEmpty()) "none (add rewards)" else ready.joinToString(", ")}.")
            }
        }
    }

    /** Why [level] can't be opened or given at all, or null when it can (see [LootboxConfig.levelProblem]). */
    fun levelProblem(level: Int): String? = LootboxConfig.levelProblem(settings, level)

    private val resolver = object : LootboxConfig.Resolver {
        override fun itemMaterial(name: String): String? {
            val material = Material.matchMaterial(name)
            return if (material == null || !material.isItem || material.isAir) "'$name' isn't an item material." else null
        }

        override fun guiMaterial(name: String): String? = itemMaterial(name)

        override fun enchantment(material: String, key: String, level: Int): String? {
            val enchant = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(key)) ?: return "unknown enchantment '$key'."
            if (level > enchant.maxLevel) return "${key} level $level is above the vanilla maximum (${enchant.maxLevel})."
            val mat = Material.matchMaterial(material) ?: return null
            if (mat != Material.ENCHANTED_BOOK && mat != Material.BOOK && !enchant.canEnchantItem(ItemStack(mat))) {
                return "$key can't go on ${mat.name.lowercase()}."
            }
            return null
        }

        override fun customEnchantment(material: String, id: String, level: Int): String? {
            val mat = Material.matchMaterial(material) ?: return null
            return try {
                if (plugin.customEnchantManager.applyEnchant(ItemStack(mat), id, level)) null
                else "custom enchant '$id' level $level can't be applied to ${mat.name.lowercase()}."
            } catch (e: Exception) {
                "custom enchants aren't available (${e.message})."
            }
        }

        override fun customItem(id: String): String? {
            val item = plugin.itemManager.getItem(id) ?: return "unknown custom item '$id' (see /joshymc give)."
            if (id == HEART_ITEM_ID) return "Heart items can never be Lootbox rewards."
            if (item.createItemStack(1).type.isAir) return "custom item '$id' builds no item."
            return null
        }

        override fun snapshot(base64: String): String? {
            val item = decode(base64) ?: return "item-base64 can't be decoded on this server."
            if (item.type.isAir) return "item-base64 is an empty item."
            if (plugin.itemManager.getCustomItemId(item) == HEART_ITEM_ID) return "Heart items can never be Lootbox rewards."
            plugin.voucherTemplateProblem(item)?.let { return "item-base64 is a voucher that can't be copied: $it" }
            return null
        }

        override fun crate(id: String): String? =
            if (plugin.crateManager.getCrate(plugin.crateManager.resolveCrateId(id)) == null) "unknown crate '$id'." else null

        override fun chatTag(id: String): String? =
            if (plugin.chatTagManager.getVoucherTag(id) == null) "'$id' isn't a voucher chat tag on this server (tags.yml voucher category)." else null

        @Suppress("DEPRECATION")
        override fun sound(key: String): String? = try {
            Sound.valueOf(key)
            null
        } catch (_: Exception) {
            "unknown sound '$key'."
        }
    }

    // ── Items ────────────────────────────────────────────────────

    private fun signature(level: Int): String =
        SurvivalFlight.sign(checkNotNull(secret) { "Lootboxes haven't started" }, LootboxRules.signaturePayload(ITEM_VERSION, level))

    /**
     * [amount] Level [level] Lootboxes. Null for a level outside 1-10. The give commands only queue
     * ready levels ([levelProblem]); a delivery already queued still builds while lootboxes.yml is
     * being fixed (default look), since a Lootbox's validity never depends on the file.
     */
    fun createItem(level: Int, amount: Int = 1): ItemStack? {
        if (level !in 1..LootboxConfig.LEVELS) return null
        val def = settings?.level(level)
        val material = def?.material?.let { Material.matchMaterial(it) } ?: Material.CHEST
        val stack = ItemStack(material, amount.coerceIn(1, material.maxStackSize.coerceAtLeast(1)))
        stack.editMeta { meta ->
            meta.displayName(legacy(def?.name ?: "&6Level $level Lootbox"))
            val lore = (def?.description ?: emptyList()).map { legacy(it) } + listOf(
                Component.empty(),
                legacy("&ePick 5 of 27 mystery slots for 5 rewards."),
                legacy("&aRight-click to open."),
            )
            meta.lore(lore)
            def?.itemModel?.let { model -> NamespacedKey.fromString(model)?.let { meta.setItemModel(it) } }
            if (def?.glint == true) meta.setEnchantmentGlintOverride(true)
            val pdc = meta.persistentDataContainer
            pdc.set(levelKey, PersistentDataType.INTEGER, level)
            pdc.set(versionKey, PersistentDataType.INTEGER, ITEM_VERSION)
            pdc.set(signatureKey, PersistentDataType.STRING, signature(level))
        }
        return stack
    }

    /** True for anything that claims to be a Lootbox (signed or not): it must never act like its vanilla material. */
    fun looksLikeLootbox(stack: ItemStack?): Boolean {
        if (stack == null || stack.type.isAir || !stack.hasItemMeta()) return false
        return stack.itemMeta.persistentDataContainer.has(levelKey, PersistentDataType.INTEGER)
    }

    /** The level of an authentic Lootbox, or null (not a Lootbox, unknown version, or the signature doesn't match). */
    fun levelOf(stack: ItemStack?): Int? {
        if (!looksLikeLootbox(stack)) return null
        val pdc = stack!!.itemMeta.persistentDataContainer
        val level = pdc.get(levelKey, PersistentDataType.INTEGER) ?: return null
        val version = pdc.get(versionKey, PersistentDataType.INTEGER) ?: return null
        if (version != ITEM_VERSION || level !in 1..LootboxConfig.LEVELS) return null
        val sig = pdc.get(signatureKey, PersistentDataType.STRING)
        val key = secret ?: return null
        return if (SurvivalFlight.verify(key, LootboxRules.signaturePayload(version, level), sig)) level else null
    }

    // ── Opening ──────────────────────────────────────────────────

    /** The gamemode [player] opens in here. */
    fun gamemodeOf(player: Player): ServerGamemode = plugin.gamemodeLockManager.gamemodeOf(player)

    /** Coins/Credits of [gamemode] live on this server (never a Survival balance on Lifesteal or back). */
    fun economyHere(gamemode: ServerGamemode): Boolean =
        TutorialRules.economyBelongsTo(gamemode, plugin.networkIdentityManager.hostedGamemodes)

    /** Why [player] can't open a Level [level] Lootbox right now, as a message; null when they can. */
    fun openProblem(player: Player, level: Int): String? {
        val s = settings ?: return message("disabled", level)
        if (!s.enabled) return message("disabled", level)
        if (!player.hasPermission(USE_PERMISSION)) return "&cYou don't have permission to open Lootboxes."
        if (levelProblem(level) != null) return message("level-unavailable", level)
        val def = s.level(level) ?: return message("level-unavailable", level)
        val gm = gamemodeOf(player)
        if (gm !in def.gamemodes) return message("wrong-gamemode", level).replace("{gamemode}", gm.displayName)
        LootboxRules.refusal(stateOf(player))?.let { return "&c${it.message}" }
        if (!LootboxDraw.canFill(eligiblePool(player, def), LootboxDraw.PRIZES)) return message("nothing-to-win", level)
        return null
    }

    private fun stateOf(player: Player): LootboxRules.State {
        // A check that throws (manager not running) refuses rather than letting the opening through.
        fun safe(check: () -> Boolean): Boolean = try { check() } catch (_: Exception) { true }
        fun safeFalse(check: () -> Boolean): Boolean = try { check() } catch (_: Exception) { false }
        return LootboxRules.State(
            dead = player.isDead,
            modMode = safe { plugin.modModeManager.isModMode(player) },
            rankedHeld = safeFalse { plugin.lifestealRankedManager.holds(player) },
            rankedSpectating = safeFalse { plugin.lifestealRankedManager.isSpectator(player.uniqueId) },
            rankedRestorePending = safeFalse { plugin.lifestealRankedManager.hasPendingRestore(player.uniqueId) },
            inEventWorld = safe { plugin.eventManager.isInEventWorld(player) },
            eventInventoryUnsettled = safe { plugin.eventManager.inventories.unsettled(player) },
            combatTagged = safe { plugin.combatManager.isTagged(player) },
            spectatorMode = player.gameMode == GameMode.SPECTATOR,
        )
    }

    /** The rewards [player] can win from [def] here: enabled, right gamemode, permission, and deliverable on this server. */
    fun eligiblePool(player: Player, def: LootboxLevel): List<LootboxDraw.Entry<LootboxReward>> {
        val gm = gamemodeOf(player)
        return def.activeRewards.filter { reward ->
            (reward.gamemodes ?: def.gamemodes).contains(gm) &&
                (reward.permission == null || player.hasPermission(reward.permission)) &&
                deliverableHere(reward, player, gm)
        }.map { LootboxDraw.Entry(it, it.weight, it.unique || it.type == LootboxRewardType.CHAT_TAG) }
    }

    private fun deliverableHere(reward: LootboxReward, player: Player, gm: ServerGamemode): Boolean = when (reward.type) {
        LootboxRewardType.COINS, LootboxRewardType.CREDITS -> economyHere(gm)
        // An unlock the player already has would be a wasted prize: it isn't in their pool.
        LootboxRewardType.CHAT_TAG -> plugin.chatTagManager.getVoucherTag(reward.reference)?.let { tag ->
            !plugin.chatTagManager.hasUnlocked(player.uniqueId, tag.id) && !(tag.permission != null && player.hasPermission(tag.permission))
        } ?: false
        LootboxRewardType.CUSTOM_ITEM -> scopeAllows(plugin.itemManager.getItem(reward.reference)?.gamemodeScope, player, gm)
        LootboxRewardType.ITEM_SNAPSHOT -> {
            val id = decode(reward.reference)?.let { plugin.itemManager.getCustomItemId(it) }
            scopeAllows(id?.let { plugin.itemManager.getItem(it) }?.gamemodeScope, player, gm)
        }
        else -> true
    }

    /** A gamemode-scoped custom item is only won in its own gamemode, and never where that gamemode is locked away. */
    private fun scopeAllows(scope: com.liam.joshymc.gamemode.GamemodeScope?, player: Player, gm: ServerGamemode): Boolean {
        if (scope == null) return true
        val own = scope.gamemode
        return (own == null || own == gm) && plugin.gamemodeLockManager.isAllowed(scope, player.world)
    }

    sealed interface ConfirmOutcome {
        data class Committed(val openingId: Int, val prizes: List<Prize>, val previews: List<ItemStack>) : ConfirmOutcome
        data class Refused(val message: String) : ConfirmOutcome
    }

    /**
     * Confirms [player]'s pick for a Level [level] Lootbox. Re-checks everything, draws and builds
     * the five prizes, stores them, takes exactly one Lootbox, saves the player and commits. Main
     * thread, one synchronous call: nothing a client sends can land in between.
     */
    fun confirm(player: Player, level: Int, slots: List<Int>): ConfirmOutcome {
        openProblem(player, level)?.let { return ConfirmOutcome.Refused(it) }
        val def = settings?.level(level) ?: return ConfirmOutcome.Refused(message("level-unavailable", level))
        val gm = gamemodeOf(player)
        val boxSlot = findLootboxSlot(player, level) ?: return ConfirmOutcome.Refused(message("lootbox-gone", level))

        val pool = eligiblePool(player, def)
        val drawn = LootboxDraw.draw(pool, LootboxDraw.PRIZES) { random.nextDouble() }
            ?: return ConfirmOutcome.Refused(message("nothing-to-win", level))
        val prizes = ArrayList<Prize>(drawn.size)
        for (reward in drawn) {
            val prize = try {
                buildPrize(level, reward, gm)
            } catch (e: Exception) {
                plugin.logger.severe("[Lootbox] Building Level $level reward '${reward.id}' failed: ${e.message}")
                null
            }
            if (prize == null) {
                plugin.logger.severe("[Lootbox] Level $level reward '${reward.id}' can't be built any more; ${player.name}'s opening was refused (nothing taken). Fix lootboxes.yml, then /lootbox reload.")
                return ConfirmOutcome.Refused(message("level-unavailable", level))
            }
            prizes += prize
        }

        val id = try {
            store.reserve(player.uniqueId, player.name, level, gm.id, slots, prizes)
        } catch (e: Exception) {
            plugin.logger.severe("[Lootbox] Could not store ${player.name}'s Level $level opening: ${e.message}. Nothing was taken.")
            return ConfirmOutcome.Refused(message("save-failed", level))
        }

        // Take exactly one Lootbox from the slot found above (same tick, so it is still there).
        val inventory = player.inventory
        val original = inventory.getItem(boxSlot)?.clone()
        if (original == null || levelOf(original) != level) {
            store.void(id, LootboxStore.OpeningState.CONFIRMED, "lootbox not found at confirm")
            return ConfirmOutcome.Refused(message("lootbox-gone", level))
        }
        inventory.setItem(boxSlot, if (original.amount > 1) original.clone().apply { amount = original.amount - 1 } else null)
        try {
            player.saveData()
        } catch (e: Exception) {
            // Undo: put the Lootbox back and void the opening, so neither a crash nor this run can duplicate or lose it.
            inventory.setItem(boxSlot, original)
            try {
                store.void(id, LootboxStore.OpeningState.CONFIRMED, "player save failed: ${e.message}")
            } catch (inner: Exception) {
                plugin.logger.severe("[Lootbox] Could not void opening #$id after a failed save: ${inner.message}; it becomes REVIEW at the next start.")
            }
            plugin.logger.warning("[Lootbox] Saving ${player.name} after taking a Level $level Lootbox failed (${e.message}); Lootbox returned, opening #$id voided.")
            return ConfirmOutcome.Refused(message("save-failed", level))
        }
        val committed = try {
            store.commit(id)
        } catch (e: Exception) {
            plugin.logger.severe("[Lootbox] Opening #$id for ${player.name}: Lootbox taken and saved, but the commit failed (${e.message}). It becomes REVIEW at the next start.")
            false
        }
        if (!committed) return ConfirmOutcome.Refused(message("review", level).replace("{id}", id.toString()))

        plugin.logger.info(
            "[Lootbox] #$id ${player.name} (${player.uniqueId}) opened Level $level in ${gm.id}, slots ${slots.joinToString(",")}: " +
                prizes.joinToString(", ") { "${it.rewardId} x${it.amount}" }
        )
        val previews = pool.map { iconFor(level, it.value) }.ifEmpty { prizes.map { iconOf(it) } }
        return ConfirmOutcome.Committed(id, prizes, previews)
    }

    /** The slot of one authentic Level [level] Lootbox: main hand first, then off hand, then the rest. */
    private fun findLootboxSlot(player: Player, level: Int): Int? {
        val inv = player.inventory
        if (levelOf(inv.itemInMainHand) == level) return inv.heldItemSlot
        if (levelOf(inv.itemInOffHand) == level) return OFF_HAND_SLOT
        for (slot in 0 until 36) if (levelOf(inv.getItem(slot)) == level) return slot
        return null
    }

    /** Builds [reward] as a stored prize, or null when it can't be built (nothing is substituted). */
    private fun buildPrize(level: Int, reward: LootboxReward, gm: ServerGamemode): Prize? {
        fun prize(payload: String, amount: Int, icon: ItemStack) =
            Prize(reward.id, reward.name, reward.type, payload, amount, encode(decorate(icon, reward, amount)))

        return when (reward.type) {
            LootboxRewardType.ITEM, LootboxRewardType.CUSTOM_ITEM, LootboxRewardType.ITEM_SNAPSHOT,
            LootboxRewardType.CRATE_KEY, LootboxRewardType.CHAT_TAG_VOUCHER -> {
                val unit = buildUnit(reward, gm) ?: return null
                if (unit.type.isAir) return null
                unit.amount = 1
                prize(encode(unit), reward.amount, previewOr(reward, unit))
            }
            LootboxRewardType.CHAT_TAG -> {
                plugin.chatTagManager.getVoucherTag(reward.reference) ?: return null
                prize(reward.reference, 1, previewOr(reward, ItemStack(Material.NAME_TAG)))
            }
            LootboxRewardType.COINS -> prize(plain(reward.money), 1, previewOr(reward, ItemStack(Material.GOLD_INGOT)))
            LootboxRewardType.CREDITS -> prize(plain(reward.money), 1, previewOr(reward, ItemStack(Material.EMERALD)))
            LootboxRewardType.COMMAND -> prize(reward.commands.joinToString("\n"), 1, previewOr(reward, ItemStack(Material.PAPER)))
        }
    }

    /** One unit of an item reward, built by the same factories the admin give commands use. */
    private fun buildUnit(reward: LootboxReward, gm: ServerGamemode): ItemStack? = when (reward.type) {
        LootboxRewardType.ITEM -> {
            val material = Material.matchMaterial(reward.reference) ?: return null
            val item = ItemStack(material)
            val enchants = reward.enchantments.map { (key, level) ->
                (Registry.ENCHANTMENT.get(NamespacedKey.minecraft(key)) ?: return null) to level
            }
            if (enchants.isNotEmpty()) {
                item.editMeta { meta ->
                    for ((enchant, level) in enchants) {
                        if (meta is EnchantmentStorageMeta) meta.addStoredEnchant(enchant, level, false) else meta.addEnchant(enchant, level, false)
                    }
                }
            }
            for ((id, level) in reward.customEnchantments) {
                if (!plugin.customEnchantManager.applyEnchant(item, id, level)) return null
            }
            if (reward.customEnchantments.isNotEmpty()) plugin.customEnchantManager.updateLore(item)
            item
        }
        LootboxRewardType.CUSTOM_ITEM -> {
            val custom = plugin.itemManager.getItem(reward.reference) ?: return null
            if (custom is com.liam.joshymc.potion.BeaconPotionItem) {
                // Beacon potions are made for the gamemode they're won in (issue #1318).
                if (!plugin.beaconPotionManager.settingsFor(gm).isEnabled(custom.effect)) return null
                plugin.beaconPotionManager.create(custom.effect, custom.variant, gm, 1)
            } else custom.createItemStack(1)
        }
        LootboxRewardType.ITEM_SNAPSHOT -> {
            val item = decode(reference = reward.reference) ?: return null
            if (plugin.voucherTemplateProblem(item) != null) return null
            item
        }
        LootboxRewardType.CRATE_KEY -> plugin.crateManager.createKeyStack(plugin.crateManager.resolveCrateId(reward.reference), 1)
        LootboxRewardType.CHAT_TAG_VOUCHER -> plugin.chatTagVoucherManager.createVoucherStack(reward.reference)
        else -> null
    }

    private fun previewOr(reward: LootboxReward, fallback: ItemStack): ItemStack =
        reward.preview?.let { Material.matchMaterial(it) }?.let { ItemStack(it) } ?: fallback.clone()

    /** The GUI icon of [reward] (cached per load): its preview material, or the item itself. Never something a player can keep. */
    fun iconFor(level: Int, reward: LootboxReward): ItemStack {
        val key = "$level:${reward.id}"
        iconCache[key]?.let { return it.clone() }
        val base = try {
            when (reward.type) {
                LootboxRewardType.COINS -> previewOr(reward, ItemStack(Material.GOLD_INGOT))
                LootboxRewardType.CREDITS -> previewOr(reward, ItemStack(Material.EMERALD))
                LootboxRewardType.CHAT_TAG -> previewOr(reward, ItemStack(Material.NAME_TAG))
                LootboxRewardType.COMMAND -> previewOr(reward, ItemStack(Material.PAPER))
                else -> reward.preview?.let { Material.matchMaterial(it) }?.let { ItemStack(it) }
                    ?: buildUnit(reward, ServerGamemode.SURVIVAL) ?: ItemStack(Material.CHEST)
            }
        } catch (_: Exception) {
            ItemStack(Material.CHEST)
        }
        val icon = decorate(base, reward, if (reward.type.delivery == LootboxRewardType.Delivery.ITEM) reward.amount else 1)
        iconCache[key] = icon
        return icon.clone()
    }

    /** Names an icon after its reward, shows the amount and strips anything that would make it a real item. */
    private fun decorate(base: ItemStack, reward: LootboxReward, amount: Int): ItemStack {
        val icon = base.clone()
        icon.amount = amount.coerceIn(1, icon.maxStackSize.coerceAtLeast(1))
        icon.editMeta { meta ->
            meta.displayName(legacy(reward.name))
            meta.lore(listOf(legacy("&7${rewardAmountText(reward.type, amount, reward.money)}")))
        }
        return icon
    }

    fun iconOf(prize: Prize): ItemStack = decode(prize.icon) ?: ItemStack(Material.CHEST)

    fun iconOf(row: RewardRow): ItemStack = decode(row.icon) ?: ItemStack(Material.CHEST)

    private fun rewardAmountText(type: LootboxRewardType, amount: Int, money: Double): String = when (type) {
        LootboxRewardType.COINS -> "Amount: ${plugin.economyManager.format(money)}"
        LootboxRewardType.CREDITS -> "Amount: ${plain(money)} Credits"
        LootboxRewardType.CHAT_TAG -> "Chat tag unlock"
        LootboxRewardType.COMMAND -> "Server reward"
        else -> "Amount: $amount"
    }

    /** "3x Diamond", "$500 Coins", "Chat tag ...": how a prize reads in chat. */
    fun describe(row: RewardRow): Component {
        val name = row.name
        val text = when (row.type) {
            LootboxRewardType.COINS -> "$name &7(${plugin.economyManager.format(row.payload.toDoubleOrNull() ?: 0.0)})"
            LootboxRewardType.CREDITS -> "$name &7(${row.payload} Credits)"
            LootboxRewardType.CHAT_TAG, LootboxRewardType.COMMAND -> name
            else -> "&f${row.amount}x $name"
        }
        return legacy(settings?.message("reward-line")?.let { LootboxRules.fill(it, mapOf("reward" to text)) } ?: "&8 • &f$text")
    }

    // ── Delivery ─────────────────────────────────────────────────

    enum class Delivered { DELIVERED, WAITING_ROOM, WAITING_PLACE, FAILED, SKIPPED }

    /** Delivers what's still owed from opening [openingId] and tells [player] what they got. */
    fun deliverOpening(player: Player, openingId: Int, announce: Boolean) {
        val rows = try {
            store.rewards(openingId).filter { it.uuid == player.uniqueId }
        } catch (e: Exception) {
            plugin.logger.warning("[Lootbox] Could not load opening #$openingId: ${e.message}")
            return
        }
        val opening = store.get(openingId)
        val results = deliverRows(player, rows.filter { it.state == RewardState.PENDING })
        if (announce && opening != null) report(player, opening.level, rows, results)
    }

    /** Delivers every prize [player] is still owed (join, sweep), skipping an opening that is still animating. */
    fun deliverPending(player: Player) {
        if (!player.isOnline) return
        val rows = try {
            store.pendingFor(player.uniqueId)
        } catch (e: Exception) {
            plugin.logger.warning("[Lootbox] Could not load pending prizes for ${player.name}: ${e.message}")
            return
        }
        val animating = menus.animatingOpening(player.uniqueId)
        val due = rows.filter { it.openingId != animating }
        if (due.isEmpty()) return
        val results = deliverRows(player, due)
        val delivered = due.filter { results[key(it)] == Delivered.DELIVERED }
        if (delivered.isNotEmpty()) {
            send(player, legacy("&aLootbox rewards delivered:"))
            delivered.forEach { send(player, describe(it)) }
        }
        noticeWaiting(player, results.values)
    }

    private fun key(row: RewardRow) = "${row.openingId}/${row.index}"

    private fun deliverRows(player: Player, rows: List<RewardRow>): Map<String, Delivered> {
        if (!delivering.add(player.uniqueId)) return emptyMap()
        try {
            val out = LinkedHashMap<String, Delivered>()
            for (row in rows) {
                out[key(row)] = try {
                    deliverRow(player, row)
                } catch (e: Exception) {
                    plugin.logger.severe("[Lootbox] Delivering prize ${key(row)} to ${player.name} threw: ${e.message}")
                    Delivered.SKIPPED
                }
            }
            return out
        } finally {
            delivering.remove(player.uniqueId)
        }
    }

    private class Rejected : RuntimeException()

    private fun deliverRow(player: Player, row: RewardRow): Delivered {
        if (row.uuid != player.uniqueId || row.state != RewardState.PENDING || !player.isOnline) return Delivered.SKIPPED
        val type = row.type ?: return fail(row, "unknown reward type '${row.rawType}'")
        val uuid = player.uniqueId
        val opening = store.get(row.openingId) ?: return Delivered.SKIPPED
        val gm = ServerGamemode.fromId(opening.gamemode) ?: ServerGamemode.SURVIVAL
        return when (type.delivery) {
            LootboxRewardType.Delivery.CURRENCY -> {
                if (!economyHere(gm)) return fail(row, "${gm.displayName} Coins/Credits don't live on this server")
                val amount = row.payload.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 } ?: return fail(row, "invalid stored amount '${row.payload}'")
                try {
                    val done = store.completeWith(row.openingId, row.index, uuid, null) {
                        val ok = if (type == LootboxRewardType.COINS) plugin.economyManager.deposit(uuid, amount) else plugin.creditsManager.deposit(uuid, amount)
                        if (!ok) throw Rejected()
                    }
                    if (done) Delivered.DELIVERED else Delivered.SKIPPED
                } catch (_: Rejected) {
                    fail(row, "the balance manager rejected the amount")
                }
            }
            LootboxRewardType.Delivery.UNLOCK -> {
                val tag = plugin.chatTagManager.getVoucherTag(row.payload) ?: return fail(row, "chat tag '${row.payload}' no longer exists")
                val owned = plugin.chatTagManager.hasUnlocked(uuid, tag.id) || (tag.permission != null && player.hasPermission(tag.permission))
                val done = store.completeWith(row.openingId, row.index, uuid, if (owned) "already owned" else null) {
                    plugin.chatTagManager.unlockTag(uuid, tag.id)
                }
                if (done) {
                    val text = settings?.message(if (owned) "chat-tag-owned" else "chat-tag-unlocked") ?: ""
                    send(player, legacy(LootboxRules.fill(text, mapOf("reward" to row.name))))
                    Delivered.DELIVERED
                } else Delivered.SKIPPED
            }
            LootboxRewardType.Delivery.COMMAND -> {
                // Marked delivered first, then run: a command is never run twice (at most once, logged).
                val commands = row.payload.split('\n').mapNotNull { LootboxRules.command(it, player.name, uuid.toString()) }
                if (commands.isEmpty()) return fail(row, "no runnable command for ${player.name}")
                if (!store.completeWith(row.openingId, row.index, uuid, "commands dispatched") {}) return Delivered.SKIPPED
                for (command in commands) {
                    try {
                        val ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command)
                        plugin.logger.info("[Lootbox] Prize ${key(row)} for ${player.name}: ran '/$command'" + if (ok) "" else " (it reported failure)")
                    } catch (e: Exception) {
                        plugin.logger.severe("[Lootbox] Prize ${key(row)} for ${player.name}: '/$command' threw ${e.message}; it is not run again.")
                    }
                }
                Delivered.DELIVERED
            }
            LootboxRewardType.Delivery.ITEM -> deliverItems(player, row)
        }
    }

    /** Hands over as many items as fit (never dropped), saves the player and records what was delivered. */
    private fun deliverItems(player: Player, snapshot: RewardRow): Delivered {
        if (player.isDead) return Delivered.WAITING_PLACE
        // The event inventory is temporary and a Ranked inventory is replaced later: anything given now would be lost.
        val elsewhere = try {
            plugin.eventManager.isInEventWorld(player) || plugin.eventManager.inventories.unsettled(player) ||
                plugin.lifestealRankedManager.inventoryReplacedLater(player)
        } catch (_: Exception) {
            true
        }
        if (elsewhere) return Delivered.WAITING_PLACE
        val unit = decode(snapshot.payload) ?: return fail(snapshot, "the stored item can't be decoded")
        if (unit.type.isAir) return fail(snapshot, "the stored item is empty")
        val voucher = plugin.freshVoucherCopy(unit)
        if (voucher is VoucherCopy.Refused) return fail(snapshot, "voucher can't be copied: ${voucher.reason}")
        if (!canFitOne(player, unit)) return Delivered.WAITING_ROOM

        val row = store.claim(snapshot.openingId, snapshot.index, player.uniqueId) ?: return Delivered.SKIPPED
        var delivered = 0
        var error: String? = null
        try {
            while (delivered < row.remaining) {
                val stack = if (voucher is VoucherCopy.NotVoucher) {
                    unit.clone().apply { amount = minOf(unit.maxStackSize.coerceAtLeast(1), row.remaining - delivered) }
                } else {
                    // Every voucher gets its own fresh id (and signature), one at a time (issue #1529).
                    when (val copy = plugin.freshVoucherCopy(unit)) {
                        is VoucherCopy.Fresh -> copy.item.apply { amount = 1 }
                        VoucherCopy.NotVoucher -> unit.clone().apply { amount = 1 }
                        is VoucherCopy.Refused -> { error = "voucher can't be copied: ${copy.reason}"; break }
                    }
                }
                val wanted = stack.amount
                val leftover = plugin.depositItemSafely(player, stack)
                val left = leftover?.amount ?: 0
                delivered += wanted - left
                if (left > 0) break
            }
        } catch (e: Exception) {
            error = "delivery error: ${e.message}"
            plugin.logger.severe("[Lootbox] Prize ${key(row)} delivery to ${player.name} failed after $delivered item(s): ${e.message}")
        }
        if (delivered > 0) {
            try {
                player.saveData()
            } catch (e: Exception) {
                plugin.logger.warning("[Lootbox] Could not save ${player.name} after delivering prize ${key(row)}: ${e.message}")
            }
        }
        val left = row.remaining - delivered
        val next = when {
            left <= 0 -> RewardState.DELIVERED
            error != null -> RewardState.FAILED
            else -> RewardState.PENDING
        }
        try {
            store.finish(row.openingId, row.index, delivered, next, error)
        } catch (e: Exception) {
            // Still DELIVERING, so it becomes INTERRUPTED at the next start and is never sent twice.
            plugin.logger.severe("[Lootbox] Delivered $delivered of prize ${key(row)} to ${player.name} but could not record it: ${e.message}. Review it after a restart.")
            return Delivered.SKIPPED
        }
        plugin.logger.info("[Lootbox] Prize ${key(row)} (${row.rewardId}) ${next.name}: delivered $delivered of ${row.remaining} to ${player.name} (${player.uniqueId})" + (error?.let { ", error: $it" } ?: ""))
        return when (next) {
            RewardState.DELIVERED -> Delivered.DELIVERED
            RewardState.FAILED -> Delivered.FAILED
            else -> Delivered.WAITING_ROOM
        }
    }

    private fun canFitOne(player: Player, item: ItemStack): Boolean {
        if (plugin.modModeManager.isModMode(player)) return true // depositItemSafely reports a full backup itself
        return player.inventory.storageContents.any { slot ->
            slot == null || slot.type.isAir || (slot.isSimilar(item) && slot.amount < slot.maxStackSize)
        }
    }

    private fun fail(row: RewardRow, reason: String): Delivered {
        try {
            store.fail(row.openingId, row.index, reason)
        } catch (e: Exception) {
            plugin.logger.severe("[Lootbox] Could not mark prize ${key(row)} FAILED: ${e.message}")
        }
        plugin.logger.warning("[Lootbox] Prize ${key(row)} (${row.rewardId}) for ${row.uuid} FAILED: $reason. See /lootbox admin inspect.")
        return Delivered.FAILED
    }

    /** The summary in chat after an opening: every prize, plus whether something still waits. */
    private fun report(player: Player, level: Int, rows: List<RewardRow>, results: Map<String, Delivered>) {
        val s = settings
        send(player, legacy(LootboxRules.fill(s?.message("rewards-header") ?: "&6Level {level} Lootbox rewards:", mapOf("level" to level.toString()))))
        rows.forEach { send(player, describe(it)) }
        noticeWaiting(player, results.values)
        if (results.values.any { it == Delivered.FAILED }) {
            send(player, legacy("&cA reward couldn't be delivered. Staff can see it with /lootbox admin inspect ${player.name}."))
        }
    }

    private fun noticeWaiting(player: Player, results: Collection<Delivered>) {
        val s = settings
        when {
            results.any { it == Delivered.WAITING_PLACE } -> send(player, legacy(s?.message("rewards-waiting-here") ?: ""))
            results.any { it == Delivered.WAITING_ROOM } -> send(player, legacy(s?.message("rewards-waiting") ?: ""))
        }
    }

    private fun sweep() {
        val online = Bukkit.getOnlinePlayers().map { it.uniqueId }.toSet()
        if (online.isEmpty()) return
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            val due = try {
                store.pendingPlayers().filter { it in online }
            } catch (e: Exception) {
                plugin.logger.warning("[Lootbox] Sweep query failed: ${e.message}")
                return@Runnable
            }
            if (due.isNotEmpty()) Bukkit.getScheduler().runTask(plugin, Runnable {
                due.mapNotNull { Bukkit.getPlayer(it) }.forEach { deliverPending(it) }
            })
        })
    }

    // ── Staff recovery ───────────────────────────────────────────

    /** REVIEW → owed (grant) or cancelled (void). Delivers right away when the player is online. */
    fun review(id: Int, grant: Boolean, staff: String): Boolean {
        val note = "reviewed by $staff: ${if (grant) "granted" else "voided"}"
        val moved = if (grant) store.grantReviewed(id, note) else store.void(id, LootboxStore.OpeningState.REVIEW, note)
        if (moved && grant) store.get(id)?.let { o -> Bukkit.getPlayer(o.uuid)?.let { deliverOpening(it, id, announce = true) } }
        return moved
    }

    /** INTERRUPTED/FAILED prize → sent again (resend) or recorded as delivered. */
    fun resolve(id: Int, index: Int, resend: Boolean, staff: String): Boolean {
        val note = "resolved by $staff: ${if (resend) "resend" else "marked delivered"}"
        val moved = if (resend) store.resend(id, index, note) else store.markDelivered(id, index, note)
        if (moved && resend) store.get(id)?.let { o -> Bukkit.getPlayer(o.uuid)?.let { deliverPending(it) } }
        return moved
    }

    // ── Events ───────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.LOW)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_AIR && event.action != Action.RIGHT_CLICK_BLOCK) return
        val item = event.item ?: return
        if (!looksLikeLootbox(item)) return
        // A right-click never places it or uses it as its vanilla material, from either hand.
        event.setUseItemInHand(org.bukkit.event.Event.Result.DENY)
        event.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY)
        val player = event.player
        // One opening per click: the main hand wins when both hands hold a Lootbox.
        if (event.hand == EquipmentSlot.OFF_HAND && looksLikeLootbox(player.inventory.itemInMainHand)) return
        val level = levelOf(item)
        if (level == null) {
            send(player, legacy(settings?.message("invalid-item") ?: "&cThat Lootbox isn't valid."))
            plugin.logger.warning("[Lootbox] ${player.name} (${player.uniqueId}) used a Lootbox whose signature doesn't verify.")
            try {
                plugin.antiDupeManager.record(player, "Forged Lootbox", AntiDupeManager.RiskLevel.CRITICAL, item = "lootbox", source = "lootbox")
            } catch (_: Exception) {}
            return
        }
        menus.openSelection(player, level)
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onPlace(event: BlockPlaceEvent) {
        if (looksLikeLootbox(event.itemInHand)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onEntityInteract(event: PlayerInteractEntityEvent) {
        // e.g. a chest on a donkey would be equipped and used up.
        val item = if (event.hand == EquipmentSlot.OFF_HAND) event.player.inventory.itemInOffHand else event.player.inventory.itemInMainHand
        if (looksLikeLootbox(item)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onConsume(event: PlayerItemConsumeEvent) {
        if (looksLikeLootbox(event.item)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onPrepareCraft(event: PrepareItemCraftEvent) {
        if (event.inventory.matrix.any { looksLikeLootbox(it) }) event.inventory.result = null
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onCrafter(event: CrafterCraftEvent) {
        val crafter = event.block.state as? Crafter ?: return
        if (crafter.inventory.contents.any { looksLikeLootbox(it) }) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onFuel(event: FurnaceBurnEvent) {
        if (looksLikeLootbox(event.fuel)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onDispense(event: BlockDispenseEvent) {
        if (looksLikeLootbox(event.item)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        Bukkit.getScheduler().runTaskLater(plugin, Runnable { if (player.isOnline) deliverPending(player) }, JOIN_DELAY_TICKS)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        // Prizes are committed; a quit only ends the screens. They're delivered on the next join.
        menus.forget(event.player.uniqueId)
        delivering.remove(event.player.uniqueId)
    }

    // ── Helpers ──────────────────────────────────────────────────

    fun message(id: String, level: Int): String {
        val s = settings
        val text = s?.message(id) ?: LootboxConfig.DEFAULT_MESSAGES[id] ?: id
        val name = s?.level(level)?.name ?: "Level $level Lootbox"
        return LootboxRules.fill(text, mapOf("level" to level.toString(), "name" to name))
    }

    fun legacy(text: String): Component = plugin.commsManager.parseLegacy(text).decoration(TextDecoration.ITALIC, false)

    fun send(player: Player, message: Component) = plugin.commsManager.send(player, message)

    fun playSound(player: Player, id: String) {
        val sound = settings?.sounds?.get(id) ?: return
        @Suppress("DEPRECATION")
        val bukkit = try { Sound.valueOf(sound.sound) } catch (_: Exception) { return }
        player.playSound(player.location, bukkit, sound.volume, sound.pitch)
    }

    fun encode(item: ItemStack): String = Base64.getEncoder().encodeToString(item.serializeAsBytes())

    fun decode(reference: String): ItemStack? = try {
        ItemStack.deserializeBytes(Base64.getDecoder().decode(reference))
    } catch (_: Exception) {
        null
    }

    private fun plain(value: Double): String = java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

    companion object {
        const val FILE_NAME = "lootboxes.yml"
        const val ITEM_VERSION = 1
        const val USE_PERMISSION = "joshymc.lootbox.use"
        const val HEART_ITEM_ID = "lifesteal_heart"
        /** PlayerInventory index of the off hand. */
        private const val OFF_HAND_SLOT = 40
        private const val JOIN_DELAY_TICKS = 100L
        private const val STARTUP_DELAY_TICKS = 200L
        private const val SWEEP_INTERVAL_TICKS = 20L * 60
    }
}
