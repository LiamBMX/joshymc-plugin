package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import com.liam.joshymc.listener.ChatItemListener
import com.liam.joshymc.listener.EnderchestPreviewListener
import com.liam.joshymc.listener.CombatListener
import com.liam.joshymc.listener.DeathCoordsListener
import com.liam.joshymc.listener.DrillMiningListener
import com.liam.joshymc.listener.EditKitListener
import com.liam.joshymc.listener.EasterEggListener
import com.liam.joshymc.listener.PeanutButterCupListener
import com.liam.joshymc.listener.LinkPopListener
import com.liam.joshymc.listener.JosheesBarListener
import com.liam.joshymc.listener.RurusListener
import com.liam.joshymc.listener.CandyCornListener
import com.liam.joshymc.listener.TomatoListener
import com.liam.joshymc.listener.VikingBoomerangListener
import com.liam.joshymc.listener.GSitListener
import com.liam.joshymc.listener.DiscordGroupListener
import com.liam.joshymc.listener.LinkGuiListener
import com.liam.joshymc.listener.MinecraftChatListener
import com.liam.joshymc.listener.ModModeListener
import com.liam.joshymc.listener.TraineeModeBackupRestoreListener
import com.liam.joshymc.listener.NightVisionListener
import com.liam.joshymc.listener.RecipeBlockerListener
import com.liam.joshymc.listener.AFKListener
import com.liam.joshymc.listener.AutoSmeltListener
import com.liam.joshymc.listener.ChatFormatListener
import com.liam.joshymc.listener.MobVisibilityListener
import com.liam.joshymc.listener.TreeFellerListener
import com.liam.joshymc.listener.VeinminerListener
import com.liam.joshymc.listener.UnknownCommandListener
import com.liam.joshymc.listener.PvpKillLogListener
import com.liam.joshymc.listener.AncientDebrisMonitorListener
import com.liam.joshymc.listener.PvpTeleportListener
import com.liam.joshymc.listener.PvpWorldFlightListener
import com.liam.joshymc.listener.TradeInteractListener
import com.liam.joshymc.command.BackLocationListener
import com.liam.joshymc.listener.WelcomeListener
import com.liam.joshymc.listener.WorthListener
import com.liam.joshymc.listener.WrittenBookListener
import com.liam.joshymc.listener.ClaimProtectionListener
import com.liam.joshymc.listener.BubbleButtListener
import com.liam.joshymc.listener.CustomArmorListener
import com.liam.joshymc.listener.GreatPumpkinPieListener
import com.liam.joshymc.listener.ThrownTridentVisualListener
import com.liam.joshymc.listener.AuctionBidListener
import com.liam.joshymc.listener.StockTradeChatListener
import com.liam.joshymc.listener.CustomArmorAnvilListener
import com.liam.joshymc.listener.SellWandAnvilListener
import com.liam.joshymc.listener.ItemRenameListener
import com.liam.joshymc.listener.PhysicalVoucherListener
import com.liam.joshymc.listener.CreditVoucherListener
import com.liam.joshymc.listener.RankVoucherListener
import com.liam.joshymc.listener.ChatTagVoucherListener
import com.liam.joshymc.listener.SellWandListener
import com.liam.joshymc.listener.enchant.CombatEnchantListener
import com.liam.joshymc.listener.enchant.PassiveEnchantListener
import com.liam.joshymc.listener.enchant.QuickHitListener
import com.liam.joshymc.listener.enchant.ToolEnchantListener
import com.liam.joshymc.listener.enchant.WeaponEnchantListener

class ListenerManager(private val plugin: Joshymc) {

    var passiveEnchantListener: PassiveEnchantListener? = null
    var quickHitListener: QuickHitListener? = null
        private set

    var thrownTridentVisualListener: ThrownTridentVisualListener? = null
        private set

    var greatPumpkinPieListener: GreatPumpkinPieListener? = null
        private set

    var ancientDebrisMonitorListener: AncientDebrisMonitorListener? = null
        private set

    lateinit var welcomeListener: WelcomeListener
        private set

    fun registerAll() {
        val pm = plugin.server.pluginManager

        // GUI framework — registered FIRST so it catches clicks before anything else
        pm.registerEvents(plugin.guiManager, plugin)

        // Issue #1289: gamemode lock checks for typed commands + command-list hiding.
        pm.registerEvents(com.liam.joshymc.gamemode.GamemodeLockListener(plugin), plugin)

        // Existing
        pm.registerEvents(DrillMiningListener(plugin), plugin)
        pm.registerEvents(EditKitListener(plugin), plugin)
        pm.registerEvents(EasterEggListener(plugin), plugin)
        pm.registerEvents(PeanutButterCupListener(plugin), plugin)
        pm.registerEvents(LinkPopListener(plugin), plugin)
        pm.registerEvents(JosheesBarListener(plugin), plugin)
        pm.registerEvents(RurusListener(plugin), plugin)
        pm.registerEvents(CandyCornListener(plugin), plugin)
        pm.registerEvents(TomatoListener(plugin), plugin)
        pm.registerEvents(VikingBoomerangListener(plugin), plugin)
        pm.registerEvents(MinecraftChatListener(plugin), plugin)
        // Before WelcomeListener (also NORMAL), so a blocked spam message never counts as a welcome.
        pm.registerEvents(com.liam.joshymc.listener.ChatSpamListener(plugin), plugin)
        pm.registerEvents(LinkGuiListener(plugin), plugin)
        pm.registerEvents(DiscordGroupListener(plugin), plugin)

        // Phase 1
        pm.registerEvents(NightVisionListener(plugin), plugin)
        pm.registerEvents(GSitListener(plugin), plugin)
        pm.registerEvents(DeathCoordsListener(plugin), plugin)
        pm.registerEvents(RecipeBlockerListener(plugin), plugin)
        pm.registerEvents(ChatItemListener(plugin), plugin)
        pm.registerEvents(EnderchestPreviewListener(plugin), plugin)

        // Phase 2 — Combat
        pm.registerEvents(CombatListener(plugin), plugin)
        pm.registerEvents(com.liam.joshymc.listener.ExplosionPvpListener(plugin), plugin)
        pm.registerEvents(PvpKillLogListener(plugin), plugin)
        ancientDebrisMonitorListener = AncientDebrisMonitorListener(plugin).also {
            it.start()
            pm.registerEvents(it, plugin)
        }

        // Phase 3 — Mining
        pm.registerEvents(VeinminerListener(plugin), plugin)
        pm.registerEvents(AutoSmeltListener(plugin), plugin)
        pm.registerEvents(TreeFellerListener(plugin), plugin)
        pm.registerEvents(MobVisibilityListener(plugin), plugin)

        // Phase 5 — Chat & AFK
        pm.registerEvents(ChatFormatListener(plugin), plugin)
        pm.registerEvents(AFKListener(plugin), plugin)
        pm.registerEvents(com.liam.joshymc.listener.StaffChatListener(plugin), plugin)

        // Trading
        pm.registerEvents(plugin.tradeManager, plugin)
        pm.registerEvents(TradeInteractListener(plugin), plugin)

        // Player Vaults
        pm.registerEvents(plugin.storageManager, plugin)

        // Overflow Storage (Orders / Auction House delivery fallback)
        pm.registerEvents(plugin.overflowManager, plugin)

        // Ender Chests (expanded to 54 slots)
        pm.registerEvents(plugin.enderChestManager, plugin)

        // Holograms, NPCs, Crates
        pm.registerEvents(plugin.npcManager, plugin)
        pm.registerEvents(plugin.crateManager, plugin)

        // Sign Shops, Hoppers, Spawners, Teams
        pm.registerEvents(plugin.signShopManager, plugin)
        pm.registerEvents(plugin.hopperPlusManager, plugin)
        pm.registerEvents(plugin.spawnerManager, plugin)
        pm.registerEvents(plugin.teamManager, plugin)
        pm.registerEvents(com.liam.joshymc.listener.TeamChatInputListener(plugin), plugin)
        pm.registerEvents(plugin.killStreakManager, plugin)
        pm.registerEvents(plugin.loginStreakManager, plugin)

        // AntiCheat
        pm.registerEvents(plugin.antiCheatManager, plugin)

        // Custom Enchants
        pm.registerEvents(plugin.customEnchantManager, plugin)
        pm.registerEvents(CombatEnchantListener(plugin), plugin)
        pm.registerEvents(ToolEnchantListener(plugin), plugin)
        pm.registerEvents(WeaponEnchantListener(plugin), plugin)
        passiveEnchantListener = PassiveEnchantListener(plugin)
        passiveEnchantListener!!.start()
        pm.registerEvents(passiveEnchantListener!!, plugin)
        quickHitListener = QuickHitListener(plugin)
        pm.registerEvents(quickHitListener!!, plugin)

        // Claims
        pm.registerEvents(plugin.claimManager, plugin)
        pm.registerEvents(ClaimProtectionListener(plugin), plugin)
        pm.registerEvents(com.liam.joshymc.command.SubclaimCommand(plugin), plugin)

        // Auction bid chat input + auction manager quit cleanup
        pm.registerEvents(plugin.auctionManager, plugin)
        pm.registerEvents(AuctionBidListener(plugin), plugin)

        // Giveaway coins/title/description chat input + join delivery + quit cleanup
        pm.registerEvents(plugin.giveawayManager, plugin)
        pm.registerEvents(com.liam.joshymc.listener.GiveawayChatListener(plugin), plugin)

        // Gift coins/message/offline-name chat input + join notification + quit cleanup
        pm.registerEvents(plugin.giftManager, plugin)
        pm.registerEvents(com.liam.joshymc.listener.GiftChatListener(plugin), plugin)

        // Coinflip create-amount chat input + quit cleanup
        pm.registerEvents(com.liam.joshymc.listener.CoinflipChatListener(plugin), plugin)

        // Credits Coinflip wager chat input + quit cleanup
        pm.registerEvents(com.liam.joshymc.listener.CreditsCoinflipChatListener(plugin), plugin)

        // Casino bet-amount chat input + disconnect safety (Mines/Towers auto-cashout) + quit cleanup
        pm.registerEvents(com.liam.joshymc.listener.CasinoChatListener(plugin), plugin)

        // Buy Orders chat input (create order quantity/price, custom sell amount) + join/quit cleanup
        pm.registerEvents(plugin.orderManager, plugin)
        pm.registerEvents(com.liam.joshymc.listener.OrderChatListener(plugin), plugin)

        // Stock market chat input (create name / buy amount / sell amount) + quit cleanup
        pm.registerEvents(StockTradeChatListener(plugin), plugin)

        // Sell Wand
        pm.registerEvents(SellWandListener(plugin), plugin)
        pm.registerEvents(PhysicalVoucherListener(plugin), plugin)
        pm.registerEvents(CreditVoucherListener(plugin), plugin)
        pm.registerEvents(RankVoucherListener(plugin), plugin)
        pm.registerEvents(ChatTagVoucherListener(plugin), plugin)
        pm.registerEvents(SellWandAnvilListener(plugin), plugin)
        pm.registerEvents(CustomArmorAnvilListener(plugin), plugin)
        pm.registerEvents(ItemRenameListener(plugin), plugin)

        // Quest events
        pm.registerEvents(plugin.questCycleManager, plugin)
        pm.registerEvents(plugin.eventQuestManager, plugin)
        pm.registerEvents(com.liam.joshymc.listener.TravelTrackingListener(plugin), plugin)
        pm.registerEvents(plugin.resurgeManager, plugin)

        // Talisman effects
        pm.registerEvents(plugin.talismanManager, plugin)

        // Fishing
        pm.registerEvents(plugin.fishingManager, plugin)

        // Spawn fly
        pm.registerEvents(plugin.spawnWorldManager, plugin)

        // Arena + portals + voting
        pm.registerEvents(plugin.arenaManager, plugin)
        pm.registerEvents(plugin.eventManager, plugin)
        pm.registerEvents(plugin.eventManager.games.listener, plugin)
        pm.registerEvents(plugin.eventManager.games.mobsListener, plugin)
        pm.registerEvents(plugin.portalManager, plugin)
        pm.registerEvents(plugin.voteManager, plugin)
        pm.registerEvents(plugin.fakeBaseManager, plugin)

        // PvP world flight restriction (joshymc.fly.pvp)
        pm.registerEvents(PvpWorldFlightListener(plugin), plugin)

        // PvP world teleport lock (issue #1075)
        pm.registerEvents(PvpTeleportListener(plugin), plugin)

        // Stuck-player recovery and flying-kick grace (issue #1241)
        pm.registerEvents(plugin.stuckRecoveryManager, plugin)

        // Admin panel
        pm.registerEvents(plugin.adminManager, plugin)

        // Moderator Mode
        pm.registerEvents(ModModeListener(plugin), plugin)

        // Trainee Mode was removed (issue #987) — this only restores any leftover
        // legacy backup inventory, see TraineeModeBackupRestoreListener.
        TraineeModeBackupRestoreListener(plugin).also {
            it.start()
            pm.registerEvents(it, plugin)
        }

        // Upgrades pre-#1070 Haunted Hollow / Shark armor copies to unbreakable and
        // renames pre-#1098 Ice Skates / pre-#1097 Autumn Wanderer copies to their set names
        com.liam.joshymc.listener.UnbreakableArmorUpgradeListener(plugin).also {
            it.start()
            pm.registerEvents(it, plugin)
        }

        // Mob stacking
        pm.registerEvents(plugin.mobStackManager, plugin)

        // Custom items
        val armorListener = CustomArmorListener(plugin)
        armorListener.start()
        pm.registerEvents(armorListener, plugin)
        pm.registerEvents(BubbleButtListener(plugin), plugin)
        thrownTridentVisualListener = ThrownTridentVisualListener(plugin).also {
            it.start()
            pm.registerEvents(it, plugin)
        }
        greatPumpkinPieListener = GreatPumpkinPieListener(plugin).also {
            it.start()
            pm.registerEvents(it, plugin)
        }

        // Cosmetics
        pm.registerEvents(plugin.killEffectManager, plugin)

        // Back location tracking
        pm.registerEvents(BackLocationListener(plugin), plugin)

        // Punishment (login/chat checks)
        pm.registerEvents(plugin.punishmentManager, plugin)

        // Alts (hashed connection tracking on join)
        pm.registerEvents(plugin.altManager, plugin)

        // Resource world (boss bar join/quit)
        pm.registerEvents(plugin.resourceWorldManager, plugin)

        // Scoreboard (join/quit/death)
        pm.registerEvents(plugin.scoreboardManager, plugin)

        // Playtime (join/quit)
        pm.registerEvents(plugin.playtimeManager, plugin)

        // Welcome/MOTD (join/quit messages)
        welcomeListener = WelcomeListener(plugin)
        welcomeListener.start()
        pm.registerEvents(welcomeListener, plugin)

        // Written book page limit
        pm.registerEvents(WrittenBookListener(plugin), plugin)

        // Utilities
        pm.registerEvents(UnknownCommandListener(plugin), plugin)

        // Item worth action bar on hotbar scroll
        pm.registerEvents(WorthListener(plugin), plugin)

        plugin.logger.info("Listeners registered.")
    }
}
