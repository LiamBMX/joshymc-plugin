package com.liam.joshymc.punish

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gamemode.ServerGamemode
import com.liam.joshymc.manager.CommunicationsManager
import com.liam.joshymc.util.Msg
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.UUID

/**
 * `/punish <player> <rule>` (issue #1298): rule lookup, automatic escalation, staff authority,
 * single-use confirmations and execution on the shared [com.liam.joshymc.manager.PunishmentManager]
 * backend. Every decision is made by the Bukkit-free [PunishPlanner]; state lives in [PunishStore].
 *
 * Flow: [begin] plans the punishment and stores a confirmation bound to the staff member;
 * [confirm] claims it (once), re-plans from fresh data (permissions, offense count, scope, the
 * ban being extended) and refuses it as stale when anything changed, then writes the punishment
 * row, the incident and the audit entry in one transaction before touching the player.
 */
class RulePunishManager(private val plugin: Joshymc) {

    val store = PunishStore(plugin.databaseManager) { sql, params -> plugin.databaseManager.insertReturningId(sql, *params) }

    @Volatile var registry: PunishRuleRegistry = PunishRuleRegistry.DEFAULT
        private set
    @Volatile var settings: PunishSettings = PunishSettings()
        private set

    private val random = SecureRandom()

    fun start() {
        store.createTables()
        val interrupted = store.markInterrupted()
        if (interrupted > 0) plugin.logger.warning("[Punish] $interrupted punishment(s) were mid-execution at shutdown; marked INTERRUPTED (never re-run).")
        reload()
        plugin.server.scheduler.runTaskTimer(plugin, Runnable { store.expireConfirmations(System.currentTimeMillis()) }, 1200L, 1200L)
        plugin.server.scheduler.runTaskTimer(plugin, Runnable { remindOverdueReviews() }, 6000L, 12000L)
    }

    /** Re-reads punish-rules.yml. Returns the number of problems found. */
    fun reload(): Int {
        val file = plugin.configFile(CONFIG_FILE)
        if (!file.exists()) plugin.saveResource(CONFIG_FILE, false)
        val loaded = PunishRuleConfig.load(YamlConfiguration.loadConfiguration(file), plugin.logger)
        registry = loaded.registry
        settings = loaded.settings
        return loaded.problems
    }

    // ── Staff ───────────────────────────────────────────────

    /** The sender's highest punishment level. The console acts as Sr Admin. */
    fun levelOf(sender: CommandSender): StaffLevel? =
        if (sender !is Player) StaffLevel.SRADMIN else StaffLevel.highest { sender.hasPermission(it.permission) }

    fun isNetworkApprover(sender: CommandSender): Boolean = sender !is Player || sender.hasPermission(NETWORK_PERMISSION)

    private fun staffKey(sender: CommandSender): String = (sender as? Player)?.uniqueId?.toString() ?: CONSOLE_KEY

    /** Rules [sender] may use, for tab completion and the rule picker. */
    fun usableRules(sender: CommandSender): List<PunishRule> = levelOf(sender)?.let { registry.usableBy(it) } ?: emptyList()

    // ── Planning ────────────────────────────────────────────

    data class Target(val uuid: UUID, val name: String)

    fun previousCount(target: UUID, rule: PunishRule, now: Long = System.currentTimeMillis()): Int =
        store.qualifyingCount(target, rule.id, now - settings.windowMs(rule.window))

    private fun violationGamemode(sender: CommandSender, target: UUID, flags: PunishFlags): ServerGamemode =
        flags.gamemode
            ?: Bukkit.getPlayer(target)?.let { plugin.gamemodeLockManager.gamemodeOf(it.world) }
            ?: plugin.gamemodeLockManager.gamemodeOf(sender)

    private fun activeBan(target: UUID, gamemode: ServerGamemode, network: Boolean) =
        (if (network) plugin.punishmentManager.isBanned(target) else plugin.punishmentManager.isBannedIn(target, gamemode))

    fun plan(sender: CommandSender, target: UUID, rule: PunishRule, flags: PunishFlags, now: Long = System.currentTimeMillis()): PlanResult {
        val gamemode = violationGamemode(sender, target, flags)
        val ban = activeBan(target, gamemode, flags.network)
        return PunishPlanner.plan(
            PlanRequest(
                rule = rule,
                level = levelOf(sender),
                networkApprover = isNetworkApprover(sender),
                gamemode = gamemode,
                network = flags.network,
                previousCount = previousCount(target, rule, now),
                overrideTier = flags.tier,
                alternate = flags.alternate,
                targetOnline = Bukkit.getPlayer(target) != null,
                activeBan = ban?.let { ActiveBanInfo(permanent = it.expiresAt == null, originalDurationMs = it.durationMs) },
            ),
            settings,
        )
    }

    /** Refuses self-punishment and punishing a higher rank. Null when allowed. */
    private fun targetProblem(sender: CommandSender, target: Target): String? {
        if (sender !is Player) return null
        if (sender.uniqueId == target.uuid) return "You cannot punish yourself."
        val senderWeight = plugin.rankManager.getPlayerRankById(sender.uniqueId)?.weight ?: 0
        val targetWeight = plugin.rankManager.getPlayerRankById(target.uuid)?.weight ?: 0
        if (targetWeight > senderWeight) return "You cannot punish a player with a higher rank than you."
        return null
    }

    /** `/punish preview`: shows what would happen, creates nothing. */
    fun preview(sender: CommandSender, target: Target, rule: PunishRule, flags: PunishFlags) {
        when (val result = plan(sender, target.uuid, rule, flags)) {
            is PlanResult.Denied -> Msg.error(sender, result.reason)
            is PlanResult.Allowed -> {
                Msg.reply(sender, Component.text("Preview - nothing has been applied.", NamedTextColor.GRAY))
                for (line in summaryLines(target.name, result.plan, sender.name, flags.evidence)) sender.sendMessage(line)
            }
        }
    }

    /** `/punish <player> <rule>`: plans the punishment and asks for confirmation. */
    fun begin(sender: CommandSender, target: Target, rule: PunishRule, flags: PunishFlags) {
        targetProblem(sender, target)?.let { Msg.error(sender, it); return }
        val now = System.currentTimeMillis()
        val plan = when (val result = plan(sender, target.uuid, rule, flags, now)) {
            is PlanResult.Denied -> {
                Msg.error(sender, result.reason)
                if (result.kind in PERMISSION_DENIALS) audit(sender, "PERMISSION_DENIED", target, rule = rule, details = "${result.kind}: ${result.reason}")
                return
            }
            is PlanResult.Allowed -> result.plan
        }
        val evidence = flags.evidence
        if (evidence != null && store.hasDuplicateEvidence(target.uuid, rule.id, evidence, now - DUPLICATE_EVIDENCE_WINDOW_MS)) {
            Msg.error(sender, "${target.name} was already punished for ${rule.displayName} with that evidence/reference. If this is a new incident, describe it differently.")
            audit(sender, "DUPLICATE_PREVENTED", target, rule = rule, details = "evidence reused: $evidence")
            return
        }

        val id = newConfirmationId()
        val key = staffKey(sender)
        try {
            plugin.databaseManager.transaction {
                store.supersede(key, target.uuid, rule.id)
                store.insertConfirmation(
                    PunishStore.Confirmation(
                        id = id, staffUuid = key, staffName = sender.name, targetUuid = target.uuid, targetName = target.name,
                        ruleId = rule.id, gamemode = plan.gamemode.id, network = plan.network, overrideTier = flags.tier,
                        alternate = flags.alternate, evidence = evidence, fingerprint = plan.fingerprint(), createdAt = now,
                        expiresAt = now + settings.confirmationExpiryMs, status = PunishStore.ConfirmationStatus.PENDING,
                    )
                )
                audit(sender, "CONFIRMATION_CREATED", target, rule = rule, details = "confirmation=$id ${describePlan(plan)}", rethrow = true)
            }
        } catch (e: Exception) {
            plugin.logger.severe("[Punish] Could not store a confirmation for ${target.name}: ${e.message}")
            Msg.error(sender, "Could not start the punishment (database error). Nothing was applied.")
            return
        }

        val view = ConfirmationView(id, target, plan, sender.name, evidence, now + settings.confirmationExpiryMs)
        if (sender is Player) plugin.punishMenus.openConfirmation(sender, view)
        sendConfirmationChat(sender, view)
    }

    data class ConfirmationView(
        val id: String,
        val target: Target,
        val plan: PunishPlan,
        val staffName: String,
        val evidence: String?,
        val expiresAt: Long,
    )

    // ── Confirm / cancel ────────────────────────────────────

    private class Abort(val reason: String, val auditAction: String, val close: PunishStore.ConfirmationStatus?) : RuntimeException(reason)

    /**
     * `/punish confirm <id> [evidence]` (and the GUI button). Single use, bound to the staff
     * member who started it, unexpired, and only when a fresh plan matches the one shown.
     */
    fun confirm(sender: CommandSender, id: String, extraEvidence: String? = null) {
        val now = System.currentTimeMillis()
        val key = staffKey(sender)
        val c = store.confirmation(id.uppercase())
        if (c == null) { Msg.error(sender, "Unknown confirmation id."); return }
        val target = Target(c.targetUuid, c.targetName)
        val rule = registry.byId(c.ruleId)
        if (c.staffUuid != key) {
            Msg.error(sender, "That confirmation belongs to ${c.staffName}.")
            audit(sender, "PERMISSION_DENIED", target, rule = rule, details = "tried to use ${c.staffName}'s confirmation ${c.id}")
            return
        }
        when (c.status) {
            PunishStore.ConfirmationStatus.PENDING -> {}
            PunishStore.ConfirmationStatus.USED -> {
                Msg.error(sender, "That punishment was already confirmed.")
                audit(sender, "DUPLICATE_PREVENTED", target, rule = rule, details = "confirmation ${c.id} reused")
                return
            }
            else -> { Msg.error(sender, "That confirmation is ${c.status.name.lowercase()}. Run /punish again."); return }
        }
        if (c.expiresAt <= now) {
            store.closeConfirmation(c.id, PunishStore.ConfirmationStatus.EXPIRED)
            Msg.error(sender, "That confirmation expired. Run /punish again.")
            return
        }
        if (rule == null) {
            store.closeConfirmation(c.id, PunishStore.ConfirmationStatus.STALE)
            Msg.error(sender, "That rule no longer exists.")
            return
        }
        targetProblem(sender, target)?.let { Msg.error(sender, it); return }

        val gamemode = ServerGamemode.fromId(c.gamemode) ?: plugin.gamemodeLockManager.serverGamemode
        val flags = PunishFlags(gamemode = gamemode, network = c.network, tier = c.overrideTier, alternate = c.alternate)
        val plan = when (val result = plan(sender, target.uuid, rule, flags, now)) {
            is PlanResult.Denied -> {
                store.closeConfirmation(c.id, PunishStore.ConfirmationStatus.STALE)
                Msg.error(sender, "Not applied: ${result.reason}")
                audit(sender, if (result.kind in PERMISSION_DENIALS) "PERMISSION_DENIED" else "STALE_REJECTED", target, rule = rule, details = "confirmation ${c.id}: ${result.reason}")
                return
            }
            is PlanResult.Allowed -> result.plan
        }
        if (plan.fingerprint() != c.fingerprint) {
            store.closeConfirmation(c.id, PunishStore.ConfirmationStatus.STALE)
            Msg.error(sender, "${target.name}'s record changed since you started (now offense ${plan.offense}: ${plan.describe()}). Nothing was applied; run /punish again.")
            audit(sender, "STALE_REJECTED", target, rule = rule, details = "confirmation ${c.id} expected ${c.fingerprint}, now ${plan.fingerprint()}")
            return
        }

        val evidence = listOfNotNull(c.evidence, extraEvidence?.trim()?.takeIf { it.isNotEmpty() }).joinToString(" | ").takeIf { it.isNotEmpty() }
        if (plan.needsEvidence && evidence == null) {
            Msg.error(sender, "${plan.describe()} needs evidence or a written justification: /punish confirm ${c.id} <evidence>")
            return
        }
        if (extraEvidence != null && evidence != null) store.setConfirmationEvidence(c.id, evidence)

        val online = Bukkit.getPlayer(target.uuid)
        if (plan.tier.action == PunishAction.KICK && online == null) {
            Msg.error(sender, "${target.name} is no longer online, so the kick can't be applied.")
            return
        }

        val staffUuid = (sender as? Player)?.uniqueId
        val scope = if (plan.network) null else plan.gamemode
        var incidentId = -1
        var appliedDurationMs: Long? = null
        try {
            plugin.databaseManager.transaction {
                if (!store.claimConfirmation(c.id, key, now)) throw Abort("That punishment was already confirmed.", "DUPLICATE_PREVENTED", null)
                if (store.qualifyingCount(target.uuid, rule.id, now - settings.windowMs(rule.window)) != plan.previousCount) {
                    throw Abort("${target.name}'s offense count changed. Nothing was applied; run /punish again.", "STALE_REJECTED", PunishStore.ConfirmationStatus.STALE)
                }
                if (evidence != null && store.hasDuplicateEvidence(target.uuid, rule.id, evidence, now - DUPLICATE_EVIDENCE_WINDOW_MS)) {
                    throw Abort("${target.name} was already punished for ${rule.displayName} with that evidence/reference.", "DUPLICATE_PREVENTED", PunishStore.ConfirmationStatus.CANCELLED)
                }

                val reason = rule.displayName
                val pm = plugin.punishmentManager
                var punishmentId: Int? = null
                when (plan.tier.action) {
                    PunishAction.WARN -> punishmentId = pm.insertRulePunishment(target.uuid, target.name, sender.name, staffUuid, "WARN", reason, null, scope, plan.offense).id
                    PunishAction.KICK -> punishmentId = pm.insertRulePunishment(target.uuid, target.name, sender.name, staffUuid, "KICK", reason, null, scope, plan.offense).id
                    PunishAction.MUTE -> {
                        appliedDurationMs = plan.tier.durationMs
                        punishmentId = pm.insertRulePunishment(target.uuid, target.name, sender.name, staffUuid, "TEMPMUTE", reason, appliedDurationMs, scope, plan.offense).id
                    }
                    PunishAction.TEMPBAN -> {
                        appliedDurationMs = plan.tier.durationMs
                        punishmentId = pm.insertRulePunishment(target.uuid, target.name, sender.name, staffUuid, "TEMPBAN", reason, appliedDurationMs, scope, plan.offense).id
                    }
                    PunishAction.PERMBAN -> punishmentId = pm.insertRulePunishment(target.uuid, target.name, sender.name, staffUuid, "BAN", reason, null, scope, plan.offense).id
                    PunishAction.EXTEND_BAN -> {
                        val ban = activeBan(target.uuid, plan.gamemode, plan.network)
                            ?: throw Abort("${target.name}'s ban ended before confirmation. Run /punish again.", "STALE_REJECTED", PunishStore.ConfirmationStatus.STALE)
                        val extension = plan.extensionMs
                        val currentEnd = ban.expiresAt
                        if (currentEnd != null && extension != null) {
                            // Lengthen from the current end (never from now), so it is never shortened.
                            val newEnd = maxOf(currentEnd, now) + extension
                            appliedDurationMs = newEnd - now
                            punishmentId = pm.insertRulePunishment(target.uuid, target.name, sender.name, staffUuid, "TEMPBAN", reason, appliedDurationMs, scope, plan.offense, expiresAt = newEnd).id
                        }
                        // A permanent ban can't get longer: the offense is recorded, nothing is added.
                    }
                }

                val status = if (plan.tier.action == PunishAction.KICK) PunishStore.Status.EXECUTING else PunishStore.Status.ENFORCED
                incidentId = store.insertIncident(
                    PunishStore.NewIncident(
                        confirmationId = c.id, targetUuid = target.uuid, targetName = target.name, plan = plan,
                        staffUuid = staffUuid?.toString(), staffName = sender.name, durationMs = appliedDurationMs,
                        evidence = evidence, status = status, punishmentId = punishmentId,
                        reviewDueAt = if (plan.needsReview) now + settings.emergencyReviewMs else null, now = now,
                    )
                ) ?: error("incident row was not inserted")

                audit(sender, "EXECUTED", target, incidentId, rule, "confirmation=${c.id} ${describePlan(plan)} punishment=${punishmentId ?: "-"} evidence=${evidence ?: "-"}", rethrow = true)
                if (plan.overridden) audit(sender, "TIER_OVERRIDE", target, incidentId, rule, "tier ${plan.tierNumber} instead of ${(plan.previousCount + 1).coerceAtMost(5)}: $evidence", rethrow = true)
                if (plan.network) audit(sender, if (plan.needsReview) "NETWORK_EMERGENCY" else "NETWORK_APPROVAL", target, incidentId, rule, describePlan(plan), rethrow = true)
            }
        } catch (abort: Abort) {
            abort.close?.let { store.closeConfirmation(c.id, it) }
            Msg.error(sender, abort.reason)
            audit(sender, abort.auditAction, target, rule = rule, details = "confirmation ${c.id}: ${abort.reason}")
            return
        } catch (e: Exception) {
            plugin.logger.severe("[Punish] Executing ${rule.keyword} on ${target.name} failed: ${e.message}")
            Msg.error(sender, "The punishment could not be saved (database error). Nothing was applied; you can retry /punish confirm ${c.id}.")
            audit(sender, "FAILED", target, rule = rule, details = "confirmation ${c.id}: ${e.message}")
            return
        }

        applyToPlayer(target, plan, incidentId, sender)
        announce(sender, target, plan, incidentId, appliedDurationMs)
    }

    fun cancel(sender: CommandSender, id: String) {
        val c = store.confirmation(id.uppercase())
        if (c == null || c.staffUuid != staffKey(sender)) { Msg.error(sender, "Unknown confirmation id."); return }
        if (store.closeConfirmation(c.id, PunishStore.ConfirmationStatus.CANCELLED)) {
            Msg.reply(sender, Component.text("Punishment cancelled. Nothing was applied.", NamedTextColor.YELLOW))
            audit(sender, "CANCELLED", Target(c.targetUuid, c.targetName), rule = registry.byId(c.ruleId), details = "confirmation ${c.id}")
        } else {
            Msg.error(sender, "That confirmation is no longer pending.")
        }
    }

    /** Messages / kicks the punished player after the records are committed. */
    private fun applyToPlayer(target: Target, plan: PunishPlan, incidentId: Int, sender: CommandSender) {
        val online = Bukkit.getPlayer(target.uuid)
        val name = plan.rule.displayName
        when (plan.tier.action) {
            PunishAction.KICK -> {
                try {
                    online?.kick(kickMessage(plan)) ?: error("player went offline")
                    store.setStatus(incidentId, PunishStore.Status.EXECUTING, PunishStore.Status.ENFORCED)
                } catch (e: Exception) {
                    store.setStatus(incidentId, PunishStore.Status.EXECUTING, PunishStore.Status.FAILED, e.message)
                    Msg.error(sender, "The kick failed (${e.message}); incident #$incidentId is marked FAILED and doesn't count.")
                    audit(sender, "FAILED", target, incidentId, plan.rule, "kick failed: ${e.message}")
                }
            }
            PunishAction.WARN -> online?.let {
                plugin.commsManager.send(it, Component.text("You have been warned! Reason: $name (Offense ${plan.offense})", NamedTextColor.YELLOW), CommunicationsManager.Category.ADMIN)
            }
            PunishAction.MUTE -> online?.let {
                if (!plan.network && plugin.gamemodeLockManager.gamemodeOf(it.world) != plan.gamemode) return@let
                plugin.commsManager.send(it, Component.text("You have been muted for ${PunishDurations.words(plan.tier.durationMs!!)}! Reason: $name (Offense ${plan.offense})", NamedTextColor.RED), CommunicationsManager.Category.ADMIN)
            }
            PunishAction.TEMPBAN, PunishAction.PERMBAN, PunishAction.EXTEND_BAN -> online?.let { player ->
                val gm = plugin.gamemodeLockManager.gamemodeOf(player.world)
                val ban = plugin.punishmentManager.isBannedIn(target.uuid, gm) ?: return@let
                player.kick(plugin.punishmentManager.banMessageFor(target.uuid, ban))
            }
        }
    }

    private fun kickMessage(plan: PunishPlan): Component = Component.text()
        .append(plugin.commsManager.parseLegacy("&#D96320&l✦ JOSHYMC ✦")).append(Component.newline()).append(Component.newline())
        .append(plugin.commsManager.parseLegacy("&#C9442A&lYOU HAVE BEEN KICKED")).append(Component.newline()).append(Component.newline())
        .append(plugin.commsManager.parseLegacy("&#E8BF73Reason")).append(Component.newline())
        .append(Component.text(plan.rule.displayName, NamedTextColor.WHITE)).append(Component.newline())
        .append(plugin.commsManager.parseLegacy("&#E8BF73Offense &f#${plan.offense}"))
        .build()

    private fun announce(sender: CommandSender, target: Target, plan: PunishPlan, incidentId: Int, appliedDurationMs: Long?) {
        val extra = when {
            plan.tier.action == PunishAction.EXTEND_BAN && appliedDurationMs == null -> " (ban is already permanent; offense recorded)"
            plan.tier.action == PunishAction.EXTEND_BAN -> " (now ${com.liam.joshymc.manager.PunishmentManager.formatDuration(appliedDurationMs!!)} left)"
            else -> ""
        }
        val msg = Component.text("${target.name}: ${plan.describe()} for ${plan.rule.displayName} (offense ${plan.offense}, ${plan.scopeLabel}) - incident #$incidentId$extra", NamedTextColor.RED)
        Msg.reply(sender, msg)
        com.liam.joshymc.command.notifyStaff(sender, msg)
        if (plan.needsReview) notifySeniors(Component.text("Emergency network suspension #$incidentId of ${target.name} by ${sender.name} needs Sr Admin review within ${PunishDurations.label(settings.emergencyReviewMs).lowercase()}: /punish review $incidentId <note>", NamedTextColor.GOLD))
    }

    // ── Overturn / review ───────────────────────────────────

    /**
     * `/punish overturn <incident> <reason>`: marks the incident overturned (kept in history,
     * no longer counted for escalation) and lifts the punishment it created.
     */
    fun overturn(sender: CommandSender, incidentId: Int, reason: String) {
        val incident = store.incident(incidentId) ?: run { Msg.error(sender, "Unknown incident #$incidentId."); return }
        val action = PunishAction.entries.firstOrNull { it.name == incident.action } ?: PunishAction.TEMPBAN
        val level = levelOf(sender)
        val target = Target(incident.targetUuid, incident.targetName)
        val rule = registry.byId(incident.ruleId)
        if (level == null || !level.mayOverturn(action)) {
            Msg.error(sender, if (action == PunishAction.WARN) "Overturning warnings needs Jr Mod or higher." else "Overturning punishments needs Sr Admin.")
            audit(sender, "PERMISSION_DENIED", target, incidentId, rule, "overturn refused")
            return
        }
        if (incident.appeal == PunishStore.Appeal.OVERTURNED) { Msg.error(sender, "Incident #$incidentId is already overturned."); return }
        val now = System.currentTimeMillis()
        var lifted = false
        try {
            plugin.databaseManager.transaction {
                if (!store.markOverturned(incidentId, (sender as? Player)?.uniqueId?.toString(), sender.name, reason, now)) {
                    throw Abort("Incident #$incidentId is already overturned.", "DUPLICATE_PREVENTED", null)
                }
                incident.punishmentId?.let { lifted = plugin.punishmentManager.revokeById(it, sender.name, (sender as? Player)?.uniqueId, "Overturned: $reason") }
                audit(sender, "OVERTURNED", target, incidentId, rule, "reason=$reason lifted=$lifted", rethrow = true)
            }
        } catch (abort: Abort) {
            Msg.error(sender, abort.reason); return
        } catch (e: Exception) {
            plugin.logger.severe("[Punish] Overturning #$incidentId failed: ${e.message}")
            Msg.error(sender, "Could not overturn #$incidentId (database error). Nothing changed.")
            return
        }
        val msg = Component.text("Incident #$incidentId (${incident.ruleName}, ${incident.targetName}) overturned${if (lifted) " and lifted" else ""}: $reason", NamedTextColor.GREEN)
        Msg.reply(sender, msg)
        com.liam.joshymc.command.notifyStaff(sender, msg)
        if (lifted) Bukkit.getPlayer(incident.targetUuid)?.let {
            plugin.commsManager.send(it, Component.text("A punishment on your record was overturned.", NamedTextColor.GREEN), CommunicationsManager.Category.ADMIN)
        }
    }

    /** `/punish review [incident] [note]`: Sr Admin review of emergency network suspensions. */
    fun review(sender: CommandSender, incidentId: Int?, note: String?) {
        val level = levelOf(sender)
        if (level == null || !(level.isSenior || isNetworkApprover(sender))) {
            Msg.noPermission(sender); return
        }
        if (incidentId == null) {
            val pending = store.pendingReviews()
            if (pending.isEmpty()) { Msg.reply(sender, Component.text("No punishments are waiting for review.", NamedTextColor.GREEN)); return }
            Msg.reply(sender, Component.text("Waiting for senior review:", NamedTextColor.GOLD))
            val now = System.currentTimeMillis()
            for (i in pending) {
                val due = i.reviewDueAt?.let { if (it <= now) "OVERDUE" else "due in ${com.liam.joshymc.manager.PunishmentManager.formatDuration(it - now)}" } ?: ""
                sender.sendMessage(Component.text(" #${i.id} ${i.targetName} - ${i.ruleName} (${i.scope}) by ${i.staffName} - $due", NamedTextColor.YELLOW))
            }
            return
        }
        if (note.isNullOrBlank()) { Msg.usage(sender, "/punish review <incident> <note>"); return }
        val incident = store.incident(incidentId)
        if (incident == null || !store.markReviewed(incidentId, (sender as? Player)?.uniqueId?.toString(), sender.name, note, System.currentTimeMillis())) {
            Msg.error(sender, "Incident #$incidentId isn't waiting for review."); return
        }
        audit(sender, "REVIEWED", Target(incident.targetUuid, incident.targetName), incidentId, registry.byId(incident.ruleId), note)
        Msg.success(sender, "Incident #$incidentId reviewed. Use /punish overturn $incidentId <reason> to reverse it instead.")
    }

    private fun remindOverdueReviews() {
        val now = System.currentTimeMillis()
        val overdue = try { store.pendingReviews().filter { (it.reviewDueAt ?: Long.MAX_VALUE) <= now } } catch (_: Exception) { return }
        if (overdue.isEmpty()) return
        notifySeniors(Component.text("${overdue.size} emergency network suspension(s) are overdue for review: /punish review", NamedTextColor.GOLD))
    }

    private fun notifySeniors(message: Component) {
        for (p in Bukkit.getOnlinePlayers()) {
            if (p.hasPermission(StaffLevel.SRADMIN.permission) || p.hasPermission(NETWORK_PERMISSION)) plugin.commsManager.send(p, message, CommunicationsManager.Category.ADMIN)
        }
    }

    /** `/punish audit verify` (Sr Admin): checks the audit hash chain. */
    fun verifyAudit(sender: CommandSender) {
        if (levelOf(sender)?.isSenior != true) { Msg.noPermission(sender); return }
        val broken = store.verifyAudit()
        if (broken == null) Msg.success(sender, "Punishment audit log intact (${store.auditCount()} entries).")
        else Msg.error(sender, "Punishment audit log was altered at or before entry #$broken.")
    }

    // ── History ─────────────────────────────────────────────

    fun sendHistory(sender: CommandSender, target: Target, rule: PunishRule?) {
        val incidents = store.history(target.uuid, rule?.id)
        val header = "Punishment history of ${target.name}" + (rule?.let { " - ${it.displayName}" } ?: "")
        Msg.reply(sender, Component.text(header, NamedTextColor.GOLD))
        if (incidents.isEmpty()) {
            sender.sendMessage(Component.text(" No /punish rule offenses on record. Older punishments: /history ${target.name}", NamedTextColor.GRAY))
            return
        }
        val now = System.currentTimeMillis()
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm")
        for (i in incidents) {
            val counts = i.status == PunishStore.Status.ENFORCED && i.appeal != PunishStore.Appeal.OVERTURNED &&
                registry.byId(i.ruleId)?.let { i.createdAt >= now - settings.windowMs(it.window) } == true
            val state = when {
                i.appeal == PunishStore.Appeal.OVERTURNED -> "OVERTURNED"
                i.status != PunishStore.Status.ENFORCED -> i.status.name
                i.appeal == PunishStore.Appeal.PENDING_REVIEW -> "PENDING REVIEW"
                !counts -> "EXPIRED FROM ESCALATION"
                else -> "COUNTS"
            }
            val type = describeIncident(i)
            val color = if (counts) NamedTextColor.RED else NamedTextColor.GRAY
            sender.sendMessage(
                Component.text(" #${i.id} ", NamedTextColor.DARK_GRAY)
                    .append(Component.text(i.ruleName, color))
                    .append(Component.text(" - offense ${i.offense}, tier ${i.tier}: $type (${i.scope})", NamedTextColor.GRAY))
                    .append(Component.text(" [$state]", if (counts) NamedTextColor.YELLOW else NamedTextColor.DARK_GRAY))
                    .hoverEvent(HoverEvent.showText(Component.text(
                        "${format.format(Date(i.createdAt))} by ${i.staffName} (${i.staffLevel})\nGamemode: ${i.gamemode}\nEvidence: ${i.evidence ?: "-"}" +
                            (i.reversedBy?.let { "\nOverturned by $it: ${i.reversedReason}" } ?: ""),
                        NamedTextColor.GRAY
                    )))
            )
        }
    }

    // ── Output helpers ──────────────────────────────────────

    /** "1-Hour Mute", "Warning", "Ban extended (9-Day left)"... for an incident row. */
    fun describeIncident(i: PunishStore.Incident): String {
        val action = PunishAction.entries.firstOrNull { it.name == i.action } ?: return i.action
        val duration = i.durationMs
        return when (action) {
            PunishAction.MUTE, PunishAction.TEMPBAN -> if (duration != null) PunishTier(action, duration).describe() else action.name
            PunishAction.EXTEND_BAN -> if (duration == null) "Ban Extension (ban already permanent)" else "Ban Extension (${com.liam.joshymc.manager.PunishmentManager.formatDuration(duration)} total)"
            else -> PunishTier(action).describe()
        }
    }

    fun summaryLines(targetName: String, plan: PunishPlan, staffName: String, evidence: String?): List<Component> {
        fun row(label: String, value: String, color: NamedTextColor = NamedTextColor.WHITE) =
            Component.text(" $label: ", NamedTextColor.GRAY).append(Component.text(value, color))
        return listOfNotNull(
            row("Player", targetName),
            row("Rule", "${plan.rule.displayName} (${plan.rule.keyword})"),
            row("Gamemode", plan.gamemode.displayName),
            row("Scope", plan.scopeLabel, if (plan.network) NamedTextColor.GOLD else NamedTextColor.WHITE),
            row("Previous Offenses", plan.previousCount.toString()),
            row("Current Offense", plan.offense.toString() + if (plan.overridden) " (tier ${plan.tierNumber} override)" else ""),
            row("Punishment", plan.describe(), NamedTextColor.RED),
            row("Staff", staffName),
            row("Evidence", evidence ?: if (plan.needsEvidence) "REQUIRED" else "-", if (evidence == null && plan.needsEvidence) NamedTextColor.RED else NamedTextColor.WHITE),
            if (plan.needsReview) Component.text(" Emergency network suspension: Sr Admin review required.", NamedTextColor.GOLD) else null,
        )
    }

    private fun sendConfirmationChat(sender: CommandSender, view: ConfirmationView) {
        sender.sendMessage(Component.text("JOSHYMC PUNISHMENT CONFIRMATION", NamedTextColor.GOLD).decorate(TextDecoration.BOLD))
        for (line in summaryLines(view.target.name, view.plan, view.staffName, view.evidence)) sender.sendMessage(line)
        val needsEvidence = view.plan.needsEvidence && view.evidence == null
        val confirmCommand = "/punish confirm ${view.id}" + if (needsEvidence) " " else ""
        sender.sendMessage(
            Component.text(" [CONFIRM]", NamedTextColor.GREEN).decorate(TextDecoration.BOLD)
                .clickEvent(if (needsEvidence) ClickEvent.suggestCommand(confirmCommand) else ClickEvent.runCommand(confirmCommand))
                .hoverEvent(HoverEvent.showText(Component.text(if (needsEvidence) "Add evidence, then send" else "Apply the punishment")))
                .append(Component.text("  "))
                .append(Component.text("[CANCEL]", NamedTextColor.RED).decorate(TextDecoration.BOLD).clickEvent(ClickEvent.runCommand("/punish cancel ${view.id}")))
                .append(Component.text("  "))
                .append(Component.text("[HISTORY]", NamedTextColor.AQUA).decorate(TextDecoration.BOLD).clickEvent(ClickEvent.runCommand("/punish history ${view.target.name}")))
                .append(Component.text("  id ${view.id}", NamedTextColor.DARK_GRAY))
        )
    }

    private fun describePlan(plan: PunishPlan): String =
        "rule=${plan.rule.id}:${plan.rule.keyword} gamemode=${plan.gamemode.id} scope=${plan.scopeId} previous=${plan.previousCount} tier=${plan.tierNumber} action=${plan.tier.code()} level=${plan.level.id}" +
            (if (plan.alternate) " alternate" else "") + (if (plan.overridden) " override" else "")

    private fun newConfirmationId(): String = buildString { repeat(8) { append(ID_ALPHABET[random.nextInt(ID_ALPHABET.length)]) } }

    /**
     * Writes one audit row (and a log line). Inside a transaction pass [rethrow] so a failed
     * audit write rolls the action back; elsewhere a failure is only logged.
     */
    private fun audit(sender: CommandSender, action: String, target: Target?, incidentId: Int? = null, rule: PunishRule? = null, details: String = "", rethrow: Boolean = false) {
        val entry = PunishStore.AuditEntry(
            actorUuid = (sender as? Player)?.uniqueId?.toString(), actorName = sender.name, action = action,
            targetUuid = target?.uuid, targetName = target?.name, incidentId = incidentId, rule = rule, details = details,
        )
        plugin.logger.info("[Punish] $action by ${sender.name}${target?.let { " on ${it.name}" } ?: ""}${incidentId?.let { " #$it" } ?: ""}: $details")
        try {
            store.audit(entry, System.currentTimeMillis())
        } catch (e: Exception) {
            plugin.logger.severe("[Punish] Could not write audit entry $action: ${e.message}")
            if (rethrow) throw e
        }
    }

    companion object {
        const val CONFIG_FILE = "punish-rules.yml"
        const val NETWORK_PERMISSION = "joshymc.punish.network"
        private const val CONSOLE_KEY = "CONSOLE"
        /** Same target, rule and evidence within this window = the same incident reported twice. */
        private const val DUPLICATE_EVIDENCE_WINDOW_MS = 24 * PunishDurations.HOUR
        private const val ID_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

        private val PERMISSION_DENIALS = setOf(
            PlanResult.Kind.NO_LEVEL, PlanResult.Kind.RULE_LEVEL, PlanResult.Kind.TIER_AUTHORITY,
            PlanResult.Kind.NETWORK_APPROVAL, PlanResult.Kind.OVERRIDE,
        )
    }
}
