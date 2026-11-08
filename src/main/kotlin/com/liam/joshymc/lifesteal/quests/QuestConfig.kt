package com.liam.joshymc.lifesteal.quests

import com.liam.joshymc.manager.PurchaseRewards
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

/**
 * Parses `lifesteal-quests.yml` (issue #1425), Bukkit-free.
 *
 * - A missing scalar uses its default (a warning).
 * - A wrong scalar (bad timezone, negative cost, ...) is severe: [Result.settings] is null and the
 *   quest system pauses (no progress, claims or rerolls; nothing stored is touched).
 * - A broken template (unknown objective, crate or chat tag, bad reward, ...) only skips that
 *   template (a warning), so one typo never takes the whole quest board down.
 * - A pool left with fewer usable templates than quests per cycle is a warning: players get
 *   fewer quests that cycle (selection never repeats a template or an objective to fill up).
 */
object QuestConfig {

    data class Problem(val severe: Boolean, val message: String)

    data class Result(val settings: QuestSettings?, val problems: List<Problem>)

    /** Reads the file. [keys] lists the child keys of a section (null when it isn't one). */
    interface Source {
        fun get(path: String): Any?
        fun keys(path: String): List<String>?
    }

    const val MAX_QUESTS_PER_CYCLE = 3
    const val MAX_TARGET = 1_000_000_000L
    const val MAX_WEIGHT = 10_000
    const val MAX_REROLL_COST = 1_000_000.0
    private val ID = Regex("^[a-z0-9_]{1,48}$")

    fun parse(source: Source, catalog: PurchaseRewards.Catalog): Result {
        val problems = mutableListOf<Problem>()
        fun warn(m: String) { problems += Problem(false, m) }
        fun severe(m: String) { problems += Problem(true, m) }

        fun bool(path: String, default: Boolean): Boolean = when (val raw = source.get(path)) {
            null -> { warn("$path is missing; using $default."); default }
            is Boolean -> raw
            else -> { severe("$path must be true or false (got '$raw')."); default }
        }

        fun long(path: String, default: Long, min: Long, max: Long): Long {
            val raw = source.get(path) ?: run { warn("$path is missing; using $default."); return default }
            val value = when (raw) {
                is Int -> raw.toLong()
                is Long -> raw
                is Short -> raw.toLong()
                else -> null
            }
            if (value == null || value !in min..max) {
                severe("$path must be a whole number from $min to $max (got '$raw').")
                return default
            }
            return value
        }

        fun int(path: String, default: Int, min: Int, max: Int): Int = long(path, default.toLong(), min.toLong(), max.toLong()).toInt()

        fun double(path: String, default: Double, min: Double, max: Double): Double {
            val raw = source.get(path) ?: run { warn("$path is missing; using $default."); return default }
            val value = (raw as? Number)?.toDouble()
            if (value == null || !value.isFinite() || value < min || value > max) {
                severe("$path must be a number from $min to $max (got '$raw').")
                return default
            }
            return value
        }

        fun string(path: String, default: String): String = when (val raw = source.get(path)) {
            null -> { warn("$path is missing; using the default text."); default }
            is String -> raw
            else -> { severe("$path must be text (got '$raw')."); default }
        }

        fun time(path: String, default: LocalTime): LocalTime {
            val raw = source.get(path) ?: run { warn("$path is missing; using $default."); return default }
            return try {
                val text = raw.toString().trim()
                require(Regex("^\\d{2}:\\d{2}$").matches(text))
                LocalTime.parse(text)
            } catch (e: Exception) {
                severe("$path must be a time like \"00:00\" (got '$raw').")
                default
            }
        }

        val enabled = bool("enabled", true)
        val zone = when (val raw = source.get("timezone")) {
            null -> { warn("timezone is missing; using ${QuestSettings.DEFAULT_ZONE}."); QuestSettings.DEFAULT_ZONE }
            else -> try { ZoneId.of(raw.toString().trim()) } catch (e: Exception) {
                severe("timezone must be an IANA zone like America/New_York (got '$raw')."); QuestSettings.DEFAULT_ZONE
            }
        }
        val dailyReset = time("daily.reset-time", LocalTime.MIDNIGHT)
        val weeklyDay = when (val raw = source.get("weekly.reset-day")) {
            null -> { warn("weekly.reset-day is missing; using MONDAY."); DayOfWeek.MONDAY }
            else -> try { DayOfWeek.valueOf(raw.toString().trim().uppercase(Locale.ROOT)) } catch (e: IllegalArgumentException) {
                severe("weekly.reset-day must be a day like MONDAY (got '$raw')."); DayOfWeek.MONDAY
            }
        }
        val weeklyReset = time("weekly.reset-time", LocalTime.MIDNIGHT)
        val perCycle = QuestPeriod.entries.associateWith { int("${it.id}.quests-per-cycle", 3, 1, MAX_QUESTS_PER_CYCLE) }

        val rerollDefaults = mapOf(
            QuestPeriod.DAILY to RerollSettings(1, 25.0, 3),
            QuestPeriod.WEEKLY to RerollSettings(0, 75.0, 2),
        )
        val rerolls = QuestPeriod.entries.associateWith { p ->
            val d = rerollDefaults.getValue(p)
            RerollSettings(
                freePerCycle = int("rerolls.${p.id}.free-per-cycle", d.freePerCycle, 0, 100),
                creditCost = double("rerolls.${p.id}.credit-cost", d.creditCost, 0.0, MAX_REROLL_COST),
                maxPaidPerCycle = int("rerolls.${p.id}.max-paid-per-cycle", d.maxPaidPerCycle, 0, 100),
            )
        }
        val freeWhenIneligible = bool("rerolls.free-when-ineligible", true)

        val a = QuestAntiAbuse()
        val reasons = when (val raw = source.get("anti-abuse.mob-spawn-reasons")) {
            null -> { warn("anti-abuse.mob-spawn-reasons is missing; using the defaults."); a.mobSpawnReasons }
            is List<*> -> raw.map { it.toString().upperToken() }.toSet().also {
                if ("SPAWNER" in it || "SPAWNER_EGG" in it || "CUSTOM" in it) warn("anti-abuse.mob-spawn-reasons includes spawner/egg/plugin spawns: those mobs will count.")
            }
            else -> { severe("anti-abuse.mob-spawn-reasons must be a list (got '$raw')."); a.mobSpawnReasons }
        }
        val antiAbuse = QuestAntiAbuse(
            pvpSameVictimCooldownMillis = long("anti-abuse.pvp-same-victim-cooldown-minutes", 30, 0, 7 * 24 * 60) * 60_000L,
            pvpMaxKillsPerHour = int("anti-abuse.pvp-max-kills-per-hour", a.pvpMaxKillsPerHour, 0, 1000),
            pvpBlockSharedConnections = bool("anti-abuse.pvp-block-shared-connections", a.pvpBlockSharedConnections),
            pvpDamageCapHeartsPerVictimPerHour = int("anti-abuse.pvp-damage-cap-hearts-per-victim-per-hour", a.pvpDamageCapHeartsPerVictimPerHour, 0, 100_000),
            victoryWindowMillis = long("anti-abuse.victory-window-seconds", 30, 1, 600) * 1000L,
            assistWindowMillis = long("anti-abuse.assist-window-seconds", 15, 1, 600) * 1000L,
            teamRadius = double("anti-abuse.team-radius", a.teamRadius, 1.0, 512.0),
            idleMillis = long("anti-abuse.idle-seconds", 120, 10, 3600) * 1000L,
            mobSpawnReasons = reasons,
            maxMobKillsPerMinute = int("anti-abuse.max-mob-kills-per-minute", a.maxMobKillsPerMinute, 0, 10_000),
            fishingMinIntervalMillis = long("anti-abuse.fishing-min-interval-seconds", 3, 0, 600) * 1000L,
            requireCorrectTool = bool("anti-abuse.require-correct-tool", a.requireCorrectTool),
            exploreMaxBlocksPerSecond = double("anti-abuse.explore-max-blocks-per-second", a.exploreMaxBlocksPerSecond, 1.0, 100.0),
        )

        val g = QuestGuiText()
        val gui = QuestGuiText(
            title = string("gui.title", g.title),
            rerollTitle = string("gui.reroll-title", g.rerollTitle),
        )
        val m = QuestMessages()
        val messages = QuestMessages(
            completed = string("messages.completed", m.completed),
            claimed = string("messages.claimed", m.claimed),
            rerolled = string("messages.rerolled", m.rerolled),
            disabled = string("messages.disabled", m.disabled),
        )
        val completionMessages = bool("messages.completion-enabled", true)

        val pools = QuestPeriod.entries.associateWith { period ->
            val keys = source.keys("pools.${period.id}")
            if (keys == null) {
                severe("pools.${period.id} is missing: there are no ${period.label} quests to assign.")
                emptyList()
            } else {
                val parsed = keys.mapNotNull { key -> template(source, period, key, catalog, problems) }
                val usable = parsed.filter { it.enabled }
                if (usable.size < perCycle.getValue(period)) {
                    warn("pools.${period.id} has only ${usable.size} enabled quests (${perCycle.getValue(period)} per cycle): players get fewer ${period.label} quests.")
                }
                parsed
            }
        }
        val seen = HashSet<String>()
        for (t in pools.values.flatten()) {
            if (!seen.add(t.id)) severe("Quest id '${t.id}' is used in both the daily and the weekly pool; ids must be unique.")
        }

        val settings = QuestSettings(
            enabled = enabled,
            zone = zone,
            dailyResetTime = dailyReset,
            weeklyResetDay = weeklyDay,
            weeklyResetTime = weeklyReset,
            questsPerCycle = perCycle,
            rerolls = rerolls,
            freeRerollWhenIneligible = freeWhenIneligible,
            antiAbuse = antiAbuse,
            completionMessages = completionMessages,
            gui = gui,
            messages = messages,
            pools = pools,
        )
        return Result(if (problems.any { it.severe }) null else settings, problems)
    }

    /** One template, or null (with a warning) when it can't be used. */
    private fun template(
        source: Source, period: QuestPeriod, key: String, catalog: PurchaseRewards.Catalog, problems: MutableList<Problem>,
    ): QuestTemplate? {
        val base = "pools.${period.id}.$key"
        fun skip(why: String): QuestTemplate? {
            problems += Problem(false, "$base skipped: $why")
            return null
        }
        if (!ID.matches(key)) return skip("ids may only use a-z, 0-9 and _ (1-48 characters).")
        val objective = QuestObjective.of(source.get("$base.objective")?.toString())
            ?: return skip("unknown objective '${source.get("$base.objective")}'. Objectives: ${QuestObjective.entries.joinToString(", ") { it.id }}.")
        val category = when (val raw = source.get("$base.category")) {
            null -> objective.category
            else -> QuestCategory.of(raw.toString()) ?: return skip("unknown category '$raw' (combat, survival, teamwork).")
        }
        if (category != objective.category) return skip("objective ${objective.id} belongs to the ${objective.category.id} category, not '${category.id}'.")
        val target = when (val raw = source.get("$base.target")) {
            is Int -> raw.toLong()
            is Long -> raw
            else -> null
        }
        if (target == null || target < 1 || target > MAX_TARGET) return skip("target must be a whole number from 1 to $MAX_TARGET.")
        val name = source.get("$base.name")?.toString()?.takeIf { it.isNotBlank() } ?: return skip("name is missing.")
        val description = source.get("$base.description")?.toString() ?: ""
        val match = when (val raw = source.get("$base.match")) {
            null -> emptySet()
            is List<*> -> raw.map { it.toString().upperToken() }.filter { it.isNotEmpty() }.toSet()
            else -> return skip("match must be a list of material or entity names.")
        }
        if (match.isNotEmpty() && !objective.usesMatch) return skip("objective ${objective.id} doesn't use match.")
        if (match.isEmpty() && objective.requiresMatch) return skip("objective ${objective.id} needs a match list (which blocks count).")
        val team = when (val raw = source.get("$base.team")) {
            null -> if (objective.needsTeam) TeamRequirement.REQUIRED else TeamRequirement.ANY
            else -> TeamRequirement.of(raw.toString()) ?: return skip("team must be any, required or none (got '$raw').")
        }
        if (objective.needsTeam && team != TeamRequirement.REQUIRED) return skip("teamwork objective ${objective.id} needs team: required.")
        val enabled = when (val raw = source.get("$base.enabled")) {
            null -> true
            is Boolean -> raw
            else -> return skip("enabled must be true or false.")
        }
        val weight = when (val raw = source.get("$base.weight")) {
            null -> 10
            is Int -> raw
            else -> -1
        }
        if (weight < 1 || weight > MAX_WEIGHT) return skip("weight must be a whole number from 1 to $MAX_WEIGHT.")
        val group = source.get("$base.group")?.toString()?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
        val rawRewards = source.get("$base.rewards") as? List<*> ?: return skip("rewards must be a list like [\"coins 15000\"].")
        if (rawRewards.isEmpty()) return skip("rewards is empty.")
        val rewards = ArrayList<PurchaseRewards.Spec>()
        for (entry in rawRewards) {
            val args = entry.toString().trim().split(Regex("\\s+"))
            when (val r = PurchaseRewards.parse(args, catalog)) {
                is PurchaseRewards.Result.Error -> return skip("reward '$entry': ${r.message}")
                is PurchaseRewards.Result.Ok -> {
                    if (r.value.reference != null) return skip("reward '$entry' has an extra word.")
                    if (r.value.spec.kind !in QuestRewardKinds.ALLOWED) {
                        return skip("reward '$entry': ${r.value.spec.kind.id} can't be a quest reward (allowed: ${QuestRewardKinds.ALLOWED.joinToString(", ") { it.id }}).")
                    }
                    rewards += r.value.spec
                }
            }
        }
        return QuestTemplate(key, period, name, description, category, objective, target, match, rewards, team, enabled, weight, group)
    }
}
