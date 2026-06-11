package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.CommunicationsManager
import com.liam.joshymc.manager.TeamManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import java.text.SimpleDateFormat
import java.util.Date
import java.util.UUID

class TeamCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    private val dateFormat = SimpleDateFormat("MM/dd/yyyy")
    // sender name -> (team name, expiry ms)
    private val pendingDeletes = mutableMapOf<String, Pair<String, Long>>()

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isNotEmpty() && args[0].lowercase() == "delete") {
            handleDelete(sender, args)
            return true
        }

        if (sender !is Player) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED))
            return true
        }

        if (!sender.hasPermission("joshymc.team")) {
            plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return true
        }

        if (args.isEmpty()) {
            sendUsage(sender)
            return true
        }

        when (args[0].lowercase()) {
            "create" -> handleCreate(sender, args)
            "invite" -> handleInvite(sender, args)
            "accept" -> handleAccept(sender, args)
            "kick" -> handleKick(sender, args)
            "leave" -> handleLeave(sender)
            "promote" -> handlePromote(sender, args)
            "demote" -> handleDemote(sender, args)
            "transfer" -> handleTransfer(sender, args)
            "disband" -> handleDisband(sender)
            "info" -> handleInfo(sender, args)
            "list" -> handleList(sender)
            "chat" -> handleChat(sender, args)
            "deposit" -> handleDeposit(sender, args)
            "withdraw" -> handleWithdraw(sender, args)
            "balance", "bal" -> handleBalance(sender)
            "echest" -> handleEchest(sender)
            "sethome" -> handleSetHome(sender)
            "home" -> handleHome(sender)
            "rename" -> handleRename(sender, args)
            "pvp" -> handlePvp(sender, args)
            "open" -> handleOpen(sender)
            "close" -> handleClose(sender)
            "join" -> handleJoin(sender, args)
            else -> sendUsage(sender)
        }
        return true
    }

    private fun sendUsage(player: Player) {
        plugin.commsManager.send(player, Component.text("Team Commands:", NamedTextColor.GREEN), CommunicationsManager.Category.DEFAULT)
        val commands = listOf(
            "/team create <name> [display name]" to "Create a team",
            "/team invite <player>" to "Invite a player",
            "/team accept <team>" to "Accept an invite",
            "/team kick <player>" to "Kick a member",
            "/team leave" to "Leave your team",
            "/team promote <player>" to "Promote to admin",
            "/team demote <player>" to "Demote to member",
            "/team transfer <player>" to "Transfer ownership",
            "/team disband" to "Disband your team",
            "/team info [team]" to "View team info",
            "/team list" to "List all teams",
            "/team chat <on/off>" to "Toggle always-on team chat",
            "/team deposit <amount>" to "Deposit to team bank",
            "/team withdraw <amount>" to "Withdraw from team bank",
            "/team balance" to "View team balance",
            "/team echest" to "Open team ender chest",
            "/team sethome" to "Set the team home (owner only)",
            "/team home" to "Teleport to the team home",
            "/team rename <name>" to "Rename the team (owner only, once per week)",
            "/team pvp <on/off>" to "Toggle friendly fire within the team (owner/admin only)",
            "/team open" to "Open the team so anyone can join without an invite (owner only)",
            "/team close" to "Close the team to invite-only (owner only)",
            "/team join <team>" to "Join an open team"
        )
        commands.forEach { (cmd, desc) ->
            player.sendMessage(
                Component.text(" $cmd ", NamedTextColor.GRAY)
                    .append(Component.text("- $desc", NamedTextColor.DARK_GRAY))
            )
        }
    }

    private fun handleCreate(player: Player, args: Array<out String>) {
        if (args.size < 2) {
            plugin.commsManager.send(player, Component.text("Usage: /team create <name> [display name]", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        val rawName = args[1]
        if (!rawName.matches(Regex("^[a-zA-Z0-9_]{2,16}$"))) {
            plugin.commsManager.send(player, Component.text("Team name must be 2-16 characters (a-z, A-Z, 0-9, _).", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        val name = rawName.lowercase()
        val displayName = if (args.size > 2) args.drop(2).joinToString(" ") else rawName

        if (plugin.teamManager.createTeam(name, displayName, player)) {
            plugin.commsManager.send(
                player,
                Component.text("Team ", NamedTextColor.GRAY)
                    .append(Component.text(displayName, NamedTextColor.GREEN))
                    .append(Component.text(" created!", NamedTextColor.GRAY)),
                CommunicationsManager.Category.DEFAULT
            )
        } else {
            plugin.commsManager.send(player, Component.text("Could not create team. Name may be taken or you are already in a team.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
        }
    }

    private fun handleInvite(player: Player, args: Array<out String>) {
        if (args.size < 2) {
            plugin.commsManager.send(player, Component.text("Usage: /team invite <player>", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        val teamName = plugin.teamManager.getPlayerTeam(player.uniqueId)
        if (teamName == null) {
            plugin.commsManager.send(player, Component.text("You are not in a team.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        val role = plugin.teamManager.getPlayerRole(player.uniqueId)
        if (role != "owner" && role != "admin") {
            plugin.commsManager.send(player, Component.text("Only the owner or admins can invite players.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        val target = Bukkit.getPlayer(args[1])
        if (target == null) {
            plugin.commsManager.send(player, Component.text("Player not found.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        if (plugin.teamManager.getPlayerTeam(target.uniqueId) != null) {
            plugin.commsManager.send(player, Component.text("That player is already in a team.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        if (plugin.teamManager.getTeamMembers(teamName).size >= TeamManager.MAX_TEAM_SIZE) {
            plugin.commsManager.send(player, Component.text("Your team is full (max ${TeamManager.MAX_TEAM_SIZE} members).", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        plugin.teamManager.invitePlayer(teamName, player.uniqueId, target.uniqueId)

        val team = plugin.teamManager.getTeam(teamName)!!
        plugin.commsManager.send(
            player,
            Component.text("Invited ", NamedTextColor.GRAY)
                .append(Component.text(target.name, NamedTextColor.GREEN))
                .append(Component.text(" to your team.", NamedTextColor.GRAY)),
            CommunicationsManager.Category.DEFAULT
        )
        plugin.commsManager.send(
            target,
            Component.text("You have been invited to team ", NamedTextColor.GRAY)
                .append(Component.text(team.displayName, NamedTextColor.GREEN))
                .append(Component.text(". Use ", NamedTextColor.GRAY))
                .append(Component.text("/team accept $teamName", NamedTextColor.WHITE))
                .append(Component.text(" to join.", NamedTextColor.GRAY)),
            CommunicationsManager.Category.DEFAULT
        )
    }

    private fun handleAccept(player: Player, args: Array<out String>) {
        if (args.size < 2) {
            plugin.commsManager.send(player, Component.text("Usage: /team accept <team>", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        val teamName = args[1].lowercase()

        if (plugin.teamManager.acceptInvite(player.uniqueId, teamName)) {
            val team = plugin.teamManager.getTeam(teamName)!!
            plugin.commsManager.send(
                player,
                Component.text("You joined team ", NamedTextColor.GRAY)
                    .append(Component.text(team.displayName, NamedTextColor.GREEN)),
                CommunicationsManager.Category.DEFAULT
            )

            // Notify team members
            plugin.teamManager.getTeamMembers(teamName).forEach { member ->
                val online = Bukkit.getPlayer(UUID.fromString(member.uuid))
                if (online != null && online != player) {
                    plugin.commsManager.send(
                        online,
                        Component.text(player.name, NamedTextColor.GREEN)
                            .append(Component.text(" joined the team.", NamedTextColor.GRAY)),
                        CommunicationsManager.Category.DEFAULT
                    )
                }
            }
        } else {
            plugin.commsManager.send(player, Component.text("Could not accept invite. No pending invite or you are already in a team.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
        }
    }

    private fun handleKick(player: Player, args: Array<out String>) {
        if (args.size < 2) {
            plugin.commsManager.send(player, Component.text("Usage: /team kick <player>", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        val teamName = plugin.teamManager.getPlayerTeam(player.uniqueId)
        if (teamName == null) {
            plugin.commsManager.send(player, Component.text("You are not in a team.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        val role = plugin.teamManager.getPlayerRole(player.uniqueId)
        if (role != "owner" && role != "admin") {
            plugin.commsManager.send(player, Component.text("Only the owner or admins can kick members.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        val target = Bukkit.getOfflinePlayer(args[1])
        val targetUuid = target.uniqueId

        // Admins cannot kick other admins or the owner
        if (role == "admin") {
            val targetRole = plugin.teamManager.getPlayerRole(targetUuid)
            if (targetRole == "admin" || targetRole == "owner") {
                plugin.commsManager.send(player, Component.text("You cannot kick an admin or the owner.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
                return
            }
        }

        if (plugin.teamManager.kickMember(teamName, targetUuid)) {
            plugin.commsManager.send(
                player,
                Component.text("Kicked ", NamedTextColor.GRAY)
                    .append(Component.text(target.name ?: "Unknown", NamedTextColor.RED))
                    .append(Component.text(" from the team.", NamedTextColor.GRAY)),
                CommunicationsManager.Category.DEFAULT
            )

            val onlineTarget = Bukkit.getPlayer(targetUuid)
            if (onlineTarget != null) {
                plugin.commsManager.send(
                    onlineTarget,
                    Component.text("You have been kicked from your team.", NamedTextColor.RED),
                    CommunicationsManager.Category.DEFAULT
                )
            }
        } else {
            plugin.commsManager.send(player, Component.text("Could not kick that player.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
        }
    }

    private fun handleLeave(player: Player) {
        val teamName = plugin.teamManager.getPlayerTeam(player.uniqueId)
        if (teamName == null) {
            plugin.commsManager.send(player, Component.text("You are not in a team.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        val role = plugin.teamManager.getPlayerRole(player.uniqueId)
        if (role == "owner") {
            plugin.commsManager.send(player, Component.text("The owner cannot leave. Use /team transfer or /team disband.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        if (plugin.teamManager.leaveTeam(player.uniqueId)) {
            plugin.commsManager.send(player, Component.text("You left the team.", NamedTextColor.GRAY), CommunicationsManager.Category.DEFAULT)

            // Notify remaining members
            plugin.teamManager.getTeamMembers(teamName).forEach { member ->
                val online = Bukkit.getPlayer(UUID.fromString(member.uuid))
                if (online != null) {
                    plugin.commsManager.send(
                        online,
                        Component.text(player.name, NamedTextColor.RED)
                            .append(Component.text(" left the team.", NamedTextColor.GRAY)),
                        CommunicationsManager.Category.DEFAULT
                    )
                }
            }
        } else {
            plugin.commsManager.send(player, Component.text("Could not leave team.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
        }
    }

    private fun handlePromote(player: Player, args: Array<out String>) {
        if (args.size < 2) {
            plugin.commsManager.send(player, Component.text("Usage: /team promote <player>", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        val teamName = plugin.teamManager.getPlayerTeam(player.uniqueId)
        if (teamName == null) {
            plugin.commsManager.send(player, Component.text("You are not in a team.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        if (plugin.teamManager.getPlayerRole(player.uniqueId) != "owner") {
            plugin.commsManager.send(player, Component.text("Only the owner can promote members.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        val target = Bukkit.getOfflinePlayer(args[1])
        if (plugin.teamManager.promoteToAdmin(teamName, target.uniqueId)) {
            plugin.commsManager.send(
                player,
                Component.text("Promoted ", NamedTextColor.GRAY)
                    .append(Component.text(target.name ?: "Unknown", NamedTextColor.GREEN))
                    .append(Component.text(" to admin.", NamedTextColor.GRAY)),
                CommunicationsManager.Category.DEFAULT
            )

            val onlineTarget = Bukkit.getPlayer(target.uniqueId)
            if (onlineTarget != null) {
                plugin.commsManager.send(onlineTarget, Component.text("You have been promoted to admin!", NamedTextColor.GREEN), CommunicationsManager.Category.DEFAULT)
            }
        } else {
            plugin.commsManager.send(player, Component.text("Could not promote that player. They may not be a member.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
        }
    }

    private fun handleDemote(player: Player, args: Array<out String>) {
        if (args.size < 2) {
            plugin.commsManager.send(player, Component.text("Usage: /team demote <player>", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        val teamName = plugin.teamManager.getPlayerTeam(player.uniqueId)
        if (teamName == null) {
            plugin.commsManager.send(player, Component.text("You are not in a team.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        if (plugin.teamManager.getPlayerRole(player.uniqueId) != "owner") {
            plugin.commsManager.send(player, Component.text("Only the owner can demote members.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        val target = Bukkit.getOfflinePlayer(args[1])
        if (plugin.teamManager.demoteToMember(teamName, target.uniqueId)) {
            plugin.commsManager.send(
                player,
                Component.text("Demoted ", NamedTextColor.GRAY)
                    .append(Component.text(target.name ?: "Unknown", NamedTextColor.RED))
                    .append(Component.text(" to member.", NamedTextColor.GRAY)),
                CommunicationsManager.Category.DEFAULT
            )

            val onlineTarget = Bukkit.getPlayer(target.uniqueId)
            if (onlineTarget != null) {
                plugin.commsManager.send(onlineTarget, Component.text("You have been demoted to member.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            }
        } else {
            plugin.commsManager.send(player, Component.text("Could not demote that player. They may not be an admin.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
        }
    }

    private fun handleTransfer(player: Player, args: Array<out String>) {
        if (args.size < 2) {
            plugin.commsManager.send(player, Component.text("Usage: /team transfer <player>", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        val teamName = plugin.teamManager.getPlayerTeam(player.uniqueId)
        if (teamName == null) {
            plugin.commsManager.send(player, Component.text("You are not in a team.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        if (plugin.teamManager.getPlayerRole(player.uniqueId) != "owner") {
            plugin.commsManager.send(player, Component.text("Only the owner can transfer ownership.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        val target = Bukkit.getOfflinePlayer(args[1])
        if (plugin.teamManager.transferOwnership(teamName, target.uniqueId)) {
            plugin.commsManager.send(
                player,
                Component.text("Transferred ownership to ", NamedTextColor.GRAY)
                    .append(Component.text(target.name ?: "Unknown", NamedTextColor.GREEN)),
                CommunicationsManager.Category.DEFAULT
            )

            val onlineTarget = Bukkit.getPlayer(target.uniqueId)
            if (onlineTarget != null) {
                plugin.commsManager.send(onlineTarget, Component.text("You are now the team owner!", NamedTextColor.GREEN), CommunicationsManager.Category.DEFAULT)
            }
        } else {
            plugin.commsManager.send(player, Component.text("Could not transfer ownership. Player must be on your team.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
        }
    }

    private fun handleDelete(sender: CommandSender, args: Array<out String>) {
        if (!sender.hasPermission("joshymc.team.admin") && !sender.isOp) {
            sender.sendMessage(Component.text("No permission.", NamedTextColor.RED))
            return
        }

        if (args.size < 2) {
            sender.sendMessage(Component.text("Usage: /team delete <team> [confirm]", NamedTextColor.RED))
            return
        }

        val teamName = args[1].lowercase()
        val team = plugin.teamManager.getTeam(teamName)
        if (team == null) {
            sender.sendMessage(Component.text("Team '$teamName' not found.", NamedTextColor.RED))
            return
        }

        val confirming = args.size >= 3 && args[2].lowercase() == "confirm"
        val key = sender.name
        val pending = pendingDeletes[key]

        if (!confirming || pending == null || pending.first != teamName || System.currentTimeMillis() > pending.second) {
            pendingDeletes[key] = teamName to (System.currentTimeMillis() + 30_000L)
            sender.sendMessage(
                Component.text("Are you sure you want to delete team ", NamedTextColor.YELLOW)
                    .append(Component.text(team.displayName, NamedTextColor.RED))
                    .append(Component.text("? Run ", NamedTextColor.YELLOW))
                    .append(Component.text("/team delete $teamName confirm", NamedTextColor.WHITE))
                    .append(Component.text(" within 30 seconds to confirm.", NamedTextColor.YELLOW))
            )
            return
        }

        pendingDeletes.remove(key)
        val members = plugin.teamManager.getTeamMembers(teamName)

        if (plugin.teamManager.deleteTeam(teamName)) {
            members.forEach { member ->
                val online = Bukkit.getPlayer(UUID.fromString(member.uuid))
                if (online != null) {
                    plugin.commsManager.send(
                        online,
                        Component.text("Your team ", NamedTextColor.GRAY)
                            .append(Component.text(team.displayName, NamedTextColor.RED))
                            .append(Component.text(" was deleted by an administrator.", NamedTextColor.GRAY)),
                        CommunicationsManager.Category.DEFAULT
                    )
                }
            }
            sender.sendMessage(
                Component.text("Team ", NamedTextColor.GRAY)
                    .append(Component.text(team.displayName, NamedTextColor.RED))
                    .append(Component.text(" has been deleted.", NamedTextColor.GRAY))
            )
        } else {
            sender.sendMessage(Component.text("Could not delete team.", NamedTextColor.RED))
        }
    }

    private fun handleDisband(player: Player) {
        val teamName = plugin.teamManager.getPlayerTeam(player.uniqueId)
        if (teamName == null) {
            plugin.commsManager.send(player, Component.text("You are not in a team.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        if (plugin.teamManager.getPlayerRole(player.uniqueId) != "owner") {
            plugin.commsManager.send(player, Component.text("Only the owner can disband the team.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        val team = plugin.teamManager.getTeam(teamName)!!
        val members = plugin.teamManager.getTeamMembers(teamName)

        if (plugin.teamManager.deleteTeam(teamName)) {
            members.forEach { member ->
                val online = Bukkit.getPlayer(UUID.fromString(member.uuid))
                if (online != null) {
                    plugin.commsManager.send(
                        online,
                        Component.text("Team ", NamedTextColor.GRAY)
                            .append(Component.text(team.displayName, NamedTextColor.RED))
                            .append(Component.text(" has been disbanded.", NamedTextColor.GRAY)),
                        CommunicationsManager.Category.DEFAULT
                    )
                }
            }
        } else {
            plugin.commsManager.send(player, Component.text("Could not disband team.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
        }
    }

    private fun handleInfo(player: Player, args: Array<out String>) {
        val teamName = if (args.size >= 2) {
            args[1].lowercase()
        } else {
            plugin.teamManager.getPlayerTeam(player.uniqueId)
        }

        if (teamName == null) {
            plugin.commsManager.send(player, Component.text("You are not in a team. Use /team info <team> to view another team.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        val team = plugin.teamManager.getTeam(teamName)
        if (team == null) {
            plugin.commsManager.send(player, Component.text("Team not found.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        val members = plugin.teamManager.getTeamMembers(teamName)
        val owner = Bukkit.getOfflinePlayer(UUID.fromString(team.ownerUuid))

        player.sendMessage(Component.text("--- ", NamedTextColor.DARK_GRAY)
            .append(Component.text(team.displayName, NamedTextColor.GREEN))
            .append(Component.text(" ---", NamedTextColor.DARK_GRAY)))
        player.sendMessage(Component.text(" Name: ", NamedTextColor.GRAY).append(Component.text(team.name, NamedTextColor.WHITE)))
        player.sendMessage(Component.text(" Owner: ", NamedTextColor.GRAY).append(Component.text(owner.name ?: "Unknown", NamedTextColor.WHITE)))
        player.sendMessage(Component.text(" Members (${members.size}):", NamedTextColor.GRAY))

        members.forEach { member ->
            val memberPlayer = Bukkit.getOfflinePlayer(UUID.fromString(member.uuid))
            val roleColor = when (member.role) {
                "owner" -> NamedTextColor.GOLD
                "admin" -> NamedTextColor.YELLOW
                else -> NamedTextColor.GRAY
            }
            val roleLabel = member.role.replaceFirstChar { it.uppercase() }
            player.sendMessage(
                Component.text("  - ", NamedTextColor.DARK_GRAY)
                    .append(Component.text(memberPlayer.name ?: "Unknown", NamedTextColor.WHITE))
                    .append(Component.text(" [$roleLabel]", roleColor))
            )
        }

        val isOpen = plugin.teamManager.isTeamOpen(team.name)
        player.sendMessage(Component.text(" Status: ", NamedTextColor.GRAY)
            .append(if (isOpen) Component.text("Open", NamedTextColor.GREEN) else Component.text("Invite Only", NamedTextColor.YELLOW)))
        player.sendMessage(Component.text(" Created: ", NamedTextColor.GRAY)
            .append(Component.text(dateFormat.format(Date(team.createdAt)), NamedTextColor.WHITE)))
    }

    private fun handleList(player: Player) {
        val teams = plugin.teamManager.getAllTeams()
        if (teams.isEmpty()) {
            plugin.commsManager.send(player, Component.text("No teams exist yet.", NamedTextColor.GRAY), CommunicationsManager.Category.DEFAULT)
            return
        }

        player.sendMessage(Component.text("--- Teams ---", NamedTextColor.GREEN))
        teams.forEach { team ->
            val memberCount = plugin.teamManager.getTeamMembers(team.name).size
            player.sendMessage(
                Component.text(" ${team.displayName} ", NamedTextColor.WHITE)
                    .append(Component.text("(${team.name})", NamedTextColor.DARK_GRAY))
                    .append(Component.text(" - $memberCount members", NamedTextColor.GRAY))
            )
        }
    }

    private fun handleChat(player: Player, args: Array<out String>) {
        if (args.size < 2) {
            plugin.commsManager.send(player, Component.text("Usage: /team chat <on/off>", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        if (plugin.teamManager.getPlayerTeam(player.uniqueId) == null) {
            plugin.commsManager.send(player, Component.text("You are not in a team.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        when (args[1].lowercase()) {
            "on" -> {
                plugin.teamManager.setTeamChat(player.uniqueId, true)
                plugin.commsManager.send(player, Component.text("Team chat enabled. All your messages will go to your team. Use ! prefix for a quick team message.", NamedTextColor.GREEN), CommunicationsManager.Category.DEFAULT)
            }
            "off" -> {
                plugin.teamManager.setTeamChat(player.uniqueId, false)
                plugin.commsManager.send(player, Component.text("Team chat disabled.", NamedTextColor.GRAY), CommunicationsManager.Category.DEFAULT)
            }
            else -> plugin.commsManager.send(player, Component.text("Usage: /team chat <on/off>", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
        }
    }

    private fun handleDeposit(player: Player, args: Array<out String>) {
        if (args.size < 2) {
            plugin.commsManager.send(player, Component.text("Usage: /team deposit <amount>", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        val teamName = plugin.teamManager.getPlayerTeam(player.uniqueId)
        if (teamName == null) {
            plugin.commsManager.send(player, Component.text("You are not in a team.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        val amount = plugin.economyManager.parseAmount(args[1])
        if (amount == null || amount <= 0) {
            plugin.commsManager.send(player, Component.text("Invalid amount.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        if (!plugin.economyManager.withdraw(player.uniqueId, amount)) {
            plugin.commsManager.send(player, Component.text("You don't have enough money.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        plugin.teamManager.depositTeamBalance(teamName, amount)
        val newBalance = plugin.teamManager.getTeamBalance(teamName)
        plugin.commsManager.send(
            player,
            Component.text("Deposited ", NamedTextColor.GRAY)
                .append(Component.text(plugin.economyManager.format(amount), NamedTextColor.GREEN))
                .append(Component.text(" to the team bank. Balance: ", NamedTextColor.GRAY))
                .append(Component.text(plugin.economyManager.format(newBalance), NamedTextColor.GREEN)),
            CommunicationsManager.Category.DEFAULT
        )
    }

    private fun handleWithdraw(player: Player, args: Array<out String>) {
        if (args.size < 2) {
            plugin.commsManager.send(player, Component.text("Usage: /team withdraw <amount>", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        val teamName = plugin.teamManager.getPlayerTeam(player.uniqueId)
        if (teamName == null) {
            plugin.commsManager.send(player, Component.text("You are not in a team.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        val role = plugin.teamManager.getPlayerRole(player.uniqueId)
        if (role != "owner" && role != "admin") {
            plugin.commsManager.send(player, Component.text("Only the owner or admins can withdraw from the team bank.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        val amount = plugin.economyManager.parseAmount(args[1])
        if (amount == null || amount <= 0) {
            plugin.commsManager.send(player, Component.text("Invalid amount.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        if (!plugin.teamManager.withdrawTeamBalance(teamName, amount)) {
            plugin.commsManager.send(player, Component.text("Insufficient team balance.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        plugin.economyManager.deposit(player.uniqueId, amount)
        val newBalance = plugin.teamManager.getTeamBalance(teamName)
        plugin.commsManager.send(
            player,
            Component.text("Withdrew ", NamedTextColor.GRAY)
                .append(Component.text(plugin.economyManager.format(amount), NamedTextColor.GREEN))
                .append(Component.text(" from the team bank. Balance: ", NamedTextColor.GRAY))
                .append(Component.text(plugin.economyManager.format(newBalance), NamedTextColor.GREEN)),
            CommunicationsManager.Category.DEFAULT
        )
    }

    private fun handleBalance(player: Player) {
        val teamName = plugin.teamManager.getPlayerTeam(player.uniqueId)
        if (teamName == null) {
            plugin.commsManager.send(player, Component.text("You are not in a team.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        val balance = plugin.teamManager.getTeamBalance(teamName)
        plugin.commsManager.send(
            player,
            Component.text("Team balance: ", NamedTextColor.GRAY)
                .append(Component.text(plugin.economyManager.format(balance), NamedTextColor.GREEN)),
            CommunicationsManager.Category.DEFAULT
        )
    }

    private fun handleEchest(player: Player) {
        val teamName = plugin.teamManager.getPlayerTeam(player.uniqueId)
        if (teamName == null) {
            plugin.commsManager.send(player, Component.text("You are not in a team.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        val role = plugin.teamManager.getPlayerRole(player.uniqueId)
        if (role != "owner" && role != "admin") {
            plugin.commsManager.send(player, Component.text("Only the owner or admins can access the team chest.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        plugin.teamManager.openTeamEchest(player, teamName)
    }

    private fun handleSetHome(player: Player) {
        val teamName = plugin.teamManager.getPlayerTeam(player.uniqueId)
        if (teamName == null) {
            plugin.commsManager.send(player, Component.text("You are not in a team.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        if (plugin.teamManager.getPlayerRole(player.uniqueId) != "owner") {
            plugin.commsManager.send(player, Component.text("Only the team owner can set the team home.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        plugin.teamManager.setTeamHome(teamName, player.location)
        plugin.commsManager.send(player, Component.text("Team home set.", NamedTextColor.GREEN), CommunicationsManager.Category.DEFAULT)
    }

    private fun handleHome(player: Player) {
        val teamName = plugin.teamManager.getPlayerTeam(player.uniqueId)
        if (teamName == null) {
            plugin.commsManager.send(player, Component.text("You are not in a team.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        val home = plugin.teamManager.getTeamHome(teamName)
        if (home == null) {
            plugin.commsManager.send(player, Component.text("Your team has no home set. The owner can set one with /team sethome.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        player.teleport(home)
        plugin.commsManager.send(player, Component.text("Teleported to team home.", NamedTextColor.GREEN), CommunicationsManager.Category.DEFAULT)
    }

    private fun handleRename(player: Player, args: Array<out String>) {
        if (args.size < 2) {
            plugin.commsManager.send(player, Component.text("Usage: /team rename <new name>", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        val teamName = plugin.teamManager.getPlayerTeam(player.uniqueId)
        if (teamName == null) {
            plugin.commsManager.send(player, Component.text("You are not in a team.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        if (plugin.teamManager.getPlayerRole(player.uniqueId) != "owner") {
            plugin.commsManager.send(player, Component.text("Only the owner can rename the team.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        val cooldownMs = 7L * 24 * 60 * 60 * 1000
        val lastRenamed = plugin.teamManager.getLastRenamedAt(teamName)
        val elapsed = System.currentTimeMillis() - lastRenamed
        if (elapsed < cooldownMs && !player.hasPermission("joshymc.team.admin")) {
            val remaining = cooldownMs - elapsed
            val days = remaining / (24 * 60 * 60 * 1000)
            val hours = (remaining % (24 * 60 * 60 * 1000)) / (60 * 60 * 1000)
            plugin.commsManager.send(
                player,
                Component.text("You can rename your team again in ", NamedTextColor.RED)
                    .append(Component.text("${days}d ${hours}h", NamedTextColor.WHITE)),
                CommunicationsManager.Category.DEFAULT
            )
            return
        }

        val newDisplayName = args.drop(1).joinToString(" ")
        if (newDisplayName.length > 32) {
            plugin.commsManager.send(player, Component.text("Team name must be 32 characters or less.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        val oldDisplayName = plugin.teamManager.getTeam(teamName)!!.displayName
        plugin.teamManager.renameTeam(teamName, newDisplayName)

        plugin.teamManager.getTeamMembers(teamName).forEach { member ->
            val online = Bukkit.getPlayer(UUID.fromString(member.uuid))
            if (online != null) {
                plugin.commsManager.send(
                    online,
                    Component.text("Team renamed from ", NamedTextColor.GRAY)
                        .append(Component.text(oldDisplayName, NamedTextColor.WHITE))
                        .append(Component.text(" to ", NamedTextColor.GRAY))
                        .append(Component.text(newDisplayName, NamedTextColor.GREEN)),
                    CommunicationsManager.Category.DEFAULT
                )
            }
        }
    }

    private fun handlePvp(player: Player, args: Array<out String>) {
        if (args.size < 2) {
            plugin.commsManager.send(player, Component.text("Usage: /team pvp <on/off>", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        val teamName = plugin.teamManager.getPlayerTeam(player.uniqueId)
        if (teamName == null) {
            plugin.commsManager.send(player, Component.text("You are not in a team.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        val role = plugin.teamManager.getPlayerRole(player.uniqueId)
        if (role != "owner" && role != "admin") {
            plugin.commsManager.send(player, Component.text("Only the owner or admins can change team PvP.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        when (args[1].lowercase()) {
            "on" -> {
                plugin.teamManager.setTeamPvp(teamName, true)
                plugin.teamManager.getTeamMembers(teamName).forEach { member ->
                    val online = Bukkit.getPlayer(UUID.fromString(member.uuid))
                    if (online != null) {
                        plugin.commsManager.send(
                            online,
                            Component.text("Team PvP has been ", NamedTextColor.GRAY)
                                .append(Component.text("enabled", NamedTextColor.GREEN))
                                .append(Component.text(". Teammates can now damage each other.", NamedTextColor.GRAY)),
                            CommunicationsManager.Category.DEFAULT
                        )
                    }
                }
            }
            "off" -> {
                plugin.teamManager.setTeamPvp(teamName, false)
                plugin.teamManager.getTeamMembers(teamName).forEach { member ->
                    val online = Bukkit.getPlayer(UUID.fromString(member.uuid))
                    if (online != null) {
                        plugin.commsManager.send(
                            online,
                            Component.text("Team PvP has been ", NamedTextColor.GRAY)
                                .append(Component.text("disabled", NamedTextColor.RED))
                                .append(Component.text(". Teammates cannot damage each other.", NamedTextColor.GRAY)),
                            CommunicationsManager.Category.DEFAULT
                        )
                    }
                }
            }
            else -> plugin.commsManager.send(player, Component.text("Usage: /team pvp <on/off>", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
        }
    }

    private fun handleOpen(player: Player) {
        val teamName = plugin.teamManager.getPlayerTeam(player.uniqueId)
        if (teamName == null) {
            plugin.commsManager.send(player, Component.text("You are not in a team.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        if (plugin.teamManager.getPlayerRole(player.uniqueId) != "owner") {
            plugin.commsManager.send(player, Component.text("Only the owner can open the team.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        if (plugin.teamManager.isTeamOpen(teamName)) {
            plugin.commsManager.send(player, Component.text("Your team is already open.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        plugin.teamManager.setTeamOpen(teamName, true)
        plugin.teamManager.getTeamMembers(teamName).forEach { member ->
            val online = Bukkit.getPlayer(UUID.fromString(member.uuid))
            if (online != null) {
                plugin.commsManager.send(
                    online,
                    Component.text("Your team is now ", NamedTextColor.GRAY)
                        .append(Component.text("open", NamedTextColor.GREEN))
                        .append(Component.text(". Anyone can join with /team join ${teamName}.", NamedTextColor.GRAY)),
                    CommunicationsManager.Category.DEFAULT
                )
            }
        }
    }

    private fun handleClose(player: Player) {
        val teamName = plugin.teamManager.getPlayerTeam(player.uniqueId)
        if (teamName == null) {
            plugin.commsManager.send(player, Component.text("You are not in a team.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        if (plugin.teamManager.getPlayerRole(player.uniqueId) != "owner") {
            plugin.commsManager.send(player, Component.text("Only the owner can close the team.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        if (!plugin.teamManager.isTeamOpen(teamName)) {
            plugin.commsManager.send(player, Component.text("Your team is already invite-only.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        plugin.teamManager.setTeamOpen(teamName, false)
        plugin.teamManager.getTeamMembers(teamName).forEach { member ->
            val online = Bukkit.getPlayer(UUID.fromString(member.uuid))
            if (online != null) {
                plugin.commsManager.send(
                    online,
                    Component.text("Your team is now ", NamedTextColor.GRAY)
                        .append(Component.text("invite-only", NamedTextColor.YELLOW))
                        .append(Component.text(". Players need an invite to join.", NamedTextColor.GRAY)),
                    CommunicationsManager.Category.DEFAULT
                )
            }
        }
    }

    private fun handleJoin(player: Player, args: Array<out String>) {
        if (args.size < 2) {
            plugin.commsManager.send(player, Component.text("Usage: /team join <team>", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        if (plugin.teamManager.getPlayerTeam(player.uniqueId) != null) {
            plugin.commsManager.send(player, Component.text("You are already in a team.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        val teamName = args[1].lowercase()
        val team = plugin.teamManager.getTeam(teamName)
        if (team == null) {
            plugin.commsManager.send(player, Component.text("Team not found.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        if (!plugin.teamManager.isTeamOpen(teamName)) {
            plugin.commsManager.send(player, Component.text("That team is invite-only. Ask a member to invite you.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        if (plugin.teamManager.getTeamMembers(teamName).size >= TeamManager.MAX_TEAM_SIZE) {
            plugin.commsManager.send(player, Component.text("That team is full.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        if (plugin.teamManager.joinOpenTeam(player.uniqueId, teamName)) {
            plugin.commsManager.send(
                player,
                Component.text("You joined team ", NamedTextColor.GRAY)
                    .append(Component.text(team.displayName, NamedTextColor.GREEN)),
                CommunicationsManager.Category.DEFAULT
            )

            plugin.teamManager.getTeamMembers(teamName).forEach { member ->
                val online = Bukkit.getPlayer(UUID.fromString(member.uuid))
                if (online != null && online != player) {
                    plugin.commsManager.send(
                        online,
                        Component.text(player.name, NamedTextColor.GREEN)
                            .append(Component.text(" joined the team.", NamedTextColor.GRAY)),
                        CommunicationsManager.Category.DEFAULT
                    )
                }
            }
        } else {
            plugin.commsManager.send(player, Component.text("Could not join that team.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
        }
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        val isAdmin = sender.hasPermission("joshymc.team.admin") || sender.isOp

        // Console only gets completions for admin-only subcommands
        if (sender !is Player) {
            if (args.size == 1 && isAdmin) return listOf("delete").filter { it.startsWith(args[0], ignoreCase = true) }
            if (args.size == 2 && args[0].lowercase() == "delete" && isAdmin) return plugin.teamManager.getAllTeams().map { it.name }.filter { it.startsWith(args[1], ignoreCase = true) }
            if (args.size == 3 && args[0].lowercase() == "delete" && isAdmin) return listOf("confirm").filter { it.startsWith(args[2], ignoreCase = true) }
            return emptyList()
        }

        if (args.size == 1) {
            val base = listOf("create", "invite", "accept", "kick", "leave", "promote", "demote", "transfer", "disband", "info", "list", "chat", "deposit", "withdraw", "balance", "echest", "sethome", "home", "rename", "pvp", "open", "close", "join")
            val all = if (isAdmin) base + "delete" else base
            return all.filter { it.startsWith(args[0], ignoreCase = true) }
        }

        if (args.size == 2) {
            return when (args[0].lowercase()) {
                "invite" -> Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(args[1], ignoreCase = true) }
                "accept" -> {
                    if (sender !is Player) return emptyList()
                    plugin.teamManager.getPendingInvites(sender.uniqueId).filter { it.startsWith(args[1], ignoreCase = true) }
                }
                "kick", "promote", "demote", "transfer" -> {
                    if (sender !is Player) return emptyList()
                    val teamName = plugin.teamManager.getPlayerTeam(sender.uniqueId) ?: return emptyList()
                    plugin.teamManager.getTeamMembers(teamName).mapNotNull { member ->
                        val p = Bukkit.getOfflinePlayer(UUID.fromString(member.uuid))
                        p.name
                    }.filter { it.startsWith(args[1], ignoreCase = true) && !it.equals(sender.name, ignoreCase = true) }
                }
                "info", "delete" -> plugin.teamManager.getAllTeams().map { it.name }.filter { it.startsWith(args[1], ignoreCase = true) }
                "join" -> plugin.teamManager.getAllTeams().filter { plugin.teamManager.isTeamOpen(it.name) }.map { it.name }.filter { it.startsWith(args[1], ignoreCase = true) }
                "chat", "pvp" -> listOf("on", "off").filter { it.startsWith(args[1], ignoreCase = true) }
                else -> emptyList()
            }
        }

        if (args.size == 3 && args[0].lowercase() == "delete") {
            return listOf("confirm").filter { it.startsWith(args[2], ignoreCase = true) }
        }

        return emptyList()
    }
}
