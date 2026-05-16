package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import com.liam.joshymc.listener.ChatItemListener
import com.liam.joshymc.listener.CombatListener
import com.liam.joshymc.listener.DeathCoordsListener
import com.liam.joshymc.listener.DrillMiningListener
import com.liam.joshymc.listener.EasterEggListener
import com.liam.joshymc.listener.GSitListener
import com.liam.joshymc.listener.LinkGuiListener
import com.liam.joshymc.listener.MinecraftChatListener
import com.liam.joshymc.listener.NightVisionListener
import com.liam.joshymc.listener.CustomCraftingListener
import com.liam.joshymc.listener.RecipeBlockerListener
import com.liam.joshymc.listener.AFKListener
import com.liam.joshymc.listener.AutoSmeltListener
import com.liam.joshymc.listener.ChatFormatListener
import com.liam.joshymc.listener.MobVisibilityListener
import com.liam.joshymc.listener.TreeFellerListener
import com.liam.joshymc.listener.VeinminerListener
import com.liam.joshymc.listener.UnknownCommandListener
import com.liam.joshymc.listener.TradeInteractListener
import com.liam.joshymc.command.BackLocationListener
import com.liam.joshymc.listener.WelcomeListener
import com.liam.joshymc.listener.ClaimProtectionListener
import com.liam.joshymc.listener.ConsumableListener
import com.liam.joshymc.listener.BubbleButtListener
import com.liam.joshymc.listener.CustomArmorListener
import com.liam.joshymc.listener.CustomDropListener
import com.liam.joshymc.listener.AuctionBidListener
import com.liam.joshymc.listener.SellWandListener
import com.liam.joshymc.listener.VoidBoreListener
import com.liam.joshymc.listener.enchant.CombatEnchantListener
import com.liam.joshymc.listener.enchant.PassiveEnchantListener
import com.liam.joshymc.listener.enchant.ToolEnchantListener
import com.liam.joshymc.listener.enchant.WeaponEnchantListener

class ListenerManager(private val plugin: Joshymc) {

    var passiveEnchantListener: PassiveEnchantListener? = null
        private set

    fun registerAll() {
        val pm = plugin.server.pluginManager

        // GUI framework — registered FIRST so it catches clicks before anything else
        pm.registerEvents(plugin.guiManager, plugin)

        // Existing
        pm.registerEvents(DrillMiningListener(plugin), plugin)
        pm.registerEvents(VoidBoreListener(plugin), plugin)
        pm.registerEvents(EasterEggListener(plugin), plugin)
        pm.registerEvents(MinecraftChatListener(plugin), plugin)
        pm.registerEvents(LinkGuiListener(plugin), plugin)

        // Phase 1
        pm.registerEvents(NightVisionListener(plugin), plugin)
        pm.registerEvents(GSitListener(plugin), plugin)
        pm.registerEvents(DeathCoordsListener(plugin), plugin)
        pm.registerEvents(RecipeBlockerListener(plugin), plugin)
        pm.registerEvents(CustomCraftingListener(plugin), plugin)
        pm.registerEvents(ChatItemListener(plugin), plugin)

        // Phase 2 — Combat
        pm.registerEvents(CombatListener(plugin), plugin)

        // Phase 3 — Mining
        pm.registerEvents(VeinminerListener(plugin), plugin)
        pm.registerEvents(AutoSmeltListener(plugin), plugin)
        pm.registerEvents(TreeFellerListener(plugin), plugin)
        pm.registerEvents(MobVisibilityListener(plugin), plugin)

        // Phase 5 — Chat & AFK
        pm.registerEvents(ChatFormatListener(plugin), plugin)
        pm.registerEvents(AFKListener(plugin), plugin)

        // Trading
        pm.registerEvents(plugin.tradeManager, plugin)
        pm.registerEvents(TradeInteractListener(plugin), plugin)

        // Player Vaults
        pm.registerEvents(plugin.storageManager, plugin)

        // Holograms, NPCs, Crates
        pm.registerEvents(plugin.npcManager, plugin)
        pm.registerEvents(plugin.crateManager, plugin)

        // Sign Shops, Hoppers, Spawners, Teams
        pm.registerEvents(plugin.signShopManager, plugin)
        pm.registerEvents(plugin.hopperPlusManager, plugin)
        pm.registerEvents(plugin.spawnerManager, plugin)
        pm.registerEvents(plugin.teamManager, plugin)

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

        // Claims
        pm.registerEvents(plugin.claimManager, plugin)
        pm.registerEvents(ClaimProtectionListener(plugin), plugin)
        pm.registerEvents(com.liam.joshymc.command.SubclaimCommand(plugin), plugin)

        // Auction bid chat input
        pm.registerEvents(AuctionBidListener(plugin), plugin)

        // Sell Wand
        pm.registerEvents(SellWandListener(plugin), plugin)

        // Quest events
        pm.registerEvents(plugin.questManager, plugin)
        pm.registerEvents(plugin.dailyQuestManager, plugin)
        pm.registerEvents(plugin.resurgeManager, plugin)

        // Talisman effects
        pm.registerEvents(plugin.talismanManager, plugin)

        // Fishing
        pm.registerEvents(plugin.fishingManager, plugin)

        // Skills
        pm.registerEvents(plugin.skillManager, plugin)

        // Spawn fly
        pm.registerEvents(plugin.spawnWorldManager, plugin)

        // Arena + World flags + portals + voting
        pm.registerEvents(plugin.arenaManager, plugin)
        pm.registerEvents(plugin.worldFlagManager, plugin)
        pm.registerEvents(plugin.eventManager, plugin)
        pm.registerEvents(plugin.portalManager, plugin)
        pm.registerEvents(plugin.voteManager, plugin)

        // Admin panel
        pm.registerEvents(plugin.adminManager, plugin)

        // Custom items
        pm.registerEvents(CustomDropListener(plugin), plugin)
        pm.registerEvents(ConsumableListener(plugin), plugin)
        val armorListener = CustomArmorListener(plugin)
        armorListener.start()
        pm.registerEvents(armorListener, plugin)
        pm.registerEvents(BubbleButtListener(plugin), plugin)

        // Cosmetics
        pm.registerEvents(plugin.trailManager, plugin)
        pm.registerEvents(plugin.killEffectManager, plugin)
        pm.registerEvents(plugin.joinEffectManager, plugin)
        pm.registerEvents(plugin.glowManager, plugin)
        pm.registerEvents(plugin.gadgetManager, plugin)

        // Back location tracking
        pm.registerEvents(BackLocationListener(plugin), plugin)

        // Punishment (login/chat checks)
        pm.registerEvents(plugin.punishmentManager, plugin)

        // Resource world (boss bar join/quit)
        pm.registerEvents(plugin.resourceWorldManager, plugin)

        // Scoreboard (join/quit/death)
        pm.registerEvents(plugin.scoreboardManager, plugin)

        // Playtime (join/quit)
        pm.registerEvents(plugin.playtimeManager, plugin)

        // Welcome/MOTD (join/quit messages)
        val welcomeListener = WelcomeListener(plugin)
        welcomeListener.start()
        pm.registerEvents(welcomeListener, plugin)

        // Utilities
        pm.registerEvents(UnknownCommandListener(plugin), plugin)

        plugin.logger.info("Listeners registered.")
    }
}
