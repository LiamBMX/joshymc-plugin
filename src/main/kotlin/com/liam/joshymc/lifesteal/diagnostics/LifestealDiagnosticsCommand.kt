package com.liam.joshymc.lifesteal.diagnostics

import com.liam.joshymc.Joshymc
import com.liam.joshymc.lifesteal.diagnostics.LifestealDiagnostics.Line
import com.liam.joshymc.lifesteal.diagnostics.LifestealDiagnostics.Tone
import com.liam.joshymc.lifesteal.ranked.LifestealRankedManager
import com.liam.joshymc.staff.AdminAudit
import com.liam.joshymc.util.BedrockUtil
import com.liam.joshymc.util.Msg
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.util.UUID

/**
 * `/lifesteal admin diag player <player|uuid>` and `/lifesteal admin diag health` (issue #1564):
 * read-only Lifesteal diagnostics for staff, under the existing `/lifesteal admin` structure.
 *
 * Memory state (combat tag, ranked hold, progression, manager states) is read on the main thread
 * through the managers' existing read accessors; stored state is read by [LifestealDiagnosticsStore]
 * on the database thread (`databaseManager.async`), and the reply goes back on the main thread to
 * whoever asked (resolved again by UUID, so nothing keeps a Player). No task repeats, nothing is
 * cached, and nothing here can change hearts, inventories, Elo, coins, matches or progression.
 * Player lookups are audited (`LIFESTEAL_DIAG_PLAYER`).
 */
class LifestealDiagnosticsCommand(private val plugin: Joshymc) {

    private val store by lazy { LifestealDiagnosticsStore(plugin.databaseManager) }

    private val hosted: Boolean get() = runCatching { plugin.lifestealHeartManager.hosted }.getOrDefault(false)

    fun visibleTo(sender: CommandSender): Boolean = sender.hasPermission(LifestealDiagnostics.PERMISSION) && hosted

    /** [args] starts after `diag`. */
    fun execute(sender: CommandSender, args: List<String>) {
        LifestealDiagnostics.accessProblem(sender.hasPermission(LifestealDiagnostics.PERMISSION), hosted)?.let {
            return if (!sender.hasPermission(LifestealDiagnostics.PERMISSION)) Msg.noPermission(sender) else Msg.error(sender, it)
        }
        when (args.firstOrNull()?.lowercase()) {
            "player" -> if (args.size == 2) player(sender, args[1]) else Msg.usage(sender, "/lifesteal admin diag player <player|uuid>")
            "health" -> if (args.size == 1) health(sender) else Msg.usage(sender, "/lifesteal admin diag health")
            else -> Msg.usage(sender, USAGE)
        }
    }

    fun complete(sender: CommandSender, args: List<String>): List<String> {
        if (!visibleTo(sender)) return emptyList()
        return when (args.size) {
            1 -> listOf("player", "health").filter { it.startsWith(args[0].lowercase()) }
            2 -> if (args[0].equals("player", true)) Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(args[1], ignoreCase = true) } else emptyList()
            else -> emptyList()
        }
    }

    private fun player(sender: CommandSender, input: String) {
        val uuid = runCatching { UUID.fromString(input) }.getOrNull()
        val resolved = if (uuid != null) null else plugin.playerResolver.resolve(input)
        val target = uuid ?: resolved?.uniqueId ?: return Msg.playerNotFound(sender, input)
        val name = Bukkit.getPlayer(target)?.name ?: resolved?.name ?: plugin.playerResolver.lastKnownName(target) ?: input

        val self = (sender as? Player)?.uniqueId == target
        val protection = if (self) null else runCatching { plugin.staffRankManager.protectionProblem(sender, target) }
            .getOrElse { "Staff ranks couldn't be checked, so this lookup is refused." }
        LifestealDiagnostics.targetProblem(self, protection)?.let { return Msg.error(sender, it) }

        val live = live(target, name)
        val now = System.currentTimeMillis()
        if (!plugin.databaseManager.isOpen) {
            return send(sender, LifestealDiagnostics.playerLines(live, null, now, "database closed"))
        }
        val reply = replyTarget(sender)
        reply(sender, "Reading stored state for $name...", NamedTextColor.DARK_GRAY)
        plugin.databaseManager.async("lifesteal diagnostics player") {
            val lines = try {
                LifestealDiagnostics.playerLines(live, store.player(target), now)
            } catch (e: Exception) {
                LifestealDiagnostics.playerLines(live, null, now, "couldn't be read (${e.message})")
            }
            AdminAudit.log(plugin, sender, "LIFESTEAL_DIAG_PLAYER", target, name)
            deliver(reply, lines)
        }
    }

    private fun health(sender: CommandSender) {
        val live = healthLive()
        if (!live.databaseOpen) return send(sender, LifestealDiagnostics.healthLines(live, null, "closed"))
        val reply = replyTarget(sender)
        plugin.databaseManager.async("lifesteal diagnostics health") {
            val lines = try {
                LifestealDiagnostics.healthLines(live, store.health())
            } catch (e: Exception) {
                LifestealDiagnostics.healthLines(live, null, "couldn't be read (${e.message})")
            }
            deliver(reply, lines)
        }
    }

    // ── Memory state (main thread, existing read accessors only) ──

    private fun live(uuid: UUID, name: String): LifestealDiagnostics.Live {
        val unavailable = mutableListOf<String>()
        fun <T> read(label: String, block: () -> T): T? = try {
            block()
        } catch (_: Exception) {
            unavailable += label
            null
        }
        val online = Bukkit.getPlayer(uuid)
        val now = System.currentTimeMillis()
        val match = read("ranked") { plugin.lifestealRankedManager.matchOf(uuid)?.let { view(it) } }
        return LifestealDiagnostics.Live(
            uuid = uuid,
            name = name,
            online = online != null,
            bedrock = BedrockUtil.isBedrockPlayer(uuid),
            world = online?.world?.name,
            inLifestealWorld = online?.let { p -> read("hearts") { plugin.lifestealHeartManager.isLifesteal(p) } },
            loadedHearts = read("hearts") { plugin.lifestealHeartManager.heartsOf(uuid) },
            combatTagMillisLeft = read("combat tag") { plugin.combatManager.tagExpiresAt(uuid)?.let { it - now } },
            rankedMatch = match,
            spectatingMatch = read("ranked") { plugin.lifestealRankedManager.spectatorMatch(uuid)?.id },
            pendingRestoreFlag = read("ranked") { plugin.lifestealRankedManager.hasPendingRestore(uuid) },
            progressionPhase = read("progression") { plugin.lifestealProgressionManager.progress(now).phase.label },
            unavailable = unavailable.distinct(),
        )
    }

    private fun healthLive(): LifestealDiagnostics.HealthLive {
        val unavailable = mutableListOf<String>()
        fun <T> read(label: String, block: () -> T): T? = try {
            block()
        } catch (_: Exception) {
            unavailable += label
            null
        }
        val progression = read("progression") { plugin.lifestealProgressionManager }
        return LifestealDiagnostics.HealthLive(
            heartsActive = read("hearts") { plugin.lifestealHeartManager.active },
            rankedActive = read("ranked") { plugin.lifestealRankedManager.active },
            openMatches = read("ranked") { plugin.lifestealRankedManager.openMatches().map { view(it) } },
            combatLoggingActive = read("combat logging") { plugin.lifestealCombatManager.active },
            combatNpcsStanding = read("combat NPCs") { plugin.lifestealCombatNpcManager.standing },
            bankReady = read("bank") { plugin.lifestealBankManager.ready },
            bountiesProcessing = read("bounties") { plugin.lifestealBountyManager.processing },
            configStatus = read("lifesteal.yml") { plugin.lifestealConfigManager.status.label },
            progressionStatus = progression?.let { p -> read("progression") { p.status.name } },
            progressionPhase = progression?.let { p -> read("progression") { p.progress().phase.label } },
            progressionProblem = progression?.let { p -> read("progression") { p.problem } },
            databaseOpen = plugin.databaseManager.isOpen,
            unavailable = unavailable.distinct(),
        )
    }

    private fun view(match: LifestealRankedManager.Match) =
        LifestealDiagnostics.MatchView(match.id, match.state.name, match.kind.name, match.state.terminal)

    // ── Replies ──

    /** Who to answer: a player by UUID (looked up again, so a quit player just gets nothing), else the console. */
    private fun replyTarget(sender: CommandSender): UUID? = (sender as? Player)?.uniqueId

    private fun deliver(player: UUID?, lines: List<Line>) {
        if (!plugin.isEnabled) return
        Bukkit.getScheduler().runTask(plugin, Runnable {
            val to: CommandSender = if (player == null) Bukkit.getConsoleSender() else Bukkit.getPlayer(player) ?: return@Runnable
            send(to, lines)
        })
    }

    private fun send(sender: CommandSender, lines: List<Line>) {
        for (line in lines) reply(sender, line.text, color(line.tone))
    }

    private fun reply(sender: CommandSender, text: String, color: NamedTextColor) = Msg.reply(sender, Component.text(text, color))

    private fun color(tone: Tone): NamedTextColor = when (tone) {
        Tone.HEADER -> NamedTextColor.GOLD
        Tone.OK -> NamedTextColor.GREEN
        Tone.INFO -> NamedTextColor.GRAY
        Tone.WARN -> NamedTextColor.YELLOW
        Tone.BAD -> NamedTextColor.RED
    }

    companion object {
        const val USAGE = "/lifesteal admin diag <player <player|uuid>|health>"
    }
}
