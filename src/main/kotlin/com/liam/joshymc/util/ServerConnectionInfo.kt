package com.liam.joshymc.util

import org.bukkit.configuration.ConfigurationSection

/**
 * The public connection details players are told about (issue #1359), read from
 * `config.yml > server-info`. Display only: nothing here changes a listening port.
 *
 * Java and Bedrock are separate endpoints with independently configurable ports:
 * `server-info.port` is the Java port (key kept from issue #1038), `server-info.bedrock-port`
 * the Bedrock (Geyser) port. `/ip` and the rotating ServerBar both read this, and ServerBar
 * messages use the `{ip}`, `{java_port}` and `{bedrock_port}` placeholders instead of
 * repeating the values.
 */
data class ServerConnectionInfo(val ip: String, val javaPort: Int, val bedrockPort: Int) {

    /** Replaces `{ip}`, `{java_port}` and `{bedrock_port}` in [template]. */
    fun fill(template: String): String = template
        .replace("{ip}", ip)
        .replace("{java_port}", javaPort.toString())
        .replace("{bedrock_port}", bedrockPort.toString())

    companion object {
        const val DEFAULT_IP = "play.joshymc.net"
        const val DEFAULT_JAVA_PORT = 25565
        const val DEFAULT_BEDROCK_PORT = 8160

        /** Ports outside 1..65535 (or non-numbers) fall back to the defaults. */
        fun of(ip: String?, javaPort: Int?, bedrockPort: Int?): ServerConnectionInfo = ServerConnectionInfo(
            ip = ip?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_IP,
            javaPort = javaPort?.takeIf { it in 1..65535 } ?: DEFAULT_JAVA_PORT,
            bedrockPort = bedrockPort?.takeIf { it in 1..65535 } ?: DEFAULT_BEDROCK_PORT,
        )

        /** Reads `server-info` from the root config; call it each time so a reload applies. */
        fun from(config: ConfigurationSection): ServerConnectionInfo = of(
            config.getString("server-info.ip"),
            if (config.isInt("server-info.port")) config.getInt("server-info.port") else null,
            if (config.isInt("server-info.bedrock-port")) config.getInt("server-info.bedrock-port") else null,
        )
    }
}
