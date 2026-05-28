package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Sound
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom

class LotteryManager(private val plugin: Joshymc) {

    // uuid -> ticket count for the current round
    private val tickets = mutableMapOf<UUID, Int>()
    private var taskId = -1
    private var minuteCounter = 0

    // Config-driven values (reloaded on start())
    var ticketCost = 10_000.0; private set
    var basePrize = 50_000.0; private set
    var prizePerTicket = 10_000.0; private set
    var drawIntervalMinutes = 60; private set
    var maxTicketsPerPlayer = 0; private set  // 0 = unlimited

    fun start() {
        stop()

        val cfg = plugin.config
        ticketCost = cfg.getDouble("lottery.ticket-cost", 10_000.0)
        basePrize = cfg.getDouble("lottery.base-prize", 50_000.0)
        prizePerTicket = cfg.getDouble("lottery.prize-per-ticket", 10_000.0)
        drawIntervalMinutes = cfg.getInt("lottery.draw-interval-minutes", 60).coerceAtLeast(1)
        maxTicketsPerPlayer = cfg.getInt("lottery.max-tickets-per-player", 0).coerceAtLeast(0)

        tickets.clear()
        minuteCounter = 0

        // Tick every real minute (1200 ticks)
        taskId = plugin.server.scheduler.scheduleSyncRepeatingTask(plugin, Runnable {
            minuteCounter++
            onMinuteTick()
        }, 1200L, 1200L)

        plugin.logger.info("[Lottery] Started. Draw every $drawIntervalMinutes minutes. Ticket cost: ${plugin.economyManager.format(ticketCost)}.")
    }

    fun stop() {
        if (taskId != -1) {
            plugin.server.scheduler.cancelTask(taskId)
            taskId = -1
        }
    }

    private fun onMinuteTick() {
        val minuteInCycle = minuteCounter % drawIntervalMinutes
        when {
            minuteInCycle == 0 -> drawLottery()
            drawIntervalMinutes - minuteInCycle == 15 -> broadcastReminder(15)
            drawIntervalMinutes - minuteInCycle == 30 -> broadcastReminder(30)
            drawIntervalMinutes - minuteInCycle == 45 -> broadcastReminder(45)
            drawIntervalMinutes - minuteInCycle in 1..5 -> broadcastReminder(drawIntervalMinutes - minuteInCycle)
        }
    }

    private fun broadcastReminder(minutesLeft: Int) {
        val timeStr = if (minutesLeft == 1) "1 minute" else "$minutesLeft minutes"
        val prize = getPrize()
        val totalTickets = getTotalTickets()
        val poolLine = if (totalTickets > 0) {
            Component.text("  Prize Pool: ", NamedTextColor.GRAY)
                .append(Component.text(plugin.economyManager.format(prize), NamedTextColor.GREEN))
                .append(Component.text("  |  $totalTickets ticket(s) sold", NamedTextColor.DARK_GRAY))
        } else {
            Component.text("  Prize Pool: ", NamedTextColor.GRAY)
                .append(Component.text(plugin.economyManager.format(basePrize), NamedTextColor.GREEN))
                .append(Component.text("  (no entries yet)", NamedTextColor.DARK_GRAY))
        }

        Bukkit.broadcast(
            Component.text("🎫 ", NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true)
                .append(Component.text("Lottery", NamedTextColor.YELLOW).decoration(TextDecoration.BOLD, true))
                .append(Component.text(" — draw in $timeStr!", NamedTextColor.GRAY).decoration(TextDecoration.BOLD, false))
        )
        Bukkit.broadcast(poolLine)
        Bukkit.broadcast(
            Component.text("  Use ", NamedTextColor.GRAY)
                .append(Component.text("/lottery buy <amount>", NamedTextColor.AQUA))
                .append(Component.text(" to enter. Each ticket costs ", NamedTextColor.GRAY))
                .append(Component.text(plugin.economyManager.format(ticketCost), NamedTextColor.GOLD))
                .append(Component.text(".", NamedTextColor.GRAY))
        )
        for (player in Bukkit.getOnlinePlayers()) {
            player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BELL, 0.7f, 1.0f)
        }
    }

    private fun drawLottery() {
        val totalTickets = getTotalTickets()

        if (tickets.isEmpty() || totalTickets == 0) {
            Bukkit.broadcast(
                Component.text("🎫 ", NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true)
                    .append(Component.text("Lottery", NamedTextColor.YELLOW).decoration(TextDecoration.BOLD, true))
                    .append(Component.text(" — No one entered this round. No winner!", NamedTextColor.GRAY).decoration(TextDecoration.BOLD, false))
            )
            tickets.clear()
            return
        }

        val prize = getPrize()

        // Weighted random selection: pick a roll in [0, totalTickets)
        val roll = ThreadLocalRandom.current().nextInt(totalTickets)
        var cumulative = 0
        var winner: UUID? = null
        for ((uuid, count) in tickets) {
            cumulative += count
            if (roll < cumulative) {
                winner = uuid
                break
            }
        }
        if (winner == null) winner = tickets.keys.last()

        val winnerName = Bukkit.getOfflinePlayer(winner).name ?: "Unknown"
        val winnerTickets = tickets[winner] ?: 0
        val winChance = String.format("%.1f", winnerTickets.toDouble() / totalTickets * 100)
        val prizeStr = plugin.economyManager.format(prize)

        try {
            plugin.economyManager.deposit(winner, prize)
            plugin.logger.info("[Lottery] Deposited $prizeStr to $winnerName ($winner)")
        } catch (e: Exception) {
            plugin.logger.severe("[Lottery] Failed to deposit $prizeStr to $winnerName ($winner): ${e.message}")
            e.printStackTrace()
        }

        Bukkit.broadcast(
            Component.text("🎫 ", NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true)
                .append(Component.text("Lottery Winner! ", NamedTextColor.YELLOW).decoration(TextDecoration.BOLD, true))
                .append(Component.text(winnerName, NamedTextColor.AQUA).decoration(TextDecoration.BOLD, true))
                .append(Component.text(" won ", NamedTextColor.GRAY).decoration(TextDecoration.BOLD, false))
                .append(Component.text(prizeStr, NamedTextColor.GREEN).decoration(TextDecoration.BOLD, false))
                .append(Component.text("!", NamedTextColor.GRAY).decoration(TextDecoration.BOLD, false))
        )
        Bukkit.broadcast(
            Component.text("  $totalTickets ticket(s) sold | $winnerTickets ticket(s) held by winner ($winChance% chance)", NamedTextColor.DARK_GRAY)
        )

        val onlineWinner = Bukkit.getPlayer(winner)
        if (onlineWinner != null) {
            plugin.commsManager.send(
                onlineWinner,
                Component.text("You won the lottery! ", NamedTextColor.GOLD)
                    .append(Component.text(prizeStr, NamedTextColor.GREEN))
                    .append(Component.text(" has been added to your balance.", NamedTextColor.GOLD)),
                com.liam.joshymc.manager.CommunicationsManager.Category.ECONOMY
            )
        }

        for (player in Bukkit.getOnlinePlayers()) {
            player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.0f)
        }

        tickets.clear()
    }

    fun buyTickets(uuid: UUID, count: Int): Boolean {
        if (maxTicketsPerPlayer > 0) {
            val held = tickets[uuid] ?: 0
            if (held + count > maxTicketsPerPlayer) return false
        }
        val cost = ticketCost * count
        if (!plugin.economyManager.has(uuid, cost)) return false
        plugin.economyManager.withdraw(uuid, cost)
        tickets[uuid] = (tickets[uuid] ?: 0) + count
        return true
    }

    fun wouldExceedMax(uuid: UUID, count: Int): Boolean {
        if (maxTicketsPerPlayer <= 0) return false
        return (tickets[uuid] ?: 0) + count > maxTicketsPerPlayer
    }

    fun getTicketCount(uuid: UUID): Int = tickets[uuid] ?: 0

    fun getTotalTickets(): Int = tickets.values.sum()

    fun getPrize(): Double = basePrize + getTotalTickets() * prizePerTicket

    fun getMinutesUntilDraw(): Int {
        val minuteInCycle = minuteCounter % drawIntervalMinutes
        return if (minuteInCycle == 0 && minuteCounter == 0) drawIntervalMinutes
        else drawIntervalMinutes - minuteInCycle
    }
}
