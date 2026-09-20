package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.entity.Player

class CommunicationsManager(private val plugin: Joshymc) {

    companion object {
        // Player preference for whether they want optional personal/direct
        // messages (e.g. /msg, /reply). Never gates critical/system messages.
        const val PERSONAL_MESSAGES_SETTING_KEY = "personal_messages"
    }

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
        CASINO("CASINO", TextColor.color(0xFF55FF)),
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

    /**
     * Whether [player] wants to receive OPTIONAL personal/direct messages
     * (e.g. /msg, /reply). Features must never route critical/system
     * messages (punishments, moderation notices, errors, transaction
     * confirmations) through this check.
     */
    fun canReceivePersonalMessages(player: Player): Boolean =
        plugin.settingsManager.getSetting(player, PERSONAL_MESSAGES_SETTING_KEY)

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
                // Simple color — map & code to NamedTextColor. Setting the color on the
                // root only affects style inheritance, so any child with its own explicit
                // click/hover event (e.g. the [ec] token) keeps working.
                val namedColor = namedColorFor(colorCode)
                if (namedColor != null) message.color(namedColor) else message
            } else if (colorCode == "&l") {
                message.decoration(net.kyori.adventure.text.format.TextDecoration.BOLD, true)
            } else if (colorCode != null) {
                // Rainbow/gradient — recolor per character while walking the component
                // tree, instead of flattening to plain text and re-parsing. Flattening
                // would destroy any interactive child (e.g. the [ec] Ender Chest token),
                // so components carrying a clickEvent/hoverEvent are left untouched.
                applyChatColorToComponent(message, colorCode)
            } else {
                message
            }
        } else {
            message
        }

        return prefixComponent.append(messageComponent)
    }

    private fun namedColorFor(code: String): NamedTextColor? = when (code) {
        "&0" -> NamedTextColor.BLACK
        "&1" -> NamedTextColor.DARK_BLUE
        "&2" -> NamedTextColor.DARK_GREEN
        "&3" -> NamedTextColor.DARK_AQUA
        "&4" -> NamedTextColor.DARK_RED
        "&5" -> NamedTextColor.DARK_PURPLE
        "&6" -> NamedTextColor.GOLD
        "&7" -> NamedTextColor.GRAY
        "&8" -> NamedTextColor.DARK_GRAY
        "&9" -> NamedTextColor.BLUE
        "&a" -> NamedTextColor.GREEN
        "&b" -> NamedTextColor.AQUA
        "&c" -> NamedTextColor.RED
        "&d" -> NamedTextColor.LIGHT_PURPLE
        "&e" -> NamedTextColor.YELLOW
        "&f" -> NamedTextColor.WHITE
        else -> null
    }

    /**
     * Recolors a rainbow/gradient chat color onto [component] character-by-character
     * without flattening it to a string first, so any child component that carries its
     * own clickEvent/hoverEvent (e.g. the `[ec]` Ender Chest preview token) is left
     * completely untouched — its interactivity survives regardless of chat color.
     */
    private fun applyChatColorToComponent(component: Component, code: String): Component {
        return when (code) {
            "RAINBOW" -> {
                val colors = com.liam.joshymc.command.ChatColorCommand.rainbowColors
                var position = 0
                recolorComponent(component) { isSpace ->
                    val picked = if (isSpace) null else colors[position % colors.size]
                    position++
                    picked
                }
            }
            "GRADIENT_FIRE", "GRADIENT_ICE", "GRADIENT_NATURE", "GRADIENT_SUNSET" -> {
                val colors = com.liam.joshymc.command.ChatColorCommand.gradientColorLists[code] ?: return component
                val totalVisible = countVisibleChars(component)
                var visited = 0
                recolorComponent(component) { isSpace ->
                    if (isSpace) {
                        null
                    } else {
                        val ratio = if (totalVisible <= 1) 0.0 else visited.toDouble() / (totalVisible - 1) * (colors.size - 1)
                        val picked = colors[ratio.toInt().coerceIn(0, colors.size - 1)]
                        visited++
                        picked
                    }
                }
            }
            else -> component
        }
    }

    private fun countVisibleChars(component: Component): Int {
        if (component.clickEvent() != null || component.hoverEvent() != null) return 0
        var count = if (component is TextComponent) component.content().count { it != ' ' } else 0
        for (child in component.children()) {
            count += countVisibleChars(child)
        }
        return count
    }

    private fun recolorComponent(component: Component, colorForChar: (isSpace: Boolean) -> String?): Component {
        // Interactive components (click/hover events, e.g. the [ec] token) are left
        // completely as-is — they neither get recolored nor consume position/index.
        if (component.clickEvent() != null || component.hoverEvent() != null) {
            return component
        }

        val newChildren = component.children().map { recolorComponent(it, colorForChar) }

        if (component is TextComponent && component.content().isNotEmpty()) {
            val pieces = component.content().map { c ->
                val code = colorForChar(c == ' ')
                if (code == null) Component.text(c.toString()) else Component.text(c.toString(), namedColorFor(code))
            }
            return Component.text("").style(component.style()).children(pieces + newChildren)
        }

        return component.children(newChildren)
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
