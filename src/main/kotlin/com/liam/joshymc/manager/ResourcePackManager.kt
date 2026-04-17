package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import com.sun.net.httpserver.HttpServer
import net.kyori.adventure.resource.ResourcePackInfo
import net.kyori.adventure.resource.ResourcePackRequest
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import java.io.File
import java.net.InetSocketAddress
import java.net.URI
import java.security.MessageDigest
import java.util.UUID

class ResourcePackManager(private val plugin: Joshymc) : Listener {

    private var packHash: String = ""
    private var packUrl: String = ""
    private var httpServer: HttpServer? = null

    fun start() {
        val packFile = extractPack()
        packHash = sha1Hex(packFile)

        val port = plugin.config.getInt("resource-pack.port", 8163)
        val host = plugin.config.getString("resource-pack.host", "") ?: ""

        // Start embedded HTTP server to serve the pack
        val server = HttpServer.create(InetSocketAddress(port), 0)
        val packBytes = packFile.readBytes()

        server.createContext("/pack.zip") { exchange ->
            exchange.responseHeaders.set("Content-Type", "application/zip")
            exchange.sendResponseHeaders(200, packBytes.size.toLong())
            exchange.responseBody.use { it.write(packBytes) }
        }
        server.executor = null
        server.start()
        httpServer = server

        // Build the URL players will download from
        val resolvedHost = if (host.isNotBlank()) host else "localhost"
        packUrl = "http://$resolvedHost:$port/pack.zip"

        plugin.server.pluginManager.registerEvents(this, plugin)
        plugin.logger.info("Resource pack server started on port $port (hash: $packHash)")
    }

    fun shutdown() {
        httpServer?.stop(0)
    }

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val packInfo = ResourcePackInfo.resourcePackInfo()
            .uri(URI.create(packUrl))
            .hash(packHash)
            .id(UUID.nameUUIDFromBytes(packUrl.toByteArray()))
            .build()

        val request = ResourcePackRequest.resourcePackRequest()
            .packs(packInfo)
            .required(true)
            .prompt(
                Component.text("This server requires a resource pack for custom items.", TextColor.color(0x55FFFF))
            )
            .build()

        event.player.sendResourcePacks(request)
    }

    private fun extractPack(): File {
        val outFile = File(plugin.dataFolder, "resourcepack.zip")
        if (!outFile.exists()) {
            plugin.dataFolder.mkdirs()
            plugin.getResource("resourcepack.zip")?.use { input ->
                outFile.outputStream().use { output -> input.copyTo(output) }
            } ?: error("resourcepack.zip not found in plugin JAR")
        }
        return outFile
    }

    private fun sha1Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-1")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
