package com.liam.joshymc.tutorial

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gamemode.ServerGamemode
import net.kyori.adventure.key.Key
import net.kyori.adventure.sound.Sound
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.event.vehicle.VehicleExitEvent
import org.bukkit.scheduler.BukkitTask
import java.io.InputStreamReader
import java.time.Duration
import java.util.UUID
import java.util.logging.Level

/**
 * The guided tutorial walkthrough (issue #1387): seven real objectives per gamemode, shown one at
 * a time in chat (once, when the objective begins or resumes) and on the action bar.
 *
 * - Objectives complete only from success callbacks of the systems that do the work
 *   ([Joshymc.tutorialSignal]: KitManager, RtpCommand, ClaimManager, SaleManager, the quest
 *   menu, /discord, /lifesteal info|pvp, the Lifesteal team menu), plus walking, which this
 *   listener measures itself. Typed commands never complete anything.
 * - Each signal is checked against the player's current gamemode and the current step, and every
 *   store write is a conditional UPDATE, so a duplicate or out-of-order event changes nothing.
 * - State lives in [TutorialStore] (per UUID + gamemode). Everything runs on the main thread.
 *   Writes happen only when something changes (never per movement tick) and are synchronous, so a
 *   step advance can never be reordered behind a later write.
 * - The completion reward is paid once per player per gamemode through the existing economy;
 *   see [TutorialStore.pay] and [TutorialRules.economyBelongsTo].
 */
class TutorialManager(private val plugin: Joshymc) : Listener {

    private val store = TutorialStore(plugin.databaseManager)

    /** One player's active walkthrough in the gamemode they're in now. Main thread only. */
    private class Session(val gamemode: ServerGamemode, var step: Int, var progress: Double) {
        var anchor: Location? = null
    }

    private val sessions = HashMap<UUID, Session>()
    private val pendingEnter = HashMap<UUID, BukkitTask>()
    private var actionBarTask: BukkitTask? = null
    private var texts: YamlConfiguration = YamlConfiguration()
    private var installedAt = Long.MAX_VALUE
    @Volatile private var ready = false

    // Settings (tutorial-walkthrough.yml > settings)
    private var walkDistance = 10.0
    private var earnAmount = 100.0
    private var actionBarTicks = 40L
    private var joinDelayTicks = 60L

    // ── Lifecycle ───────────────────────────────────────────

    fun start() {
        ready = false
        try {
            store.createTables()
            installedAt = store.installedAt(System.currentTimeMillis())
        } catch (e: Exception) {
            plugin.logger.log(Level.SEVERE, "[Tutorial] Could not create the tutorial tables; the tutorial is off.", e)
            return
        }
        loadTexts()
        Bukkit.getPluginManager().registerEvents(this, plugin)
        actionBarTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { tickActionBars() }, actionBarTicks, actionBarTicks)
        ready = true
        // Plugin reloads: players already online pick their tutorial back up.
        for (player in Bukkit.getOnlinePlayers()) scheduleEnter(player, 20L)
        plugin.logger.info(
            "[Tutorial] Walkthrough ready (survival ${if (enabled(ServerGamemode.SURVIVAL)) "on" else "off"}, " +
                "lifesteal ${if (enabled(ServerGamemode.LIFESTEAL)) "on" else "off"}); auto-start for players first seen after $installedAt."
        )
    }

    fun stop() {
        ready = false
        actionBarTask?.cancel()
        actionBarTask = null
        pendingEnter.values.forEach { it.cancel() }
        pendingEnter.clear()
        sessions.clear() // progress is already saved: every change was written when it happened
        HandlerList.unregisterAll(this)
    }

    fun reloadTexts() = loadTexts()

    private fun loadTexts() {
        val file = plugin.configFile(FILE_NAME)
        if (!file.exists()) {
            try {
                plugin.saveResource(FILE_NAME, false)
            } catch (e: IllegalArgumentException) {
                plugin.logger.warning("[Tutorial] $FILE_NAME is missing from the jar.")
            }
        }
        val loaded = YamlConfiguration.loadConfiguration(file)
        plugin.getResource(FILE_NAME)?.use { stream ->
            loaded.setDefaults(YamlConfiguration.loadConfiguration(InputStreamReader(stream, Charsets.UTF_8)))
        }
        texts = loaded
        walkDistance = loaded.getDouble("settings.walk-distance", 10.0).takeIf { it.isFinite() && it in 1.0..1000.0 } ?: 10.0
        earnAmount = loaded.getDouble("settings.earn-amount", 100.0).takeIf { it.isFinite() && it > 0.0 } ?: 100.0
        actionBarTicks = loaded.getInt("settings.action-bar-interval-seconds", 2).coerceIn(1, 10) * 20L
        joinDelayTicks = loaded.getInt("settings.join-delay-seconds", 3).coerceIn(0, 30) * 20L + 1L
    }

    fun enabled(gamemode: ServerGamemode): Boolean =
        gamemode in plugin.networkIdentityManager.hostedGamemodes && texts.getBoolean("settings.${gamemode.id}.enabled", true)

    fun rewardFor(gamemode: ServerGamemode): Double {
        val default = when (gamemode) {
            ServerGamemode.SURVIVAL -> 50_000.0
            ServerGamemode.LIFESTEAL -> 25_000.0
        }
        val path = "settings.${gamemode.id}.reward"
        return TutorialRules.rewardAmount(if (texts.isSet(path)) texts.getDouble(path) else null, default)
    }

    private fun gamemodeOf(player: Player): ServerGamemode = plugin.gamemodeLockManager.gamemodeOf(player.world)

    // ── Joining / entering a gamemode ───────────────────────

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        // After login and the first-join spawn teleport have settled.
        scheduleEnter(event.player, joinDelayTicks)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        val uuid = event.player.uniqueId
        pendingEnter.remove(uuid)?.cancel()
        sessions.remove(uuid)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onWorldChange(event: PlayerChangedWorldEvent) {
        val player = event.player
        val session = sessions[player.uniqueId]
        if (session != null && session.gamemode == gamemodeOf(player)) {
            session.anchor = player.location.clone()
            return
        }
        // Another gamemode's world (servers that map worlds to both): stop this display, pick up the other.
        if (session != null) {
            sessions.remove(player.uniqueId)
            player.sendActionBar(Component.empty())
        }
        scheduleEnter(player, 20L)
    }

    private fun scheduleEnter(player: Player, delay: Long) {
        val uuid = player.uniqueId
        pendingEnter.remove(uuid)?.cancel()
        pendingEnter[uuid] = Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            pendingEnter.remove(uuid)
            if (player.isOnline) enter(player)
        }, delay)
    }

    /** Starts (first time in this gamemode) or resumes the tutorial, and retries a pending reward. */
    private fun enter(player: Player) {
        if (!ready) return
        val gamemode = gamemodeOf(player)
        if (sessions[player.uniqueId]?.gamemode == gamemode) return
        sessions.remove(player.uniqueId)
        try {
            val row = store.load(player.uniqueId, gamemode)
            // A brand-new player's first-played time may not be stamped yet; that's "now".
            val firstPlayed = player.firstPlayed.takeIf { it > 0 } ?: System.currentTimeMillis()
            when (TutorialRules.onEnter(enabled(gamemode), row?.state, firstPlayed, installedAt)) {
                TutorialRules.JoinAction.START -> {
                    if (store.start(player.uniqueId, gamemode, System.currentTimeMillis())) {
                        plugin.logger.info("[Tutorial] Started the ${gamemode.id} tutorial for ${player.name}.")
                        begin(player, Session(gamemode, 0, 0.0))
                    }
                }
                TutorialRules.JoinAction.RESUME -> if (row != null) resume(player, row)
                TutorialRules.JoinAction.NONE -> {}
            }
            settlePendingReward(player, gamemode)
        } catch (e: Exception) {
            plugin.logger.log(Level.WARNING, "[Tutorial] Could not load the ${gamemode.id} tutorial of ${player.name}.", e)
        }
    }

    private fun resume(player: Player, row: TutorialStore.Progress) {
        if (TutorialRules.stepAt(row.gamemode, row.step) == null) {
            plugin.logger.warning("[Tutorial] ${player.name}'s ${row.gamemode.id} tutorial is at an unknown step ${row.step}; use /tutorial restart.")
            return
        }
        begin(player, Session(row.gamemode, row.step, row.progress))
    }

    /** Makes [session] the player's current one and sends the objective's instructions once. */
    private fun begin(player: Player, session: Session) {
        session.anchor = player.location.clone()
        sessions[player.uniqueId] = session
        sendInstructions(player, session)
        sendActionBar(player, session)
    }

    // ── Signals from gameplay systems ───────────────────────

    /**
     * A gameplay system confirmed [signal]. [origin] is where the action started (the world a
     * random teleport left from); it and the current world must both be in the session's gamemode.
     */
    fun signal(player: Player, signal: TutorialSignal, origin: World? = null) {
        if (!ready) return
        val session = sessions[player.uniqueId] ?: return
        if (gamemodeOf(player) != session.gamemode) return
        if (origin != null && plugin.gamemodeLockManager.gamemodeOf(origin) != session.gamemode) return
        val step = TutorialRules.stepAt(session.gamemode, session.step) ?: return
        val outcome = TutorialRules.apply(step, session.progress, signal, earnAmount)
        if (!outcome.changed) return
        if (outcome.completed) {
            completeStep(player, session)
            return
        }
        try {
            if (store.saveProgress(player.uniqueId, session.gamemode, session.step, outcome.progress, System.currentTimeMillis())) {
                session.progress = outcome.progress
                sendActionBar(player, session)
            }
        } catch (e: Exception) {
            plugin.logger.log(Level.WARNING, "[Tutorial] Could not save ${player.name}'s tutorial progress.", e)
        }
    }

    private fun completeStep(player: Player, session: Session) {
        val index = session.step
        val last = TutorialRules.STEP_COUNT - 1
        if (index >= last) {
            finish(player, session)
            return
        }
        val advanced = try {
            store.advance(player.uniqueId, session.gamemode, index, System.currentTimeMillis())
        } catch (e: Exception) {
            plugin.logger.log(Level.WARNING, "[Tutorial] Could not save ${player.name}'s completed objective ${index + 1}.", e)
            return // nothing moves in memory either; the objective can simply be done again
        }
        if (!advanced) {
            // Another handler already moved it (or the row changed): reload what's stored.
            sessions.remove(player.uniqueId)
            store.load(player.uniqueId, session.gamemode)?.takeIf { it.state == TutorialState.ACTIVE }?.let { resume(player, it) }
            return
        }
        session.step = index + 1
        session.progress = 0.0
        plugin.commsManager.sendRaw(player, legacy(message("step-complete")))
        player.playSound(Sound.sound(Key.key("entity.experience_orb.pickup"), Sound.Source.MASTER, 0.6f, 1.2f))
        begin(player, session)
    }

    private fun finish(player: Player, session: Session) {
        val gamemode = session.gamemode
        val reward = rewardFor(gamemode)
        val completion = try {
            store.complete(player.uniqueId, gamemode, TutorialRules.STEP_COUNT - 1, reward, System.currentTimeMillis())
        } catch (e: Exception) {
            plugin.logger.log(Level.WARNING, "[Tutorial] Could not save ${player.name}'s completed ${gamemode.id} tutorial.", e)
            return
        }
        if (completion == TutorialStore.Completion.NOT_COMPLETED) return
        sessions.remove(player.uniqueId)
        player.sendActionBar(Component.empty())
        plugin.logger.info("[Tutorial] ${player.name} completed the ${gamemode.id} tutorial.")

        player.showTitle(
            Title.title(
                legacy(message("complete-title")), legacy(message("complete-subtitle")),
                Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(3), Duration.ofSeconds(1))
            )
        )
        playConfiguredSound(player, message("complete-sound"))
        for (line in texts.getStringList("messages.complete-chat")) plugin.commsManager.sendRaw(player, legacy(fill(line, player)))
        payReward(player, gamemode, atCompletion = true)
    }

    // ── Rewards ─────────────────────────────────────────────

    /** On join: pays a reward left PENDING by an earlier failure (never a second one). */
    private fun settlePendingReward(player: Player, gamemode: ServerGamemode) {
        val reward = store.reward(player.uniqueId, gamemode) ?: return
        if (reward.status != TutorialStore.RewardStatus.PENDING) return
        if (!TutorialRules.economyBelongsTo(gamemode, plugin.networkIdentityManager.hostedGamemodes)) return
        payReward(player, gamemode, atCompletion = false)
    }

    private fun payReward(player: Player, gamemode: ServerGamemode, atCompletion: Boolean) {
        val reward = try {
            store.reward(player.uniqueId, gamemode)
        } catch (e: Exception) {
            plugin.logger.log(Level.WARNING, "[Tutorial] Could not read ${player.name}'s ${gamemode.id} tutorial reward.", e)
            if (atCompletion) send(player, "reward-pending", rewardFor(gamemode))
            return
        } ?: return
        val amountText = reward.amount
        if (reward.status == TutorialStore.RewardStatus.PAID) {
            if (atCompletion) send(player, "reward-already", amountText)
            return
        }
        if (!TutorialRules.economyBelongsTo(gamemode, plugin.networkIdentityManager.hostedGamemodes)) {
            if (atCompletion) send(player, "reward-held", amountText, gamemode)
            plugin.logger.info("[Tutorial] Holding ${player.name}'s ${gamemode.id} tutorial reward: this server's Money isn't ${gamemode.displayName}'s.")
            return
        }
        when (store.pay(player.uniqueId, gamemode, System.currentTimeMillis()) { amount -> plugin.economyManager.deposit(player.uniqueId, amount) }) {
            TutorialStore.Payout.PAID -> {
                plugin.logger.info("[Tutorial] Paid ${player.name} (${player.uniqueId}) the ${gamemode.id} tutorial reward of ${reward.amount}.")
                send(player, if (atCompletion) "reward-received" else "reward-late", amountText)
            }
            TutorialStore.Payout.ALREADY_PAID -> if (atCompletion) send(player, "reward-already", amountText)
            TutorialStore.Payout.NO_ENTITLEMENT -> {}
            TutorialStore.Payout.FAILED -> {
                plugin.logger.warning("[Tutorial] Paying ${player.name} (${player.uniqueId}) the ${gamemode.id} tutorial reward failed; it stays pending and is retried on their next join.")
                if (atCompletion) send(player, "reward-pending", amountText)
            }
        }
    }

    private fun send(player: Player, key: String, amount: Double, gamemode: ServerGamemode? = null) {
        val text = message(key).replace("{reward}", TutorialRules.money(amount)).replace("{gamemode}", gamemode?.displayName ?: "")
        plugin.commsManager.sendRaw(player, legacy(text))
    }

    // ── Commands (/tutorial) ────────────────────────────────

    fun status(player: Player) {
        val gamemode = gamemodeOf(player)
        if (!ready || !enabled(gamemode)) {
            plugin.commsManager.send(player, legacy(message("disabled")))
            return
        }
        val row = store.load(player.uniqueId, gamemode)
        val reward = store.reward(player.uniqueId, gamemode)
        val state = row?.state ?: TutorialState.NOT_STARTED
        val session = sessions[player.uniqueId]?.takeIf { it.gamemode == gamemode }
        plugin.commsManager.sendRaw(player, legacy("&6&l✦ ${gamemode.displayName} Tutorial"))
        val stateText = when (state) {
            TutorialState.NOT_STARTED -> "&7Not started"
            TutorialState.ACTIVE -> "&eIn progress"
            TutorialState.SKIPPED -> "&7Skipped"
            TutorialState.COMPLETED -> "&aCompleted"
        }
        plugin.commsManager.sendRaw(player, legacy("&7Status: $stateText"))
        if (session != null) {
            val step = TutorialRules.stepAt(gamemode, session.step)
            if (step != null) {
                plugin.commsManager.sendRaw(player, legacy("&7Objective ${session.step + 1}/${TutorialRules.STEP_COUNT}: &f${stepText(player, session, step, "action-bar")}"))
                if (canContinue(player, session, step)) plugin.commsManager.sendRaw(player, continueLine(stepText(player, session, step, "continue")))
            }
        }
        val rewardText = when (reward?.status) {
            TutorialStore.RewardStatus.PAID -> "&aClaimed"
            TutorialStore.RewardStatus.PENDING -> "&eEarned, waiting to be paid"
            null -> "&6${TutorialRules.money(rewardFor(gamemode))} Money &7on completion"
        }
        plugin.commsManager.sendRaw(player, legacy("&7Reward: $rewardText"))
        val buttons = when (state) {
            TutorialState.ACTIVE -> if (session == null) listOf(button("&a[Resume]", "/tutorial start")) else listOf(button("&c[Skip]", "/tutorial skip"), button("&e[Restart]", "/tutorial restart"))
            TutorialState.NOT_STARTED -> listOf(button("&a[Start]", "/tutorial start"))
            TutorialState.SKIPPED, TutorialState.COMPLETED -> listOf(button("&e[Restart]", "/tutorial restart"))
        }
        plugin.commsManager.sendRaw(player, Component.join(net.kyori.adventure.text.JoinConfiguration.separator(Component.space()), buttons))
    }

    /** `/tutorial start`: starts a never-started one, resumes an active one, else points to restart. */
    fun startCommand(player: Player) {
        val gamemode = gamemodeOf(player)
        if (!ready || !enabled(gamemode)) return plugin.commsManager.send(player, legacy(message("disabled")))
        val row = store.load(player.uniqueId, gamemode)
        when (row?.state) {
            null, TutorialState.NOT_STARTED -> restart(player)
            TutorialState.ACTIVE -> {
                val session = sessions[player.uniqueId]?.takeIf { it.gamemode == gamemode }
                if (session != null) sendInstructions(player, session) else resume(player, row)
            }
            TutorialState.SKIPPED, TutorialState.COMPLETED ->
                plugin.commsManager.send(player, legacy("&7Your tutorial here is ${row.state.name.lowercase()}. Replay it with &e/tutorial restart&7."))
        }
    }

    fun skip(player: Player) {
        val gamemode = gamemodeOf(player)
        if (!ready || !enabled(gamemode)) return plugin.commsManager.send(player, legacy(message("disabled")))
        val skipped = try {
            store.skip(player.uniqueId, gamemode, System.currentTimeMillis())
        } catch (e: Exception) {
            plugin.logger.log(Level.WARNING, "[Tutorial] Could not skip ${player.name}'s tutorial.", e)
            plugin.commsManager.send(player, legacy("&cCouldn't save that right now. Please try again."))
            return
        }
        if (!skipped) {
            plugin.commsManager.send(player, legacy(message("not-active")))
            return
        }
        sessions.remove(player.uniqueId)
        player.sendActionBar(Component.empty())
        plugin.logger.info("[Tutorial] ${player.name} skipped the ${gamemode.id} tutorial.")
        plugin.commsManager.send(player, legacy(message("skipped")))
    }

    fun restart(player: Player) {
        val gamemode = gamemodeOf(player)
        if (!ready || !enabled(gamemode)) return plugin.commsManager.send(player, legacy(message("disabled")))
        try {
            store.restart(player.uniqueId, gamemode, System.currentTimeMillis())
        } catch (e: Exception) {
            plugin.logger.log(Level.WARNING, "[Tutorial] Could not restart ${player.name}'s tutorial.", e)
            plugin.commsManager.send(player, legacy("&cCouldn't save that right now. Please try again."))
            return
        }
        plugin.commsManager.send(player, legacy(message("restarted")))
        begin(player, Session(gamemode, 0, 0.0))
    }

    /** `/tutorial continue`: the clickable confirmation, re-checked here (never trusted from chat). */
    fun continueCommand(player: Player) {
        val session = sessions[player.uniqueId]?.takeIf { it.gamemode == gamemodeOf(player) }
            ?: return plugin.commsManager.send(player, legacy(message("not-active")))
        val step = TutorialRules.stepAt(session.gamemode, session.step) ?: return
        if (!canContinue(player, session, step)) {
            plugin.commsManager.send(player, legacy("&7Complete the objective first: &f${stepText(player, session, step, "objective")}"))
            return
        }
        completeStep(player, session)
    }

    /**
     * Whether an objective can be passed with the [Continue] button instead of being done: only
     * when it was already done before (a Starter Kit claimed earlier, a claim already owned) or the
     * system it teaches can't be used here (no Starter Kit in this gamemode, /rtp not available,
     * quests or teams switched off), so a step can never become impossible.
     */
    private fun canContinue(player: Player, session: Session, step: TutorialStep): Boolean = try {
        when (step) {
            TutorialStep.STARTER_KIT -> {
                val kits = plugin.kitManager.starterKitsFor(player)
                kits.isEmpty() || kits.none { player.hasPermission(it.permission) } ||
                    kits.any { plugin.kitManager.hasClaimedBefore(player.uniqueId, it.name) }
            }
            TutorialStep.RTP -> !commandUsable(player, "rtp", "joshymc.rtp")
            TutorialStep.CLAIM -> !commandUsable(player, "claim", "joshymc.claim") ||
                plugin.claimManager.getClaimsByPlayer(player.uniqueId).any { claim ->
                    Bukkit.getWorld(claim.world)?.let { plugin.gamemodeLockManager.gamemodeOf(it) } == session.gamemode
                }
            TutorialStep.QUESTS -> !plugin.isFeatureEnabled("quests") || !commandUsable(player, "quests", null)
            TutorialStep.HEARTS -> !plugin.lifestealHeartManager.hosted
            TutorialStep.TEAMS -> plugin.lifestealTeamManager.unavailableReason() != null
            TutorialStep.WALK, TutorialStep.EARN, TutorialStep.COMBAT, TutorialStep.DISCORD -> false
        }
    } catch (e: Exception) {
        plugin.logger.log(Level.WARNING, "[Tutorial] Could not check objective ${step.id} for ${player.name}.", e)
        false
    }

    private fun commandUsable(player: Player, name: String, permission: String?): Boolean {
        val command = plugin.getCommand(name) ?: return false
        if (permission != null && !player.hasPermission(permission)) return false
        return plugin.gamemodeLockManager.canUse(player, command)
    }

    // ── Walking ─────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        val from = event.from
        val to = event.to
        if (from.x == to.x && from.z == to.z) return
        val session = sessions[event.player.uniqueId] ?: return
        if (TutorialRules.stepAt(session.gamemode, session.step) != TutorialStep.WALK) return
        val player = event.player
        val anchor = session.anchor
        // Riding, a different world, or a jump too big for one step (another plugin's setTo) re-anchors.
        if (player.isInsideVehicle || anchor == null || anchor.world?.name != to.world?.name ||
            TutorialRules.walkedDistance(from.x, from.z, to.x, to.z) > MAX_STEP_PER_MOVE
        ) {
            session.anchor = to.clone()
            return
        }
        if (gamemodeOf(player) != session.gamemode) return
        if (TutorialRules.walked(anchor.world?.name ?: "", anchor.x, anchor.z, to.world?.name ?: "", to.x, to.z, walkDistance)) {
            session.anchor = null
            completeStep(player, session)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTeleport(event: PlayerTeleportEvent) {
        sessions[event.player.uniqueId]?.let { it.anchor = event.to.clone() }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onRespawn(event: PlayerRespawnEvent) {
        sessions[event.player.uniqueId]?.let { it.anchor = event.respawnLocation.clone() }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onVehicleExit(event: VehicleExitEvent) {
        val player = event.exited as? Player ?: return
        val session = sessions[player.uniqueId] ?: return
        session.anchor = null // the next move anchors where they got off
        Bukkit.getScheduler().runTask(plugin, Runnable { if (player.isOnline) session.anchor = player.location.clone() })
    }

    // ── Presentation ────────────────────────────────────────

    private fun tickActionBars() {
        if (sessions.isEmpty()) return
        val now = System.currentTimeMillis()
        for ((uuid, session) in sessions) {
            val player = Bukkit.getPlayer(uuid) ?: continue
            // Combat and other systems' action bars (RTP warmup, quest progress, ...) come first.
            if (plugin.combatManager.isTagged(player)) continue
            if (now - plugin.commsManager.lastActionBarAt(player) < OTHER_ACTION_BAR_GRACE_MS) continue
            sendActionBar(player, session)
        }
    }

    private fun sendActionBar(player: Player, session: Session) {
        val step = TutorialRules.stepAt(session.gamemode, session.step) ?: return
        if (plugin.combatManager.isTagged(player)) return
        if (System.currentTimeMillis() - plugin.commsManager.lastActionBarAt(player) < OTHER_ACTION_BAR_GRACE_MS) return
        val text = message("action-bar")
            .replace("{step}", (session.step + 1).toString())
            .replace("{total}", TutorialRules.STEP_COUNT.toString())
            .replace("{text}", stepText(player, session, step, "action-bar"))
        // Sent directly (not commsManager.sendActionBar), so it never holds back higher-priority bars.
        player.sendActionBar(legacy(text))
    }

    private fun sendInstructions(player: Player, session: Session) {
        val step = TutorialRules.stepAt(session.gamemode, session.step) ?: return
        val header = message("header")
            .replace("{step}", (session.step + 1).toString())
            .replace("{total}", TutorialRules.STEP_COUNT.toString())
        val base = "${session.gamemode.id}.${step.id}"
        plugin.commsManager.sendRaw(player, Component.empty())
        plugin.commsManager.sendRaw(player, legacy(header))
        plugin.commsManager.sendRaw(player, Component.empty())
        for (line in texts.getStringList("$base.lines")) plugin.commsManager.sendRaw(player, legacy(placeholders(player, session, line)))
        plugin.commsManager.sendRaw(player, Component.empty())
        plugin.commsManager.sendRaw(player, legacy(message("objective").replace("{text}", stepText(player, session, step, "objective"))))
        if (canContinue(player, session, step)) plugin.commsManager.sendRaw(player, continueLine(stepText(player, session, step, "continue")))
        plugin.commsManager.sendRaw(player, legacy(message("skip-hint")).clickEvent(ClickEvent.suggestCommand("/tutorial skip")))
        plugin.commsManager.sendRaw(player, Component.empty())
    }

    private fun continueLine(prompt: String): Component =
        legacy(prompt).append(Component.space()).append(button(message("continue-button"), "/tutorial continue"))

    private fun button(label: String, command: String): Component =
        legacy(label)
            .clickEvent(ClickEvent.runCommand(command))
            .hoverEvent(HoverEvent.showText(legacy(if (command == "/tutorial continue") message("continue-hover") else "&7$command")))

    private fun stepText(player: Player, session: Session, step: TutorialStep, key: String): String {
        val raw = texts.getString("${session.gamemode.id}.${step.id}.$key")
            ?: if (key == "continue") "&7Can't do this one here? Click to continue." else step.id
        return placeholders(player, session, raw)
    }

    private fun placeholders(player: Player, session: Session, text: String): String {
        if (!text.contains('{')) return text
        val values = HashMap<String, String>()
        values["player"] = player.name
        values["walk"] = formatNumber(walkDistance)
        values["earn"] = TutorialRules.money(earnAmount)
        values["earned"] = TutorialRules.money(TutorialRules.shownEarnings(if (stepIs(session, TutorialStep.EARN)) session.progress else 0.0, earnAmount))
        val bits = if (stepIs(session, TutorialStep.QUESTS)) session.progress.toInt() else 0
        values["daily"] = if (bits and TutorialRules.QUEST_DAILY != 0) "✔" else "✘"
        values["weekly"] = if (bits and TutorialRules.QUEST_WEEKLY != 0) "✔" else "✘"
        values["reward"] = TutorialRules.money(rewardFor(session.gamemode))
        values["step"] = (session.step + 1).toString()
        values["total"] = TutorialRules.STEP_COUNT.toString()
        if (session.gamemode == ServerGamemode.LIFESTEAL) lifestealFacts(values)
        return TutorialRules.fill(text, values)
    }

    /** Lifesteal rules as actually configured right now (never hard-coded numbers). */
    private fun lifestealFacts(values: MutableMap<String, String>) {
        val limits = plugin.lifestealHeartManager.limits
        val settings = plugin.lifestealConfigManager.settings
        values["hearts_start"] = limits?.starting?.toString() ?: "?"
        values["hearts_max"] = limits?.maximum?.toString() ?: "?"
        values["hearts_min"] = limits?.minimum?.toString() ?: "?"
        values["same_victim_minutes"] = plugin.lifestealHeartManager.cooldownMinutes.toString()
        values["tag_seconds"] = (plugin.combatManager.tagDurationMillis / 1000).toString()
        values["team_max"] = (settings?.teams?.maxSize ?: plugin.lifestealTeamManager.maxSize).toString()
        values["invite_seconds"] = (settings?.teams?.inviteExpirationSeconds ?: 60).toString()
        values["friendly_fire"] = if (settings?.teams?.friendlyFire == true) "on" else "off (teammates can't hurt each other)"
        values["grace_hours"] = (settings?.progression?.gracePeriodHours ?: 24).toString()
    }

    private fun stepIs(session: Session, step: TutorialStep) = TutorialRules.stepAt(session.gamemode, session.step) == step

    private fun formatNumber(value: Double): String =
        if (value == Math.floor(value)) value.toLong().toString() else String.format(java.util.Locale.US, "%.1f", value)

    private fun fill(text: String, player: Player): String = text.replace("{player}", player.name)

    private fun message(key: String): String = texts.getString("messages.$key") ?: ""

    private fun legacy(text: String): Component = plugin.commsManager.parseLegacy(text)

    private fun playConfiguredSound(player: Player, raw: String) {
        if (raw.isBlank()) return
        try {
            player.playSound(Sound.sound(Key.key(raw.trim().lowercase()), Sound.Source.MASTER, 1f, 1f))
        } catch (e: Exception) {
            plugin.logger.warning("[Tutorial] Invalid complete-sound '$raw' in $FILE_NAME.")
        }
    }

    companion object {
        const val FILE_NAME = "tutorial-walkthrough.yml"
        /** A single move event longer than this is a teleport-like jump, not a step. */
        private const val MAX_STEP_PER_MOVE = 8.0
        /** Another system's action bar stays on screen this long before the tutorial's comes back. */
        private const val OTHER_ACTION_BAR_GRACE_MS = 3000L
    }
}
