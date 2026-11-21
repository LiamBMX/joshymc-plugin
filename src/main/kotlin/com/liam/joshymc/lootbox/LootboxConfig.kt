package com.liam.joshymc.lootbox

import com.liam.joshymc.gamemode.ServerGamemode
import java.util.Locale

/**
 * What a reward hands out (issue #1602). [delivery] decides how it reaches the player:
 * items wait for inventory room, currencies and unlocks are one transaction, commands run once.
 */
enum class LootboxRewardType(val id: String, val delivery: Delivery) {
    /** A vanilla item, optionally with vanilla and JoshyMC custom enchantments. */
    ITEM("item", Delivery.ITEM),
    /** A registered JoshyMC custom item (`/joshymc give` id), built by its own class. */
    CUSTOM_ITEM("custom-item", Delivery.ITEM),
    /** An exact item snapshot (Base64 of `serializeAsBytes`, like crates.yml `item-base64`). */
    ITEM_SNAPSHOT("item-base64", Delivery.ITEM),
    /** Keys of an existing crate. */
    CRATE_KEY("crate-key", Delivery.ITEM),
    /** A Chat Tag Voucher item for an existing voucher tag. */
    CHAT_TAG_VOUCHER("chat-tag-voucher", Delivery.ITEM),
    /** A voucher chat tag unlocked straight on the player's account (`chat_tag_unlocks`). */
    CHAT_TAG("chat-tag", Delivery.UNLOCK),
    COINS("coins", Delivery.CURRENCY),
    CREDITS("credits", Delivery.CURRENCY),
    /** Console commands the Owner configured, run once ({player}, {uuid}). */
    COMMAND("command", Delivery.COMMAND);

    enum class Delivery { ITEM, CURRENCY, UNLOCK, COMMAND }

    companion object {
        fun of(id: String?): LootboxRewardType? = entries.firstOrNull { it.id == id?.trim()?.lowercase(Locale.ROOT) }
    }
}

/** One entry of a level's loot pool. */
data class LootboxReward(
    val id: String,
    /** `&` colour codes. */
    val name: String,
    val type: LootboxRewardType,
    /** Material, custom item id, Base64 snapshot, crate id or tag id; empty for currencies and commands. */
    val reference: String,
    /** Item count (items, keys, vouchers); 1 for everything else. */
    val amount: Int,
    /** Coins / Credits amount; 0 for everything else. */
    val money: Double,
    val weight: Double,
    /** Material shown for this reward while rolling and in the summary; null = the item itself / a default icon. */
    val preview: String?,
    /** Only players with this permission can win it (others never draw it). */
    val permission: String?,
    /** Gamemodes it can be won in; null = the level's gamemodes. */
    val gamemodes: Set<ServerGamemode>?,
    /** At most once per opening (otherwise draws are with replacement). */
    val unique: Boolean,
    val enchantments: Map<String, Int>,
    val customEnchantments: Map<String, Int>,
    val commands: List<String>,
    val enabled: Boolean,
)

data class LootboxLevel(
    val level: Int,
    val name: String,
    val description: List<String>,
    val material: String,
    /** Optional item model (`namespace:path`) for when the art exists; null = the material's own look. */
    val itemModel: String?,
    val glint: Boolean,
    val enabled: Boolean,
    val gamemodes: Set<ServerGamemode>,
    val rewards: List<LootboxReward>,
    /** Anything wrong with this level; a level with problems can't be opened or given. */
    val problems: List<String>,
) {
    val activeRewards: List<LootboxReward> get() = rewards.filter { it.enabled }
}

data class LootboxAnimationSettings(
    /** Ticks between preview changes when a reel starts. */
    val startIntervalTicks: Int,
    /** Ticks between preview changes right before a reel stops. */
    val endIntervalTicks: Int,
    /** When the first reel stops. */
    val firstStopTicks: Int,
    /** How much later each next reel stops. */
    val staggerTicks: Int,
    /** Pause after the last reel before the reward summary opens. */
    val summaryDelayTicks: Int,
)

data class LootboxSound(val sound: String, val volume: Float, val pitch: Float)

data class LootboxGuiSettings(
    val selectionTitle: String,
    val openingTitle: String,
    val summaryTitle: String,
    val mysteryMaterial: String,
    val selectedMaterial: String,
    val fillerMaterial: String,
    val rollingFillerMaterial: String,
    val stoppedMaterial: String,
)

data class LootboxSettings(
    val enabled: Boolean,
    val levels: Map<Int, LootboxLevel>,
    val gui: LootboxGuiSettings,
    val animation: LootboxAnimationSettings,
    /** Sound id → sound; a null value = that sound is off. */
    val sounds: Map<String, LootboxSound?>,
    /** Message id → `&` text. */
    val messages: Map<String, String>,
) {
    fun level(level: Int): LootboxLevel? = levels[level]
    fun message(id: String): String = messages[id] ?: LootboxConfig.DEFAULT_MESSAGES[id] ?: id
}

/**
 * Parses `lootboxes.yml` (issue #1602), Bukkit-free. Things only the server can check (materials,
 * enchantments, custom items, crates, tags, sounds, snapshots) go through [Resolver]. A problem in
 * the file's top-level settings means [Result.settings] is null (Lootboxes stay closed, nothing is
 * guessed); a problem in one level only closes that level. Disabled rewards are parsed for their
 * shape but not resolved, so the shipped examples never break a level.
 */
object LootboxConfig {

    const val LEVELS = 10
    const val MAX_ITEM_AMOUNT = 36 * 64
    const val MAX_MONEY = 1_000_000_000.0
    const val MAX_WEIGHT = 1_000_000_000.0
    const val MAX_REWARDS_PER_LEVEL = 200
    const val MAX_COMMANDS = 10

    private val ID = Regex("^[a-z0-9_-]{1,48}$")
    private val ITEM_MODEL = Regex("^[a-z0-9_.-]+:[a-z0-9_./-]+$")
    private val PERMISSION = Regex("^[A-Za-z0-9_.*-]{1,128}$")

    data class Result(val settings: LootboxSettings?, val problems: List<String>)

    interface Source {
        /** A scalar or list at [path]; null when missing (never a section). */
        fun get(path: String): Any?
        /** The keys of the section at [path]; null when it isn't a section. */
        fun keys(path: String): List<String>?
    }

    /** Server-side lookups; each returns a problem description, or null when fine. */
    interface Resolver {
        fun itemMaterial(name: String): String?
        fun guiMaterial(name: String): String?
        fun enchantment(material: String, key: String, level: Int): String?
        fun customEnchantment(material: String, id: String, level: Int): String?
        fun customItem(id: String): String?
        fun snapshot(base64: String): String?
        fun crate(id: String): String?
        fun chatTag(id: String): String?
        fun sound(key: String): String?
    }

    val DEFAULT_MESSAGES: Map<String, String> = linkedMapOf(
        "disabled" to "&cLootboxes are disabled right now. You still have your Lootbox.",
        "level-unavailable" to "&cLevel {level} Lootboxes aren't available right now. You still have your Lootbox.",
        "wrong-gamemode" to "&cLevel {level} Lootboxes can't be opened in {gamemode}.",
        "nothing-to-win" to "&cThere's nothing you can win from this Lootbox here. You still have it.",
        "invalid-item" to "&cThat Lootbox isn't valid.",
        "busy" to "&cFinish the Lootbox you're opening first.",
        "selection-full" to "&eYou already picked 5. Click a picked slot to change it.",
        "selection-cancelled" to "&7Selection cancelled. Your Lootbox was not used.",
        "lootbox-gone" to "&cYou no longer have a Level {level} Lootbox.",
        "opened" to "&aYou opened a {name}&a!",
        "rewards-header" to "&6&lLevel {level} Lootbox rewards:",
        "reward-line" to "&8 • &f{reward}",
        "rewards-waiting" to "&eSome rewards are waiting for inventory space. Free up room and they arrive within a minute.",
        "rewards-waiting-here" to "&eYour Lootbox rewards will arrive once you leave this area.",
        "chat-tag-owned" to "&eYou already owned the {reward} &echat tag.",
        "chat-tag-unlocked" to "&aChat tag unlocked: {reward}&a. Equip it with /tags.",
        "save-failed" to "&cYour opening couldn't be saved. Your Lootbox was not used; try again.",
        "review" to "&cYour opening (#{id}) needs a staff check. Your rewards are not lost.",
    )

    val DEFAULT_SOUNDS: Map<String, LootboxSound> = linkedMapOf(
        "open" to LootboxSound("BLOCK_CHEST_OPEN", 0.7f, 1.0f),
        "select" to LootboxSound("UI_BUTTON_CLICK", 0.6f, 1.4f),
        "deselect" to LootboxSound("UI_BUTTON_CLICK", 0.6f, 0.8f),
        "denied" to LootboxSound("BLOCK_NOTE_BLOCK_BASS", 0.7f, 0.6f),
        "confirm" to LootboxSound("BLOCK_ENDER_CHEST_OPEN", 0.8f, 1.2f),
        "roll" to LootboxSound("BLOCK_NOTE_BLOCK_HAT", 0.4f, 1.6f),
        "reel-stop" to LootboxSound("BLOCK_NOTE_BLOCK_PLING", 0.8f, 1.4f),
        "finish" to LootboxSound("ENTITY_PLAYER_LEVELUP", 0.8f, 1.2f),
        "claim" to LootboxSound("ENTITY_EXPERIENCE_ORB_PICKUP", 0.8f, 1.0f),
    )

    fun parse(source: Source, resolver: Resolver): Result {
        val problems = mutableListOf<String>()
        val reader = Reader(source, problems)

        val enabled = reader.bool("enabled", true)

        val gui = LootboxGuiSettings(
            selectionTitle = reader.string("gui.selection-title", "&8Level {level} Lootbox"),
            openingTitle = reader.string("gui.opening-title", "&8Opening Level {level} Lootbox..."),
            summaryTitle = reader.string("gui.summary-title", "&8Level {level} Lootbox Rewards"),
            mysteryMaterial = reader.material("gui.mystery-material", "ENDER_CHEST", resolver),
            selectedMaterial = reader.material("gui.selected-material", "LIME_STAINED_GLASS_PANE", resolver),
            fillerMaterial = reader.material("gui.filler-material", "BLACK_STAINED_GLASS_PANE", resolver),
            rollingFillerMaterial = reader.material("gui.rolling-filler-material", "GRAY_STAINED_GLASS_PANE", resolver),
            stoppedMaterial = reader.material("gui.stopped-material", "LIME_STAINED_GLASS_PANE", resolver),
        )

        val start = reader.int("animation.start-interval-ticks", 2, 1..20)
        val end = reader.int("animation.end-interval-ticks", 8, 1..40)
        val firstStop = reader.int("animation.first-stop-ticks", 40, 10..400)
        val stagger = reader.int("animation.stagger-ticks", 12, 0..200)
        val summaryDelay = reader.int("animation.summary-delay-ticks", 30, 0..200)
        if (end < start) problems += "animation.end-interval-ticks ($end) must not be below animation.start-interval-ticks ($start)."
        val animation = LootboxAnimationSettings(start, end, firstStop, stagger, summaryDelay)

        val sounds = LinkedHashMap<String, LootboxSound?>()
        for ((id, default) in DEFAULT_SOUNDS) sounds[id] = reader.sound("sounds.$id", default, resolver)
        source.keys("sounds")?.filter { it !in DEFAULT_SOUNDS }?.forEach { problems += "sounds.$it is not a Lootbox sound (${DEFAULT_SOUNDS.keys.joinToString(", ")})." }

        val messages = LinkedHashMap(DEFAULT_MESSAGES)
        for (key in source.keys("messages").orEmpty()) {
            if (key !in DEFAULT_MESSAGES) {
                problems += "messages.$key is not a Lootbox message (${DEFAULT_MESSAGES.keys.joinToString(", ")})."
                continue
            }
            messages[key] = reader.string("messages.$key", DEFAULT_MESSAGES.getValue(key))
        }

        val levelKeys = source.keys("levels")
        if (levelKeys == null) problems += "levels is missing: it must list levels 1 to $LEVELS."
        for (key in levelKeys.orEmpty()) {
            val n = key.toIntOrNull()
            if (n == null || n !in 1..LEVELS) problems += "levels.$key: only levels 1 to $LEVELS exist."
        }

        // Top-level problems close everything: a level parse below can't make them go away.
        val topLevelProblems = problems.toList()
        val levels = (1..LEVELS).associateWith { parseLevel(source, resolver, it) }

        val settings = if (topLevelProblems.isEmpty()) LootboxSettings(enabled, levels, gui, animation, sounds, messages) else null
        val allProblems = topLevelProblems + levels.values.flatMap { level -> level.problems.map { "levels.${level.level}: $it" } }
        return Result(settings, allProblems)
    }

    private fun parseLevel(source: Source, resolver: Resolver, level: Int): LootboxLevel {
        val problems = mutableListOf<String>()
        val r = Reader(source, problems, prefix = "levels.$level.")
        val base = "levels.$level"
        val present = source.keys(base) != null
        if (!present) problems += "is missing from the file."

        val name = r.string("$base.name", "&6Level $level Lootbox")
        val description = r.stringList("$base.description", listOf("&7A mystery box of Level $level rewards."))
        val material = r.material("$base.material", "CHEST", resolver, item = true)
        val itemModel = r.optionalString("$base.item-model")?.lowercase(Locale.ROOT)?.also {
            if (!ITEM_MODEL.matches(it)) problems += "item-model '$it' must look like namespace:path."
        }
        val glint = r.bool("$base.glint", false)
        val enabled = r.bool("$base.enabled", true)
        val gamemodes = r.gamemodes("$base.gamemodes") ?: ServerGamemode.entries.toSet()
        if (gamemodes.isEmpty()) problems += "gamemodes must list at least one gamemode."

        val rewards = mutableListOf<LootboxReward>()
        val rewardKeys = source.keys("$base.rewards")
        if (rewardKeys == null && source.get("$base.rewards") != null) problems += "rewards must be a section of reward ids."
        if ((rewardKeys?.size ?: 0) > MAX_REWARDS_PER_LEVEL) problems += "has more than $MAX_REWARDS_PER_LEVEL rewards."
        val seen = HashSet<String>()
        for (rawId in rewardKeys.orEmpty().take(MAX_REWARDS_PER_LEVEL)) {
            val id = rawId.lowercase(Locale.ROOT)
            if (!ID.matches(id)) {
                problems += "reward id '$rawId' may only use a-z, 0-9, _ and - (1-48 characters)."
                continue
            }
            if (!seen.add(id)) {
                problems += "reward id '$rawId' is used twice."
                continue
            }
            parseReward(source, resolver, "$base.rewards.$rawId", id, gamemodes, problems)?.let { rewards += it }
        }
        return LootboxLevel(level, name, description, material, itemModel, glint, enabled, gamemodes, rewards, problems)
    }

    private fun parseReward(
        source: Source, resolver: Resolver, path: String, id: String,
        levelGamemodes: Set<ServerGamemode>, problems: MutableList<String>,
    ): LootboxReward? {
        val before = problems.size
        val r = Reader(source, problems, prefix = path.substringBefore(".rewards.") + ".")
        val enabled = r.bool("$path.enabled", true)
        val label = "reward '$id'"
        val typeRaw = source.get("$path.type")?.toString()
        val type = LootboxRewardType.of(typeRaw)
        if (type == null) {
            problems += "$label: type '${typeRaw ?: ""}' isn't one of ${LootboxRewardType.entries.joinToString(", ") { it.id }}."
            return null
        }

        val weight = r.weight("$path.weight", label)
        val name = r.string("$path.name", id)
        val permission = r.optionalString("$path.permission")?.also {
            if (!PERMISSION.matches(it)) problems += "$label: permission '$it' isn't a valid permission node."
        }
        val gamemodes = r.gamemodes("$path.gamemodes")
        if (gamemodes != null) {
            if (gamemodes.isEmpty()) problems += "$label: gamemodes must list at least one gamemode."
            val outside = gamemodes - levelGamemodes
            if (outside.isNotEmpty()) problems += "$label: gamemodes ${outside.joinToString(", ") { it.id }} aren't in the level's gamemodes."
        }
        val unique = r.bool("$path.unique", false)
        val preview = r.optionalString("$path.preview")?.uppercase(Locale.ROOT)
        if (preview != null && enabled) resolver.itemMaterial(preview)?.let { problems += "$label: preview $it" }

        var reference = ""
        var amount = 1
        var money = 0.0
        var enchantments = emptyMap<String, Int>()
        var customEnchantments = emptyMap<String, Int>()
        var commands = emptyList<String>()

        fun need(key: String): String? {
            val value = r.optionalString("$path.$key")
            if (value == null) problems += "$label: '$key' is required for type ${type.id}."
            return value
        }

        when (type) {
            LootboxRewardType.ITEM -> {
                reference = need("material")?.uppercase(Locale.ROOT) ?: ""
                amount = r.int("$path.amount", 1, 1..MAX_ITEM_AMOUNT)
                enchantments = r.levels("$path.enchantments", label)
                customEnchantments = r.levels("$path.custom-enchantments", label)
                if (enabled && reference.isNotEmpty()) {
                    val materialProblem = resolver.itemMaterial(reference)
                    if (materialProblem != null) problems += "$label: $materialProblem"
                    else {
                        for ((key, level) in enchantments) resolver.enchantment(reference, key, level)?.let { problems += "$label: $it" }
                        for ((key, level) in customEnchantments) resolver.customEnchantment(reference, key, level)?.let { problems += "$label: $it" }
                    }
                }
            }
            LootboxRewardType.CUSTOM_ITEM -> {
                reference = need("item")?.lowercase(Locale.ROOT) ?: ""
                amount = r.int("$path.amount", 1, 1..MAX_ITEM_AMOUNT)
                if (enabled && reference.isNotEmpty()) resolver.customItem(reference)?.let { problems += "$label: $it" }
            }
            LootboxRewardType.ITEM_SNAPSHOT -> {
                reference = need("item-base64")?.replace(Regex("\\s"), "") ?: ""
                amount = r.int("$path.amount", 1, 1..MAX_ITEM_AMOUNT)
                if (enabled && reference.isNotEmpty()) resolver.snapshot(reference)?.let { problems += "$label: $it" }
            }
            LootboxRewardType.CRATE_KEY -> {
                reference = need("crate")?.lowercase(Locale.ROOT) ?: ""
                amount = r.int("$path.amount", 1, 1..MAX_ITEM_AMOUNT)
                if (enabled && reference.isNotEmpty()) resolver.crate(reference)?.let { problems += "$label: $it" }
            }
            LootboxRewardType.CHAT_TAG_VOUCHER -> {
                reference = need("tag")?.lowercase(Locale.ROOT) ?: ""
                amount = r.int("$path.amount", 1, 1..64)
                if (enabled && reference.isNotEmpty()) resolver.chatTag(reference)?.let { problems += "$label: $it" }
            }
            LootboxRewardType.CHAT_TAG -> {
                reference = need("tag")?.lowercase(Locale.ROOT) ?: ""
                if (source.get("$path.amount") != null) problems += "$label: a chat-tag unlock has no amount."
                if (enabled && reference.isNotEmpty()) resolver.chatTag(reference)?.let { problems += "$label: $it" }
            }
            LootboxRewardType.COINS, LootboxRewardType.CREDITS -> {
                money = r.money("$path.amount", label)
            }
            LootboxRewardType.COMMAND -> {
                commands = r.stringList("$path.commands", emptyList()).map { it.trim().removePrefix("/") }.filter { it.isNotEmpty() }
                if (commands.isEmpty()) problems += "$label: a command reward needs at least one entry in 'commands'."
                if (commands.size > MAX_COMMANDS) problems += "$label: at most $MAX_COMMANDS commands."
                if (commands.any { it.contains('\n') }) problems += "$label: a command can't span lines."
            }
        }
        if (type != LootboxRewardType.COMMAND && source.get("$path.commands") != null) {
            problems += "$label: 'commands' only belongs to type command."
        }
        if (type != LootboxRewardType.ITEM && (source.get("$path.enchantments") != null || source.keys("$path.enchantments") != null ||
                source.keys("$path.custom-enchantments") != null)) {
            problems += "$label: enchantments only belong to type item (put enchanted custom gear in an item-base64 snapshot)."
        }
        if (problems.size > before) return null
        return LootboxReward(
            id, name, type, reference, amount, money, weight, preview, permission, gamemodes, unique,
            enchantments, customEnchantments, commands, enabled,
        )
    }

    /** Small typed reader that records a problem (and uses the default) for every value of the wrong kind. */
    private class Reader(val source: Source, val problems: MutableList<String>, val prefix: String = "") {

        /** [path] as shown in a problem: relative to the level ("rewards.x.amount"), since level problems are prefixed with it. */
        private fun where(path: String): String = path.removePrefix(prefix)

        fun bool(path: String, default: Boolean): Boolean = when (val raw = source.get(path)) {
            null -> default
            is Boolean -> raw
            else -> { problems += "${where(path)} must be true or false (got '$raw')."; default }
        }

        fun string(path: String, default: String): String = when (val raw = source.get(path)) {
            null -> default
            is String, is Number, is Boolean -> raw.toString()
            else -> { problems += "${where(path)} must be text."; default }
        }

        fun optionalString(path: String): String? = when (val raw = source.get(path)) {
            null -> null
            is String, is Number, is Boolean -> raw.toString().trim().takeIf { it.isNotEmpty() }
            else -> { problems += "${where(path)} must be text."; null }
        }

        fun stringList(path: String, default: List<String>): List<String> = when (val raw = source.get(path)) {
            null -> default
            is List<*> -> raw.mapNotNull { item ->
                when (item) {
                    is String, is Number, is Boolean -> item.toString()
                    else -> { problems += "${where(path)} must be a list of text lines."; null }
                }
            }
            is String -> listOf(raw)
            else -> { problems += "${where(path)} must be a list of text lines."; default }
        }

        fun int(path: String, default: Int, range: IntRange): Int {
            val raw = source.get(path) ?: return default
            val value = (raw as? Number)?.takeIf { it !is Double && it !is Float }?.toLong()
            if (value == null || value !in range.first.toLong()..range.last.toLong()) {
                problems += "${where(path)} must be a whole number from ${range.first} to ${range.last} (got '$raw')."
                return default
            }
            return value.toInt()
        }

        fun weight(path: String, label: String): Double {
            val raw = source.get(path)
            val value = (raw as? Number)?.toDouble()
            if (raw == null) {
                problems += "$label: 'weight' is required (a number above 0)."
                return 1.0
            }
            if (value == null || !value.isFinite() || value <= 0.0 || value > MAX_WEIGHT) {
                problems += "$label: weight must be a finite number above 0 and at most ${MAX_WEIGHT.toLong()} (got '$raw')."
                return 1.0
            }
            return value
        }

        fun money(path: String, label: String): Double {
            val raw = source.get(path)
            val value = (raw as? Number)?.toDouble()
            if (value == null || !value.isFinite() || value <= 0.0 || value > MAX_MONEY) {
                problems += "$label: amount must be a number above 0 and at most ${MAX_MONEY.toLong()} (got '${raw ?: ""}')."
                return 0.0
            }
            val cents = Math.round(value * 100.0) / 100.0
            if (cents <= 0.0) {
                problems += "$label: amount rounds to 0."
                return 0.0
            }
            return cents
        }

        fun levels(path: String, label: String): Map<String, Int> {
            val keys = source.keys(path) ?: run {
                if (source.get(path) != null) problems += "$label: ${path.substringAfterLast('.')} must be a section of name: level."
                return emptyMap()
            }
            val out = LinkedHashMap<String, Int>()
            for (key in keys) {
                val raw = source.get("$path.$key")
                val level = (raw as? Number)?.takeIf { it !is Double && it !is Float }?.toInt()
                if (level == null || level < 1 || level > 255) {
                    problems += "$label: ${path.substringAfterLast('.')}.$key must be a level from 1 to 255 (got '$raw')."
                    continue
                }
                out[key.lowercase(Locale.ROOT)] = level
            }
            return out
        }

        fun gamemodes(path: String): Set<ServerGamemode>? {
            val raw = source.get(path) ?: return null
            val list = when (raw) {
                is List<*> -> raw.map { it?.toString() ?: "" }
                is String -> listOf(raw)
                else -> { problems += "${where(path)} must be a list of gamemodes (survival, lifesteal)."; return emptySet() }
            }
            val out = LinkedHashSet<ServerGamemode>()
            for (entry in list) {
                val gm = ServerGamemode.fromId(entry)
                if (gm == null) problems += "${where(path)}: '$entry' isn't a gamemode (survival, lifesteal)."
                else out += gm
            }
            return out
        }

        fun material(path: String, default: String, resolver: Resolver, item: Boolean = false): String {
            val name = optionalString(path)?.uppercase(Locale.ROOT) ?: return default
            val problem = if (item) resolver.itemMaterial(name) else resolver.guiMaterial(name)
            if (problem != null) {
                problems += "${where(path)}: $problem"
                return default
            }
            return name
        }

        fun sound(path: String, default: LootboxSound, resolver: Resolver): LootboxSound? {
            if (source.get(path) == false) return null
            val key = optionalString("$path.sound")?.uppercase(Locale.ROOT)
            val volume = (source.get("$path.volume") as? Number)?.toFloat() ?: default.volume
            val pitch = (source.get("$path.pitch") as? Number)?.toFloat() ?: default.pitch
            if (volume !in 0f..10f) problems += "${where(path)}.volume must be from 0 to 10."
            if (pitch !in 0.5f..2f) problems += "${where(path)}.pitch must be from 0.5 to 2."
            val sound = key ?: default.sound
            resolver.sound(sound)?.let { problems += "${where(path)}.sound: $it" }
            return LootboxSound(sound, volume.coerceIn(0f, 10f), pitch.coerceIn(0.5f, 2f))
        }
    }

    /** Why [level] can't be opened or given at all (independent of who opens it), or null when it can. */
    fun levelProblem(settings: LootboxSettings?, level: Int): String? {
        if (level !in 1..LEVELS) return "Lootbox levels go from 1 to $LEVELS."
        if (settings == null) return "lootboxes.yml is invalid; see the server log or /lootbox list."
        if (!settings.enabled) return "Lootboxes are disabled (lootboxes.yml enabled: false)."
        val def = settings.level(level) ?: return "Level $level is missing from lootboxes.yml."
        if (!def.enabled) return "Level $level is disabled in lootboxes.yml."
        if (def.problems.isNotEmpty()) return "Level $level has configuration problems: ${def.problems.first()}"
        val pool = def.activeRewards
        if (pool.isEmpty()) return "Level $level has no enabled rewards in lootboxes.yml."
        if (!LootboxDraw.canFill(pool.map { LootboxDraw.Entry(it, it.weight, it.unique) }, LootboxDraw.PRIZES)) {
            return "Level $level only has ${pool.size} unique reward(s); it needs ${LootboxDraw.PRIZES} or one reward that may repeat."
        }
        return null
    }
}
