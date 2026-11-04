package com.liam.joshymc.lifesteal.ranked

import com.liam.joshymc.Joshymc
import com.liam.joshymc.lifesteal.ranked.LifestealRankedManager.ArenaResult
import com.liam.joshymc.lifesteal.ranked.LifestealRankedManager.CreateResult
import com.liam.joshymc.util.Msg
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * `/lifesteal admin arena ...` and `/lifesteal admin ranked ...` (issue #1361), staff-only
 * subcommands of the existing `/lifesteal` executor next to `/lifesteal admin launch`. Everything
 * goes through [LifestealRankedManager]; nothing here decides match rules. Deleting an arena and
 * cancelling a match need a literal `confirm`.
 */
class LifestealRankedCommand(private val plugin: Joshymc) {

    private val ranked get() = plugin.lifestealRankedManager
    /** Issue #1365: Elo diagnostics (`/lifesteal admin ranked rating ...`). */
    private val rating = com.liam.joshymc.lifesteal.ranked.rating.RatingAdminCommand(plugin)

    /** Corner 1/2 per arena until both are set (then written as the boundary). */
    private val corners = HashMap<String, Pair<IntArray?, IntArray?>>()

    fun visibleTo(sender: CommandSender): Boolean = ranked.hosted &&
        (sender.hasPermission(LifestealRankedManager.ADMIN_PERMISSION) || sender.hasPermission(LifestealRankedManager.SPECTATE_PERMISSION))

    fun handles(first: String?): Boolean = first == "arena" || first == "ranked"

    /** [args] starts after `admin`: `arena ...` or `ranked ...`. */
    fun execute(sender: CommandSender, args: List<String>) {
        val group = args.firstOrNull()?.lowercase()
        val sub = args.getOrNull(1)?.lowercase()
        val spectateOnly = group == "ranked" && (sub == "spectate" || sub == "unspectate")
        val allowed = sender.hasPermission(LifestealRankedManager.ADMIN_PERMISSION) ||
            (spectateOnly && sender.hasPermission(LifestealRankedManager.SPECTATE_PERMISSION))
        if (!allowed) return Msg.noPermission(sender)
        if (!ranked.hosted) return Msg.error(sender, "Ranked Team Wars only exist on the Lifesteal server.")
        val rest = args.drop(2)
        when (group) {
            "arena" -> arena(sender, sub, rest)
            "ranked" -> ranked(sender, sub, rest)
        }
    }

    // ── Arenas ──────────────────────────────────────────────

    private fun arena(sender: CommandSender, sub: String?, a: List<String>) {
        when (sub) {
            "create" -> {
                val player = sender as? Player ?: return Msg.playersOnly(sender)
                val id = a.getOrNull(0) ?: return Msg.usage(sender, "/lifesteal admin arena create <id> [display name]")
                report(sender, ranked.createArena(sender, id, a.drop(1).joinToString(" ").ifBlank { null }, player.world), null)
            }
            "delete" -> {
                val id = a.getOrNull(0) ?: return Msg.usage(sender, "/lifesteal admin arena delete <id> [confirm]")
                report(sender, ranked.deleteArena(sender, id, confirmed(a, 1)), "/lifesteal admin arena delete $id confirm")
            }
            "list" -> list(sender)
            "info" -> {
                val id = a.getOrNull(0) ?: return Msg.usage(sender, "/lifesteal admin arena info <id>")
                info(sender, id)
            }
            "setname" -> {
                val id = a.getOrNull(0)
                val name = a.drop(1).joinToString(" ")
                if (id == null || name.isBlank()) return Msg.usage(sender, "/lifesteal admin arena setname <id> <display name>")
                val clean = RankedArenaRules.cleanName(name) ?: return Msg.error(sender, "Display names are 1-${RankedArenaRules.MAX_NAME_LENGTH} characters.")
                report(sender, ranked.editArena(sender, id, "Display name set to '$clean'") { it.copy(displayName = clean) to null }, null)
            }
            "setcorner" -> setCorner(sender, a)
            "setspawn" -> {
                val player = sender as? Player ?: return Msg.playersOnly(sender)
                val id = a.getOrNull(0)
                val side = a.getOrNull(1)?.let { Side.parse(it) }
                if (id == null || side == null) return Msg.usage(sender, "/lifesteal admin arena setspawn <id> <a|b>")
                val point = pointOf(player)
                report(sender, ranked.editArena(sender, id, "Added a ${side.label} spawn at ${describe(point)}") { arena ->
                    if (arena.world != player.world.name) return@editArena null to "Stand in the arena's world (${arena.world})."
                    val spawns = arena.spawns(side)
                    if (spawns.size >= RankedArenaRules.MAX_POINTS) return@editArena null to "${side.label} already has ${RankedArenaRules.MAX_POINTS} spawns (clearspawns first)."
                    arena.withSpawns(side, spawns + point) to null
                }, null)
            }
            "setspectator" -> {
                val player = sender as? Player ?: return Msg.playersOnly(sender)
                val id = a.getOrNull(0) ?: return Msg.usage(sender, "/lifesteal admin arena setspectator <id>")
                val point = pointOf(player)
                report(sender, ranked.editArena(sender, id, "Added a spectator point at ${describe(point)}") { arena ->
                    if (arena.world != player.world.name) return@editArena null to "Stand in the arena's world (${arena.world})."
                    if (arena.spectators.size >= RankedArenaRules.MAX_POINTS) return@editArena null to "The arena already has ${RankedArenaRules.MAX_POINTS} spectator points (clearspawns <id> spectator first)."
                    arena.copy(spectators = arena.spectators + point) to null
                }, null)
            }
            "clearspawns" -> {
                val id = a.getOrNull(0)
                val which = a.getOrNull(1)?.lowercase()
                if (id == null || which == null) return Msg.usage(sender, "/lifesteal admin arena clearspawns <id> <a|b|spectator>")
                val side = Side.parse(which)
                if (side == null && which != "spectator" && which != "spectators") return Msg.usage(sender, "/lifesteal admin arena clearspawns <id> <a|b|spectator>")
                report(sender, ranked.editArena(sender, id, "Cleared the ${side?.label ?: "spectator"} points") { arena ->
                    (if (side != null) arena.withSpawns(side, emptyList()) else arena.copy(spectators = emptyList())) to null
                }, null)
            }
            "sizes" -> {
                val id = a.getOrNull(0)
                val sizes = a.drop(1).joinToString(",").let { RankedArenaRules.parseSizes(it) }
                if (id == null || sizes == null) return Msg.usage(sender, "/lifesteal admin arena sizes <id> <2,3,4,5|all>")
                report(sender, ranked.editArena(sender, id, "Sizes set to ${sizes.joinToString { "${it}v$it" }}") { it.copy(sizes = sizes) to null }, null)
            }
            "enable", "disable" -> {
                val id = a.getOrNull(0) ?: return Msg.usage(sender, "/lifesteal admin arena $sub <id>")
                report(sender, ranked.setEnabled(sender, id, sub == "enable"), null)
            }
            else -> Msg.usage(sender, ARENA_USAGE)
        }
    }

    private fun setCorner(sender: CommandSender, a: List<String>) {
        val player = sender as? Player ?: return Msg.playersOnly(sender)
        val id = a.getOrNull(0)?.let(RankedArenaRules::canonicalId)
        val which = a.getOrNull(1)
        if (id == null || (which != "1" && which != "2")) return Msg.usage(sender, "/lifesteal admin arena setcorner <id> <1|2>")
        val arena = ranked.arena(id)?.arena ?: return Msg.error(sender, "No arena called '$id'.")
        if (arena.world != player.world.name) return Msg.error(sender, "Stand in the arena's world (${arena.world}).")
        val loc = player.location
        val corner = intArrayOf(loc.blockX, loc.blockY, loc.blockZ)
        val (c1, c2) = corners[id] ?: (null to null)
        val next = if (which == "1") corner to c2 else c1 to corner
        val first = next.first
        val second = next.second
        if (first == null || second == null) {
            corners[id] = next
            return Msg.success(sender, "Corner $which of '$id' set at (${corner.joinToString()}). Now set corner ${if (which == "1") 2 else 1}.")
        }
        corners.remove(id)
        val box = Box.of(first[0], first[1], first[2], second[0], second[1], second[2])
        report(sender, ranked.editArena(sender, id, "Boundary set to ${box.describe()}") { it.copy(bounds = box) to null }, null)
    }

    private fun list(sender: CommandSender) {
        val all = ranked.arenaList()
        if (all.isEmpty()) return Msg.reply(sender, Component.text("No ranked arenas yet. /lifesteal admin arena create <id>", NamedTextColor.GRAY))
        sender.sendMessage(Component.text("Ranked arenas", NamedTextColor.GOLD, TextDecoration.BOLD))
        for (loaded in all) {
            val av = ranked.availability(loaded)
            sender.sendMessage(
                Component.text("${loaded.arena.id} ", NamedTextColor.WHITE)
                    .append(Component.text("(${loaded.arena.displayName}, ${loaded.arena.world}, ${loaded.arena.sizes.joinToString("/") { "${it}v$it" }}) ", NamedTextColor.GRAY))
                    .append(Component.text(av.label, availabilityColor(av)))
            )
        }
    }

    private fun info(sender: CommandSender, rawId: String) {
        val loaded = ranked.arena(rawId) ?: return Msg.error(sender, "No arena called '$rawId'.")
        val a = loaded.arena
        val v = ranked.validation(loaded)
        val av = ranked.availability(loaded)
        sender.sendMessage(Component.text("Ranked arena ${a.id}", NamedTextColor.GOLD, TextDecoration.BOLD))
        line(sender, "Name", a.displayName)
        line(sender, "World", a.world)
        line(sender, "Boundary", a.bounds?.describe() ?: "not set")
        line(sender, "Team A spawns", a.teamA.joinToString(" | ") { describe(it) }.ifEmpty { "none" })
        line(sender, "Team B spawns", a.teamB.joinToString(" | ") { describe(it) }.ifEmpty { "none" })
        line(sender, "Spectator points", a.spectators.joinToString(" | ") { describe(it) }.ifEmpty { "none" })
        line(sender, "Sizes", a.sizes.sorted().joinToString { "${it}v$it" }.ifEmpty { "none" })
        line(sender, "Enabled", a.enabled.toString())
        line(sender, "Readiness", v.readiness.name + if (v.problems.isEmpty()) "" else ": " + v.problems.joinToString("; "), if (v.ready) NamedTextColor.GREEN else NamedTextColor.RED)
        val holder = ranked.holderOf(a.id)
        line(sender, "Availability", av.label + (holder?.let { " (match ${it.id}, ${it.roster.participants.count { p -> p.status == ParticipantStatus.ACTIVE }} fighting)" } ?: ""), availabilityColor(av))
    }

    // ── Matches ─────────────────────────────────────────────

    private fun ranked(sender: CommandSender, sub: String?, a: List<String>) {
        when (sub) {
            "testmatch" -> {
                val teamA = a.getOrNull(0)
                val teamB = a.getOrNull(1)
                val size = a.getOrNull(2)?.let { RankedArenaRules.parseSize(it) }
                if (teamA == null || teamB == null || size == null) return Msg.usage(sender, "/lifesteal admin ranked testmatch <teamA> <teamB> <2-5> [arena]")
                when (val result = ranked.createMatch(sender, teamA, teamB, size, a.getOrNull(3), test = true)) {
                    is CreateResult.Started -> Msg.success(sender, "TEST match ${result.match.id} started: ${result.match.label} in ${result.match.arena.id}. Players are being moved in.")
                    is CreateResult.Refused -> {
                        Msg.error(sender, "The test match can't start:")
                        for (p in result.problems) sender.sendMessage(Component.text(" - $p", NamedTextColor.RED))
                    }
                }
            }
            "list" -> listMatches(sender)
            "info" -> {
                val id = a.getOrNull(0) ?: return Msg.usage(sender, "/lifesteal admin ranked info <match>")
                matchInfo(sender, id)
            }
            "cancel" -> {
                val id = a.getOrNull(0) ?: return Msg.usage(sender, "/lifesteal admin ranked cancel <match> [confirm]")
                val match = ranked.match(id) ?: return Msg.error(sender, "No running match '$id'.")
                if (!confirmed(a, 1)) return confirm(sender, "This cancels ${match.id} (${match.label}) with no winner and restores everyone's inventory. Hearts already stolen stay stolen.", "/lifesteal admin ranked cancel ${match.id} confirm")
                ranked.cancel(match, "cancelled by ${sender.name}")
                Msg.success(sender, "Match ${match.id} cancelled.")
            }
            "remove" -> {
                val name = a.getOrNull(0) ?: return Msg.usage(sender, "/lifesteal admin ranked remove <player> [confirm]")
                val target = Bukkit.getPlayerExact(name) ?: return Msg.playerNotFound(sender, name)
                if (!confirmed(a, 1)) return confirm(sender, "This takes ${target.name} out of their match (restored and moved back). Before the fight starts it cancels the match.", "/lifesteal admin ranked remove ${target.name} confirm")
                ranked.removeParticipant(sender, target)?.let { return Msg.error(sender, it) }
                Msg.success(sender, "${target.name} was taken out of their ranked match.")
            }
            "restore" -> {
                val name = a.getOrNull(0) ?: return Msg.usage(sender, "/lifesteal admin ranked restore <player>")
                val target = Bukkit.getPlayerExact(name) ?: return Msg.playerNotFound(sender, name)
                ranked.adminRestore(target)?.let { return Msg.error(sender, it) }
                Msg.success(sender, "Restored ${target.name}'s pre-match inventory.")
            }
            "spectate" -> {
                val player = sender as? Player ?: return Msg.playersOnly(sender)
                val id = a.getOrNull(0) ?: return Msg.usage(sender, "/lifesteal admin ranked spectate <match>")
                ranked.spectate(player, id)?.let { return Msg.error(sender, it) }
                Msg.success(sender, "Spectating $id. You can't be hurt or pick anything up. /lifesteal admin ranked unspectate to stop.")
            }
            "unspectate" -> {
                val player = sender as? Player ?: return Msg.playersOnly(sender)
                ranked.unspectate(player)?.let { return Msg.error(sender, it) }
                Msg.success(sender, "Stopped spectating.")
            }
            // Issue #1363: matchmaking diagnostics (memory only, except queuelog).
            "queue" -> queueOverview(sender)
            "cancelqueue" -> {
                val id = a.getOrNull(0) ?: return Msg.usage(sender, "/lifesteal admin ranked cancelqueue <entry|team> [confirm]")
                if (!confirmed(a, 1)) return confirm(sender, "This ends queue entry/team '$id' (its players are told; nothing happens to inventories or hearts).", "/lifesteal admin ranked cancelqueue $id confirm")
                plugin.lifestealRankedQueueManager.adminCancelEntry(sender.name, id)?.let { return Msg.error(sender, it) }
                Msg.success(sender, "Queue entry '$id' cancelled.")
            }
            "cancelproposal" -> {
                val id = a.getOrNull(0) ?: return Msg.usage(sender, "/lifesteal admin ranked cancelproposal <proposal> [confirm]")
                if (!confirmed(a, 1)) return confirm(sender, "This ends found match '$id' and both of its queue entries (players can queue again at once).", "/lifesteal admin ranked cancelproposal $id confirm")
                plugin.lifestealRankedQueueManager.adminCancelProposal(sender.name, id)?.let { return Msg.error(sender, it) }
                Msg.success(sender, "Proposal '$id' cancelled.")
            }
            "rating" -> rating.execute(sender, a)
            "queuelog" -> {
                val rows = try { plugin.lifestealRankedQueueManager.recentAudit(15) } catch (e: Exception) { return Msg.error(sender, "Couldn't read the queue log: ${e.message}") }
                sender.sendMessage(Component.text("Ranked queue log (newest first)", NamedTextColor.GOLD, TextDecoration.BOLD))
                if (rows.isEmpty()) sender.sendMessage(Component.text("Nothing logged yet.", NamedTextColor.GRAY))
                for (r in rows) sender.sendMessage(Component.text("${time(r.at)} ${r.kind} ${r.ref ?: ""} ${r.uuid?.let { plugin.lifestealTeamManager.nameOf(it) } ?: ""} ${r.detail ?: ""}".replace(Regex(" +"), " ").trim(), NamedTextColor.GRAY))
            }
            else -> Msg.usage(sender, RANKED_USAGE)
        }
    }

    private fun listMatches(sender: CommandSender) {
        val open = ranked.openMatches()
        sender.sendMessage(Component.text("Ranked matches", NamedTextColor.GOLD, TextDecoration.BOLD))
        if (open.isEmpty()) sender.sendMessage(Component.text("No match is running.", NamedTextColor.GRAY))
        for (m in open) {
            val fighting = Side.entries.joinToString(" - ") { m.roster.active(it).size.toString() }
            sender.sendMessage(Component.text("${m.id} ${m.state} ${m.label} in ${m.arena.id}, fighting $fighting", NamedTextColor.YELLOW))
        }
        val recent = try { ranked.recentMatches(8) } catch (e: Exception) { return Msg.error(sender, "Couldn't read the match records: ${e.message}") }
        if (recent.isNotEmpty()) sender.sendMessage(Component.text("Recent records", NamedTextColor.GOLD))
        for (r in recent) {
            val result = when {
                r.outcome == "WIN" -> "won by ${if (r.winner == Side.A) r.teamAName else r.teamBName}"
                r.outcome == "CANCELLED" -> "cancelled: ${r.cancelReason ?: "?"}"
                else -> r.state?.name ?: "?"
            }
            sender.sendMessage(Component.text("${r.id} ${time(r.createdAt)} ${r.teamAName} vs ${r.teamBName} ${r.size}v${r.size} ${r.kind.name}: $result (restore ${r.restoration})", NamedTextColor.GRAY))
        }
    }

    private fun queueOverview(sender: CommandSender) {
        val q = plugin.lifestealRankedQueueManager
        val teams = plugin.lifestealTeamManager
        val now = System.currentTimeMillis()
        fun secs(ms: Long) = if (ms <= 0) 0 else (ms + 999) / 1000
        sender.sendMessage(Component.text("Ranked matchmaking", NamedTextColor.GOLD, TextDecoration.BOLD))
        line(sender, "State", q.unavailableReason(now) ?: "open", if (q.unavailableReason(now) == null) NamedTextColor.GREEN else NamedTextColor.RED)
        line(sender, "Queues", q.allowedSizes.joinToString { "${it}v$it: ${q.waitingCount(it)} waiting" }.ifEmpty { "none" })
        line(sender, "Reserved arenas", (q.reservedArenas().map { "$it (proposal)" } + ranked.heldArenaIds().map { "$it (match)" }).joinToString().ifEmpty { "none" })
        line(sender, "Cooldowns", q.cooldownCounts())
        val entries = q.entries()
        if (entries.isEmpty()) sender.sendMessage(Component.text("No queue entries.", NamedTextColor.GRAY))
        for (e in entries) {
            val roster = e.participants.joinToString { teams.nameOf(it) + if (e.state == com.liam.joshymc.lifesteal.ranked.queue.QueueState.FORMING && it !in e.consented) "?" else "" }
            val age = secs(now - e.stateSince)
            sender.sendMessage(Component.text("${e.id} ${e.kind} ${e.teamName} ${e.size}v${e.size} ${e.state} ${age}s [$roster]${e.problem?.let { " paused: $it" } ?: ""}", if (age > STUCK_SECONDS && e.state != com.liam.joshymc.lifesteal.ranked.queue.QueueState.WAITING) NamedTextColor.RED else NamedTextColor.YELLOW))
        }
        for (p in q.proposals()) {
            val a = q.entry(p.entryA)?.teamName ?: p.entryA
            val b = q.entry(p.entryB)?.teamName ?: p.entryB
            sender.sendMessage(Component.text("${p.id} ${p.kind} $a vs $b ${p.size}v${p.size} ${p.state} arena ${p.arenaId}, accepted ${p.accepted.size}/${p.participants.size}, ${secs(p.expiresAt - now)}s left", NamedTextColor.AQUA))
        }
        for (c in q.challenges()) {
            sender.sendMessage(Component.text("${c.id} challenge ${c.fromTeamName} -> ${c.toTeamName} ${c.size}v${c.size} ${c.state}${if (c.state == com.liam.joshymc.lifesteal.ranked.queue.ChallengeState.PENDING) ", ${secs(c.expiresAt - now)}s left" else ""}", NamedTextColor.LIGHT_PURPLE))
        }
        sender.sendMessage(Component.text("Red = possibly stuck. /lifesteal admin ranked cancelqueue <entry|team> | cancelproposal <id> | queuelog", NamedTextColor.DARK_GRAY))
    }

    private fun matchInfo(sender: CommandSender, id: String) {
        val record = try { ranked.record(id) } catch (e: Exception) { return Msg.error(sender, "Couldn't read the match record: ${e.message}") }
            ?: return Msg.error(sender, "No match '$id'.")
        val live = ranked.match(id)
        sender.sendMessage(Component.text("Ranked match ${record.id}${if (record.test) " (TEST)" else ""}", NamedTextColor.GOLD, TextDecoration.BOLD))
        line(sender, "State", (live?.state ?: record.state)?.name ?: "?")
        line(sender, "Kind", "${record.kind.label}${record.queueRef?.let { " ($it)" } ?: ""}")
        line(sender, "Season", record.seasonId)
        line(sender, "Arena", record.arenaId)
        line(sender, "Teams", "A ${record.teamAName} (${record.teamAId}) vs B ${record.teamBName} (${record.teamBId}), ${record.size}v${record.size}")
        line(sender, "Created", "${time(record.createdAt)} by ${record.createdBy}")
        line(sender, "Started / ended", "${record.startedAt?.let(::time) ?: "-"} / ${record.endedAt?.let(::time) ?: "-"}")
        line(sender, "Outcome", when (record.outcome) {
            "WIN" -> "won by ${if (record.winner == Side.A) record.teamAName else record.teamBName}"
            "CANCELLED" -> "cancelled: ${record.cancelReason}"
            else -> "not over"
        })
        line(sender, "Inventory restoration", record.restoration)
        val snapshots = try { ranked.recordSnapshots(id).associateBy { it.uuid } } catch (_: Exception) { emptyMap() }
        for (p in try { ranked.recordParticipants(id) } catch (_: Exception) { emptyList() }) {
            val liveStatus = live?.roster?.of(p.uuid)
            val status = liveStatus?.status ?: p.status
            val reason = liveStatus?.defeatReason ?: p.defeatReason
            val name = Bukkit.getPlayer(p.uuid)?.name ?: plugin.lifestealTeamManager.nameOf(p.uuid)
            sender.sendMessage(Component.text("  ${p.side.name} $name: $status${reason?.let { " (${it.label})" } ?: ""}, snapshot ${snapshots[p.uuid]?.status ?: "?"}", NamedTextColor.GRAY))
        }
        live?.spectators?.takeIf { it.isNotEmpty() }?.let { s -> line(sender, "Spectators", s.joinToString { Bukkit.getPlayer(it)?.name ?: it.toString() }) }
        val events = try { ranked.recordEvents(id, 8) } catch (_: Exception) { emptyList() }
        for (e in events) sender.sendMessage(Component.text("  ${time(e.at)} ${e.kind} ${e.detail ?: ""}".trimEnd(), NamedTextColor.DARK_GRAY))
    }

    // ── Helpers ─────────────────────────────────────────────

    private fun pointOf(player: Player): Point {
        val l = player.location
        return Point(l.blockX + 0.5, l.blockY.toDouble(), l.blockZ + 0.5, Math.round(l.yaw).toFloat(), 0f)
    }

    private fun describe(p: Point): String = "(${p.blockX}, ${p.blockY}, ${p.blockZ})"

    private fun confirmed(args: List<String>, index: Int): Boolean = args.size == index + 1 && args[index].equals("confirm", ignoreCase = true)

    private fun confirm(sender: CommandSender, preview: String, command: String) {
        sender.sendMessage(Component.text(preview, NamedTextColor.YELLOW))
        sender.sendMessage(Component.text("Run $command to do it.", NamedTextColor.GRAY))
    }

    private fun report(sender: CommandSender, result: ArenaResult, confirmCommand: String?) {
        when (result) {
            is ArenaResult.Done -> Msg.success(sender, result.message)
            is ArenaResult.Refused -> Msg.error(sender, result.reason)
            is ArenaResult.NeedsConfirmation -> confirm(sender, result.preview, confirmCommand ?: "the same command with confirm")
        }
    }

    private fun line(sender: CommandSender, label: String, value: String, color: NamedTextColor = NamedTextColor.WHITE) {
        sender.sendMessage(Component.text("$label: ", NamedTextColor.GRAY).append(Component.text(value, color)))
    }

    private fun availabilityColor(a: ArenaAvailability): NamedTextColor = when (a) {
        ArenaAvailability.AVAILABLE -> NamedTextColor.GREEN
        ArenaAvailability.DISABLED -> NamedTextColor.RED
        else -> NamedTextColor.YELLOW
    }

    private fun time(millis: Long): String = TIME.format(Instant.ofEpochMilli(millis))

    fun complete(sender: CommandSender, args: List<String>): List<String> {
        if (!visibleTo(sender)) return emptyList()
        val admin = sender.hasPermission(LifestealRankedManager.ADMIN_PERMISSION)
        val group = args.getOrNull(0)?.lowercase()
        fun filter(options: List<String>, typed: String) = options.filter { it.startsWith(typed.lowercase()) }
        if (group == "ranked" && admin && args.size >= 3 && args[1].equals("rating", true)) return rating.complete(sender, args.drop(2))
        return when (args.size) {
            2 -> when (group) {
                "arena" -> if (admin) filter(ARENA_SUBS, args[1]) else emptyList()
                "ranked" -> filter(if (admin) RANKED_SUBS else listOf("spectate", "unspectate"), args[1])
                else -> emptyList()
            }
            3 -> when {
                group == "arena" && admin && args[1].lowercase() != "create" && args[1].lowercase() != "list" -> filter(ranked.arenaList().map { it.arena.id }, args[2])
                group == "ranked" && args[1].lowercase() in setOf("info", "cancel", "spectate") -> filter(ranked.openMatches().map { it.id }, args[2])
                group == "ranked" && admin && args[1].lowercase() in setOf("remove", "restore") -> filter(Bukkit.getOnlinePlayers().map { it.name }, args[2])
                group == "ranked" && admin && args[1].equals("testmatch", true) -> filter(plugin.lifestealTeamManager.allTeams().map { it.name }, args[2])
                group == "ranked" && admin && args[1].equals("cancelqueue", true) -> filter(plugin.lifestealRankedQueueManager.entries().flatMap { listOf(it.id, it.teamName) }, args[2])
                group == "ranked" && admin && args[1].equals("cancelproposal", true) -> filter(plugin.lifestealRankedQueueManager.proposals().map { it.id }, args[2])
                else -> emptyList()
            }
            4 -> when {
                group == "arena" && args[1].lowercase() in setOf("setspawn") -> filter(listOf("a", "b"), args[3])
                group == "arena" && args[1].lowercase() == "clearspawns" -> filter(listOf("a", "b", "spectator"), args[3])
                group == "arena" && args[1].lowercase() == "setcorner" -> filter(listOf("1", "2"), args[3])
                group == "arena" && args[1].lowercase() == "sizes" -> filter(listOf("2,3,4,5", "all"), args[3])
                group == "arena" && args[1].lowercase() == "delete" -> filter(listOf("confirm"), args[3])
                group == "ranked" && args[1].lowercase() in setOf("cancel", "remove", "cancelqueue", "cancelproposal") -> filter(listOf("confirm"), args[3])
                group == "ranked" && admin && args[1].equals("testmatch", true) -> filter(plugin.lifestealTeamManager.allTeams().map { it.name }, args[3])
                else -> emptyList()
            }
            5 -> if (group == "ranked" && args[1].equals("testmatch", true)) filter(listOf("2", "3", "4", "5"), args[4]) else emptyList()
            6 -> if (group == "ranked" && args[1].equals("testmatch", true)) filter(ranked.arenaList().map { it.arena.id }, args[5]) else emptyList()
            else -> emptyList()
        }
    }

    companion object {
        const val ARENA_USAGE = "/lifesteal admin arena <create|delete|list|info|setname|setcorner|setspawn|setspectator|clearspawns|sizes|enable|disable> ..."
        const val RANKED_USAGE = "/lifesteal admin ranked <testmatch <teamA> <teamB> <size> [arena]|list|info|cancel|remove|restore|spectate|unspectate|queue|cancelqueue|cancelproposal|queuelog|rating> ..."
        private val ARENA_SUBS = listOf("create", "delete", "list", "info", "setname", "setcorner", "setspawn", "setspectator", "clearspawns", "sizes", "enable", "disable")
        private val RANKED_SUBS = listOf("testmatch", "list", "info", "cancel", "remove", "restore", "spectate", "unspectate", "queue", "cancelqueue", "cancelproposal", "queuelog", "rating")
        /** A queue entry this long in a state other than WAITING is shown as possibly stuck. */
        private const val STUCK_SECONDS = 180L
        private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss'Z'").withZone(ZoneOffset.UTC)
    }
}
