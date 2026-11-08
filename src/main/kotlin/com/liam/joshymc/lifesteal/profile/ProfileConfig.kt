package com.liam.joshymc.lifesteal.profile

import java.util.Locale

/** Lifesteal leaderboard categories (issue #1429). Ranked Elo boards are read through the existing Ranked statistics service. */
enum class BoardCategory(val id: String, val label: String, val icon: String, val stat: ProfileStat?, val seasonal: Boolean, val allTime: Boolean) {
    KILLS("kills", "PvP Kills", "IRON_SWORD", ProfileStat.PVP_KILLS, true, true),
    KILL_STREAK("kill-streak", "Highest Kill Streak", "BLAZE_POWDER", ProfileStat.BEST_KILL_STREAK, true, true),
    KD("kd", "K/D Ratio", "SKELETON_SKULL", null, true, true),
    BOUNTIES("bounties", "Bounties Claimed", "GOLD_INGOT", ProfileStat.BOUNTY_CLAIMS, true, true),
    ACHIEVEMENTS("achievements", "Achievements Completed", "BOOK", null, true, true),
    PLAYTIME("playtime", "Playtime", "CLOCK", ProfileStat.PLAYTIME_HOURS, false, true),
    PLAYER_ELO("player-elo", "Player Elo", "DIAMOND_SWORD", null, true, false),
    TEAM_ELO("team-elo", "Team Elo", "SHIELD", null, true, false);

    val ranked: Boolean get() = this == PLAYER_ELO || this == TEAM_ELO

    companion object {
        fun of(id: String?): BoardCategory? = entries.firstOrNull { it.id.equals(id?.trim(), ignoreCase = true) || it.name.equals(id?.trim(), ignoreCase = true) }
    }
}

enum class BoardPeriod(val id: String, val label: String) {
    SEASON("season", "This Season"), ALL_TIME("alltime", "All Time");

    companion object {
        fun of(id: String?): BoardPeriod? = when (id?.trim()?.lowercase(Locale.ROOT)) {
            "season", "current" -> SEASON
            "alltime", "all-time", "all", "lifetime" -> ALL_TIME
            else -> null
        }
    }
}

/** `lifesteal-profiles.yml` without the achievement list. */
data class ProfileSettings(
    val enabled: Boolean,
    val achievementsEnabled: Boolean,
    val pairCooldownMillis: Long,
    val evaluateIntervalSeconds: Int,
    val flushIntervalSeconds: Int,
    val ores: Set<String>,
    val mobSpawnReasons: Set<String>,
    val mobsPerMinute: Int,
    val fishMinIntervalMillis: Long,
    val allowPrivate: Boolean,
    val showPlaytime: Boolean,
    val defaultBorder: String,
    val boardsEnabled: Boolean,
    val categories: List<BoardCategory>,
    val kdMinimumFights: Int,
    val boardRefreshSeconds: Int,
    val announcePlayer: Boolean,
    val announceBroadcast: Boolean,
    val broadcastMessage: String,
    val profileTitle: String,
    val achievementsTitle: String,
    val leaderboardsTitle: String,
) {
    companion object {
        val DEFAULT_ORES = setOf(
            "COAL_ORE", "DEEPSLATE_COAL_ORE", "COPPER_ORE", "DEEPSLATE_COPPER_ORE", "IRON_ORE", "DEEPSLATE_IRON_ORE",
            "GOLD_ORE", "DEEPSLATE_GOLD_ORE", "REDSTONE_ORE", "DEEPSLATE_REDSTONE_ORE", "LAPIS_ORE", "DEEPSLATE_LAPIS_ORE",
            "DIAMOND_ORE", "DEEPSLATE_DIAMOND_ORE", "EMERALD_ORE", "DEEPSLATE_EMERALD_ORE", "NETHER_GOLD_ORE",
            "NETHER_QUARTZ_ORE", "ANCIENT_DEBRIS",
        )
        val DEFAULT_SPAWN_REASONS = setOf("NATURAL", "CHUNK_GEN", "PATROL", "RAID", "REINFORCEMENTS", "JOCKEY", "MOUNT", "VILLAGE_INVASION", "LIGHTNING", "TRAP")

        val DEFAULTS = ProfileSettings(
            enabled = true, achievementsEnabled = true, pairCooldownMillis = 60 * 60_000L, evaluateIntervalSeconds = 120,
            flushIntervalSeconds = 30, ores = DEFAULT_ORES, mobSpawnReasons = DEFAULT_SPAWN_REASONS, mobsPerMinute = 40,
            fishMinIntervalMillis = 3_000L, allowPrivate = true, showPlaytime = true, defaultBorder = "gray", boardsEnabled = true,
            categories = BoardCategory.entries.toList(), kdMinimumFights = 20, boardRefreshSeconds = 300, announcePlayer = true,
            announceBroadcast = true, broadcastMessage = "&6{player} &eearned the achievement &6{achievement}&e!",
            profileTitle = "Lifesteal Profile", achievementsTitle = "Achievements", leaderboardsTitle = "Lifesteal Leaderboards",
        )
    }
}

/**
 * Parses `lifesteal-profiles.yml` (as plain maps). Bukkit-free. A wrong value is reported in
 * [Result.problems] and the default is used for that one key; nothing here can delete data.
 */
object ProfileConfig {

    data class Result(val settings: ProfileSettings, val problems: List<String>)

    fun parse(root: Map<String, Any?>): Result {
        val d = ProfileSettings.DEFAULTS
        val problems = ArrayList<String>()
        fun section(name: String): Map<String, Any?> = (root[name] as? Map<*, *>)?.entries?.associate { (k, v) -> k.toString() to v } ?: emptyMap()
        fun bool(map: Map<String, Any?>, key: String, def: Boolean, path: String): Boolean = when (val v = map[key]) {
            null -> def
            is Boolean -> v
            else -> { problems += "$path must be true or false"; def }
        }
        fun int(map: Map<String, Any?>, key: String, def: Int, range: IntRange, path: String): Int = when (val v = map[key]) {
            null -> def
            is Number -> v.toLong().takeIf { it in range.first..range.last && v.toDouble() == v.toLong().toDouble() }?.toInt() ?: run { problems += "$path must be a whole number in $range"; def }
            else -> { problems += "$path must be a whole number in $range"; def }
        }
        fun string(map: Map<String, Any?>, key: String, def: String, path: String, max: Int = 200): String = when (val v = map[key]) {
            null -> def
            is String -> v.takeIf { it.isNotBlank() && it.length <= max } ?: run { problems += "$path must be 1-$max characters"; def }
            else -> { problems += "$path must be text"; def }
        }
        fun names(map: Map<String, Any?>, key: String, def: Set<String>, path: String): Set<String> = when (val v = map[key]) {
            null -> def
            is List<*> -> v.mapNotNull { it?.toString()?.trim()?.uppercase(Locale.ROOT)?.takeIf { s -> s.matches(Regex("^[A-Z0-9_]{1,64}$")) } }.toSet()
                .takeIf { it.size == v.size } ?: run { problems += "$path has an invalid name"; def }
            else -> { problems += "$path must be a list"; def }
        }

        val enabled = bool(root, "enabled", d.enabled, "enabled")
        val pvp = section("pvp")
        val cooldown = int(pvp, "same-victim-cooldown-minutes", (d.pairCooldownMillis / 60_000L).toInt(), 0..10_080, "pvp.same-victim-cooldown-minutes")
        val ach = section("achievements-settings")
        val activity = section("activity")
        val profiles = section("profiles")
        val boards = section("leaderboards")
        val announce = section("announcements")
        val gui = section("gui")

        val categories: List<BoardCategory> = when (val v = boards["categories"]) {
            null -> d.categories
            is List<*> -> {
                val parsed = v.map { BoardCategory.of(it?.toString()) }
                if (parsed.any { it == null }) { problems += "leaderboards.categories has an unknown category (known: ${BoardCategory.entries.joinToString { it.id }})"; d.categories }
                else parsed.filterNotNull().distinct()
            }
            else -> { problems += "leaderboards.categories must be a list"; d.categories }
        }
        val border = ProfileDecorations.normalizeBorder(profiles["default-border"] as? String)
            ?: run { if (profiles["default-border"] != null) problems += "profiles.default-border is not a stained glass colour"; d.defaultBorder }

        val settings = ProfileSettings(
            enabled = enabled,
            achievementsEnabled = bool(ach, "enabled", d.achievementsEnabled, "achievements-settings.enabled"),
            pairCooldownMillis = cooldown * 60_000L,
            evaluateIntervalSeconds = int(ach, "evaluate-interval-seconds", d.evaluateIntervalSeconds, 30..3_600, "achievements-settings.evaluate-interval-seconds"),
            flushIntervalSeconds = int(activity, "flush-interval-seconds", d.flushIntervalSeconds, 5..600, "activity.flush-interval-seconds"),
            ores = names(activity, "ores", d.ores, "activity.ores"),
            mobSpawnReasons = names(activity, "mob-spawn-reasons", d.mobSpawnReasons, "activity.mob-spawn-reasons"),
            mobsPerMinute = int(activity, "mobs-per-minute", d.mobsPerMinute, 1..1_000, "activity.mobs-per-minute"),
            fishMinIntervalMillis = int(activity, "fish-min-interval-seconds", (d.fishMinIntervalMillis / 1000).toInt(), 0..600, "activity.fish-min-interval-seconds") * 1000L,
            allowPrivate = bool(profiles, "allow-private", d.allowPrivate, "profiles.allow-private"),
            showPlaytime = bool(profiles, "show-playtime", d.showPlaytime, "profiles.show-playtime"),
            defaultBorder = border,
            boardsEnabled = bool(boards, "enabled", d.boardsEnabled, "leaderboards.enabled"),
            categories = categories,
            kdMinimumFights = int(boards, "kd-minimum-fights", d.kdMinimumFights, 1..100_000, "leaderboards.kd-minimum-fights"),
            boardRefreshSeconds = int(boards, "refresh-seconds", d.boardRefreshSeconds, 30..86_400, "leaderboards.refresh-seconds"),
            announcePlayer = bool(announce, "player", d.announcePlayer, "announcements.player"),
            announceBroadcast = bool(announce, "broadcast", d.announceBroadcast, "announcements.broadcast"),
            broadcastMessage = string(announce, "broadcast-message", d.broadcastMessage, "announcements.broadcast-message"),
            profileTitle = string(gui, "profile-title", d.profileTitle, "gui.profile-title", 28),
            achievementsTitle = string(gui, "achievements-title", d.achievementsTitle, "gui.achievements-title", 28),
            leaderboardsTitle = string(gui, "leaderboards-title", d.leaderboardsTitle, "gui.leaderboards-title", 28),
        )
        return Result(settings, problems)
    }
}
