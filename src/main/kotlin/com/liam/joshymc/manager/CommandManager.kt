package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import com.liam.joshymc.command.CreateKitCommand
import com.liam.joshymc.command.AnnounceCommand
import com.liam.joshymc.command.AdminCommand
import com.liam.joshymc.command.AnvilCommand
import com.liam.joshymc.command.ChatGameCommand
import com.liam.joshymc.command.LeaderboardCommand
import com.liam.joshymc.command.RepairCommand
import com.liam.joshymc.command.SmithingCommand
import com.liam.joshymc.command.ViolationBridgeCommand
import com.liam.joshymc.command.BanCommand
import com.liam.joshymc.command.HistoryCommand
import com.liam.joshymc.command.KickCommand
import com.liam.joshymc.command.MuteCommand
import com.liam.joshymc.command.NickCommand
import com.liam.joshymc.command.ReportCommand
import com.liam.joshymc.command.RulesCommand
import com.liam.joshymc.command.TempbanCommand
import com.liam.joshymc.command.TempmuteCommand
import com.liam.joshymc.command.UnbanCommand
import com.liam.joshymc.command.UnmuteCommand
import com.liam.joshymc.command.VanishCommand
import com.liam.joshymc.command.WarnCommand
import com.liam.joshymc.command.BackCommand
import com.liam.joshymc.command.ClaimCommand
import com.liam.joshymc.command.CraftCommand
import com.liam.joshymc.command.EnchantCommand
import com.liam.joshymc.command.EnderchestCommand
import com.liam.joshymc.command.FeedCommand
import com.liam.joshymc.command.FlyCommand
import com.liam.joshymc.command.GamemodeCommand
import com.liam.joshymc.command.GenCaveCommand
import com.liam.joshymc.command.GodCommand
import com.liam.joshymc.command.HatCommand
import com.liam.joshymc.command.HealCommand
import com.liam.joshymc.command.HelpCommand
import com.liam.joshymc.command.InvseeCommand
import com.liam.joshymc.command.IgnoreCommand
import com.liam.joshymc.command.MsgCommand
import com.liam.joshymc.command.ReplyCommand
import com.liam.joshymc.command.SmiteCommand
import com.liam.joshymc.command.SpeedCommand
import com.liam.joshymc.command.SudoCommand
import com.liam.joshymc.command.TopCommand
import com.liam.joshymc.command.ChatColorCommand
import com.liam.joshymc.command.CosmeticsCommand
import com.liam.joshymc.command.EmoteCommand
import com.liam.joshymc.command.GadgetCommand
import com.liam.joshymc.command.GlowCommand
import com.liam.joshymc.command.JoinEffectCommand
import com.liam.joshymc.command.KillEffectCommand
import com.liam.joshymc.command.TrailCommand
import com.liam.joshymc.command.FishCommand
import com.liam.joshymc.command.SkillsCommand
import com.liam.joshymc.command.MarketCommand
import com.liam.joshymc.command.DailyCommand
import com.liam.joshymc.command.QuestCommand
import com.liam.joshymc.command.RewardsCommand
import com.liam.joshymc.command.TagCommand
import com.liam.joshymc.command.TalismanCommand
import com.liam.joshymc.command.TimezoneCommand
import com.liam.joshymc.command.TrashCommand
import com.liam.joshymc.command.TpCommand
import com.liam.joshymc.command.TpHereCommand
import com.liam.joshymc.command.TpaCommand
import com.liam.joshymc.command.TpaHereCommand
import com.liam.joshymc.command.TpAcceptCommand
import com.liam.joshymc.command.TpDenyCommand
import com.liam.joshymc.command.TpaCancelCommand
import com.liam.joshymc.command.CustomEnchantCommand
import com.liam.joshymc.command.SubclaimCommand
import com.liam.joshymc.command.RankCommand
import com.liam.joshymc.command.SellCommand
import com.liam.joshymc.command.SellWandCommand
import com.liam.joshymc.command.ShopCommand
import com.liam.joshymc.command.DeleteKitCommand
import com.liam.joshymc.command.KitCommand
import com.liam.joshymc.command.PlayerVaultCommand
import com.liam.joshymc.command.AFKCommand
import com.liam.joshymc.command.DelHomeCommand
import com.liam.joshymc.command.DelWarpCommand
import com.liam.joshymc.command.PlayerHomeCommand
import com.liam.joshymc.command.EditWarpCommand
import com.liam.joshymc.command.HomeCommand
import com.liam.joshymc.command.JoshyCommand
import com.liam.joshymc.command.LinkCommand
import com.liam.joshymc.command.NightVisionCommand
import com.liam.joshymc.command.PlayerWarpCommand
import com.liam.joshymc.command.PvpCommand
import com.liam.joshymc.command.RtpCommand
import com.liam.joshymc.command.SetHomeCommand
import com.liam.joshymc.command.SetSpawnCommand
import com.liam.joshymc.command.SetWarpCommand
import com.liam.joshymc.command.SettingsCommand
import com.liam.joshymc.command.SitCommand
import com.liam.joshymc.command.SpawnCommand
import com.liam.joshymc.command.TradeCommand
import com.liam.joshymc.command.UnlinkCommand
import com.liam.joshymc.command.WarpCommand
import com.liam.joshymc.command.HologramCommand
import com.liam.joshymc.command.NPCCommand
import com.liam.joshymc.command.CrateCommand
import com.liam.joshymc.command.CrateEditorCommand
import com.liam.joshymc.command.AuctionCommand
import com.liam.joshymc.command.BountyCommand
import com.liam.joshymc.command.HopperPlusCommand
import com.liam.joshymc.command.SignShopCommand
import com.liam.joshymc.command.SpawnerCommand
import com.liam.joshymc.command.TeamCommand
import com.liam.joshymc.command.BalTopCommand
import com.liam.joshymc.command.KillTopCommand
import com.liam.joshymc.command.QuestTopCommand
import com.liam.joshymc.command.BalanceCommand
import com.liam.joshymc.command.EcoCommand
import com.liam.joshymc.command.PayCommand
import com.liam.joshymc.command.PortalCommand
import com.liam.joshymc.command.VoteCommand
import com.liam.joshymc.command.WorldCommand
import com.liam.joshymc.command.WorldFlagCommand
import com.liam.joshymc.command.RestartCommand
import com.liam.joshymc.command.ResurgeCommand

class CommandManager(private val plugin: Joshymc) {

    fun registerAll() {
        val pm = plugin.server.pluginManager

        plugin.getCommand("joshymc")?.let {
            val cmd = JoshyCommand(plugin)
            it.setExecutor(cmd)
            it.tabCompleter = cmd
        }
        plugin.getCommand("link")?.setExecutor(LinkCommand(plugin))
        plugin.getCommand("unlink")?.setExecutor(UnlinkCommand(plugin))

        plugin.getCommand("settings")?.let {
            val cmd = SettingsCommand(plugin)
            it.setExecutor(cmd)
            it.tabCompleter = cmd
        }

        plugin.getCommand("nightvision")?.let {
            val cmd = NightVisionCommand(plugin)
            it.setExecutor(cmd)
            it.tabCompleter = cmd
        }

        plugin.getCommand("pvp")?.let {
            val cmd = PvpCommand(plugin)
            it.setExecutor(cmd)
            it.tabCompleter = cmd
        }

        plugin.getCommand("sit")?.setExecutor(SitCommand(plugin))

        plugin.getCommand("spawn")?.setExecutor(SpawnCommand(plugin))
        plugin.getCommand("setspawn")?.setExecutor(SetSpawnCommand(plugin))

        // Server warps
        plugin.getCommand("warp")?.let {
            val cmd = WarpCommand(plugin)
            it.setExecutor(cmd)
            it.tabCompleter = cmd
        }
        plugin.getCommand("setwarp")?.setExecutor(SetWarpCommand(plugin))
        plugin.getCommand("delwarp")?.let {
            val cmd = DelWarpCommand(plugin)
            it.setExecutor(cmd)
            it.tabCompleter = cmd
        }
        plugin.getCommand("editwarp")?.let {
            val cmd = EditWarpCommand(plugin)
            it.setExecutor(cmd)
            it.tabCompleter = cmd
        }

        // Player warps
        plugin.getCommand("pwarp")?.let {
            val cmd = PlayerWarpCommand(plugin)
            it.setExecutor(cmd)
            it.tabCompleter = cmd
        }

        plugin.getCommand("home")?.let {
            val cmd = HomeCommand(plugin)
            it.setExecutor(cmd)
            it.tabCompleter = cmd
        }
        plugin.getCommand("sethome")?.setExecutor(SetHomeCommand(plugin))
        plugin.getCommand("delhome")?.let {
            val cmd = DelHomeCommand(plugin)
            it.setExecutor(cmd)
            it.tabCompleter = cmd
        }
        plugin.getCommand("phome")?.let {
            val cmd = PlayerHomeCommand(plugin)
            it.setExecutor(cmd)
            it.tabCompleter = cmd
        }

        plugin.rtpCommand = RtpCommand(plugin)
        plugin.getCommand("rtp")?.setExecutor(plugin.rtpCommand)

        plugin.getCommand("afk")?.setExecutor(AFKCommand(plugin))

        plugin.getCommand("kit")?.let {
            val cmd = KitCommand(plugin)
            it.setExecutor(cmd)
            it.tabCompleter = cmd
        }
        plugin.getCommand("createkit")?.setExecutor(CreateKitCommand(plugin))
        plugin.getCommand("deletekit")?.let {
            val cmd = DeleteKitCommand(plugin)
            it.setExecutor(cmd)
            it.tabCompleter = cmd
        }

        plugin.getCommand("trade")?.let {
            val cmd = TradeCommand(plugin)
            it.setExecutor(cmd)
            it.tabCompleter = cmd
        }

        plugin.getCommand("pv")?.let {
            val cmd = PlayerVaultCommand(plugin)
            it.setExecutor(cmd)
            it.tabCompleter = cmd
        }

        plugin.getCommand("holo")?.let {
            val cmd = HologramCommand(plugin)
            it.setExecutor(cmd)
            it.tabCompleter = cmd
        }

        plugin.getCommand("npc")?.let {
            val cmd = NPCCommand(plugin)
            it.setExecutor(cmd)
            it.tabCompleter = cmd
        }

        plugin.getCommand("crate")?.let {
            val cmd = CrateCommand(plugin)
            it.setExecutor(cmd)
            it.tabCompleter = cmd
        }

        plugin.getCommand("crateeditor")?.let {
            val cmd = CrateEditorCommand(plugin)
            it.setExecutor(cmd)
            plugin.server.pluginManager.registerEvents(cmd, plugin)
            cmd.start()
        }

        // ── Trash ───────────────────────────────────────
        plugin.getCommand("trash")?.let {
            val cmd = TrashCommand(plugin)
            it.setExecutor(cmd)
            plugin.server.pluginManager.registerEvents(cmd, plugin)
        }

        plugin.getCommand("ah")?.let {
            val cmd = AuctionCommand(plugin)
            it.setExecutor(cmd)
            it.tabCompleter = cmd
        }

        plugin.getCommand("eco")?.let {
            val cmd = EcoCommand(plugin)
            it.setExecutor(cmd)
            it.tabCompleter = cmd
        }

        plugin.getCommand("balance")?.let {
            val cmd = BalanceCommand(plugin)
            it.setExecutor(cmd)
            it.tabCompleter = cmd
        }

        plugin.getCommand("pay")?.let {
            val cmd = PayCommand(plugin)
            it.setExecutor(cmd)
            it.tabCompleter = cmd
        }

        plugin.getCommand("baltop")?.setExecutor(BalTopCommand(plugin))
        plugin.getCommand("killtop")?.setExecutor(KillTopCommand(plugin))
        plugin.getCommand("questtop")?.setExecutor(QuestTopCommand(plugin))

        plugin.getCommand("chestshop")?.let {
            val cmd = SignShopCommand(plugin)
            it.setExecutor(cmd)
            it.tabCompleter = cmd
        }

        plugin.getCommand("hopper")?.let {
            val cmd = HopperPlusCommand(plugin)
            it.setExecutor(cmd)
            it.tabCompleter = cmd
        }

        plugin.getCommand("spawner")?.let {
            val cmd = SpawnerCommand(plugin)
            it.setExecutor(cmd)
            it.tabCompleter = cmd
        }

        plugin.getCommand("team")?.let {
            val cmd = TeamCommand(plugin)
            it.setExecutor(cmd)
            it.tabCompleter = cmd
        }

        plugin.getCommand("bounty")?.let {
            val cmd = BountyCommand(plugin)
            it.setExecutor(cmd)
            it.tabCompleter = cmd
        }

        plugin.getCommand("shop")?.setExecutor(ShopCommand(plugin))

        plugin.getCommand("sell")?.let {
            val cmd = SellCommand(plugin)
            it.setExecutor(cmd)
            it.tabCompleter = cmd
            plugin.server.pluginManager.registerEvents(cmd, plugin)
            cmd.refreshSellableCache()
        }

        // ── Chat Colors ──────────────────────────────
        ChatColorCommand.createTable(plugin)
        plugin.getCommand("chatcolor")?.let { val c = ChatColorCommand(plugin); it.setExecutor(c); it.tabCompleter = c }

        // ── Ignore ───────────────────────────────────
        IgnoreCommand.createTable(plugin)
        plugin.getCommand("ignore")?.let { val c = IgnoreCommand(plugin); it.setExecutor(c); it.tabCompleter = c }

        // ── Market ──────────────────────────────────
        if (plugin.isFeatureEnabled("market")) {
            plugin.getCommand("market")?.let { val c = MarketCommand(plugin); it.setExecutor(c); it.tabCompleter = c }
        }

        // ── Quests ──────────────────────────────────
        plugin.getCommand("quests")?.let { val c = QuestCommand(plugin); it.setExecutor(c); it.tabCompleter = c }
        plugin.getCommand("rewards")?.setExecutor(RewardsCommand(plugin))
        plugin.getCommand("daily")?.setExecutor(DailyCommand(plugin))

        // ── Cosmetics ─────────────────────────────────
        plugin.getCommand("trail")?.let { val c = TrailCommand(plugin); it.setExecutor(c); it.tabCompleter = c }
        plugin.getCommand("killeffect")?.let { val c = KillEffectCommand(plugin); it.setExecutor(c); it.tabCompleter = c }
        plugin.getCommand("joineffect")?.let { val c = JoinEffectCommand(plugin); it.setExecutor(c); it.tabCompleter = c }
        plugin.getCommand("emote")?.let { val c = EmoteCommand(plugin); it.setExecutor(c); it.tabCompleter = c }
        plugin.getCommand("glow")?.let { val c = GlowCommand(plugin); it.setExecutor(c); it.tabCompleter = c }
        plugin.getCommand("gadget")?.let { val c = GadgetCommand(plugin); it.setExecutor(c); it.tabCompleter = c }
        plugin.getCommand("cosmetics")?.let { val c = CosmeticsCommand(plugin); it.setExecutor(c); it.tabCompleter = c }

        // ── Fishing ───────────────────────────────────
        plugin.getCommand("fish")?.let { val c = FishCommand(plugin); it.setExecutor(c); it.tabCompleter = c }

        // ── Skills ────────────────────────────────────
        plugin.getCommand("skills")?.let { val c = SkillsCommand(plugin); it.setExecutor(c); it.tabCompleter = c }

        // ── Resurge ───────────────────────────────────
        plugin.getCommand("resurge")?.let { val c = ResurgeCommand(plugin); it.setExecutor(c); it.tabCompleter = c }

        // ── Talismans ─────────────────────────────────
        plugin.getCommand("talisman")?.let { val c = TalismanCommand(plugin); it.setExecutor(c); it.tabCompleter = c }

        // ── Timezone ──────────────────────────────────
        plugin.getCommand("timezone")?.let { val c = TimezoneCommand(plugin); it.setExecutor(c); it.tabCompleter = c }

        // ── Chat Tags ────────────────────────────────
        plugin.getCommand("tag")?.let { val c = TagCommand(plugin); it.setExecutor(c); it.tabCompleter = c }

        // ── Nickname ─────────────────────────────────
        NickCommand.createTable(plugin)
        plugin.getCommand("nick")?.let { val c = NickCommand(plugin); it.setExecutor(c); it.tabCompleter = c }

        // ── Announce ─────────────────────────────────
        plugin.getCommand("announce")?.let { val c = AnnounceCommand(plugin); it.setExecutor(c); it.tabCompleter = c }

        // ── Help & Rules GUI ─────────────────────────
        plugin.getCommand("help")?.setExecutor(HelpCommand(plugin))
        plugin.getCommand("rules")?.setExecutor(RulesCommand(plugin))

        // ── Essentials-style commands ────────────────
        plugin.getCommand("back")?.setExecutor(BackCommand(plugin))

        val gmCmd = GamemodeCommand(plugin)
        for (cmd in listOf("gamemode", "gmc", "gms", "gma", "gmsp")) {
            plugin.getCommand(cmd)?.let { it.setExecutor(gmCmd); it.tabCompleter = gmCmd }
        }

        val flyCmd = FlyCommand(plugin)
        plugin.getCommand("fly")?.let { it.setExecutor(flyCmd); it.tabCompleter = flyCmd }

        val healCmd = HealCommand(plugin)
        plugin.getCommand("heal")?.let { it.setExecutor(healCmd); it.tabCompleter = healCmd }

        val feedCmd = FeedCommand(plugin)
        plugin.getCommand("feed")?.let { it.setExecutor(feedCmd); it.tabCompleter = feedCmd }

        val godCmd = GodCommand(plugin)
        plugin.getCommand("god")?.let { it.setExecutor(godCmd); it.tabCompleter = godCmd }

        val speedCmd = SpeedCommand(plugin)
        plugin.getCommand("speed")?.let { it.setExecutor(speedCmd); it.tabCompleter = speedCmd }

        val tpCmd = TpCommand(plugin)
        plugin.getCommand("tp")?.let { it.setExecutor(tpCmd); it.tabCompleter = tpCmd }

        val tpHereCmd = TpHereCommand(plugin)
        plugin.getCommand("tphere")?.let { it.setExecutor(tpHereCmd); it.tabCompleter = tpHereCmd }

        plugin.getCommand("invsee")?.let { val c = InvseeCommand(plugin); it.setExecutor(c); it.tabCompleter = c }
        plugin.getCommand("enderchest")?.let { val c = EnderchestCommand(plugin); it.setExecutor(c); it.tabCompleter = c }
        plugin.getCommand("hat")?.setExecutor(HatCommand(plugin))
        plugin.getCommand("craft")?.setExecutor(CraftCommand(plugin))
        plugin.getCommand("anvil")?.setExecutor(AnvilCommand(plugin))
        plugin.getCommand("smithing")?.setExecutor(SmithingCommand(plugin))
        plugin.getCommand("repair")?.let { val c = RepairCommand(plugin); it.setExecutor(c); it.tabCompleter = c }
        plugin.getCommand("jmc-violation")?.setExecutor(ViolationBridgeCommand(plugin))
        plugin.getCommand("chatgame")?.let { val c = ChatGameCommand(plugin); it.setExecutor(c); it.tabCompleter = c }
        plugin.getCommand("leaderboard")?.let { val c = LeaderboardCommand(plugin); it.setExecutor(c); it.tabCompleter = c }
        plugin.getCommand("gencave")?.let { val c = GenCaveCommand(plugin); it.setExecutor(c); it.tabCompleter = c }
        plugin.getCommand("admin")?.let { val c = AdminCommand(plugin); it.setExecutor(c); it.tabCompleter = c }
        plugin.getCommand("enchant")?.let { val c = EnchantCommand(plugin); it.setExecutor(c); it.tabCompleter = c }
        plugin.getCommand("smite")?.let { val c = SmiteCommand(plugin); it.setExecutor(c); it.tabCompleter = c }
        plugin.getCommand("top")?.setExecutor(TopCommand(plugin))
        plugin.getCommand("sudo")?.let { val c = SudoCommand(plugin); it.setExecutor(c); it.tabCompleter = c }
        plugin.getCommand("restart")?.let { val c = RestartCommand(plugin); it.setExecutor(c); it.tabCompleter = c }

        val msgCmd = MsgCommand(plugin)
        plugin.getCommand("msg")?.let { it.setExecutor(msgCmd); it.tabCompleter = msgCmd }
        plugin.getCommand("reply")?.setExecutor(ReplyCommand(plugin))

        // TPA system
        val tpaCmd = TpaCommand(plugin)
        plugin.getCommand("tpa")?.let { it.setExecutor(tpaCmd); it.tabCompleter = tpaCmd }
        val tpaHereCmd = TpaHereCommand(plugin)
        plugin.getCommand("tpahere")?.let { it.setExecutor(tpaHereCmd); it.tabCompleter = tpaHereCmd }
        val tpAcceptCmd = TpAcceptCommand(plugin)
        plugin.getCommand("tpaccept")?.let { it.setExecutor(tpAcceptCmd); it.tabCompleter = tpAcceptCmd }
        val tpDenyCmd = TpDenyCommand(plugin)
        plugin.getCommand("tpdeny")?.let { it.setExecutor(tpDenyCmd); it.tabCompleter = tpDenyCmd }
        plugin.getCommand("tpacancel")?.setExecutor(TpaCancelCommand(plugin))

        // Register god mode listener
        plugin.server.pluginManager.registerEvents(godCmd, plugin)

        // ── Punishment commands ────────────────────────
        plugin.getCommand("ban")?.let { val c = BanCommand(plugin); it.setExecutor(c); it.tabCompleter = c }
        plugin.getCommand("tempban")?.let { val c = TempbanCommand(plugin); it.setExecutor(c); it.tabCompleter = c }
        plugin.getCommand("unban")?.let { val c = UnbanCommand(plugin); it.setExecutor(c); it.tabCompleter = c }
        plugin.getCommand("mute")?.let { val c = MuteCommand(plugin); it.setExecutor(c); it.tabCompleter = c }
        plugin.getCommand("tempmute")?.let { val c = TempmuteCommand(plugin); it.setExecutor(c); it.tabCompleter = c }
        plugin.getCommand("unmute")?.let { val c = UnmuteCommand(plugin); it.setExecutor(c); it.tabCompleter = c }
        plugin.getCommand("warn")?.let { val c = WarnCommand(plugin); it.setExecutor(c); it.tabCompleter = c }
        plugin.getCommand("pkick")?.let { val c = KickCommand(plugin); it.setExecutor(c); it.tabCompleter = c }
        plugin.getCommand("history")?.let { val c = HistoryCommand(plugin); it.setExecutor(c); it.tabCompleter = c }

        // ── Vanish ──────────────────────────────────────
        val vanishCmd = VanishCommand(plugin)
        plugin.vanishCommand = vanishCmd
        plugin.getCommand("vanish")?.let { it.setExecutor(vanishCmd); it.tabCompleter = vanishCmd }
        plugin.server.pluginManager.registerEvents(vanishCmd, plugin)
        vanishCmd.start()

        // ── Report ──────────────────────────────────────
        val reportCmd = ReportCommand(plugin)
        reportCmd.createTable()
        plugin.getCommand("report")?.let { it.setExecutor(reportCmd); it.tabCompleter = reportCmd }
        plugin.getCommand("reports")?.let { it.setExecutor(reportCmd); it.tabCompleter = reportCmd }

        // ── Resource World ──────────────────────────────
        plugin.getCommand("resource")?.setExecutor(object : org.bukkit.command.CommandExecutor {
            override fun onCommand(sender: org.bukkit.command.CommandSender, cmd: org.bukkit.command.Command, label: String, args: Array<out String>): Boolean {
                if (sender !is org.bukkit.entity.Player) { sender.sendMessage("Players only."); return true }
                if (!sender.hasPermission("joshymc.resource")) {
                    plugin.commsManager.send(sender, net.kyori.adventure.text.Component.text("No permission.", net.kyori.adventure.text.format.NamedTextColor.RED))
                    return true
                }
                plugin.resourceWorldManager.teleportToResource(sender)
                return true
            }
        })

        // ── Playtime ────────────────────────────────────
        val ptCmd = plugin.playtimeManager.PlaytimeCommand()
        plugin.getCommand("playtime")?.let { it.setExecutor(ptCmd); it.tabCompleter = ptCmd }
        plugin.getCommand("playtimetop")?.setExecutor(plugin.playtimeManager.PlaytimeTopCommand())

        plugin.getCommand("claim")?.let {
            val cmd = ClaimCommand(plugin)
            it.setExecutor(cmd)
            it.tabCompleter = cmd
        }

        plugin.getCommand("subclaim")?.let {
            val cmd = SubclaimCommand(plugin)
            it.setExecutor(cmd)
            it.tabCompleter = cmd
        }

        plugin.getCommand("unclaim")?.setExecutor(ClaimCommand(plugin))

        plugin.getCommand("rank")?.let {
            val cmd = RankCommand(plugin)
            it.setExecutor(cmd)
            it.tabCompleter = cmd
        }

        plugin.getCommand("sellwand")?.let {
            val cmd = SellWandCommand(plugin)
            it.setExecutor(cmd)
            it.tabCompleter = cmd
        }

        plugin.getCommand("customenchant")?.let {
            val cmd = CustomEnchantCommand(plugin)
            it.setExecutor(cmd)
            it.tabCompleter = cmd
        }

        // ── Arena ────────────────────────────────────────
        plugin.getCommand("arena")?.let {
            val cmd = plugin.arenaManager.ArenaCommand()
            it.setExecutor(cmd); it.tabCompleter = cmd
        }

        // ── Event ─────────────────────────────────────────
        plugin.getCommand("event")?.let {
            val cmd = plugin.eventManager.EventCommand()
            it.setExecutor(cmd); it.tabCompleter = cmd
        }

        // ── Portals ──────────────────────────────────────
        plugin.getCommand("portal")?.let { val c = PortalCommand(plugin); it.setExecutor(c); it.tabCompleter = c }

        // ── Voting ──────────────────────────────────────
        // JoshyMC no longer claims the unprefixed `/vote` alias — it's exposed
        // only as `/jvote` (alias `/joshyvote`). This guarantees other voting
        // plugins (NuVotifier, VotingPlugin, etc.) own `/vote` cleanly.
        // Belt-and-suspenders: also unregister `/vote` at runtime in case any
        // leftover registration was cached from a previous plugin version.
        unregisterCommand("vote")
        if (plugin.isFeatureEnabled("voting") && plugin.config.getBoolean("voting.enabled", true)) {
            plugin.getCommand("jvote")?.let { val c = VoteCommand(plugin); it.setExecutor(c); it.tabCompleter = c }
        }

        // ── Spawn Decorations ───────────────────────────
        plugin.getCommand("spawndecor")?.let {
            val cmd = plugin.spawnDecorationManager.SpawnDecorCommand()
            it.setExecutor(cmd); it.tabCompleter = cmd
        }

        // ── World Flags ──────────────────────────────────
        plugin.getCommand("worldflag")?.let { val c = WorldFlagCommand(plugin); it.setExecutor(c); it.tabCompleter = c }

        // ── World Management ────────────────────────────
        plugin.getCommand("world")?.let {
            val cmd = WorldCommand(plugin)
            it.setExecutor(cmd)
            it.tabCompleter = cmd
        }

        plugin.logger.info("Commands registered.")
    }

    /**
     * Remove a command (and its aliases) from Bukkit's command map so another
     * plugin's identically-named command can take the unprefixed slot. Used when
     * a feature is disabled to avoid hijacking commands like `/vote`.
     */
    private fun unregisterCommand(name: String) {
        val pluginCommand = plugin.getCommand(name) ?: return
        try {
            val server = org.bukkit.Bukkit.getServer()
            val commandMap = server.javaClass.getMethod("getCommandMap").invoke(server)
                    as org.bukkit.command.CommandMap
            pluginCommand.unregister(commandMap)
            // Also strip the known knownCommands map entries (some Bukkit versions
            // keep aliases there even after unregister). Reflection is intentionally
            // best-effort — failure just means a leftover ghost entry, not a crash.
            try {
                val knownField = commandMap.javaClass.getDeclaredField("knownCommands")
                knownField.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                val known = knownField.get(commandMap) as MutableMap<String, org.bukkit.command.Command>
                known.remove(name)
                known.remove("joshymc:$name")
                pluginCommand.aliases.forEach {
                    known.remove(it)
                    known.remove("joshymc:$it")
                }
            } catch (_: Exception) { /* knownCommands not exposed on this server build */ }
            plugin.logger.info("[CommandManager] Released /$name — other plugins may now claim it.")
        } catch (e: Exception) {
            plugin.logger.warning("[CommandManager] Could not release /$name: ${e.message}")
        }
    }
}
