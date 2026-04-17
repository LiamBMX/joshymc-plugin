package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.entity.Player

class CommunicationsManager(private val plugin: Joshymc) {

    // Supports & color codes AND &#RRGGBB hex colors (e.g., "&#FF5555&lRed")
    private val legacySerializer = LegacyComponentSerializer.builder()
        .character('&')
        .hexCharacter('#')
        .hexColors()
        .build()

    private var chatFormat: String = "{prefix}{tag}&f{player}{suffix} &8\u00BB &f{message}"
    private var defaultPrefix: Component = Component.empty()

    /**
     * Category prefixes — each feature area gets its own styled prefix.
     * Format: "LABEL" in bold + dark gray " » "
     */
    enum class Category(val label: String, val color: TextColor) {
        DEFAULT("JOSHYMC", TextColor.color(0x55FFFF)),
        WARP("WARP", TextColor.color(0x55FF55)),
        HOME("WARP", TextColor.color(0x55FF55)),
        COMBAT("COMBAT", TextColor.color(0xFF5555)),
        AFK("AFK", TextColor.color(0xAAAAAA)),
        MINING("MINING", TextColor.color(0x55FFFF)),
        SETTINGS("SETTINGS", TextColor.color(0xBB99FF)),
        TELEPORT("WARP", TextColor.color(0x55FF55)),
        ADMIN("ADMIN", TextColor.color(0xFF5555)),
        ECONOMY("ECONOMY", TextColor.color(0xFFD700)),
    }

    private val categoryPrefixes = mutableMapOf<Category, Component>()

    fun start() {
        chatFormat = plugin.config.getString("chat.format", chatFormat) ?: chatFormat

        // Build category prefixes — wrapped in a non-bold parent so appended messages don't inherit bold
        for (cat in Category.entries) {
            categoryPrefixes[cat] = Component.empty()
                .append(Component.text(cat.label, cat.color).decoration(TextDecoration.BOLD, true))
                .append(Component.text(" \u00BB ", NamedTextColor.DARK_GRAY).decoration(TextDecoration.BOLD, false))
        }

        defaultPrefix = categoryPrefixes[Category.DEFAULT]!!

        plugin.logger.info("[Comms] Communications manager started.")
    }

    private fun prefix(category: Category): Component {
        return categoryPrefixes[category] ?: defaultPrefix
    }

    // ---- Public messaging API ----

    fun broadcast(message: Component, category: Category = Category.DEFAULT) {
        val prefixed = prefix(category).append(message)
        Bukkit.getOnlinePlayers().forEach { it.sendMessage(prefixed) }
    }

    fun broadcastActionBar(message: Component) {
        Bukkit.getOnlinePlayers().forEach { it.sendActionBar(message) }
    }

    fun send(player: Player, message: Component, category: Category = Category.DEFAULT) {
        player.sendMessage(prefix(category).append(message))
    }

    fun sendRaw(player: Player, message: Component) {
        player.sendMessage(message)
    }

    fun sendActionBar(player: Player, message: Component) {
        player.sendActionBar(message)
    }

    // ---- Chat formatting ----

    fun formatChat(player: Player, message: Component): Component {
        val prefix = getPlayerPrefix(player)
        val suffix = getPlayerSuffix(player)

        // Use nickname if set (displayName differs from real name)
        val displayComponent = player.displayName()
        val displaySerialized = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(displayComponent)
        val playerName = if (displaySerialized != player.name) {
            // Has a nickname — serialize with color codes for legacy format
            LegacyComponentSerializer.legacyAmpersand().serialize(displayComponent)
        } else {
            player.name
        }

        val tag = plugin.chatTagManager.getPlayerTagDisplay(player)

        // Build the prefix portion as legacy (rank, tag, name, separator)
        val prefixPart = chatFormat
            .replace("{prefix}", prefix)
            .replace("{suffix}", suffix)
            .replace("{tag}", tag)
            .replace("{player}", playerName)
            .replace("{message}", "")

        val prefixComponent = parseLegacy(prefixPart)

        // Apply chat color to the message while preserving hover events
        val chatColorId = com.liam.joshymc.command.ChatColorCommand.getPlayerColor(plugin, player.uniqueId)
        val messageComponent = if (chatColorId != null) {
            val colorCode = com.liam.joshymc.command.ChatColorCommand.CHAT_COLORS[chatColorId]
            if (colorCode != null && colorCode.startsWith("&") && colorCode != "&l") {
                // Simple color — map & code to NamedTextColor
                val namedColor = when (colorCode) {
                    "&0" -> net.kyori.adventure.text.format.NamedTextColor.BLACK
                    "&1" -> net.kyori.adventure.text.format.NamedTextColor.DARK_BLUE
                    "&2" -> net.kyori.adventure.text.format.NamedTextColor.DARK_GREEN
                    "&3" -> net.kyori.adventure.text.format.NamedTextColor.DARK_AQUA
                    "&4" -> net.kyori.adventure.text.format.NamedTextColor.DARK_RED
                    "&5" -> net.kyori.adventure.text.format.NamedTextColor.DARK_PURPLE
                    "&6" -> net.kyori.adventure.text.format.NamedTextColor.GOLD
                    "&7" -> net.kyori.adventure.text.format.NamedTextColor.GRAY
                    "&8" -> net.kyori.adventure.text.format.NamedTextColor.DARK_GRAY
                    "&9" -> net.kyori.adventure.text.format.NamedTextColor.BLUE
                    "&a" -> net.kyori.adventure.text.format.NamedTextColor.GREEN
                    "&b" -> net.kyori.adventure.text.format.NamedTextColor.AQUA
                    "&c" -> net.kyori.adventure.text.format.NamedTextColor.RED
                    "&d" -> net.kyori.adventure.text.format.NamedTextColor.LIGHT_PURPLE
                    "&e" -> net.kyori.adventure.text.format.NamedTextColor.YELLOW
                    "&f" -> net.kyori.adventure.text.format.NamedTextColor.WHITE
                    else -> null
                }
                if (namedColor != null) message.color(namedColor) else message
            } else if (colorCode == "&l") {
                message.decoration(net.kyori.adventure.text.format.TextDecoration.BOLD, true)
            } else {
                // Rainbow/gradient — serialize to plain text, apply color, re-parse
                val plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(message)
                val colored = com.liam.joshymc.command.ChatColorCommand.applyColor(plugin, player.uniqueId, plain)
                parseLegacy(colored)
            }
        } else {
            message
        }

        return prefixComponent.append(messageComponent)
    }

    // ---- LuckPerms integration (runtime reflection, no compile dependency) ----

    private val luckPermsProvider: Any? by lazy {
        try {
            val clazz = Class.forName("net.luckperms.api.LuckPerms")
            val registration = Bukkit.getServicesManager().getRegistration(clazz)
            registration?.provider
        } catch (_: Exception) {
            plugin.logger.info("[Comms] LuckPerms not found — chat prefixes/suffixes disabled.")
            null
        }
    }

    private fun getPlayerPrefix(player: Player): String {
        // Try LuckPerms first, fall back to our rank system
        val lp = getLuckPermsMeta(player, "getPrefix")
        if (lp.isNotEmpty()) return lp
        return plugin.rankManager.getPrefix(player)
    }

    private fun getPlayerSuffix(player: Player): String {
        return getLuckPermsMeta(player, "getSuffix")
    }

    private fun getLuckPermsMeta(player: Player, metaMethod: String): String {
        return try {
            val lp = luckPermsProvider ?: return ""
            val userManager = lp.javaClass.getMethod("getUserManager").invoke(lp)
            val user = userManager.javaClass.getMethod("getUser", java.util.UUID::class.java)
                .invoke(userManager, player.uniqueId) ?: return ""
            val cachedData = user.javaClass.getMethod("getCachedData").invoke(user)
            val metaData = cachedData.javaClass.getMethod("getMetaData").invoke(cachedData)
            val value = metaData.javaClass.getMethod(metaMethod).invoke(metaData) as? String
            value ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    fun parseLegacy(text: String): Component {
        return legacySerializer.deserialize(text)
    }
}
