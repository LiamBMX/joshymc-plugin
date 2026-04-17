package com.liam.joshymc.util

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.entity.Player

object Msg {

    private val serializer = LegacyComponentSerializer.legacyAmpersand()

    fun prefix(): Component {
        val raw = Joshymc.instance.config.getString("prefix") ?: "&b&lJoshyMC &8» &r"
        return serializer.deserialize(raw)
    }

    fun send(player: Player, message: Component) {
        player.sendMessage(prefix().append(message))
    }

    fun send(player: Player, legacyMessage: String) {
        player.sendMessage(prefix().append(serializer.deserialize(legacyMessage)))
    }
}
