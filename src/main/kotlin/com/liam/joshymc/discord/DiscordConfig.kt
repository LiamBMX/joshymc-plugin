package com.liam.joshymc.discord

import org.bukkit.configuration.Configuration

/**
 * Where the Discord integration's routing settings live (issue #1161).
 *
 * The bot's own channels sit under `discord.channels`, and the server it registers slash
 * commands in is `discord.guild-id`. Feature feeds that have their own config section keep
 * their channel next to their toggles (`anti-dupe.discord.channel-id`,
 * `combat-alerts.discord.channel-id`, `pvp-kill-logs.discord.channel-id`,
 * `ancient-debris-monitor.discord-channel-id`, `update-highlights.discord-channel`).
 *
 * Older keys are copied to the new ones once by [migrate], then ignored:
 * - `discord.channel-id` / `discord.chat-channel-id` -> `discord.channels.chat`
 * - `discord.staff-monitoring.anticheat-channel-id` -> `discord.channels.staff-anticheat`
 * - `discord.staff-monitoring.reports-channel-id` -> `discord.channels.staff-reports`
 * - `discord.punishments.forum-channel-id` -> `discord.channels.punishments-forum`
 */
object DiscordConfig {

    enum class Channel(val key: String, val legacyKeys: List<String>) {
        CHAT("discord.channels.chat", listOf("discord.channel-id", "discord.chat-channel-id")),
        STAFF_ANTICHEAT("discord.channels.staff-anticheat", listOf("discord.staff-monitoring.anticheat-channel-id")),
        STAFF_REPORTS("discord.channels.staff-reports", listOf("discord.staff-monitoring.reports-channel-id")),
        PUNISHMENTS_FORUM("discord.channels.punishments-forum", listOf("discord.punishments.forum-channel-id")),
    }

    const val GUILD_ID_KEY = "discord.guild-id"
    /** The guild slash commands were registered in before it became configurable. */
    const val LEGACY_GUILD_ID = "1284630112234508330"

    /**
     * The configured id for [channel]: the canonical key when the file has it, otherwise the
     * first deprecated key the file has, otherwise "". Only values actually in config.yml
     * count: the bundled defaults never stand in for a missing key, so a test server with an
     * incomplete config can't fall back to production channels.
     */
    fun channelId(config: Configuration, channel: Channel): String {
        for (key in listOf(channel.key) + channel.legacyKeys) {
            if (config.contains(key, true)) return config.getString(key)?.trim().orEmpty()
        }
        return ""
    }

    /** The guild to register slash commands in, or "" to skip registration. */
    fun guildId(config: Configuration): String =
        if (config.contains(GUILD_ID_KEY, true)) config.getString(GUILD_ID_KEY)?.trim().orEmpty() else LEGACY_GUILD_ID

    /**
     * Copies each deprecated key's value to its canonical key when the canonical key isn't in
     * the file yet, so upgrading keeps every channel exactly where it was. Runs before the
     * default backfill, which would otherwise fill the canonical keys with bundled values.
     * Never overwrites a canonical key and leaves the old keys in place (they are ignored
     * from then on). Returns log lines describing what moved.
     */
    fun migrate(config: Configuration): List<String> {
        val moved = mutableListOf<String>()
        for (channel in Channel.entries) {
            if (config.contains(channel.key, true)) continue
            val legacy = channel.legacyKeys.firstOrNull { config.contains(it, true) } ?: continue
            config.set(channel.key, config.getString(legacy)?.trim().orEmpty())
            moved += "$legacy -> ${channel.key}"
        }
        // discord.guild-id needs nothing here: the backfill adds it with the same id that
        // used to be hardcoded, so existing installs keep registering in that guild.
        return moved
    }
}
