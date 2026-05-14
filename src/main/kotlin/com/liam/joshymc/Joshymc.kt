package com.liam.joshymc

import com.liam.joshymc.gui.GuiManager
import com.liam.joshymc.discord.DiscordManager
import com.liam.joshymc.link.LinkManager
import com.liam.joshymc.manager.CombatManager
import com.liam.joshymc.manager.CommandManager
import com.liam.joshymc.manager.DatabaseManager
import com.liam.joshymc.manager.ItemManager
import com.liam.joshymc.manager.LagCleanerManager
import com.liam.joshymc.manager.ListenerManager
import com.liam.joshymc.manager.RecipeManager
import com.liam.joshymc.manager.ResourcePackManager
import com.liam.joshymc.manager.SettingsManager
import com.liam.joshymc.enchant.CustomEnchant
import com.liam.joshymc.enchant.CustomEnchantManager
import com.liam.joshymc.enchant.EnchantTarget
import com.liam.joshymc.manager.AFKManager
import com.liam.joshymc.manager.AdminManager
import com.liam.joshymc.manager.AnnouncementManager
import com.liam.joshymc.manager.AutoRestartManager
import com.liam.joshymc.manager.AntiCheatManager
import com.liam.joshymc.manager.PlaytimeManager
import com.liam.joshymc.manager.PunishmentManager
import com.liam.joshymc.manager.ResourceWorldManager
import com.liam.joshymc.manager.ScoreboardManager
import com.liam.joshymc.manager.ChatTagManager
import com.liam.joshymc.manager.MarketManager
import com.liam.joshymc.manager.DailyQuestManager
import com.liam.joshymc.manager.QuestManager
import com.liam.joshymc.manager.EmoteManager
import com.liam.joshymc.manager.FishingManager
import com.liam.joshymc.manager.GadgetManager
import com.liam.joshymc.manager.GlowManager
import com.liam.joshymc.manager.JoinEffectManager
import com.liam.joshymc.manager.KillEffectManager
import com.liam.joshymc.manager.TrailManager
import com.liam.joshymc.manager.SkillManager
import com.liam.joshymc.manager.SpawnWorldManager
import com.liam.joshymc.manager.TalismanManager
import com.liam.joshymc.manager.ClaimManager
import com.liam.joshymc.manager.RankManager
import com.liam.joshymc.manager.ServerShopManager
import com.liam.joshymc.manager.AuctionManager
import com.liam.joshymc.manager.CommunicationsManager
import com.liam.joshymc.manager.EconomyManager
import com.liam.joshymc.manager.CrateManager
import com.liam.joshymc.manager.HologramManager
import com.liam.joshymc.manager.KitManager
import com.liam.joshymc.manager.NPCManager
import com.liam.joshymc.manager.HopperPlusManager
import com.liam.joshymc.manager.SignShopManager
import com.liam.joshymc.manager.SpawnerManager
import com.liam.joshymc.manager.StorageManager
import com.liam.joshymc.manager.TeamManager
import com.liam.joshymc.manager.TimezoneManager
import com.liam.joshymc.manager.TradeManager
import com.liam.joshymc.manager.WarpManager
import com.liam.joshymc.command.VanishCommand
import com.liam.joshymc.command.WorldCommand
import com.liam.joshymc.manager.ArenaManager
import com.liam.joshymc.manager.PortalManager
import com.liam.joshymc.manager.SpawnDecorationManager
import com.liam.joshymc.manager.VoteManager
import com.liam.joshymc.manager.WorldFlagManager
import org.bukkit.Bukkit
import org.bukkit.GameRule
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.event.HandlerList
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.util.Properties

class Joshymc : JavaPlugin() {

    companion object {
        lateinit var instance: Joshymc
            private set
    }

    fun isFeatureEnabled(feature: String): Boolean {
        return config.getBoolean("features.$feature", true)
    }

    /**
     * Resolve a config file by name. Looks in:
     *   1. plugins/joshymc/config/<name> (preferred)
     *   2. plugins/joshymc/<name> (legacy)
     *
     * If neither exists, returns the legacy plugin-root path so the caller can
     * create a new file there.
     */
    fun configFile(name: String): File {
        val configDir = File(dataFolder, "config")
        val inSubdir = File(configDir, name)
        if (inSubdir.exists()) return inSubdir

        val inRoot = File(dataFolder, name)
        if (inRoot.exists()) return inRoot

        // Neither exists — default to plugin root for backwards compatibility
        return inRoot
    }

    lateinit var itemManager: ItemManager
        private set
    lateinit var commandManager: CommandManager
        private set
    lateinit var recipeManager: RecipeManager
        private set
    lateinit var listenerManager: ListenerManager
        private set
    lateinit var discordManager: DiscordManager
        private set
    lateinit var linkManager: LinkManager
        private set
    lateinit var resourcePackManager: ResourcePackManager
        private set
    lateinit var databaseManager: DatabaseManager
        private set
    lateinit var settingsManager: SettingsManager
        private set
    lateinit var lagCleanerManager: LagCleanerManager
        private set
    lateinit var combatManager: CombatManager
        private set
    lateinit var warpManager: WarpManager
        private set
    lateinit var commsManager: CommunicationsManager
        private set
    lateinit var afkManager: AFKManager
        private set
    lateinit var kitManager: KitManager
        private set
    lateinit var tradeManager: TradeManager
        private set
    lateinit var storageManager: StorageManager
        private set
    lateinit var hologramManager: HologramManager
        private set
    lateinit var leaderboardManager: com.liam.joshymc.manager.LeaderboardManager
        private set
    lateinit var npcManager: NPCManager
        private set
    lateinit var crateManager: CrateManager
        private set
    lateinit var auctionManager: AuctionManager
        private set
    lateinit var signShopManager: SignShopManager
        private set
    lateinit var hopperPlusManager: HopperPlusManager
        private set
    lateinit var spawnerManager: SpawnerManager
        private set
    lateinit var teamManager: TeamManager
        private set
    lateinit var economyManager: EconomyManager
        private set
    lateinit var guiManager: GuiManager
        private set
    lateinit var antiCheatManager: AntiCheatManager
        private set
    lateinit var customEnchantManager: CustomEnchantManager
        private set
    lateinit var serverShopManager: ServerShopManager
        private set
    lateinit var rankManager: RankManager
        private set
    lateinit var claimManager: ClaimManager
        private set
    lateinit var punishmentManager: PunishmentManager
        private set
    lateinit var resourceWorldManager: ResourceWorldManager
        private set
    lateinit var scoreboardManager: ScoreboardManager
        private set
    lateinit var announcementManager: AnnouncementManager
        private set
    lateinit var autoRestartManager: AutoRestartManager
        private set
    lateinit var playtimeManager: PlaytimeManager
        private set
    lateinit var chatTagManager: ChatTagManager
        private set
    lateinit var marketManager: MarketManager
        private set
    lateinit var chatGamesManager: com.liam.joshymc.manager.ChatGamesManager
        private set
    lateinit var vanishCommand: VanishCommand
    lateinit var rtpCommand: com.liam.joshymc.command.RtpCommand
    lateinit var questManager: QuestManager
        private set
    lateinit var dailyQuestManager: DailyQuestManager
        private set
    lateinit var talismanManager: TalismanManager
        private set
    lateinit var fishingManager: FishingManager
        private set
    lateinit var skillManager: SkillManager
        private set
    lateinit var trailManager: TrailManager
        private set
    lateinit var killEffectManager: KillEffectManager
        private set
    lateinit var joinEffectManager: JoinEffectManager
        private set
    lateinit var emoteManager: EmoteManager
        private set
    lateinit var glowManager: GlowManager
        private set
    lateinit var gadgetManager: GadgetManager
        private set
    lateinit var adminManager: AdminManager
        private set
    lateinit var worldFlagManager: WorldFlagManager
        private set
    lateinit var arenaManager: ArenaManager
        private set
    lateinit var portalManager: PortalManager
        private set
    lateinit var voteManager: VoteManager
        private set
    lateinit var spawnDecorationManager: SpawnDecorationManager
        private set
    lateinit var spawnWorldManager: SpawnWorldManager
        private set
    lateinit var timezoneManager: TimezoneManager
        private set

    override fun onEnable() {
        instance = this

        saveDefaultConfig()
        migrateConfig()

        databaseManager = DatabaseManager(this)
        databaseManager.start()

        settingsManager = SettingsManager(this)
        settingsManager.start()
        registerSettings()

        rankManager = RankManager(this)
        rankManager.start()

        chatTagManager = ChatTagManager(this)
        chatTagManager.start()

        commsManager = CommunicationsManager(this)
        commsManager.start()

        economyManager = EconomyManager(this)
        economyManager.start()

        guiManager = GuiManager()

        combatManager = CombatManager(this)
        itemManager = ItemManager(this)
        recipeManager = RecipeManager(this)
        commandManager = CommandManager(this)
        listenerManager = ListenerManager(this)
        linkManager = LinkManager(this)
        warpManager = WarpManager(this)
        kitManager = KitManager(this)
        storageManager = StorageManager(this)
        tradeManager = TradeManager(this)
        hologramManager = HologramManager(this)
        leaderboardManager = com.liam.joshymc.manager.LeaderboardManager(this)
        npcManager = NPCManager(this)
        crateManager = CrateManager(this)
        auctionManager = AuctionManager(this)
        signShopManager = SignShopManager(this)
        hopperPlusManager = HopperPlusManager(this)
        spawnerManager = SpawnerManager(this)
        teamManager = TeamManager(this)
        resourcePackManager = ResourcePackManager(this)
        discordManager = DiscordManager(this)
        lagCleanerManager = LagCleanerManager(this)
        afkManager = AFKManager(this)
        antiCheatManager = AntiCheatManager(this)
        claimManager = ClaimManager(this)
        punishmentManager = PunishmentManager(this)
        resourceWorldManager = ResourceWorldManager(this)
        timezoneManager = TimezoneManager(this)
        timezoneManager.start()
        scoreboardManager = ScoreboardManager(this)
        announcementManager = AnnouncementManager(this)
        autoRestartManager = AutoRestartManager(this)
        playtimeManager = PlaytimeManager(this)
        questManager = QuestManager(this)
        dailyQuestManager = DailyQuestManager(this)
        talismanManager = TalismanManager(this)
        fishingManager = FishingManager(this)
        skillManager = SkillManager(this)
        trailManager = TrailManager(this)
        killEffectManager = KillEffectManager(this)
        joinEffectManager = JoinEffectManager(this)
        emoteManager = EmoteManager(this)
        glowManager = GlowManager(this)
        gadgetManager = GadgetManager(this)
        adminManager = AdminManager(this)
        worldFlagManager = WorldFlagManager(this)
        arenaManager = ArenaManager(this)
        portalManager = PortalManager(this)
        voteManager = VoteManager(this)
        spawnDecorationManager = SpawnDecorationManager(this)
        spawnWorldManager = SpawnWorldManager(this)
        customEnchantManager = CustomEnchantManager(this)
        serverShopManager = ServerShopManager(this)

        itemManager.registerAll()
        recipeManager.registerAll()
        commandManager.registerAll()
        listenerManager.registerAll()
        linkManager.load()
        warpManager.start()
        kitManager.start()
        storageManager.start()
        hologramManager.start()
        leaderboardManager.start()
        if (isFeatureEnabled("npcs")) npcManager.start()
        crateManager.start()
        auctionManager.start()
        signShopManager.start()
        hopperPlusManager.start()
        spawnerManager.start()
        teamManager.start()
        resourcePackManager.start()
        lagCleanerManager.start()
        combatManager.start()
        afkManager.start()
        if (isFeatureEnabled("anticheat")) antiCheatManager.start()
        claimManager.start()
        punishmentManager.start()
        if (isFeatureEnabled("resource-world")) resourceWorldManager.start()
        scoreboardManager.start()
        announcementManager.start()
        autoRestartManager.start()
        playtimeManager.start()
        registerEnchants()
        if (isFeatureEnabled("custom-enchants")) customEnchantManager.start()
        serverShopManager.start()

        marketManager = MarketManager(this)
        if (isFeatureEnabled("market")) marketManager.start()

        chatGamesManager = com.liam.joshymc.manager.ChatGamesManager(this)
        if (isFeatureEnabled("chat-games")) chatGamesManager.start()

        if (isFeatureEnabled("quests")) questManager.start()
        if (isFeatureEnabled("quests")) dailyQuestManager.start()
        if (isFeatureEnabled("talismans")) talismanManager.start()
        if (isFeatureEnabled("custom-fishing")) fishingManager.start()
        if (isFeatureEnabled("skills")) skillManager.start()
        if (isFeatureEnabled("trails")) trailManager.start()
        if (isFeatureEnabled("kill-effects")) killEffectManager.start()
        if (isFeatureEnabled("join-effects")) joinEffectManager.start()
        if (isFeatureEnabled("emotes")) emoteManager.start()
        if (isFeatureEnabled("gadgets")) gadgetManager.start()
        if (isFeatureEnabled("glow")) glowManager.start()
        adminManager.start()
        worldFlagManager.start()
        // Start spawn world BEFORE arenas so the world exists when arena ticks begin
        spawnWorldManager.start()

        if (isFeatureEnabled("arenas")) arenaManager.start()
        if (isFeatureEnabled("portals")) portalManager.start()
        if (isFeatureEnabled("voting")) voteManager.start()
        spawnDecorationManager.start()

        discordManager.start()

        // Ensure dungeon void world exists
        WorldCommand.ensureDungeonWorld(this)

        // Ensure default overworld has structures disabled (only resource world has structures)
        enforceServerStructures()

        // Apply world borders and game rules on a 1-tick delay (after all worlds are loaded)
        server.scheduler.runTaskLater(this, Runnable { applyWorldSettings() }, 1L)

        // After every plugin has finished loading, try to auto-install the
        // Grim AntiCheat → JoshyMC admin-panel bridge so flags surface in
        // /admin without admins having to hand-edit punishments.yml. Safe
        // no-op if Grim isn't installed.
        server.scheduler.runTaskLater(this, Runnable {
            com.liam.joshymc.manager.GrimIntegration(this).start()
        }, 40L)

        logger.info("JoshyMC has been enabled!")
    }

    override fun onDisable() {
        storageManager.saveOpenVaults()
        hologramManager.stop()
        npcManager.stop()
        crateManager.stop()
        auctionManager.stop()
        hopperPlusManager.stop()
        spawnerManager.stop()
        afkManager.stop()
        antiCheatManager.stop()
        portalManager.stop()
        voteManager.stop()
        spawnDecorationManager.stop()
        listenerManager.passiveEnchantListener?.stop()
        claimManager.stop()
        scoreboardManager.stop()
        announcementManager.stop()
        autoRestartManager.stop()
        playtimeManager.stop()
        dailyQuestManager.stop()
        resourceWorldManager.stop()
        combatManager.stop()
        lagCleanerManager.stop()
        resourcePackManager.shutdown()
        discordManager.shutdown()
        databaseManager.shutdown()
        logger.info("JoshyMC has been disabled!")
    }

    /**
     * Full soft reload: tears down listeners, tasks, recipes, and managers,
     * reloads config from disk, then re-initializes everything.
     * Commands are not re-registered (they persist via plugin.yml).
     */
    fun reload() {
        // Wrap every manager call in `safe(name) { … }` so one broken stop() or
        // start() doesn't abort the whole reload and leave the plugin half-up.
        // The exception is logged with the manager name so the offender is
        // identifiable in the console output.

        // 1. Cancel all scheduled tasks (Discord flush, status updater, etc.)
        safe("scheduler.cancelTasks") { server.scheduler.cancelTasks(this) }

        // 2. Unregister all event listeners owned by this plugin
        safe("HandlerList.unregisterAll") { HandlerList.unregisterAll(this) }

        // 3. Shutdown services
        safe("storageManager.saveOpenVaults") { storageManager.saveOpenVaults() }
        safe("hologramManager.stop") { hologramManager.stop() }
        safe("npcManager.stop") { npcManager.stop() }
        safe("crateManager.stop") { crateManager.stop() }
        safe("auctionManager.stop") { auctionManager.stop() }
        safe("hopperPlusManager.stop") { hopperPlusManager.stop() }
        safe("spawnerManager.stop") { spawnerManager.stop() }
        safe("afkManager.stop") { afkManager.stop() }
        safe("antiCheatManager.stop") { antiCheatManager.stop() }
        safe("combatManager.stop") { combatManager.stop() }
        safe("autoRestartManager.stop") { autoRestartManager.stop() }
        safe("lagCleanerManager.stop") { lagCleanerManager.stop() }
        safe("resourcePackManager.shutdown") { resourcePackManager.shutdown() }
        safe("discordManager.shutdown") { discordManager.shutdown() }

        // 4. Clear registries
        safe("itemManager.clear") { itemManager.clear() }
        safe("recipeManager.clear") { recipeManager.clear() }

        // 5. Reload config from disk
        safe("reloadConfig") { reloadConfig() }

        // 6. Re-register everything
        safe("itemManager.registerAll") { itemManager.registerAll() }
        safe("recipeManager.registerAll") { recipeManager.registerAll() }
        safe("listenerManager.registerAll") { listenerManager.registerAll() }
        safe("linkManager.load") { linkManager.load() }
        // WarpManager uses DB directly, no reload needed
        safe("kitManager.start") { kitManager.start() }
        safe("storageManager.start") { storageManager.start() }
        safe("hologramManager.start") { hologramManager.start() }
        if (isFeatureEnabled("npcs")) safe("npcManager.start") { npcManager.start() }
        safe("crateManager.start") { crateManager.start() }
        safe("auctionManager.start") { auctionManager.start() }
        safe("signShopManager.start") { signShopManager.start() }
        safe("hopperPlusManager.start") { hopperPlusManager.start() }
        safe("spawnerManager.start") { spawnerManager.start() }
        safe("teamManager.start") { teamManager.start() }
        safe("resourcePackManager.start") { resourcePackManager.start() }

        // 7. Commands just get new instances (executors are swapped, not re-registered)
        safe("commandManager.registerAll") { commandManager.registerAll() }

        // 8. Restart Discord
        safe("discordManager.start") { discordManager.start() }

        // 9. Restart lag cleaner, combat manager, chat manager, and AFK manager with new config
        safe("rankManager.start") { rankManager.start() }
        safe("commsManager.start") { commsManager.start() }
        safe("lagCleanerManager.start") { lagCleanerManager.start() }
        safe("combatManager.start") { combatManager.start() }
        safe("autoRestartManager.start") { autoRestartManager.start() }
        safe("afkManager.start") { afkManager.start() }
        safe("antiCheatManager.start") { antiCheatManager.start() }
        safe("registerEnchants") { registerEnchants() }
        safe("customEnchantManager.start") { customEnchantManager.start() }

        logger.info("JoshyMC has been fully reloaded!")
    }

    private inline fun safe(label: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            logger.severe("[Reload] $label failed: ${e.javaClass.simpleName}: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * Register all custom enchants. Add new enchants here.
     */
    private fun registerEnchants() {
        val cem = customEnchantManager

        // ── Sword ───────────────────────────────────────
        cem.register(CustomEnchant("lifesteal", "Lifesteal", 3, EnchantTarget.SWORD,
            description = "Heal a percentage of damage dealt"))
        cem.register(CustomEnchant("execute", "Execute", 3, EnchantTarget.SWORD,
            description = "More damage to low HP targets"))
        cem.register(CustomEnchant("bleed", "Bleed", 3, EnchantTarget.SWORD,
            description = "Causes damage over time"))
        cem.register(CustomEnchant("adrenaline", "Adrenaline", 3, EnchantTarget.SWORD,
            description = "More kills = more damage in a time window"))
        cem.register(CustomEnchant("striker", "Striker", 3, EnchantTarget.SWORD,
            conflicts = setOf("fire_aspect_conflict"),
            description = "Chance to strike lightning on the enemy"))

        // ── Axe ─────────────────────────────────────────
        cem.register(CustomEnchant("cleave", "Cleave", 3, EnchantTarget.AXE,
            description = "Sweeping edge on axes"))
        cem.register(CustomEnchant("berserk", "Berserk", 3, EnchantTarget.AXE,
            description = "Strength boost with a negative side effect"))
        cem.register(CustomEnchant("paralysis", "Paralysis", 3, EnchantTarget.AXE,
            description = "Chance to stun the target"))
        cem.register(CustomEnchant("blizzard", "Blizzard", 3, EnchantTarget.AXE,
            description = "Applies freezing slowness to opponent"))

        // ── Helmet ──────────────────────────────────────
        cem.register(CustomEnchant("night_vision", "Night Vision", 1, EnchantTarget.HELMET,
            description = "Permanent night vision while worn"))
        cem.register(CustomEnchant("clarity", "Clarity", 3, EnchantTarget.HELMET,
            description = "Immune to blindness and nausea, reduces negative effect duration"))
        cem.register(CustomEnchant("focus", "Focus", 3, EnchantTarget.HELMET,
            description = "Reduces axe damage taken"))
        cem.register(CustomEnchant("xray", "Xray", 5, EnchantTarget.HELMET,
            description = "While crouching, ores glow nearby. Higher levels = larger radius"))

        // ── Chestplate ──────────────────────────────────
        cem.register(CustomEnchant("overload", "Overload", 3, EnchantTarget.CHESTPLATE,
            description = "Increases max hearts"))
        cem.register(CustomEnchant("dodge", "Dodge", 3, EnchantTarget.CHESTPLATE,
            description = "Chance to completely negate damage"))
        cem.register(CustomEnchant("guardian", "Guardian", 3, EnchantTarget.CHESTPLATE,
            description = "Grants regeneration when at low health"))

        // ── Leggings ────────────────────────────────────
        cem.register(CustomEnchant("shockwave", "Shockwave", 3, EnchantTarget.LEGGINGS,
            description = "Reduces knockback taken"))
        cem.register(CustomEnchant("valor", "Valor", 3, EnchantTarget.LEGGINGS,
            description = "Reduces crystal/explosion damage"))
        cem.register(CustomEnchant("curse_swap", "Curse", 3, EnchantTarget.LEGGINGS,
            description = "Chance to swap health with your attacker"))

        // ── Boots ───────────────────────────────────────
        cem.register(CustomEnchant("gears", "Gears", 3, EnchantTarget.BOOTS,
            description = "Permanent speed boost while worn"))
        cem.register(CustomEnchant("springs", "Springs", 3, EnchantTarget.BOOTS,
            description = "Permanent jump boost while worn"))
        cem.register(CustomEnchant("featherweight", "Featherweight", 1, EnchantTarget.BOOTS,
            description = "No fall damage"))
        cem.register(CustomEnchant("rockets", "Rockets", 1, EnchantTarget.BOOTS,
            description = "Levitation when below 2 hearts"))

        // ── Shovel ──────────────────────────────────────
        cem.register(CustomEnchant("glass_breaker", "Glass Breaker", 1, EnchantTarget.SHOVEL,
            description = "Breaks glass instantly"))

        // ── Pickaxe / Shovel / Axe ──────────────────────
        cem.register(CustomEnchant("magnet", "Magnet", 1, EnchantTarget.ALL_TOOLS,
            description = "Mined blocks go straight to your inventory"))

        // ── Pickaxe ─────────────────────────────────────
        cem.register(CustomEnchant("autosmelt", "Autosmelt", 1, EnchantTarget.PICKAXE,
            description = "Automatically smelts mined ores"))
        cem.register(CustomEnchant("experience", "Experience", 3, EnchantTarget.PICKAXE,
            description = "Gain XP from mining stone"))
        cem.register(CustomEnchant("condenser", "Condenser", 1, EnchantTarget.PICKAXE,
            description = "Automatically condenses ores into blocks"))
        cem.register(CustomEnchant("explosive", "Explosive", 3, EnchantTarget.PICKAXE,
            description = "Chance to blow up a 3x3x3 area"))

        // ── Hoe ─────────────────────────────────────────
        cem.register(CustomEnchant("ground_pound", "Ground Pound", 1, EnchantTarget.HOE,
            description = "Breaks crops in a 3x3 area"))
        cem.register(CustomEnchant("great_harvest", "Great Harvest", 1, EnchantTarget.HOE,
            description = "Auto-replants crops when broken"))
        cem.register(CustomEnchant("blessing", "Blessing", 1, EnchantTarget.HOE,
            description = "Auto-regrows replanted crops (requires Great Harvest)"))
    }

    /**
     * Ensures server.properties has generate-structures=false so the main
     * overworld doesn't generate structures. The resource world (created via
     * WorldCreator) will still have structures since it uses its own settings.
     * Requires a server restart to take effect on new chunks.
     */
    private fun enforceServerStructures() {
        val propsFile = File(server.worldContainer.parentFile, "server.properties")
        if (!propsFile.exists()) return

        try {
            val props = Properties()
            propsFile.inputStream().use { props.load(it) }

            val current = props.getProperty("generate-structures", "true")
            if (current == "true") {
                props.setProperty("generate-structures", "false")
                propsFile.outputStream().use { props.store(it, null) }
                logger.info("[WorldSetup] Set generate-structures=false in server.properties. Restart for full effect on new chunks.")
            }
        } catch (e: Exception) {
            logger.warning("[WorldSetup] Could not update server.properties: ${e.message}")
        }
    }

    /**
     * Apply world borders and structure settings to Nether, End, and Overworld.
     * - Nether and End get a 10k x 10k border
     * - Default overworld has structures disabled (resource world keeps them)
     */
    fun applyWorldSettings() {
        val resourceWorldName = config.getString("resource-world.world-name", "resource") ?: "resource"

        // Per-world border sizes (legacy fallback: nether-end-size)
        val legacy = config.getDouble("world-borders.nether-end-size", 10000.0)
        val overworldSize = config.getDouble("world-borders.overworld", 10000.0)
        val resourceSize = config.getDouble("world-borders.resource", 10000.0)
        val netherSize = config.getDouble("world-borders.nether", legacy)
        val endSize = config.getDouble("world-borders.end", legacy)

        for (world in Bukkit.getWorlds()) {
            when (world.environment) {
                World.Environment.NETHER -> {
                    world.worldBorder.center = Location(world, 0.0, 0.0, 0.0)
                    world.worldBorder.size = netherSize
                    logger.info("[WorldSetup] Nether '${world.name}' border set to ${netherSize.toInt()}x${netherSize.toInt()}.")
                }
                World.Environment.THE_END -> {
                    world.worldBorder.center = Location(world, 0.0, 0.0, 0.0)
                    world.worldBorder.size = endSize
                    logger.info("[WorldSetup] End '${world.name}' border set to ${endSize.toInt()}x${endSize.toInt()}.")
                }
                World.Environment.NORMAL -> {
                    if (world.name == "spawn") {
                        // spawn world has its own border managed elsewhere
                    } else if (world.name == resourceWorldName) {
                        world.worldBorder.center = Location(world, 0.0, 0.0, 0.0)
                        world.worldBorder.size = resourceSize
                        logger.info("[WorldSetup] Resource '${world.name}' border set to ${resourceSize.toInt()}x${resourceSize.toInt()}.")
                    } else {
                        world.worldBorder.center = Location(world, 0.0, 0.0, 0.0)
                        world.worldBorder.size = overworldSize
                        logger.info("[WorldSetup] Overworld '${world.name}' border set to ${overworldSize.toInt()}x${overworldSize.toInt()}.")
                        world.setGameRule(GameRule.DO_TRADER_SPAWNING, false)
                        disableStructureGeneration(world)
                    }
                }
                else -> {}
            }
        }
    }

    /**
     * Disable structure generation for an already-loaded world.
     *
     * Structure generation is baked into `level.dat` when the world is first
     * created, so flipping `generate-structures=false` in server.properties
     * only affects NEWLY created worlds — it does nothing for the existing
     * main overworld. We have to patch the in-memory world gen options.
     *
     * Tries three strategies in order:
     *   1. Bukkit's `World.setCanGenerateStructures(boolean)` if this Paper
     *      build exposes it.
     *   2. NMS reflection into the backing `WorldOptions` record — builds a
     *      replacement record with generateStructures=false and swaps it.
     *   3. Gives up and logs a clear manual-fix instruction.
     *
     * Wrapped in try/catch so a failed reflection path never breaks startup.
     */
    private fun disableStructureGeneration(world: World) {
        // Already off? Nothing to do.
        if (!world.canGenerateStructures()) {
            logger.info("[WorldSetup] Structures already disabled on '${world.name}'.")
            return
        }

        // Strategy 1: Bukkit API (newer Paper builds may expose a setter).
        try {
            val setter = world.javaClass.getMethod("setCanGenerateStructures", Boolean::class.javaPrimitiveType)
            setter.invoke(world, false)
            logger.info("[WorldSetup] Disabled structures on '${world.name}' via Bukkit API.")
            return
        } catch (_: NoSuchMethodException) {
            // Fall through to reflection.
        } catch (e: Exception) {
            logger.warning("[WorldSetup] Bukkit setCanGenerateStructures failed: ${e.message}")
        }

        // Strategy 2: NMS reflection. Paper 1.21.x:
        //   CraftWorld.getHandle() -> ServerLevel
        //   ServerLevel.getServer().getWorldData() -> PrimaryLevelData (global)
        //   PrimaryLevelData.worldGenOptions() -> WorldOptions (record)
        //   Swap in a new WorldOptions record with generateStructures=false.
        try {
            val handle = world.javaClass.getMethod("getHandle").invoke(world)
            val server = handle.javaClass.getMethod("getServer").invoke(handle)
            val worldData = server.javaClass.getMethod("getWorldData").invoke(server)
            // worldGenOptions() is the accessor on PrimaryLevelData since 1.19+.
            val optsMethod = worldData.javaClass.methods.firstOrNull {
                it.name == "worldGenOptions" && it.parameterCount == 0
            } ?: worldData.javaClass.methods.firstOrNull {
                it.name == "getWorldGenOptions" && it.parameterCount == 0
            } ?: throw NoSuchMethodException("worldGenOptions() not found on ${worldData.javaClass.name}")

            val opts = optsMethod.invoke(worldData)
            val optsCls = opts.javaClass
            val seed = optsCls.getMethod("seed").invoke(opts) as Long
            val generateBonusChest = optsCls.getMethod("generateBonusChest").invoke(opts) as Boolean
            val dimensions = optsCls.getMethod("dimensions").invoke(opts)

            // WorldOptions(long seed, boolean generateStructures, boolean generateBonusChest, Registry<LevelStem> dimensions)
            val ctor = optsCls.constructors.firstOrNull { it.parameterCount == 4 }
                ?: throw NoSuchMethodException("WorldOptions 4-arg ctor not found")
            val newOpts = ctor.newInstance(seed, false, generateBonusChest, dimensions)

            // Replace on the world data. Prefer setWorldGenOptions if present,
            // otherwise write the backing field directly.
            val setter = worldData.javaClass.methods.firstOrNull {
                it.name == "setWorldGenOptions" && it.parameterCount == 1
            }
            if (setter != null) {
                setter.invoke(worldData, newOpts)
            } else {
                val field = worldData.javaClass.declaredFields.firstOrNull {
                    it.type == optsCls
                } ?: throw NoSuchFieldException("WorldOptions field not found on ${worldData.javaClass.name}")
                field.isAccessible = true
                field.set(worldData, newOpts)
            }

            logger.info("[WorldSetup] Disabled structures on '${world.name}' via NMS reflection. New chunks will not generate structures.")
        } catch (e: Exception) {
            logger.warning("[WorldSetup] Could not programmatically disable structures on '${world.name}': ${e.message}")
            logger.warning("[WorldSetup] To disable manually: stop the server, open '${world.name}/level.dat'")
            logger.warning("[WorldSetup] in an NBT editor, and set Data.WorldGenSettings.generate_features to 0b.")
        }
    }

    private fun registerSettings() {
        settingsManager.register(SettingsManager.SettingDef(
            key = "night_vision",
            displayName = "Night Vision",
            description = "Permanent night vision effect",
            material = org.bukkit.Material.ENDER_EYE,
            disabledMaterial = org.bukkit.Material.ENDER_PEARL,
            default = false,
            permission = "joshymc.nightvision",
            onToggle = { player, enabled ->
                com.liam.joshymc.command.NightVisionCommand.applyNightVision(player, enabled)
            }
        ))
        settingsManager.register(SettingsManager.SettingDef(
            key = "gsit",
            displayName = "Sit on Blocks",
            description = "Right-click stairs/slabs to sit",
            material = org.bukkit.Material.OAK_STAIRS,
            disabledMaterial = org.bukkit.Material.BARRIER,
            default = true,
            permission = "joshymc.gsit"
        ))
        settingsManager.register(SettingsManager.SettingDef(
            key = "death_coords",
            displayName = "Death Coordinates",
            description = "Show coordinates when you die",
            material = org.bukkit.Material.RECOVERY_COMPASS,
            disabledMaterial = org.bukkit.Material.COMPASS,
            default = true,
            permission = "joshymc.deathcoords"
        ))
        settingsManager.register(SettingsManager.SettingDef(
            key = "pvp",
            displayName = "PvP",
            description = "Toggle player vs player combat",
            material = org.bukkit.Material.DIAMOND_SWORD,
            disabledMaterial = org.bukkit.Material.WOODEN_SWORD,
            default = false,
            permission = "joshymc.pvp"
        ))
        settingsManager.register(SettingsManager.SettingDef(
            key = "veinminer",
            displayName = "Veinminer",
            description = "Sneak + mine to break ore veins",
            material = org.bukkit.Material.DIAMOND_PICKAXE,
            disabledMaterial = org.bukkit.Material.STONE_PICKAXE,
            default = true,
            permission = "joshymc.veinminer"
        ))
        settingsManager.register(SettingsManager.SettingDef(
            key = "autosmelt",
            displayName = "Auto Smelt",
            description = "Automatically smelt ore drops",
            material = org.bukkit.Material.BLAST_FURNACE,
            disabledMaterial = org.bukkit.Material.FURNACE,
            default = false,
            permission = "joshymc.autosmelt"
        ))
        settingsManager.register(SettingsManager.SettingDef(
            key = "treefeller",
            displayName = "Tree Feller",
            description = "Sneak + chop to fell entire trees",
            material = org.bukkit.Material.DIAMOND_AXE,
            disabledMaterial = org.bukkit.Material.STONE_AXE,
            default = false,
            permission = "joshymc.treefeller"
        ))
        settingsManager.register(SettingsManager.SettingDef(
            key = "mob_visibility",
            displayName = "Show Mobs",
            description = "Hide mobs from your view (mobs ignore you too)",
            material = org.bukkit.Material.ZOMBIE_HEAD,
            disabledMaterial = org.bukkit.Material.SKELETON_SKULL,
            default = true,
            permission = "joshymc.mobvisibility",
            onToggle = { player, enabled ->
                com.liam.joshymc.listener.MobVisibilityListener.applyTo(this, player, enabled)
            }
        ))
    }

    /**
     * Backfill any config keys that exist in the bundled default config.yml but
     * are missing from the player-edited file on disk. Bukkit's saveDefaultConfig
     * only writes when the file is absent — without this, users upgrading from
     * older versions never see new sections (e.g. afk.reward.money).
     *
     * We touch only missing keys, so hand-edited values are left alone.
     */
    private fun migrateConfig() {
        val defaultStream = getResource("config.yml") ?: return
        val defaults = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
            defaultStream.bufferedReader()
        )

        var changed = 0
        for (key in defaults.getKeys(true)) {
            if (defaults.isConfigurationSection(key)) continue
            if (!config.contains(key, true)) {
                config.set(key, defaults.get(key))
                changed++
            }
        }

        if (changed > 0) {
            saveConfig()
            logger.info("[ConfigMigrator] Backfilled $changed missing config key(s) from defaults.")
        }
    }
}
