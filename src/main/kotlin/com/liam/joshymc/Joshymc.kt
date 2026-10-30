package com.liam.joshymc

import com.liam.joshymc.gui.GuiManager
import com.liam.joshymc.discord.DiscordManager
import com.liam.joshymc.link.LinkManager
import com.liam.joshymc.manager.CombatManager
import com.liam.joshymc.manager.CommandManager
import com.liam.joshymc.manager.DatabaseManager
import com.liam.joshymc.manager.PlayerDatabaseManager
import com.liam.joshymc.manager.ItemManager
import com.liam.joshymc.manager.KillStreakManager
import com.liam.joshymc.manager.LagCleanerManager
import com.liam.joshymc.manager.ListenerManager
import com.liam.joshymc.manager.RecipeManager
import com.liam.joshymc.manager.ResourcePackManager
import com.liam.joshymc.manager.SettingsManager
import com.liam.joshymc.enchant.CustomEnchant
import com.liam.joshymc.enchant.CustomEnchantManager
import com.liam.joshymc.enchant.EnchantTarget
import com.liam.joshymc.enchant.QuickHit
import com.liam.joshymc.enchant.QuickHitSettings
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
import com.liam.joshymc.manager.StaffTagManager
import com.liam.joshymc.manager.MarketManager
import com.liam.joshymc.manager.QuestCycleManager
import com.liam.joshymc.manager.EventQuestManager
import com.liam.joshymc.manager.FishingManager
import com.liam.joshymc.manager.KillEffectManager
import com.liam.joshymc.manager.SpawnWorldManager
import com.liam.joshymc.manager.TalismanManager
import com.liam.joshymc.manager.ClaimManager
import com.liam.joshymc.manager.RankManager
import com.liam.joshymc.manager.ServerShopManager
import com.liam.joshymc.manager.AuctionManager
import com.liam.joshymc.manager.CommunicationsManager
import com.liam.joshymc.manager.EconomyManager
import com.liam.joshymc.manager.StockMarketManager
import com.liam.joshymc.manager.CrateManager
import com.liam.joshymc.manager.HologramManager
import com.liam.joshymc.manager.KitManager
import com.liam.joshymc.manager.NPCManager
import com.liam.joshymc.manager.HopperPlusManager
import com.liam.joshymc.manager.SignShopManager
import com.liam.joshymc.manager.SpawnerManager
import com.liam.joshymc.manager.StorageManager
import com.liam.joshymc.manager.EnderChestManager
import com.liam.joshymc.manager.TeamManager
import com.liam.joshymc.manager.TimezoneManager
import com.liam.joshymc.manager.TradeManager
import com.liam.joshymc.manager.WarpManager
import com.liam.joshymc.command.VanishCommand
import com.liam.joshymc.command.WorldCommand
import com.liam.joshymc.manager.ArenaManager
import com.liam.joshymc.manager.BuildPvpManager
import com.liam.joshymc.manager.PortalManager
import com.liam.joshymc.manager.SpawnDecorationManager
import com.liam.joshymc.manager.VoteManager
import com.liam.joshymc.manager.EndManager
import com.liam.joshymc.manager.ChatManager
import com.liam.joshymc.manager.BoosterManager
import com.liam.joshymc.manager.MobStackManager
import com.liam.joshymc.lifecycle.ShutdownSequence
import com.liam.joshymc.util.ConfigUtil
import com.liam.joshymc.util.ProfanityFilter
import org.bukkit.Bukkit
import org.bukkit.GameRule
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.event.HandlerList
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.util.concurrent.TimeUnit

class Joshymc : JavaPlugin() {

    companion object {
        lateinit var instance: Joshymc
            private set

        // Dotted-key prefixes the config migrator must never backfill into an existing
        // config.yml: these are admin-defined name->value maps (rank ids, purchasable-rank
        // keys) shipped with example entries, not fixed plugin schema. Restoring entries an
        // admin deleted from these breaks LuckPerms setups that reuse rank names. Issue #911.
        private val NO_BACKFILL_SECTION_PREFIXES = listOf(
            "ranks.list.",
            "homes.limits-by-rank.",
            "warps.limits-by-rank.",
            "orders.limits.",
            "rank-perks.",
            // Issue #1192: Mob Waves wave list. Backfilled only as a whole, when the section is missing.
            "$MOB_WAVES_KEY."
        )

        private const val MOB_WAVES_KEY = "hosted-events.mobs.waves"

        /** How long onDisable waits for async tasks already running before closing the databases. */
        private const val ASYNC_TASK_WAIT_SECONDS = 3L
    }

    /** Set once onDisable starts, so a repeated call can't run the final saves twice. */
    private var shutdownStarted = false

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
    lateinit var bedrockPackManager: com.liam.joshymc.bedrock.BedrockPackManager
        private set
    lateinit var databaseManager: DatabaseManager
        private set
    lateinit var playerDatabaseManager: PlayerDatabaseManager
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
    lateinit var overflowManager: com.liam.joshymc.manager.OverflowManager
        private set
    lateinit var enderChestManager: EnderChestManager
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
    lateinit var orderManager: com.liam.joshymc.manager.OrderManager
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

    lateinit var antiDupeManager: com.liam.joshymc.manager.AntiDupeManager
        private set
    lateinit var combatAlertManager: com.liam.joshymc.manager.CombatAlertManager
        private set

    lateinit var creditsManager: com.liam.joshymc.manager.CreditsManager
    lateinit var creditsCoinflipManager: com.liam.joshymc.manager.CreditsCoinflipManager
        private set
    lateinit var stockMarketManager: StockMarketManager
        private set
    lateinit var giveawayManager: com.liam.joshymc.manager.GiveawayManager
        private set
    lateinit var giftManager: com.liam.joshymc.manager.GiftManager
        private set
    lateinit var coinflipManager: com.liam.joshymc.manager.CoinflipManager
        private set
    lateinit var casinoManager: com.liam.joshymc.manager.CasinoManager
        private set
    lateinit var casinoMinesManager: com.liam.joshymc.manager.CasinoMinesManager
        private set
    lateinit var casinoRouletteManager: com.liam.joshymc.manager.CasinoRouletteManager
        private set
    lateinit var casinoCrashManager: com.liam.joshymc.manager.CasinoCrashManager
        private set
    lateinit var casinoTowersManager: com.liam.joshymc.manager.CasinoTowersManager
        private set
    lateinit var guiManager: GuiManager
        private set
    lateinit var antiCheatManager: AntiCheatManager
        private set
    lateinit var customEnchantManager: CustomEnchantManager
        private set
    lateinit var serverShopManager: ServerShopManager
        private set
    lateinit var sellPriceManager: com.liam.joshymc.manager.SellPriceManager
        private set
    lateinit var saleManager: com.liam.joshymc.manager.SaleManager
        private set
    lateinit var candyShopManager: com.liam.joshymc.candyshop.CandyShopManager
        private set
    lateinit var eventShopManager: com.liam.joshymc.eventshop.EventShopManager
        private set
    lateinit var creditShopManager: com.liam.joshymc.manager.CreditShopManager
        private set
    lateinit var voucherManager: com.liam.joshymc.manager.VoucherManager
        private set
    lateinit var physicalVoucherManager: com.liam.joshymc.manager.PhysicalVoucherManager
        private set
    lateinit var creditVoucherManager: com.liam.joshymc.manager.CreditVoucherManager
        private set
    lateinit var rankVoucherManager: com.liam.joshymc.manager.RankVoucherManager
        private set
    lateinit var freeRankManager: com.liam.joshymc.freerank.FreeRankManager
        private set
    lateinit var chatTagVoucherManager: com.liam.joshymc.manager.ChatTagVoucherManager
        private set
    lateinit var flightTimeManager: com.liam.joshymc.flight.FlightTimeManager
        private set
    lateinit var flyVoucherManager: com.liam.joshymc.flight.FlyVoucherManager
        private set
    lateinit var beaconPotionManager: com.liam.joshymc.potion.BeaconPotionManager
        private set
    lateinit var purchaseFulfillmentManager: com.liam.joshymc.manager.PurchaseFulfillmentManager
        private set
    lateinit var stuckRecoveryManager: com.liam.joshymc.recovery.StuckRecoveryManager
        private set
    lateinit var networkIdentityManager: com.liam.joshymc.network.NetworkIdentityManager
        private set
    lateinit var transferManager: com.liam.joshymc.network.transfer.TransferManager
        private set
    lateinit var gamemodeLockManager: com.liam.joshymc.gamemode.GamemodeLockManager
        private set
    lateinit var rankManager: RankManager
        private set
    lateinit var claimManager: ClaimManager
        private set
    lateinit var punishmentManager: PunishmentManager
        private set
    lateinit var rulePunishManager: com.liam.joshymc.punish.RulePunishManager
        private set
    lateinit var punishMenus: com.liam.joshymc.punish.PunishMenus
        private set
    lateinit var altManager: com.liam.joshymc.manager.AltManager
        private set
    lateinit var resourceWorldManager: ResourceWorldManager
        private set
    lateinit var scoreboardManager: ScoreboardManager
        private set
    lateinit var announcementManager: AnnouncementManager
        private set
    lateinit var rotatingBossBarManager: com.liam.joshymc.manager.RotatingBossBarManager
        private set
    lateinit var autoRestartManager: AutoRestartManager
        private set
    lateinit var playtimeManager: PlaytimeManager
        private set
    lateinit var killStreakManager: KillStreakManager
        private set
    lateinit var loginStreakManager: com.liam.joshymc.manager.LoginStreakManager
        private set
    lateinit var chatTagManager: ChatTagManager
        private set
    lateinit var staffTagManager: StaffTagManager
        private set
    lateinit var marketManager: MarketManager
        private set
    lateinit var chatGamesManager: com.liam.joshymc.manager.ChatGamesManager
        private set
    lateinit var vanishCommand: VanishCommand
    lateinit var rtpCommand: com.liam.joshymc.command.RtpCommand
    lateinit var sellCommand: com.liam.joshymc.command.SellCommand
    lateinit var questCycleManager: QuestCycleManager
        private set
    lateinit var eventQuestManager: EventQuestManager
        private set
    lateinit var talismanManager: TalismanManager
        private set
    lateinit var fishingManager: FishingManager
        private set
    lateinit var killEffectManager: KillEffectManager
        private set
    lateinit var adminManager: AdminManager
        private set
    lateinit var endManager: EndManager
        private set
    lateinit var chatManager: ChatManager
        private set
    lateinit var arenaManager: ArenaManager
        private set
    lateinit var buildPvpManager: BuildPvpManager
        private set
    lateinit var portalManager: PortalManager
        private set
    lateinit var fakeBaseManager: com.liam.joshymc.manager.FakeBaseManager
        private set
    lateinit var voteManager: VoteManager
        private set
    lateinit var spawnDecorationManager: SpawnDecorationManager
        private set
    lateinit var spawnWorldManager: SpawnWorldManager
        private set
    lateinit var timezoneManager: TimezoneManager
        private set
    lateinit var playerHeadManager: com.liam.joshymc.manager.PlayerHeadManager
        private set
    lateinit var playerResolver: com.liam.joshymc.manager.PlayerResolver
        private set
    lateinit var eventManager: com.liam.joshymc.manager.EventManager
        private set
    lateinit var resurgeManager: com.liam.joshymc.manager.ResurgeManager
        private set
    lateinit var boosterManager: BoosterManager
        private set
    lateinit var mobStackManager: MobStackManager
        private set
    lateinit var mutationsManager: com.liam.joshymc.manager.MutationsManager
        private set
    lateinit var modModeManager: com.liam.joshymc.manager.ModModeManager
        private set
    lateinit var hideStaffManager: com.liam.joshymc.manager.HideStaffManager
        private set
    lateinit var staffChatManager: com.liam.joshymc.manager.StaffChatManager
        private set

    override fun onEnable() {
        instance = this
        shutdownStarted = false

        ConfigUtil.backupsDir(dataFolder)

        saveDefaultConfig()
        migrateConfig()
        // First, so every manager sees this server's id and primary flag (issue #1323).
        networkIdentityManager = com.liam.joshymc.network.NetworkIdentityManager(this)
        networkIdentityManager.load()
        transferManager = com.liam.joshymc.network.transfer.TransferManager(this)

        ProfanityFilter.load(this)

        databaseManager = DatabaseManager(this)
        databaseManager.start()

        playerDatabaseManager = PlayerDatabaseManager(this)
        playerDatabaseManager.start()

        settingsManager = SettingsManager(this)
        settingsManager.start()
        registerSettings()

        rankManager = RankManager(this)
        rankManager.start()

        chatTagManager = ChatTagManager(this)
        chatTagManager.start()

        staffTagManager = StaffTagManager(this)
        staffTagManager.start()

        commsManager = CommunicationsManager(this)
        commsManager.start()

        economyManager = EconomyManager(this)
        economyManager.start()

        antiDupeManager = com.liam.joshymc.manager.AntiDupeManager(this)
        antiDupeManager.start()

        combatAlertManager = com.liam.joshymc.manager.CombatAlertManager(this)
        combatAlertManager.start()

        stockMarketManager = StockMarketManager(this)
        stockMarketManager.start()

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
        overflowManager = com.liam.joshymc.manager.OverflowManager(this)
        enderChestManager = EnderChestManager(this)
        tradeManager = TradeManager(this)
        hologramManager = HologramManager(this)
        leaderboardManager = com.liam.joshymc.manager.LeaderboardManager(this)
        npcManager = NPCManager(this)
        crateManager = CrateManager(this)
        coinflipManager = com.liam.joshymc.manager.CoinflipManager(this)
        casinoManager = com.liam.joshymc.manager.CasinoManager(this)
        casinoMinesManager = com.liam.joshymc.manager.CasinoMinesManager(this)
        casinoRouletteManager = com.liam.joshymc.manager.CasinoRouletteManager(this)
        casinoCrashManager = com.liam.joshymc.manager.CasinoCrashManager(this)
        casinoTowersManager = com.liam.joshymc.manager.CasinoTowersManager(this)
        auctionManager = AuctionManager(this)
        giveawayManager = com.liam.joshymc.manager.GiveawayManager(this)
        giftManager = com.liam.joshymc.manager.GiftManager(this)
        orderManager = com.liam.joshymc.manager.OrderManager(this)
        signShopManager = SignShopManager(this)
        hopperPlusManager = HopperPlusManager(this)
        spawnerManager = SpawnerManager(this)
        teamManager = TeamManager(this)
        killStreakManager = KillStreakManager(this)
        loginStreakManager = com.liam.joshymc.manager.LoginStreakManager(this)
        resourcePackManager = ResourcePackManager(this)
        bedrockPackManager = com.liam.joshymc.bedrock.BedrockPackManager(this)
        discordManager = DiscordManager(this)
        lagCleanerManager = LagCleanerManager(this)
        afkManager = AFKManager(this)
        antiCheatManager = AntiCheatManager(this)
        claimManager = ClaimManager(this)
        punishmentManager = PunishmentManager(this)
        rulePunishManager = com.liam.joshymc.punish.RulePunishManager(this)
        punishMenus = com.liam.joshymc.punish.PunishMenus(this)
        altManager = com.liam.joshymc.manager.AltManager(this)
        resourceWorldManager = ResourceWorldManager(this)
        timezoneManager = TimezoneManager(this)
        timezoneManager.start()
        playerHeadManager = com.liam.joshymc.manager.PlayerHeadManager(this)
        playerHeadManager.start()
        playerResolver = com.liam.joshymc.manager.PlayerResolver(this)
        playerResolver.start()
        scoreboardManager = ScoreboardManager(this)
        announcementManager = AnnouncementManager(this)
        rotatingBossBarManager = com.liam.joshymc.manager.RotatingBossBarManager(this)
        autoRestartManager = AutoRestartManager(this)
        playtimeManager = PlaytimeManager(this)
        creditsManager = com.liam.joshymc.manager.CreditsManager(this)
        creditsCoinflipManager = com.liam.joshymc.manager.CreditsCoinflipManager(this)
        questCycleManager = QuestCycleManager(this)
        questCycleManager.createTables()
        eventQuestManager = EventQuestManager(this)
        eventQuestManager.createTables()
        talismanManager = TalismanManager(this)
        fishingManager = FishingManager(this)
        killEffectManager = KillEffectManager(this)
        adminManager = AdminManager(this)
        modModeManager = com.liam.joshymc.manager.ModModeManager(this)
        hideStaffManager = com.liam.joshymc.manager.HideStaffManager(this)
        staffChatManager = com.liam.joshymc.manager.StaffChatManager(this)
        endManager = EndManager(this)
        chatManager = ChatManager(this)
        arenaManager = ArenaManager(this)
        buildPvpManager = BuildPvpManager(this)
        portalManager = PortalManager(this)
        fakeBaseManager = com.liam.joshymc.manager.FakeBaseManager(this)
        voteManager = VoteManager(this)
        spawnDecorationManager = SpawnDecorationManager(this)
        spawnWorldManager = SpawnWorldManager(this)
        customEnchantManager = CustomEnchantManager(this)
        serverShopManager = ServerShopManager(this)
        creditShopManager = com.liam.joshymc.manager.CreditShopManager(this)
        voucherManager = com.liam.joshymc.manager.VoucherManager(this)
        physicalVoucherManager = com.liam.joshymc.manager.PhysicalVoucherManager(this)
        creditVoucherManager = com.liam.joshymc.manager.CreditVoucherManager(this)
        rankVoucherManager = com.liam.joshymc.manager.RankVoucherManager(this)
        freeRankManager = com.liam.joshymc.freerank.FreeRankManager(this)
        chatTagVoucherManager = com.liam.joshymc.manager.ChatTagVoucherManager(this)
        flightTimeManager = com.liam.joshymc.flight.FlightTimeManager(this)
        flyVoucherManager = com.liam.joshymc.flight.FlyVoucherManager(this)
        purchaseFulfillmentManager = com.liam.joshymc.manager.PurchaseFulfillmentManager(this)
        eventManager = com.liam.joshymc.manager.EventManager(this)
        resurgeManager = com.liam.joshymc.manager.ResurgeManager(this)
        boosterManager = BoosterManager(this)
        mobStackManager = MobStackManager(this)
        if (isFeatureEnabled("mob-stacking")) mobStackManager.start()
        mutationsManager = com.liam.joshymc.manager.MutationsManager(this)
        mutationsManager.start()
        sellPriceManager = com.liam.joshymc.manager.SellPriceManager(this)
        sellPriceManager.start()
        saleManager = com.liam.joshymc.manager.SaleManager(this)
        candyShopManager = com.liam.joshymc.candyshop.CandyShopManager(this)
        eventShopManager = com.liam.joshymc.eventshop.EventShopManager(this)
        stuckRecoveryManager = com.liam.joshymc.recovery.StuckRecoveryManager(this)
        gamemodeLockManager = com.liam.joshymc.gamemode.GamemodeLockManager(this)
        beaconPotionManager = com.liam.joshymc.potion.BeaconPotionManager(this)

        itemManager.registerAll()
        recipeManager.registerAll()
        commandManager.registerAll()
        // Right after the commands get their executors: it wraps the gamemode-assigned ones.
        gamemodeLockManager.start()
        // After the gamemode lock: brewing mixes are built for the server's gamemode.
        beaconPotionManager.start()
        listenerManager.registerAll()
        linkManager.load()
        warpManager.start()
        kitManager.start()
        storageManager.start()
        overflowManager.start()
        enderChestManager.start()
        hologramManager.start()
        leaderboardManager.start()
        if (isFeatureEnabled("npcs")) npcManager.start()
        crateManager.start()
        if (isFeatureEnabled("coinflip")) coinflipManager.start()
        if (isFeatureEnabled("coinflip")) creditsCoinflipManager.start()
        if (isFeatureEnabled("casino")) {
            casinoManager.start()
            casinoMinesManager.start()
            casinoRouletteManager.start()
            casinoCrashManager.start()
            casinoTowersManager.start()
        }
        auctionManager.start()
        if (isFeatureEnabled("giveaways")) giveawayManager.start()
        if (isFeatureEnabled("gift")) giftManager.start()
        if (isFeatureEnabled("orders")) orderManager.start()
        signShopManager.start()
        hopperPlusManager.start()
        spawnerManager.start()
        teamManager.start()
        if (isFeatureEnabled("kill-streaks")) killStreakManager.start()
        if (isFeatureEnabled("login-streaks")) loginStreakManager.start()
        resourcePackManager.start()
        lagCleanerManager.start()
        combatManager.start()
        afkManager.start()
        if (isFeatureEnabled("anticheat")) antiCheatManager.start()
        claimManager.start()
        punishmentManager.start()
        rulePunishManager.start()
        altManager.start()
        if (isFeatureEnabled("resource-world")) resourceWorldManager.start()
        scoreboardManager.start()
        announcementManager.start()
        rotatingBossBarManager.start()
        autoRestartManager.start()
        playtimeManager.start()
        creditsManager.start()
        registerEnchants()
        if (isFeatureEnabled("custom-enchants")) customEnchantManager.start()
        // Players already online (plugman / /reload) get Quick Hit for what they hold.
        listenerManager.quickHitListener?.refreshAll()
        serverShopManager.start()
        creditShopManager.start()
        voucherManager.start()
        physicalVoucherManager.start()
        creditVoucherManager.start()
        rankVoucherManager.start()
        freeRankManager.start()
        chatTagVoucherManager.start()
        flightTimeManager.start()
        flyVoucherManager.start()

        marketManager = MarketManager(this)
        if (isFeatureEnabled("market")) marketManager.start()

        chatGamesManager = com.liam.joshymc.manager.ChatGamesManager(this)
        if (isFeatureEnabled("chat-games")) chatGamesManager.start()

        resurgeManager.start()
        if (isFeatureEnabled("quests")) questCycleManager.start()
        if (isFeatureEnabled("event-quests")) eventQuestManager.start()
        if (isFeatureEnabled("talismans")) talismanManager.start()
        if (isFeatureEnabled("custom-fishing")) fishingManager.start()
        // After items, crates and fish: it maps their models for Geyser, which reads them when it starts.
        bedrockPackManager.start()
        if (isFeatureEnabled("kill-effects")) killEffectManager.start()
        adminManager.start()
        modModeManager.start()
        // After every reward factory it builds items from (crates, vouchers, chat tags) and Mod Mode.
        purchaseFulfillmentManager.start()
        hideStaffManager.start()
        endManager.start()
        chatManager.start()
        // Start spawn world BEFORE arenas so the world exists when arena ticks begin
        spawnWorldManager.start()
        // Auto-import the pvp world BEFORE arenas so it's loaded when arena ticks begin
        WorldCommand.ensurePvpWorld(this)

        if (isFeatureEnabled("arenas")) arenaManager.start()
        buildPvpManager.start()
        if (isFeatureEnabled("portals")) portalManager.start()
        fakeBaseManager.start()
        if (isFeatureEnabled("voting")) voteManager.start()
        spawnDecorationManager.start()
        eventManager.start()
        // After items (it sells registered candies), economy, Mod Mode and the event world.
        if (isFeatureEnabled("candy-shop")) candyShopManager.start()
        // Same dependencies as the Candy Shop, plus the gamemode locks (Survival only).
        if (isFeatureEnabled("event-shop")) eventShopManager.start()
        // Reads the feature flag itself: its listener is always registered.
        stuckRecoveryManager.start()
        // Issue #1331: off (and silent) unless network.enabled; never blocks startup.
        try { transferManager.start() } catch (e: Exception) { logger.severe("[Network] Transfer manager failed to start: ${e.message}") }

        discordManager.start()

        // Ensure dungeon void world exists
        WorldCommand.ensureDungeonWorld(this)

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
        // Issue #1325: every step runs on its own, so one manager throwing (or never
        // initialized because onEnable failed part way) can't keep the rest, and above all
        // the two databases, from shutting down. Same order as before; see ShutdownSequence.
        if (shutdownStarted) {
            logger.warning("[Shutdown] onDisable called again; JoshyMC already shut down.")
            return
        }
        shutdownStarted = true

        val shutdown = ShutdownSequence(logger)
        // Final saves first, while every manager they use is still up.
        shutdown.step("storageManager.saveOpenVaults", { ::storageManager.isInitialized }) { storageManager.saveOpenVaults() }
        shutdown.step("overflowManager.closeOpenStorages", { ::overflowManager.isInitialized }) { overflowManager.closeOpenStorages() }
        shutdown.step("enderChestManager.saveOpenSessions", { ::enderChestManager.isInitialized }) { enderChestManager.saveOpenSessions() }
        shutdown.step("sellCommand.resolveAllOpenSessions", { ::sellCommand.isInitialized }) { sellCommand.resolveAllOpenSessions() }
        shutdown.step("hologramManager.stop", { ::hologramManager.isInitialized }) { hologramManager.stop() }
        shutdown.step("npcManager.stop", { ::npcManager.isInitialized }) { npcManager.stop() }
        shutdown.step("crateManager.stop", { ::crateManager.isInitialized }) { crateManager.stop() }
        shutdown.step("auctionManager.stop", { ::auctionManager.isInitialized }) { auctionManager.stop() }
        shutdown.step("giveawayManager.stop", { ::giveawayManager.isInitialized }) { giveawayManager.stop() }
        shutdown.step("giftManager.stop", { ::giftManager.isInitialized }) { giftManager.stop() }
        shutdown.step("coinflipManager.stop", { ::coinflipManager.isInitialized }) { coinflipManager.stop() }
        shutdown.step("creditsCoinflipManager.stop", { ::creditsCoinflipManager.isInitialized }) { creditsCoinflipManager.stop() }
        shutdown.step("casinoMinesManager.stop", { ::casinoMinesManager.isInitialized }) { casinoMinesManager.stop() }
        shutdown.step("casinoRouletteManager.stop", { ::casinoRouletteManager.isInitialized }) { casinoRouletteManager.stop() }
        shutdown.step("casinoCrashManager.stop", { ::casinoCrashManager.isInitialized }) { casinoCrashManager.stop() }
        shutdown.step("casinoTowersManager.stop", { ::casinoTowersManager.isInitialized }) { casinoTowersManager.stop() }
        shutdown.step("casinoManager.stop", { ::casinoManager.isInitialized }) { casinoManager.stop() }
        shutdown.step("orderManager.stop", { ::orderManager.isInitialized }) { orderManager.stop() }
        shutdown.step("hopperPlusManager.stop", { ::hopperPlusManager.isInitialized }) { hopperPlusManager.stop() }
        shutdown.step("spawnerManager.stop", { ::spawnerManager.isInitialized }) { spawnerManager.stop() }
        shutdown.step("kitManager.stop", { ::kitManager.isInitialized }) { kitManager.stop() }
        shutdown.step("afkManager.stop", { ::afkManager.isInitialized }) { afkManager.stop() }
        shutdown.step("antiCheatManager.stop", { ::antiCheatManager.isInitialized }) { antiCheatManager.stop() }
        shutdown.step("portalManager.stop", { ::portalManager.isInitialized }) { portalManager.stop() }
        shutdown.step("voteManager.stop", { ::voteManager.isInitialized }) { voteManager.stop() }
        shutdown.step("purchaseFulfillmentManager.stop", { ::purchaseFulfillmentManager.isInitialized }) { purchaseFulfillmentManager.stop() }
        shutdown.step("candyShopManager.stop", { ::candyShopManager.isInitialized }) { candyShopManager.stop() }
        shutdown.step("eventShopManager.stop", { ::eventShopManager.isInitialized }) { eventShopManager.stop() }
        shutdown.step("flightTimeManager.stop", { ::flightTimeManager.isInitialized }) { flightTimeManager.stop() }
        shutdown.step("beaconPotionManager.stop", { ::beaconPotionManager.isInitialized }) { beaconPotionManager.stop() }
        shutdown.step("stuckRecoveryManager.stop", { ::stuckRecoveryManager.isInitialized }) { stuckRecoveryManager.stop() }
        shutdown.step("transferManager.stop", { ::transferManager.isInitialized }) { transferManager.stop() }
        shutdown.step("spawnDecorationManager.stop", { ::spawnDecorationManager.isInitialized }) { spawnDecorationManager.stop() }
        shutdown.step("passiveEnchantListener.stop", { ::listenerManager.isInitialized }) { listenerManager.passiveEnchantListener?.stop() }
        shutdown.step("quickHitListener.stop", { ::listenerManager.isInitialized }) { listenerManager.quickHitListener?.stop() }
        shutdown.step("thrownTridentVisualListener.stop", { ::listenerManager.isInitialized }) { listenerManager.thrownTridentVisualListener?.stop() }
        shutdown.step("greatPumpkinPieListener.stop", { ::listenerManager.isInitialized }) { listenerManager.greatPumpkinPieListener?.stop() }
        shutdown.step("furnitureListener.stop", { ::listenerManager.isInitialized }) { listenerManager.furnitureListener?.stop() }
        // Before discordManager.shutdown(), so the last mining summaries still go out.
        shutdown.step("ancientDebrisMonitor.stop", { ::listenerManager.isInitialized }) { listenerManager.ancientDebrisMonitorListener?.stop() }
        shutdown.step("claimManager.stop", { ::claimManager.isInitialized }) { claimManager.stop() }
        shutdown.step("scoreboardManager.stop", { ::scoreboardManager.isInitialized }) { scoreboardManager.stop() }
        shutdown.step("announcementManager.stop", { ::announcementManager.isInitialized }) { announcementManager.stop() }
        shutdown.step("rotatingBossBarManager.stop", { ::rotatingBossBarManager.isInitialized }) { rotatingBossBarManager.stop() }
        shutdown.step("autoRestartManager.stop", { ::autoRestartManager.isInitialized }) { autoRestartManager.stop() }
        shutdown.step("playtimeManager.stop", { ::playtimeManager.isInitialized }) { playtimeManager.stop() }
        shutdown.step("killStreakManager.stop", { ::killStreakManager.isInitialized }) { killStreakManager.stop() }
        shutdown.step("loginStreakManager.stop", { ::loginStreakManager.isInitialized }) { loginStreakManager.stop() }
        shutdown.step("creditsManager.stop", { ::creditsManager.isInitialized }) { creditsManager.stop() }
        shutdown.step("questCycleManager.stop", { ::questCycleManager.isInitialized }) { questCycleManager.stop() }
        shutdown.step("eventQuestManager.stop", { ::eventQuestManager.isInitialized }) { eventQuestManager.stop() }
        shutdown.step("resourceWorldManager.stop", { ::resourceWorldManager.isInitialized }) { resourceWorldManager.stop() }
        shutdown.step("buildPvpManager.stop", { ::buildPvpManager.isInitialized }) { buildPvpManager.stop() }
        shutdown.step("combatManager.stop", { ::combatManager.isInitialized }) { combatManager.stop() }
        shutdown.step("lagCleanerManager.stop", { ::lagCleanerManager.isInitialized }) { lagCleanerManager.stop() }
        shutdown.step("mobStackManager.stop", { ::mobStackManager.isInitialized }) { mobStackManager.stop() }
        shutdown.step("mutationsManager.stop", { ::mutationsManager.isInitialized }) { mutationsManager.stop() }
        shutdown.step("modModeManager.stop", { ::modModeManager.isInitialized }) { modModeManager.stop() }
        shutdown.step("hideStaffManager.stop", { ::hideStaffManager.isInitialized }) { hideStaffManager.stop() }
        shutdown.step("eventManager.shutdown", { ::eventManager.isInitialized }) { eventManager.shutdown() }
        shutdown.step("resourcePackManager.shutdown", { ::resourcePackManager.isInitialized }) { resourcePackManager.shutdown() }
        shutdown.step("discordManager.shutdown", { ::discordManager.isInitialized }) { discordManager.shutdown() }
        // No new work for the databases: drop every timer that's still scheduled (market,
        // leaderboards, punishment expiry...; Paper would only do this after onDisable) and
        // give async runs already in flight a moment to finish their writes.
        shutdown.step("scheduler.cancelTasks") { server.scheduler.cancelTasks(this) }
        shutdown.step("awaitAsyncTasks") { awaitAsyncTasks() }
        shutdown.step("playerDatabaseManager.shutdown", { ::playerDatabaseManager.isInitialized }) { playerDatabaseManager.shutdown() }
        shutdown.step("databaseManager.shutdown", { ::databaseManager.isInitialized }) { databaseManager.shutdown() }

        val report = shutdown.run()
        if (report.ok) {
            logger.info("JoshyMC has been disabled!")
        } else {
            logger.severe("JoshyMC disabled with ${report.failed.size} failed shutdown step(s): ${report.failed.joinToString()}")
        }
    }

    /**
     * Waits up to [ASYNC_TASK_WAIT_SECONDS] for this plugin's async scheduler runs that are
     * already executing (their timers were just cancelled), so their writes land before the
     * databases close. Throws if some are still running, then shutdown carries on.
     */
    private fun awaitAsyncTasks() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(ASYNC_TASK_WAIT_SECONDS)
        while (true) {
            val running = server.scheduler.activeWorkers.filter { it.owner === this && it.thread !== Thread.currentThread() }
            if (running.isEmpty()) return
            if (System.nanoTime() >= deadline) {
                throw IllegalStateException(
                    "${running.size} async task(s) still running after ${ASYNC_TASK_WAIT_SECONDS}s " +
                        "(task ids ${running.joinToString { it.taskId.toString() }}); closing the databases anyway"
                )
            }
            try {
                Thread.sleep(25)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
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
        safe("overflowManager.closeOpenStorages") { overflowManager.closeOpenStorages() }
        safe("enderChestManager.saveOpenSessions") { enderChestManager.saveOpenSessions() }
        safe("sellCommand.resolveAllOpenSessions") { if (::sellCommand.isInitialized) sellCommand.resolveAllOpenSessions() }
        safe("hologramManager.stop") { hologramManager.stop() }
        safe("npcManager.stop") { npcManager.stop() }
        safe("crateManager.stop") { crateManager.stop() }
        safe("auctionManager.stop") { auctionManager.stop() }
        safe("giveawayManager.stop") { giveawayManager.stop() }
        safe("giftManager.stop") { giftManager.stop() }
        safe("coinflipManager.stop") { coinflipManager.stop() }
        safe("creditsCoinflipManager.stop") { creditsCoinflipManager.stop() }
        safe("casinoMinesManager.stop") { casinoMinesManager.stop() }
        safe("casinoRouletteManager.stop") { casinoRouletteManager.stop() }
        safe("casinoCrashManager.stop") { casinoCrashManager.stop() }
        safe("casinoTowersManager.stop") { casinoTowersManager.stop() }
        safe("casinoManager.stop") { casinoManager.stop() }
        safe("orderManager.stop") { orderManager.stop() }
        safe("candyShopManager.stop") { candyShopManager.stop() }
        safe("eventShopManager.stop") { eventShopManager.stop() }
        safe("stuckRecoveryManager.stop") { stuckRecoveryManager.stop() }
        safe("transferManager.stop") { transferManager.stop() }
        safe("hopperPlusManager.stop") { hopperPlusManager.stop() }
        safe("questCycleManager.stop") { questCycleManager.stop() }
        safe("eventQuestManager.stop") { eventQuestManager.stop() }
        safe("spawnerManager.stop") { spawnerManager.stop() }
        safe("kitManager.stop") { kitManager.stop() }
        safe("afkManager.stop") { afkManager.stop() }
        safe("killStreakManager.stop") { killStreakManager.stop() }
        safe("loginStreakManager.stop") { loginStreakManager.stop() }
        safe("antiCheatManager.stop") { antiCheatManager.stop() }
        safe("combatManager.stop") { combatManager.stop() }
        safe("autoRestartManager.stop") { autoRestartManager.stop() }
        safe("rotatingBossBarManager.stop") { rotatingBossBarManager.stop() }
        safe("lagCleanerManager.stop") { lagCleanerManager.stop() }
        safe("resourcePackManager.shutdown") { resourcePackManager.shutdown() }
        // Post the pending Ancient Debris summaries while the bot is still up (its task was cancelled in step 1).
        safe("ancientDebrisMonitor.stop") { listenerManager.ancientDebrisMonitorListener?.stop() }
        safe("discordManager.shutdown") { discordManager.shutdown(serverStopping = false) }

        // 4. Clear registries
        safe("itemManager.clear") { itemManager.clear() }
        safe("recipeManager.clear") { recipeManager.clear() }
        safe("beaconPotionManager.stop") { beaconPotionManager.stop() }

        // 5. Reload config from disk
        safe("reloadConfig") { reloadConfig() }
        // Before anything that starts primary-only work (Discord group sync, the bot).
        safe("networkIdentityManager.load") { networkIdentityManager.load() }
        safe("ProfanityFilter.load") { ProfanityFilter.load(this) }

        // 6. Re-register everything
        safe("sellPriceManager.start") { sellPriceManager.start() }
        safe("itemManager.registerAll") { itemManager.registerAll() }
        safe("recipeManager.registerAll") { recipeManager.registerAll() }
        safe("listenerManager.registerAll") { listenerManager.registerAll() }
        safe("linkManager.load") { linkManager.load() }
        // WarpManager uses DB directly, no reload needed
        safe("physicalVoucherManager.reload") { physicalVoucherManager.reload() }
        // Its countdown task was cancelled above; restart it and reload online balances.
        safe("flightTimeManager.start") { flightTimeManager.start() }
        // Re-registers its join listener (unregistered above) and settles pending claims.
        safe("freeRankManager.start") { freeRankManager.start() }
        safe("kitManager.start") { kitManager.start() }
        safe("storageManager.start") { storageManager.start() }
        safe("overflowManager.start") { overflowManager.start() }
        safe("enderChestManager.start") { enderChestManager.start() }
        safe("hologramManager.start") { hologramManager.start() }
        if (isFeatureEnabled("npcs")) safe("npcManager.start") { npcManager.start() }
        if (isFeatureEnabled("quests")) safe("questCycleManager.start") { questCycleManager.start() }
        if (isFeatureEnabled("event-quests")) safe("eventQuestManager.start") { eventQuestManager.start() }
        safe("crateManager.start") { crateManager.start() }
        safe("auctionManager.start") { auctionManager.start() }
        if (isFeatureEnabled("giveaways")) safe("giveawayManager.start") { giveawayManager.start() }
        if (isFeatureEnabled("gift")) safe("giftManager.start") { giftManager.start() }
        if (isFeatureEnabled("coinflip")) safe("coinflipManager.start") { coinflipManager.start() }
        if (isFeatureEnabled("coinflip")) safe("creditsCoinflipManager.start") { creditsCoinflipManager.start() }
        if (isFeatureEnabled("casino")) {
            safe("casinoManager.start") { casinoManager.start() }
            safe("casinoMinesManager.start") { casinoMinesManager.start() }
            safe("casinoRouletteManager.start") { casinoRouletteManager.start() }
            safe("casinoCrashManager.start") { casinoCrashManager.start() }
            safe("casinoTowersManager.start") { casinoTowersManager.start() }
        }
        if (isFeatureEnabled("orders")) safe("orderManager.start") { orderManager.start() }
        safe("signShopManager.start") { signShopManager.start() }
        safe("hopperPlusManager.start") { hopperPlusManager.start() }
        safe("spawnerManager.start") { spawnerManager.start() }
        safe("teamManager.start") { teamManager.start() }
        if (isFeatureEnabled("kill-streaks")) safe("killStreakManager.start") { killStreakManager.start() }
        if (isFeatureEnabled("login-streaks")) safe("loginStreakManager.start") { loginStreakManager.start() }
        safe("resourcePackManager.start") { resourcePackManager.start() }
        if (isFeatureEnabled("candy-shop")) safe("candyShopManager.start") { candyShopManager.start() }
        if (isFeatureEnabled("event-shop")) safe("eventShopManager.start") { eventShopManager.start() }
        safe("stuckRecoveryManager.start") { stuckRecoveryManager.start() }
        safe("transferManager.start") { transferManager.start() }
        // Refreshes the exported files (crate keys may have changed); Geyser reads them on its next start.
        safe("bedrockPackManager.start") { bedrockPackManager.start() }

        // 7. Commands just get new instances (executors are swapped, not re-registered)
        safe("commandManager.registerAll") { commandManager.registerAll() }
        safe("gamemodeLockManager.start") { gamemodeLockManager.start() }
        safe("beaconPotionManager.start") { beaconPotionManager.start() }

        // 8. Restart Discord
        safe("discordManager.start") { discordManager.start(announceStartup = false) }

        // 9. Restart lag cleaner, combat manager, chat manager, and AFK manager with new config
        safe("rankManager.start") { rankManager.start() }
        safe("staffTagManager.start") { staffTagManager.start() }
        safe("commsManager.start") { commsManager.start() }
        safe("lagCleanerManager.start") { lagCleanerManager.start() }
        safe("combatManager.start") { combatManager.start() }
        safe("autoRestartManager.start") { autoRestartManager.start() }
        safe("rotatingBossBarManager.start") { rotatingBossBarManager.start() }
        safe("afkManager.start") { afkManager.start() }
        safe("antiCheatManager.start") { antiCheatManager.start() }
        safe("registerEnchants") { registerEnchants() }
        safe("customEnchantManager.start") { customEnchantManager.start() }
        safe("quickHitListener.refreshAll") { listenerManager.quickHitListener?.refreshAll() }

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
        cem.register(CustomEnchant("slayer", "Slayer", 2, EnchantTarget.SWORD,
            description = "Increases XP gained from killing mobs"))

        // ── Axe ─────────────────────────────────────────
        cem.register(CustomEnchant("cleave", "Cleave", 3, EnchantTarget.AXE,
            description = "Sweeping edge on axes"))
        cem.register(CustomEnchant("berserk", "Berserk", 3, EnchantTarget.AXE,
            description = "Strength boost with a negative side effect"))
        cem.register(CustomEnchant("paralysis", "Paralysis", 3, EnchantTarget.AXE,
            description = "Chance to stun the target"))
        cem.register(CustomEnchant("blizzard", "Blizzard", 3, EnchantTarget.AXE,
            description = "Applies freezing slowness to opponent"))

        // ── Melee weapons & tools ───────────────────────
        val quickHit = QuickHitSettings.load(config.getConfigurationSection("quick-hit"))
        quickHit.problems.forEach { logger.warning("[Enchants] quick-hit: $it") }
        cem.quickHit = quickHit
        if (quickHit.enabled) {
            cem.register(CustomEnchant(QuickHit.ID, QuickHit.DISPLAY_NAME, quickHit.maxLevel, EnchantTarget.MELEE_TOOL,
                description = QuickHit.DESCRIPTION, appliesTo = quickHit::canApplyTo))
        } else {
            cem.unregister(QuickHit.ID)
        }

        // ── Helmet ──────────────────────────────────────
        cem.register(CustomEnchant("night_vision", "Night Vision", 1, EnchantTarget.HELMET,
            description = "Permanent night vision while worn"))
        cem.register(CustomEnchant("clarity", "Clarity", 3, EnchantTarget.HELMET,
            description = "Immune to blindness and nausea, reduces negative effect duration"))
        cem.register(CustomEnchant("focus", "Focus", 3, EnchantTarget.HELMET,
            description = "Reduces axe damage taken"))
        cem.register(CustomEnchant("xray", "Xray", 5, EnchantTarget.HELMET,
            description = "While crouching, ores glow nearby. Higher levels = larger radius"))
        cem.register(CustomEnchant("saturation", "Saturation", 1, EnchantTarget.HELMET,
            description = "Slowly restores hunger over time"))

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
        cem.register(CustomEnchant("lava_walker", "Lava Walker", 3, EnchantTarget.BOOTS,
            description = "Converts nearby lava to obsidian as you walk"))

        // ── Shovel ──────────────────────────────────────
        cem.register(CustomEnchant("glass_breaker", "Glass Breaker", 1, EnchantTarget.SHOVEL,
            description = "Breaks glass instantly"))

        // ── Pickaxe / Shovel / Axe / Hoe ────────────────
        cem.register(CustomEnchant("magnet", "Magnet", 1, EnchantTarget.ALL_TOOLS,
            description = "Mined blocks go straight to your inventory"))
        cem.register(CustomEnchant("crab_claw", "Crab Claw", 3, EnchantTarget.ALL_TOOLS,
            description = "Extends block-mining reach by 1-3 blocks"))

        // ── Pickaxe ─────────────────────────────────────
        cem.register(CustomEnchant("autosmelt", "Autosmelt", 1, EnchantTarget.PICKAXE,
            description = "Automatically smelts mined ores"))
        cem.register(CustomEnchant("experience", "Experience", 3, EnchantTarget.PICKAXE,
            description = "Gain XP from mining stone"))
        cem.register(CustomEnchant("condenser", "Condenser", 1, EnchantTarget.PICKAXE,
            description = "Automatically condenses ores into blocks"))
        cem.register(CustomEnchant("explosive", "Explosive", 3, EnchantTarget.PICKAXE,
            description = "Chance to blow up a 3x3x3 area"))
        cem.register(CustomEnchant("bedrock_breaker", "Bedrock Breaker", 3, EnchantTarget.PICKAXE,
            description = "Mine bedrock (no drops): I=60s, II=45s, III=30s"))

        // ── Hoe ─────────────────────────────────────────
        cem.register(CustomEnchant("ground_pound", "Ground Pound", 1, EnchantTarget.HOE,
            description = "Breaks crops in a 3x3 area"))
        cem.register(CustomEnchant("great_harvest", "Great Harvest", 1, EnchantTarget.HOE,
            description = "Auto-replants crops when broken"))
        cem.register(CustomEnchant("blessing", "Blessing", 1, EnchantTarget.HOE,
            description = "Auto-regrows replanted crops (requires Great Harvest)"))

        // ── Mace ─────────────────────────────────────────
        cem.register(CustomEnchant("meteor", "Meteor", 1, EnchantTarget.MACE,
            description = "Higher fall = wider smash damage radius"))
        cem.register(CustomEnchant("crater", "Crater", 3, EnchantTarget.MACE,
            description = "Smash attacks stun nearby enemies"))

        // ── Fishing Rod ──────────────────────────────────
        cem.register(CustomEnchant("barbed_hook", "Barbed Hook", 4, EnchantTarget.FISHING_ROD,
            description = "Hooked entities take damage over time"))
        cem.register(CustomEnchant("netcaster", "Netcaster", 3, EnchantTarget.FISHING_ROD,
            description = "Chance to catch multiple fish at once"))

        // ── Crossbow ─────────────────────────────────────
        cem.register(CustomEnchant("shrapnel", "Shrapnel", 3, EnchantTarget.CROSSBOW,
            description = "Arrows explode on impact"))
        cem.register(CustomEnchant("sniper", "Sniper", 1, EnchantTarget.CROSSBOW,
            description = "Increased damage the further the target"))
        cem.register(CustomEnchant("hunter", "Hunter", 5, EnchantTarget.CROSSBOW,
            description = "Bonus damage to flying mobs and gliding players"))

        // ── Trident ──────────────────────────────────────
        cem.register(CustomEnchant("harpoon", "Harpoon", 1, EnchantTarget.TRIDENT,
            description = "Thrown tridents drag hit entities back toward you"))
        cem.register(CustomEnchant("tidal_leap", "Tidal Leap", 1, EnchantTarget.TRIDENT,
            description = "Riptide launches even without rain (requires Riptide)"))

        // ── Shield ───────────────────────────────────────
        cem.register(CustomEnchant("reflect", "Reflect", 3, EnchantTarget.SHIELD,
            description = "Chance to reflect incoming damage back at the attacker"))

        // ── Elytra ───────────────────────────────────────
        cem.register(CustomEnchant("cloudstep", "Cloudstep", 1, EnchantTarget.ELYTRA,
            description = "Prevents collision damage while gliding with an elytra"))

        // ── Spyglass ─────────────────────────────────────
        cem.register(CustomEnchant("spyglass_hunter", "Hunter", 3, EnchantTarget.SPYGLASS,
            description = "Highlights nearby players and mobs while zoomed in"))

        // ── Any item ─────────────────────────────────────
        cem.register(CustomEnchant("absorb", "Absorb", 4, EnchantTarget.ALL,
            description = "Slowly repairs durability while exposed to sunlight"))
    }

    /**
     * Apply world borders and structure settings to Nether, End, and Overworld.
     * - Nether and End get a 10k x 10k border; vanilla structures stay enabled
     *   there (and in the main overworld) — only custom/server worlds have
     *   structures disabled.
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
                        // The main survival overworld keeps vanilla structure
                        // generation — only custom/server worlds (pvp, afk,
                        // dungeon, event, etc.) get structures disabled.
                        if (world.name != "world") {
                            disableStructureGeneration(world)
                        }
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
        // Legacy settings — retired from the /settings GUI/command (see issue #501),
        // but the toggle keys are still read by their own dedicated command/listener,
        // so they stay registered (hidden) to preserve correct defaults for new players.
        settingsManager.register(SettingsManager.SettingDef(
            key = "gsit",
            displayName = "Sit on Blocks",
            description = "Right-click stairs/slabs to sit",
            material = org.bukkit.Material.OAK_STAIRS,
            disabledMaterial = org.bukkit.Material.BARRIER,
            default = true,
            permission = "joshymc.gsit",
            hidden = true
        ))
        settingsManager.register(SettingsManager.SettingDef(
            key = "death_coords",
            displayName = "Death Coordinates",
            description = "Show coordinates when you die",
            material = org.bukkit.Material.RECOVERY_COMPASS,
            disabledMaterial = org.bukkit.Material.COMPASS,
            default = true,
            permission = "joshymc.deathcoords",
            hidden = true
        ))
        settingsManager.register(SettingsManager.SettingDef(
            key = "random_coords",
            displayName = "Random Coords",
            description = "Randomize X/Z shown to you (Y stays real)",
            material = org.bukkit.Material.ENDER_PEARL,
            disabledMaterial = org.bukkit.Material.GRAY_DYE,
            default = false,
            permission = "joshymc.randomcoords",
            hidden = true
        ))
        settingsManager.register(SettingsManager.SettingDef(
            key = "pvp",
            displayName = "PvP",
            description = "Toggle player vs player combat",
            material = org.bukkit.Material.DIAMOND_SWORD,
            disabledMaterial = org.bukkit.Material.WOODEN_SWORD,
            default = true,
            permission = "joshymc.pvp",
            hidden = true
        ))
        settingsManager.register(SettingsManager.SettingDef(
            key = "veinminer",
            displayName = "Veinminer",
            description = "Sneak + mine to break ore veins",
            material = org.bukkit.Material.DIAMOND_PICKAXE,
            disabledMaterial = org.bukkit.Material.STONE_PICKAXE,
            default = true,
            permission = "joshymc.veinminer",
            hidden = true
        ))
        settingsManager.register(SettingsManager.SettingDef(
            key = "autosmelt",
            displayName = "Auto Smelt",
            description = "Automatically smelt ore drops",
            material = org.bukkit.Material.BLAST_FURNACE,
            disabledMaterial = org.bukkit.Material.FURNACE,
            default = false,
            permission = "joshymc.autosmelt",
            hidden = true
        ))
        settingsManager.register(SettingsManager.SettingDef(
            key = "treefeller",
            displayName = "Tree Feller",
            description = "Sneak + chop to fell entire trees",
            material = org.bukkit.Material.DIAMOND_AXE,
            disabledMaterial = org.bukkit.Material.STONE_AXE,
            default = false,
            permission = "joshymc.treefeller",
            hidden = true
        ))
        settingsManager.register(SettingsManager.SettingDef(
            key = com.liam.joshymc.manager.StaffChatManager.VIEW_SETTING_KEY,
            displayName = "Staff Chat Viewing",
            description = "Receive Staff Chat messages from other staff",
            material = org.bukkit.Material.WRITABLE_BOOK,
            disabledMaterial = org.bukkit.Material.BARRIER,
            default = true,
            permission = com.liam.joshymc.manager.StaffChatManager.PERM_VIEW,
            hidden = true
        ))
        settingsManager.register(SettingsManager.SettingDef(
            key = com.liam.joshymc.manager.ChatManager.CLEARING_SETTING_KEY,
            displayName = "Chat Clearing",
            description = "Have your screen cleared by /chat clear",
            material = org.bukkit.Material.WRITABLE_BOOK,
            disabledMaterial = org.bukkit.Material.BARRIER,
            default = true,
            permission = com.liam.joshymc.manager.ChatManager.PERM_CLEARING,
            hidden = true
        ))
        settingsManager.register(SettingsManager.SettingDef(
            key = com.liam.joshymc.manager.CoinflipManager.NOTIFY_SETTING_KEY,
            displayName = "Coinflip Notifications",
            description = "Receive global Coinflip creation/winner announcements",
            material = org.bukkit.Material.GOLD_NUGGET,
            disabledMaterial = org.bukkit.Material.BARRIER,
            default = true,
            permission = "joshymc.coinflip",
            hidden = true
        ))

        // Owned by /serverbar (issue #1059); default on so new players see the bar.
        settingsManager.register(SettingsManager.SettingDef(
            key = com.liam.joshymc.manager.RotatingBossBarManager.SETTING_KEY,
            displayName = "Server Bar",
            description = "Show the rotating server info BossBar",
            material = org.bukkit.Material.NAME_TAG,
            disabledMaterial = org.bukkit.Material.GRAY_DYE,
            default = true,
            permission = "joshymc.serverbar",
            hidden = true
        ))

        // Owned by /sj (issue #1063); default on so silent-join staff see each other.
        settingsManager.register(SettingsManager.SettingDef(
            key = VanishCommand.SILENT_JOIN_NOTIFY_SETTING_KEY,
            displayName = "Silent Join Alerts",
            description = "See other silent staff join and leave",
            material = org.bukkit.Material.BELL,
            disabledMaterial = org.bukkit.Material.GRAY_DYE,
            default = true,
            permission = VanishCommand.SILENT_JOIN_PERMISSION,
            hidden = true
        ))

        // Owned by /sj <on|off> <public|private> (issue #1067); default private.
        settingsManager.register(SettingsManager.SettingDef(
            key = VanishCommand.SILENT_JOIN_PRIVATE_SETTING_KEY,
            displayName = "Private Join/Leave",
            description = "Hide your own join and leave from public chat",
            material = org.bukkit.Material.ENDER_EYE,
            disabledMaterial = org.bukkit.Material.GRAY_DYE,
            default = true,
            permission = VanishCommand.SILENT_JOIN_VISIBILITY_PERMISSION,
            hidden = true
        ))

        // Active /settings toggles (issue #501)
        settingsManager.register(SettingsManager.SettingDef(
            key = com.liam.joshymc.manager.ScoreboardManager.SCOREBOARD_SETTING_KEY,
            displayName = "Scoreboard",
            description = "Show your sidebar scoreboard",
            material = org.bukkit.Material.ITEM_FRAME,
            disabledMaterial = org.bukkit.Material.GRAY_DYE,
            default = true,
            permission = "joshymc.scoreboard",
            onToggle = { player, _ ->
                scoreboardManager.refreshSidebar(player)
            }
        ))
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
            key = "mob_visibility",
            displayName = "Mob Visibility",
            description = "Hide ordinary mobs while keeping tamed animals visible",
            material = org.bukkit.Material.ZOMBIE_HEAD,
            disabledMaterial = org.bukkit.Material.SKELETON_SKULL,
            default = true,
            permission = "joshymc.mobvisibility",
            onToggle = { player, enabled ->
                com.liam.joshymc.listener.MobVisibilityListener.applyTo(this, player, enabled)
            }
        ))
        settingsManager.register(SettingsManager.SettingDef(
            key = "payments",
            displayName = "Payments",
            description = "Allow other players to send you money",
            material = org.bukkit.Material.GOLD_INGOT,
            disabledMaterial = org.bukkit.Material.IRON_INGOT,
            default = true
        ))
        settingsManager.register(SettingsManager.SettingDef(
            key = "credit_payments",
            displayName = "Credit Payments",
            description = "Allow other players to send you Credits",
            material = org.bukkit.Material.SUNFLOWER,
            disabledMaterial = org.bukkit.Material.WITHER_ROSE,
            default = true
        ))
        settingsManager.register(SettingsManager.SettingDef(
            key = "tpa",
            displayName = "TPA Requests",
            description = "Allow other players to send you /tpa requests",
            material = org.bukkit.Material.ENDER_PEARL,
            disabledMaterial = org.bukkit.Material.GRAY_DYE,
            default = true
        ))
        settingsManager.register(SettingsManager.SettingDef(
            key = "tpahere",
            displayName = "TPAHere Requests",
            description = "Allow other players to send you /tpahere requests",
            material = org.bukkit.Material.ENDER_EYE,
            disabledMaterial = org.bukkit.Material.GRAY_DYE,
            default = true
        ))
        settingsManager.register(SettingsManager.SettingDef(
            key = com.liam.joshymc.manager.CommunicationsManager.PERSONAL_MESSAGES_SETTING_KEY,
            displayName = "Personal Messages",
            description = "Allow other players to send you /msg private messages",
            material = org.bukkit.Material.WRITABLE_BOOK,
            disabledMaterial = org.bukkit.Material.GRAY_DYE,
            default = true
        ))
        // Action-bar quest progress (issue #1278); QuestCycleManager reads it.
        settingsManager.register(SettingsManager.SettingDef(
            key = com.liam.joshymc.manager.QuestCycleManager.PROGRESS_NOTIFY_SETTING_KEY,
            displayName = "Quest Progress",
            description = "Show quest progress above your hotbar",
            material = org.bukkit.Material.WRITABLE_BOOK,
            disabledMaterial = org.bukkit.Material.BOOK,
            default = true
        ))
        // Same switch as /team chat on|off (issue #1263); TeamManager reads it.
        settingsManager.register(SettingsManager.SettingDef(
            key = TeamManager.TEAM_CHAT_SETTING_KEY,
            displayName = "Team Chat",
            description = "Send your chat messages to your team only",
            material = org.bukkit.Material.OAK_SIGN,
            disabledMaterial = org.bukkit.Material.GRAY_DYE,
            default = false,
            permission = "joshymc.team",
            enableBlockedReason = { player ->
                if (teamManager.getPlayerTeam(player.uniqueId) == null) "You must be in a team to use Team Chat." else null
            }
        ))
    }

    /**
     * Backfill any config keys that exist in the bundled default config.yml but
     * are missing from the player-edited file on disk. Bukkit's saveDefaultConfig
     * only writes when the file is absent — without this, users upgrading from
     * older versions never see new sections (e.g. afk.reward.money).
     *
     * We touch only missing keys, so hand-edited values are left alone. Sections
     * in [NO_BACKFILL_SECTION_PREFIXES] are admin-owned name->value maps (rank ids,
     * purchasable-rank keys, ...) rather than fixed schema — entries an admin
     * intentionally deletes from those must stay deleted (see issue #911).
     */
    private fun migrateConfig() {
        val configFile = File(dataFolder, "config.yml")
        val onDisk = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(configFile)
        if (ConfigUtil.looksLikeParseFailure(configFile, onDisk)) {
            logger.severe("[ConfigMigrator] config.yml failed to load (invalid YAML) — the existing file has been preserved and was NOT overwritten. Fix the syntax error and restart or /joshymc reload.")
            return
        }

        val defaultStream = getResource("config.yml") ?: return
        val defaults = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
            defaultStream.bufferedReader()
        )

        // Issue #1161: Discord channel ids moved under discord.channels. Copy the old keys first,
        // or the backfill below would fill the new keys with the bundled channel ids.
        val discordMoves = com.liam.joshymc.discord.DiscordConfig.migrate(config)
        for (move in discordMoves) logger.info("[ConfigMigrator] Moved deprecated Discord setting $move.")
        var changed = discordMoves.size
        for (key in defaults.getKeys(true)) {
            if (defaults.isConfigurationSection(key)) continue
            if (NO_BACKFILL_SECTION_PREFIXES.any { key.startsWith(it) }) continue
            if (!config.contains(key, true)) {
                config.set(key, defaults.get(key))
                changed++
            }
        }

        // Issue #1192: the Mob Waves waves are staff-owned (deleted waves stay deleted), but a
        // config without the section at all, like one from before Mob Waves, gets the defaults.
        if (!config.contains(MOB_WAVES_KEY, true) && defaults.isConfigurationSection(MOB_WAVES_KEY)) {
            for (key in defaults.getKeys(true)) {
                if (!key.startsWith("$MOB_WAVES_KEY.") || defaults.isConfigurationSection(key)) continue
                config.set(key, defaults.get(key))
                changed++
            }
        }

        // Issue #1065: the Quest Master reward went from 100 to 1,000 Credits. Backfill never
        // touches existing keys, so rewrite the old default only if the admin left it untouched.
        val questMasterRewardKey = "quests.quest-master.reward.commands"
        if (config.getStringList(questMasterRewardKey) == listOf("credits give {player} 100")) {
            config.set(questMasterRewardKey, listOf("credits give {player} 1000"))
            changed++
        }

        if (changed > 0) {
            ConfigUtil.backup(configFile, logger, "ConfigMigrator")
            saveConfig()
            logger.info("[ConfigMigrator] Backfilled $changed missing config key(s) from defaults.")
        }
    }
}
