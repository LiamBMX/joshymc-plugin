package com.liam.joshymc.lifesteal.teams

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gamemode.ServerGamemode
import com.liam.joshymc.lifesteal.LifestealSettings
import com.liam.joshymc.lifesteal.teams.LifestealTeamStore.AcceptResult
import com.liam.joshymc.lifesteal.teams.LifestealTeamStore.Actor
import com.liam.joshymc.lifesteal.teams.LifestealTeamStore.CreateResult
import com.liam.joshymc.lifesteal.teams.LifestealTeamStore.DenyResult
import com.liam.joshymc.lifesteal.teams.LifestealTeamStore.DisbandResult
import com.liam.joshymc.lifesteal.teams.LifestealTeamStore.Invite
import com.liam.joshymc.lifesteal.teams.LifestealTeamStore.InviteResult
import com.liam.joshymc.lifesteal.teams.LifestealTeamStore.KickResult
import com.liam.joshymc.lifesteal.teams.LifestealTeamStore.LeaveResult
import com.liam.joshymc.lifesteal.teams.LifestealTeamStore.Team
import com.liam.joshymc.lifesteal.teams.LifestealTeamStore.TransferResult
import com.liam.joshymc.util.Msg
import com.liam.joshymc.util.ProfanityFilter
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.plugin.IllegalPluginAccessException
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/**
 * Lifesteal teams (issue #1346). Runs only where this server hosts Lifesteal
 * (`networkIdentityManager.hostedGamemodes`); on a Survival server it creates no tables, starts no
 * task and every action says Lifesteal teams don't exist here. The Survival `TeamManager` and its
 * tables are never touched; `/team` reaches this system only through [com.liam.joshymc.command.TeamCommandRouter]
 * for senders in a Lifesteal world.
 *
 * The database ([LifestealTeamStore]) is the only source of truth for every change: each one is a
 * transaction that re-reads and re-checks the current rows. Changes run off the main thread on one
 * worker ([executor]), one after another; the result comes back to the main thread, which updates
 * the in-memory cache and tells the players involved. The cache only serves reads (info, lists,
 * GUIs and the [sameTeam]-style API for later systems) so displays never query the database.
 *
 * Permissions and the Lifesteal check are inside every action here, so the command, the GUI and
 * any future caller get the same server-side checks. Team leadership is never a staff permission:
 * `joshymc.lifesteal.team.admin` only unlocks the explicit `/team admin` actions.
 */
class LifestealTeamManager(private val plugin: Joshymc) {

    private val store = LifestealTeamStore(plugin.databaseManager)

    @Volatile var settings: LifestealSettings.Teams? = null
        private set
    @Volatile private var tablesReady = false

    // Read cache (main-thread writes, any-thread reads).
    private val teams = ConcurrentHashMap<String, Team>()
    private val byPlayer = ConcurrentHashMap<UUID, String>()
    private val byName = ConcurrentHashMap<String, String>()
    private val invites = ConcurrentHashMap<String, Invite>()
    /** Disbanded team id → its final version, so a late, older snapshot can't bring it back. */
    private val retired = ConcurrentHashMap<String, Long>()
    private val names = ConcurrentHashMap<UUID, String>()
    /** Who left which team within the combat-tag duration (issue #1348, team PvP). */
    private val combatMemory = TeamCombatMemory()

    @Volatile private var executor: ExecutorService? = null
    private var sweepTask: BukkitTask? = null

    /** Actors with a change in flight (one at a time each), and their last change time. */
    private val busy = ConcurrentHashMap.newKeySet<UUID>()
    private val lastAction = ConcurrentHashMap<UUID, Long>()

    /** This server runs Lifesteal (fixed per reload). */
    val hosted: Boolean get() = ServerGamemode.LIFESTEAL in plugin.networkIdentityManager.hostedGamemodes

    /** Hosted, lifesteal.yml valid, teams enabled and tables ready. */
    val active: Boolean get() = hosted && tablesReady && settings?.enabled == true && executor != null

    val maxSize: Int get() = LifestealTeamStore.effectiveMax(settings?.maxSize ?: com.liam.joshymc.lifesteal.LifestealRules.MAX_TEAM_MEMBERS)

    fun isLifesteal(player: Player): Boolean = plugin.gamemodeLockManager.gamemodeOf(player.world) == ServerGamemode.LIFESTEAL

    // ── Lifecycle ───────────────────────────────────────────

    /** Startup and every reload, after lifestealConfigManager.load() and the gamemode locks. */
    fun refresh() {
        stopWorker()
        tablesReady = false
        clearCache()
        val config = plugin.lifestealConfigManager.settings
        if (!hosted) {
            settings = null
            return
        }
        if (config == null) {
            settings = null
            plugin.logger.severe("[Teams] lifesteal.yml is invalid, so Lifesteal teams are paused (stored teams are kept as they are).")
            return
        }
        settings = config.teams
        if (!config.teams.enabled) {
            plugin.logger.info("[Teams] Lifesteal teams are turned off in lifesteal.yml (teams.enabled: false); stored teams are kept.")
            return
        }
        try {
            store.createTables()
            tablesReady = true
            val now = System.currentTimeMillis()
            store.expireInvites(now)
            for (team in store.loadActiveTeams()) applyTeam(team)
            for (invite in store.invites()) invites[inviteKey(invite)] = invite
            for (uuid in byPlayer.keys) names.computeIfAbsent(uuid) { plugin.playerResolver.lastKnownName(it) ?: "" }
            for (player in Bukkit.getOnlinePlayers()) names[player.uniqueId] = player.name
        } catch (e: Exception) {
            tablesReady = false
            clearCache()
            plugin.logger.severe("[Teams] Could not load Lifesteal teams, so teams are paused: ${e.message}")
            return
        }
        executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "JoshyMC-LifestealTeams").apply { isDaemon = true } }
        sweepTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { sweepInvites() }, 20L, 20L)
        plugin.logger.info("[Teams] Lifesteal teams on: ${teams.size} team(s), up to $maxSize players each, invitations last ${config.teams.inviteExpirationSeconds}s.")
    }

    /** Shutdown: lets changes already handed to the worker finish (before the database closes). */
    fun stop() {
        stopWorker()
        clearCache()
    }

    private fun stopWorker() {
        sweepTask?.cancel()
        sweepTask = null
        val worker = executor ?: return
        executor = null
        worker.shutdown()
        try {
            if (!worker.awaitTermination(WORKER_WAIT_SECONDS, TimeUnit.SECONDS)) {
                plugin.logger.warning("[Teams] A team change was still running after ${WORKER_WAIT_SECONDS}s; the database decides whether it was saved.")
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        tablesReady = false
    }

    private fun clearCache() {
        teams.clear(); byPlayer.clear(); byName.clear(); invites.clear(); retired.clear(); combatMemory.clear()
        busy.clear(); lastAction.clear()
    }

    fun onJoin(player: Player) {
        names[player.uniqueId] = player.name
        if (!active || !isLifesteal(player)) return
        val pending = invitesFor(player.uniqueId)
        if (pending.isNotEmpty()) {
            send(player, text("You have ${pending.size} pending team invitation(s). ", NamedTextColor.GRAY).append(command("[View]", "/team")))
        }
    }

    fun onQuit(player: Player) {
        lastAction.remove(player.uniqueId)
    }

    // ── Read API (cache only; for commands, GUIs and future systems such as Ranked Team Wars) ──

    fun team(id: String): Team? = teams[id]
    fun teamOf(uuid: UUID): Team? = byPlayer[uuid]?.let { teams[it] }
    fun teamByName(name: String): Team? = byName[TeamNames.canonical(name)]?.let { teams[it] }
    fun isInTeam(uuid: UUID): Boolean = teamOf(uuid) != null
    fun isLeader(uuid: UUID): Boolean = teamOf(uuid)?.leader == uuid
    /** Whether two different players are in the same Lifesteal team right now. */
    fun sameTeam(a: UUID, b: UUID): Boolean = a != b && byPlayer[a]?.let { it == byPlayer[b] } == true
    fun hasFreeSlot(teamId: String): Boolean = teams[teamId]?.hasRoom(maxSize) == true
    /** The current roster of [teamId] (UUIDs, leader first), e.g. for validating a ranked roster. */
    fun roster(teamId: String): List<UUID> = teams[teamId]?.members?.map { it.uuid }.orEmpty()
    fun allTeams(): List<Team> = teams.values.sortedBy { it.canonicalName }
    fun invitesFor(uuid: UUID, now: Long = System.currentTimeMillis()): List<Invite> =
        invites.values.filter { it.target == uuid && !it.expired(now) && teams.containsKey(it.teamId) }.sortedBy { it.createdAt }
    fun invitesFrom(teamId: String, now: Long = System.currentTimeMillis()): List<Invite> =
        invites.values.filter { it.teamId == teamId && !it.expired(now) }.sortedBy { it.createdAt }

    fun nameOf(uuid: UUID): String =
        Bukkit.getPlayer(uuid)?.name ?: names[uuid]?.takeIf { it.isNotEmpty() } ?: Bukkit.getOfflinePlayer(uuid).name ?: uuid.toString().take(8)

    fun isOnline(uuid: UUID): Boolean = Bukkit.getPlayer(uuid)?.isOnline == true

    // ── Team PvP (issue #1348; memory only, cheap enough for every damage event) ──

    /** `lifesteal.yml > teams.friendly-fire`, or null while lifesteal.yml can't be read here. */
    fun combatPolicy(): TeamCombatRules.Policy? =
        settings?.takeIf { hosted }?.let { TeamCombatRules.Policy(it.friendlyFire, it.teammateHeartTransfers) }

    /**
     * Whether membership can be read: the cache is loaded, or teams are turned off in lifesteal.yml
     * (then nobody counts as a teammate). False when loading failed: never "different teams".
     */
    private val membershipAvailable: Boolean
        get() {
            val config = settings ?: return false
            return hosted && (!config.enabled || active)
        }

    /** How two players relate for combat and heart stealing right now (see [TeamCombatRules.relation]). */
    fun combatRelation(a: UUID, b: UUID, now: Long = System.currentTimeMillis()): TeamCombatRules.Relation =
        TeamCombatRules.relation(a, b, membershipAvailable, { byPlayer[it] }, { combatMemory.recent(it, now) })

    /** Heart-transfer eligibility of a kill between these two (see [TeamCombatRules.heartCheck]). */
    fun heartCheck(killer: UUID, victim: UUID): TeamCombatRules.HeartCheck =
        TeamCombatRules.heartCheck(combatRelation(killer, victim), combatPolicy())

    /**
     * The current team PvP policy in player words, read live from lifesteal.yml (so it follows a
     * reload). Shown by `/team info` and the team menu; players can't change it (only lifesteal.yml).
     */
    fun friendlyFireSummary(): String {
        val policy = combatPolicy() ?: return "Unknown (server configuration)"
        return when {
            !policy.friendlyFire -> "Off - teammates can't damage each other"
            policy.teammateHeartTransfers -> "On - teammates can damage each other and steal hearts"
            else -> "On - teammates can damage each other, but never steal hearts"
        }
    }

    /** The combat-tag duration: how long a former teammate still counts as one. */
    private fun graceMillis(): Long = plugin.combatManager.tagDurationMillis

    private fun inCombat(uuid: UUID): Boolean = Bukkit.getPlayer(uuid)?.let { it.isOnline && plugin.combatManager.isTagged(it) } == true

    private fun combatRefusal(change: TeamCombatRules.TeamChange, involved: Iterable<UUID>): String? =
        if (involved.any { inCombat(it) }) TeamCombatRules.combatMessage(change) else null

    // ── Checks shared by every action ───────────────────────

    /** Why the team system can't serve anyone right now, or null. */
    fun unavailableReason(): String? = when {
        !hosted -> "Lifesteal teams only exist on the Lifesteal server."
        settings == null -> "Lifesteal teams are paused right now (server configuration). Please tell staff."
        settings?.enabled != true -> "Lifesteal teams are turned off right now."
        !active -> "Lifesteal teams are unavailable right now. Please try again later."
        else -> null
    }

    /** Null when [player] may use ordinary team features here. */
    private fun playerProblem(player: Player): String? = unavailableReason() ?: when {
        !isLifesteal(player) -> "Lifesteal teams only work in Lifesteal."
        !player.hasPermission(PERMISSION_USE) -> Msg.NO_PERMISSION
        else -> null
    }

    private fun refuse(sender: CommandSender, message: String) = Msg.error(sender, message)

    // ── Actions ─────────────────────────────────────────────

    fun create(player: Player, rawName: String) {
        playerProblem(player)?.let { return refuse(player, it) }
        val config = settings ?: return
        if (!player.hasPermission(PERMISSION_CREATE)) return Msg.noPermission(player)
        if (!config.allowCreation) return refuse(player, "Creating new teams is turned off right now.")
        teamOf(player.uniqueId)?.let { return refuse(player, "You're already in ${it.name}. Leave it before creating a team.") }
        val check = TeamNames.validate(rawName, config.nameMinLength, config.nameMaxLength, config.reservedNames) { ProfanityFilter.contains(it) }
        val valid = when (check) {
            is TeamNames.Check.Invalid -> return refuse(player, check.reason)
            is TeamNames.Check.Valid -> check
        }
        if (byName.containsKey(valid.canonical)) return refuse(player, "A team called ${valid.display} already exists.")
        val uuid = player.uniqueId
        val name = player.name
        submit(player, uuid, { store.create(UUID.randomUUID().toString(), valid.display, uuid, name, System.currentTimeMillis()) }) { result ->
            when (result) {
                is CreateResult.Created -> {
                    applyTeam(result.team)
                    removeInvitesOf(uuid)
                    plugin.logger.info("[Teams] $name created Lifesteal team ${result.team.name} (${result.team.id}).")
                    send(player, text("Team ", NamedTextColor.GREEN).append(teamName(result.team)).append(text(" created! You're its leader. ", NamedTextColor.GREEN))
                        .append(suggest("[Invite a player]", "/team invite ")))
                }
                is CreateResult.NameTaken -> refuse(player, "A team called ${result.name} already exists.")
                is CreateResult.AlreadyInTeam -> {
                    result.team?.let { applyTeam(it) }
                    refuse(player, "You're already in a team. Leave it before creating one.")
                }
            }
        }
    }

    fun invite(player: Player, targetName: String) {
        playerProblem(player)?.let { return refuse(player, it) }
        val team = teamOf(player.uniqueId) ?: return refuse(player, "You're not in a team. Create one with /team create <name>.")
        if (team.leader != player.uniqueId) return refuse(player, "Only the team leader can invite players.")
        val target = plugin.playerResolver.online(targetName)
        if (target == null || !player.canSee(target)) return refuse(player, "$targetName isn't online.")
        if (target.uniqueId == player.uniqueId) return refuse(player, "You can't invite yourself.")
        if (!isLifesteal(target)) return refuse(player, "${target.name} isn't in Lifesteal right now.")
        if (team.isMember(target.uniqueId)) return refuse(player, "${target.name} is already in your team.")
        if (isInTeam(target.uniqueId)) return refuse(player, "${target.name} is already in another team.")
        if (!team.hasRoom(maxSize)) return refuse(player, "Your team is full (${team.size}/$maxSize).")
        val ttl = (settings?.inviteExpirationSeconds ?: 60) * 1000L
        val actor = Actor(player.uniqueId, player.name)
        val targetUuid = target.uniqueId
        submit(player, player.uniqueId, { store.invite(actor, targetUuid, maxSize, System.currentTimeMillis(), ttl) }) { result ->
            val name = nameOf(targetUuid)
            when (result) {
                is InviteResult.Invited -> {
                    invites[inviteKey(result.invite)] = result.invite
                    applyTeam(result.team)
                    val seconds = ttl / 1000
                    send(player, text("Invited ", NamedTextColor.GREEN).append(text(name, NamedTextColor.WHITE))
                        .append(text(" to ", NamedTextColor.GREEN)).append(teamName(result.team)).append(text(". It expires in ${seconds}s.", NamedTextColor.GREEN)))
                    Bukkit.getPlayer(targetUuid)?.let { invited ->
                        send(invited, text(player.name, NamedTextColor.WHITE).append(text(" invited you to join ", NamedTextColor.GRAY))
                            .append(teamName(result.team)).append(text(" (${result.team.size}/$maxSize). ", NamedTextColor.GRAY))
                            .append(command("[Accept]", "/team accept ${result.team.name}", NamedTextColor.GREEN)).append(text(" ", NamedTextColor.GRAY))
                            .append(command("[Deny]", "/team deny ${result.team.name}", NamedTextColor.RED)))
                        // Bedrock clients can't click chat: the typed commands, plainly.
                        send(invited, text("Type /team accept ${result.team.name} or /team deny ${result.team.name} within ${seconds}s.", NamedTextColor.GRAY))
                    }
                }
                is InviteResult.AlreadyInvited -> {
                    invites[inviteKey(result.invite)] = result.invite
                    val left = ((result.invite.expiresAt - System.currentTimeMillis()) / 1000).coerceAtLeast(1)
                    refuse(player, "$name already has a pending invitation from your team (${left}s left).")
                }
                InviteResult.NotInTeam -> refuse(player, "You're not in a team.")
                is InviteResult.NotLeader -> { applyTeam(result.team); refuse(player, "Only the team leader can invite players.") }
                InviteResult.Self -> refuse(player, "You can't invite yourself.")
                is InviteResult.AlreadyMember -> refuse(player, "$name is already in your team.")
                is InviteResult.TargetInOtherTeam -> { applyTeam(result.team); refuse(player, "$name is already in another team.") }
                is InviteResult.Full -> { applyTeam(result.team); refuse(player, "Your team is full (${result.team.size}/$maxSize).") }
            }
        }
    }

    /** [teamName] may be null when the player has exactly one pending invitation. */
    fun accept(player: Player, teamName: String?) {
        playerProblem(player)?.let { return refuse(player, it) }
        val name = teamName ?: run {
            val pending = invitesFor(player.uniqueId)
            when (pending.size) {
                0 -> return refuse(player, "You have no pending team invitations.")
                1 -> teams[pending[0].teamId]?.name ?: return refuse(player, "You have no pending team invitations.")
                else -> return refuse(player, "You have several invitations. Use /team accept <team>.")
            }
        }
        teamOf(player.uniqueId)?.let { return refuse(player, "You're already in ${it.name}. Leave it first.") }
        combatRefusal(TeamCombatRules.TeamChange.ACCEPT, listOf(player.uniqueId))?.let { return refuse(player, it) }
        val uuid = player.uniqueId
        val playerName = player.name
        submit(player, uuid, { store.accept(uuid, playerName, name, maxSize, System.currentTimeMillis()) }) { result ->
            when (result) {
                is AcceptResult.Joined -> {
                    applyTeam(result.team)
                    removeInvitesOf(uuid)
                    plugin.logger.info("[Teams] $playerName joined Lifesteal team ${result.team.name} (${result.team.id}).")
                    send(player, text("You joined ", NamedTextColor.GREEN).append(teamName(result.team)).append(text("!", NamedTextColor.GREEN)))
                    tellTeam(result.team, text(playerName, NamedTextColor.WHITE).append(text(" joined the team (${result.team.size}/$maxSize).", NamedTextColor.GREEN)), except = uuid)
                }
                AcceptResult.NoInvite -> refuse(player, "You don't have an invitation from $name.")
                is AcceptResult.Expired -> { invites.remove(inviteKey(result.invite)); refuse(player, "That invitation has expired. Ask for a new one.") }
                is AcceptResult.Full -> { applyTeam(result.team); refuse(player, "${result.team.name} is full (${result.team.size}/$maxSize).") }
                is AcceptResult.AlreadyInTeam -> { result.team?.let { applyTeam(it) }; refuse(player, "You're already in a team.") }
                AcceptResult.TeamGone -> refuse(player, "There's no team called $name any more.")
            }
        }
    }

    fun deny(player: Player, teamName: String?) {
        playerProblem(player)?.let { return refuse(player, it) }
        val name = teamName ?: run {
            val pending = invitesFor(player.uniqueId)
            when (pending.size) {
                0 -> return refuse(player, "You have no pending team invitations.")
                1 -> teams[pending[0].teamId]?.name ?: return refuse(player, "You have no pending team invitations.")
                else -> return refuse(player, "You have several invitations. Use /team deny <team>.")
            }
        }
        val uuid = player.uniqueId
        val playerName = player.name
        submit(player, uuid, { store.deny(uuid, playerName, name, System.currentTimeMillis()) }) { result ->
            when (result) {
                is DenyResult.Denied -> {
                    invites.remove(inviteKey(result.invite))
                    send(player, text("You declined the invitation from ", NamedTextColor.GRAY).append(text(result.team?.name ?: name, NamedTextColor.WHITE)).append(text(".", NamedTextColor.GRAY)))
                    Bukkit.getPlayer(result.invite.inviter)?.let { send(it, text(playerName, NamedTextColor.WHITE).append(text(" declined your team invitation.", NamedTextColor.RED))) }
                }
                is DenyResult.Expired -> { invites.remove(inviteKey(result.invite)); refuse(player, "That invitation had already expired.") }
                DenyResult.NoInvite -> refuse(player, "You don't have an invitation from $name.")
            }
        }
    }

    fun leave(player: Player) {
        playerProblem(player)?.let { return refuse(player, it) }
        val team = teamOf(player.uniqueId) ?: return refuse(player, "You're not in a team.")
        if (team.leader == player.uniqueId) return refuse(player, leaderLeaveMessage(team))
        combatRefusal(TeamCombatRules.TeamChange.LEAVE, listOf(player.uniqueId))?.let { return refuse(player, it) }
        val uuid = player.uniqueId
        val playerName = player.name
        submit(player, uuid, { store.leave(uuid, playerName, System.currentTimeMillis()) }) { result ->
            when (result) {
                is LeaveResult.Left -> {
                    applyTeam(result.team)
                    byPlayer.remove(uuid, result.team.id)
                    plugin.logger.info("[Teams] $playerName left Lifesteal team ${result.team.name} (${result.team.id}).")
                    send(player, text("You left ", NamedTextColor.YELLOW).append(teamName(result.team)).append(text(".", NamedTextColor.YELLOW)))
                    tellTeam(result.team, text(playerName, NamedTextColor.WHITE).append(text(" left the team.", NamedTextColor.YELLOW)))
                }
                LeaveResult.NotInTeam -> { byPlayer.remove(uuid); refuse(player, "You're not in a team.") }
                is LeaveResult.LeaderMustTransfer -> { applyTeam(result.team); refuse(player, leaderLeaveMessage(result.team)) }
            }
        }
    }

    private fun leaderLeaveMessage(team: Team): String =
        if (team.size > 1) "You lead ${team.name}. Transfer leadership first (/team transfer <player>), or disband it (/team disband)."
        else "You're the only member of ${team.name}. Use /team disband to close it."

    fun kick(player: Player, targetName: String) {
        playerProblem(player)?.let { return refuse(player, it) }
        val team = teamOf(player.uniqueId) ?: return refuse(player, "You're not in a team.")
        if (team.leader != player.uniqueId) return refuse(player, "Only the team leader can remove members.")
        val target = memberByName(team, targetName) ?: return refuse(player, "$targetName isn't in your team.")
        if (target == player.uniqueId) return refuse(player, "You can't remove yourself. Use /team leave or /team disband.")
        combatRefusal(TeamCombatRules.TeamChange.KICK, listOf(player.uniqueId, target))?.let { return refuse(player, it) }
        val actor = Actor(player.uniqueId, player.name)
        submit(player, player.uniqueId, { store.kick(actor, target, System.currentTimeMillis()) }) { result ->
            val name = nameOf(target)
            when (result) {
                is KickResult.Kicked -> {
                    applyTeam(result.team)
                    byPlayer.remove(target, result.team.id)
                    plugin.logger.info("[Teams] ${player.name} removed $name from Lifesteal team ${result.team.name} (${result.team.id}).")
                    tellTeam(result.team, text(name, NamedTextColor.WHITE).append(text(" was removed from the team.", NamedTextColor.YELLOW)))
                    Bukkit.getPlayer(target)?.let { send(it, text("You were removed from ", NamedTextColor.RED).append(teamName(result.team)).append(text(".", NamedTextColor.RED))) }
                }
                KickResult.NotInTeam -> refuse(player, "You're not in a team.")
                is KickResult.NotLeader -> { applyTeam(result.team); refuse(player, "Only the team leader can remove members.") }
                KickResult.Self -> refuse(player, "You can't remove yourself.")
                is KickResult.NotMember -> { applyTeam(result.team); refuse(player, "$name isn't in your team.") }
            }
        }
    }

    /** `/team transfer <player>` (and `/team promote`, the same thing: teams have no officers). */
    fun transfer(player: Player, targetName: String, confirmed: Boolean) {
        playerProblem(player)?.let { return refuse(player, it) }
        val team = teamOf(player.uniqueId) ?: return refuse(player, "You're not in a team.")
        if (team.leader != player.uniqueId) return refuse(player, "Only the team leader can transfer leadership.")
        val target = memberByName(team, targetName) ?: return refuse(player, "$targetName isn't in your team.")
        if (target == player.uniqueId) return refuse(player, "You're already the leader.")
        val name = nameOf(target)
        if (!confirmed) {
            return send(player, text("Make ", NamedTextColor.YELLOW).append(text(name, NamedTextColor.WHITE))
                .append(text(" the leader of ", NamedTextColor.YELLOW)).append(teamName(team))
                .append(text("? You'll become a regular member. ", NamedTextColor.YELLOW))
                .append(command("[Confirm]", "/team transfer $name confirm", NamedTextColor.GREEN))
                .append(text(" (or type /team transfer $name confirm)", NamedTextColor.GRAY)))
        }
        val actor = Actor(player.uniqueId, player.name)
        submit(player, player.uniqueId, { store.transfer(actor, null, target, System.currentTimeMillis()) }) { result ->
            handleTransfer(player, result, name, admin = false)
        }
    }

    fun disband(player: Player, confirmed: Boolean) {
        playerProblem(player)?.let { return refuse(player, it) }
        val team = teamOf(player.uniqueId) ?: return refuse(player, "You're not in a team.")
        if (team.leader != player.uniqueId) return refuse(player, "Only the team leader can disband the team.")
        if (settings?.allowDisbanding != true) return refuse(player, "Disbanding teams is turned off right now.")
        combatRefusal(TeamCombatRules.TeamChange.DISBAND, team.members.map { it.uuid })?.let { return refuse(player, it) }
        if (!confirmed) {
            return send(player, text("Disband ", NamedTextColor.RED).append(teamName(team))
                .append(text("? All ${team.size} member(s) lose their membership. This can't be undone. ", NamedTextColor.RED))
                .append(command("[Confirm]", "/team disband confirm", NamedTextColor.DARK_RED))
                .append(text(" (or type /team disband confirm)", NamedTextColor.GRAY)))
        }
        val actor = Actor(player.uniqueId, player.name)
        submit(player, player.uniqueId, { store.disband(actor, null, System.currentTimeMillis()) }) { result ->
            handleDisband(player, result, admin = false)
        }
    }

    // ── Admin ───────────────────────────────────────────────

    private fun adminProblem(sender: CommandSender): String? = unavailableReason() ?: when {
        !sender.hasPermission(PERMISSION_ADMIN) -> Msg.NO_PERMISSION
        sender is Player && !isLifesteal(sender) -> "Lifesteal teams only work in Lifesteal."
        else -> null
    }

    fun adminDisband(sender: CommandSender, teamName: String, confirmed: Boolean) {
        adminProblem(sender)?.let { return refuse(sender, it) }
        val team = teamByName(teamName) ?: return refuse(sender, "There's no team called $teamName.")
        if (!confirmed) {
            return Msg.reply(sender, text("Admin: disband ", NamedTextColor.RED).append(teamName(team))
                .append(text(" (${team.size} member(s))? Only its team data changes; hearts, inventories and balances are untouched. ", NamedTextColor.RED))
                .append(command("[Confirm]", "/team admin disband ${team.name} confirm", NamedTextColor.DARK_RED))
                .append(text(" (or type /team admin disband ${team.name} confirm)", NamedTextColor.GRAY)))
        }
        val actor = Actor((sender as? Player)?.uniqueId, sender.name, admin = true)
        submit(sender, actor.uuid ?: CONSOLE, { store.disband(actor, team.id, System.currentTimeMillis()) }) { result ->
            handleDisband(sender, result, admin = true)
        }
    }

    fun adminTransfer(sender: CommandSender, teamName: String, targetName: String, confirmed: Boolean) {
        adminProblem(sender)?.let { return refuse(sender, it) }
        val team = teamByName(teamName) ?: return refuse(sender, "There's no team called $teamName.")
        val target = memberByName(team, targetName) ?: return refuse(sender, "$targetName isn't a member of ${team.name}.")
        if (target == team.leader) return refuse(sender, "${nameOf(target)} already leads ${team.name}.")
        val name = nameOf(target)
        if (!confirmed) {
            return Msg.reply(sender, text("Admin: make ", NamedTextColor.YELLOW).append(text(name, NamedTextColor.WHITE))
                .append(text(" the leader of ", NamedTextColor.YELLOW)).append(teamName(team))
                .append(text(" (now ${nameOf(team.leader)})? ", NamedTextColor.YELLOW))
                .append(command("[Confirm]", "/team admin transfer ${team.name} $name confirm", NamedTextColor.GREEN))
                .append(text(" (or type /team admin transfer ${team.name} $name confirm)", NamedTextColor.GRAY)))
        }
        val actor = Actor((sender as? Player)?.uniqueId, sender.name, admin = true)
        submit(sender, actor.uuid ?: CONSOLE, { store.transfer(actor, team.id, target, System.currentTimeMillis()) }) { result ->
            handleTransfer(sender, result, name, admin = true)
        }
    }

    fun canAdmin(sender: CommandSender): Boolean = sender.hasPermission(PERMISSION_ADMIN)

    private fun handleTransfer(sender: CommandSender, result: TransferResult, name: String, admin: Boolean) {
        when (result) {
            is TransferResult.Transferred -> {
                applyTeam(result.team)
                plugin.logger.info(
                    "[Teams] ${sender.name}${if (admin) " (admin)" else ""} made $name the leader of Lifesteal team ${result.team.name} " +
                        "(${result.team.id}), previously ${nameOf(result.previousLeader)}."
                )
                if (admin) Msg.success(sender, "$name now leads ${result.team.name}.")
                tellTeam(result.team, text(name, NamedTextColor.WHITE).append(text(" is now the leader of ", NamedTextColor.GOLD)).append(teamName(result.team))
                    .append(text(if (admin) " (changed by staff)." else ".", NamedTextColor.GOLD)))
            }
            TransferResult.NotInTeam -> refuse(sender, "You're not in a team.")
            is TransferResult.NotLeader -> { applyTeam(result.team); refuse(sender, "Only the team leader can transfer leadership.") }
            is TransferResult.NotMember -> { applyTeam(result.team); refuse(sender, "$name isn't in ${result.team.name}.") }
            is TransferResult.AlreadyLeader -> { applyTeam(result.team); refuse(sender, "$name already leads ${result.team.name}.") }
            TransferResult.TeamGone -> refuse(sender, "That team doesn't exist any more.")
        }
    }

    private fun handleDisband(sender: CommandSender, result: DisbandResult, admin: Boolean) {
        when (result) {
            is DisbandResult.Disbanded -> {
                val team = result.team
                retireTeam(team)
                plugin.logger.info("[Teams] ${sender.name}${if (admin) " (admin)" else ""} disbanded Lifesteal team ${team.name} (${team.id}), ${team.size} member(s).")
                if (admin) Msg.success(sender, "${team.name} was disbanded.")
                for (member in team.members) {
                    Bukkit.getPlayer(member.uuid)?.let {
                        send(it, text("Your team ", NamedTextColor.RED).append(teamName(team)).append(text(if (admin) " was disbanded by staff." else " was disbanded.", NamedTextColor.RED)))
                    }
                }
                for (invite in result.invites) {
                    Bukkit.getPlayer(invite.target)?.let { send(it, text("Your invitation to ${team.name} was cancelled: the team was disbanded.", NamedTextColor.GRAY)) }
                }
            }
            DisbandResult.NotInTeam -> refuse(sender, "You're not in a team.")
            is DisbandResult.NotLeader -> { applyTeam(result.team); refuse(sender, "Only the team leader can disband the team.") }
            DisbandResult.TeamGone -> refuse(sender, "That team was already disbanded.")
        }
    }

    // ── Invitation expiry ───────────────────────────────────

    /** Every second on the main thread: expired invitations leave the cache, then the database. */
    private fun sweepInvites() {
        val now = System.currentTimeMillis()
        combatMemory.sweep(now)
        val due = invites.values.filter { it.expired(now) }
        if (due.isEmpty()) return
        for (invite in due) invites.remove(inviteKey(invite), invite)
        val worker = executor ?: return
        try {
            worker.execute {
                val expired = try {
                    store.expireInvites(now)
                } catch (e: Exception) {
                    plugin.logger.warning("[Teams] Couldn't clear expired invitations (they can't be accepted anyway): ${e.message}")
                    return@execute
                }
                main {
                    for (invite in expired) {
                        val teamName = teams[invite.teamId]?.name ?: "the team"
                        Bukkit.getPlayer(invite.target)?.let { send(it, text("Your invitation to $teamName expired.", NamedTextColor.GRAY)) }
                        Bukkit.getPlayer(invite.inviter)?.let { send(it, text("Your invitation to ${nameOf(invite.target)} expired.", NamedTextColor.GRAY)) }
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
        }
    }

    // ── Plumbing ────────────────────────────────────────────

    /**
     * Runs [work] (a store transaction) on the team worker and [done] with its result on the main
     * thread. One change per actor at a time, at most one per [ACTION_COOLDOWN_MS]; a failure
     * (database error, stale write) rolled everything back and is reported as "nothing changed".
     */
    private fun <R> submit(sender: CommandSender, actorKey: UUID, work: () -> R, done: (R) -> Unit) {
        val worker = executor ?: return refuse(sender, unavailableReason() ?: "Lifesteal teams are unavailable right now.")
        val now = System.currentTimeMillis()
        val last = lastAction[actorKey]
        if (last != null && now - last < ACTION_COOLDOWN_MS) return refuse(sender, "Slow down a little.")
        if (!busy.add(actorKey)) return refuse(sender, "Your last team action is still being processed.")
        lastAction[actorKey] = now
        try {
            worker.execute {
                val result = runCatching(work)
                main {
                    busy.remove(actorKey)
                    if (sender is Player && !sender.isOnline) {
                        // Still keep the cache right; the player just isn't told.
                        result.getOrNull()?.let { runCatching { done(it) } }
                        return@main
                    }
                    result.fold(done) { e ->
                        plugin.logger.severe("[Teams] A team change by ${sender.name} failed and was rolled back; nothing changed: ${e.message}")
                        refuse(sender, "That didn't work and nothing was changed. Please try again.")
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            busy.remove(actorKey)
            refuse(sender, "Lifesteal teams are unavailable right now.")
        }
    }

    private fun main(block: () -> Unit) {
        if (!plugin.isEnabled) return
        try {
            Bukkit.getScheduler().runTask(plugin, Runnable(block))
        } catch (_: IllegalPluginAccessException) {
        }
    }

    /** Puts a fresh snapshot in the cache unless a newer one (or the team's disbanding) is already there. */
    private fun applyTeam(team: Team) {
        val retiredAt = retired[team.id]
        if (retiredAt != null && retiredAt >= team.version) return
        val old = teams[team.id]
        if (old != null && old.version > team.version) return
        teams[team.id] = team
        byName[team.canonicalName] = team.id
        val now = System.currentTimeMillis()
        old?.members?.forEach {
            if (!team.isMember(it.uuid)) {
                byPlayer.remove(it.uuid, team.id)
                combatMemory.record(it.uuid, team.id, now, graceMillis())
            }
        }
        for (member in team.members) {
            byPlayer[member.uuid] = team.id
            Bukkit.getPlayer(member.uuid)?.let { names[member.uuid] = it.name }
        }
    }

    private fun retireTeam(team: Team) {
        retired[team.id] = team.version + 1
        val cached = teams.remove(team.id)
        byName.remove(team.canonicalName, team.id)
        val now = System.currentTimeMillis()
        for (member in (cached?.members.orEmpty() + team.members)) {
            byPlayer.remove(member.uuid, team.id)
            combatMemory.record(member.uuid, team.id, now, graceMillis())
        }
        invites.values.removeIf { it.teamId == team.id }
    }

    private fun removeInvitesOf(uuid: UUID) {
        invites.values.removeIf { it.target == uuid }
    }

    /** A member of [team] by (last known) name; works for offline members. */
    private fun memberByName(team: Team, name: String): UUID? =
        team.members.firstOrNull { nameOf(it.uuid).equals(name, ignoreCase = true) }?.uuid
            ?: plugin.playerResolver.resolve(name)?.uniqueId?.takeIf { team.isMember(it) }

    private fun inviteKey(invite: Invite) = "${invite.teamId}|${invite.target}"

    private fun tellTeam(team: Team, message: Component, except: UUID? = null) {
        for (member in team.members) {
            if (member.uuid == except) continue
            Bukkit.getPlayer(member.uuid)?.let { send(it, message) }
        }
    }

    fun send(player: Player, message: Component) {
        if (player.isOnline) plugin.commsManager.send(player, message)
    }

    companion object {
        const val PERMISSION_USE = "joshymc.lifesteal.team.use"
        const val PERMISSION_CREATE = "joshymc.lifesteal.team.create"
        const val PERMISSION_ADMIN = "joshymc.lifesteal.team.admin"

        /** Minimum time between two changes by the same player (command or GUI spam). */
        const val ACTION_COOLDOWN_MS = 300L
        private const val WORKER_WAIT_SECONDS = 5L
        /** The actor key for console admin actions. */
        private val CONSOLE = UUID(0L, 0L)

        fun text(content: String, color: NamedTextColor): Component = Component.text(content, color)

        fun teamName(team: Team): Component = Component.text(team.name, NamedTextColor.GOLD).decorate(TextDecoration.BOLD)

        fun command(label: String, command: String, color: NamedTextColor = NamedTextColor.AQUA): Component =
            Component.text(label, color).decorate(TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(Component.text(command, NamedTextColor.GRAY)))

        fun suggest(label: String, command: String): Component =
            Component.text(label, NamedTextColor.AQUA)
                .clickEvent(ClickEvent.suggestCommand(command))
                .hoverEvent(HoverEvent.showText(Component.text(command, NamedTextColor.GRAY)))
    }
}
