package com.liam.joshymc.bedrock

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Bedrock custom items through Geyser (issue #1314).
 *
 * At startup this turns the icons rendered from the Java pack (`bedrock/` in the JAR,
 * written by `py -m art.bedrock`) and the items the plugin really hands out (registered
 * custom items, custom fish, crate keys with a custom model) into:
 *  - `packs/JoshyMC-Bedrock.mcpack` in Geyser's folder ([BedrockPack]), and
 *  - `custom_mappings/joshymc.json` in Geyser's folder ([GeyserMappings]).
 * It only ever writes those two files there, and only when their content changed, so
 * nothing else in Geyser's configuration is touched. A copy of both always goes to
 * `plugins/Joshymc/bedrock/` for servers whose Geyser runs elsewhere (e.g. a proxy).
 *
 * Geyser reads both files when it starts, so JoshyMC loads before Geyser-Spigot
 * (plugin.yml `loadbefore`) and runs this from onEnable. Java items, the Java pack and
 * every stored item are untouched: the mappings only describe how Geyser draws them.
 */
class BedrockPackManager(private val plugin: Joshymc) {

    companion object {
        const val PACK_FILE = "JoshyMC-Bedrock.mcpack"
        const val MAPPINGS_FILE = "joshymc.json"
        private val GEYSER_PLUGIN_NAMES = listOf("Geyser-Spigot", "Geyser-Paper")
    }

    fun start() {
        if (!plugin.isFeatureEnabled("bedrock-pack")) return
        try {
            generate()
        } catch (e: Exception) {
            plugin.logger.warning("[Bedrock] Couldn't build the Bedrock pack/mappings: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun generate() {
        val indexJson = plugin.getResource("bedrock/icons.json")?.use { it.readBytes().toString(Charsets.UTF_8) }
        if (indexJson == null) {
            plugin.logger.warning("[Bedrock] bedrock/icons.json is missing from the JAR; run `py -m art.bedrock` in resourcepack/ and rebuild")
            return
        }
        val icons = BedrockIconIndex.parse(indexJson)
        val section = plugin.config.getConfigurationSection("bedrock-pack")
        val excluded = (section?.getStringList("excluded-base-items") ?: emptyList())
            .map { it.lowercase().let { id -> if (':' in id) id else "minecraft:$id" } }.toSet()

        val result = GeyserMappings.build(sources(), icons, excluded)
        val problems = result.problems.toMutableList()
        val pack = BedrockPack.build(result.icons) { path ->
            val bytes = plugin.getResource("bedrock/icons/$path")?.use { it.readBytes() }
                ?: throw IllegalStateException("icon bedrock/icons/$path is missing from the JAR; rebuild the plugin")
            val expected = icons.items.values.firstOrNull { it.icon == path }?.sha1
            if (expected != null && sha1(bytes) != expected) problems += "icon $path doesn't match icons.json; run `py -m art.bedrock` and rebuild"
            bytes
        }
        val mappings = GeyserMappings.toJson(result).toByteArray(Charsets.UTF_8)

        val export = File(plugin.dataFolder, "bedrock")
        writeIfChanged(File(export, PACK_FILE), pack)
        writeIfChanged(File(export, MAPPINGS_FILE), mappings)

        result.skipped.forEach { plugin.logger.info("[Bedrock] $it") }
        problems.forEach { plugin.logger.warning("[Bedrock] $it") }

        val geyser = geyserFolder(section)
        if (geyser == null) {
            plugin.logger.info("[Bedrock] ${result.entries.size} custom items mapped; Geyser isn't installed here, " +
                "so the pack and mappings were only written to ${export.path} (see docs/bedrock-custom-items.md)")
            return
        }
        val changed = writeIfChanged(File(geyser, "packs/$PACK_FILE"), pack) or
            writeIfChanged(File(geyser, "custom_mappings/$MAPPINGS_FILE"), mappings)
        plugin.logger.info("[Bedrock] ${result.entries.size} custom items mapped for Geyser in ${geyser.path}" +
            (if (changed) " (updated)" else " (unchanged)"))
        checkGeyserConfig(geyser)
        val geyserEnabled = GEYSER_PLUGIN_NAMES.any { Bukkit.getPluginManager().getPlugin(it)?.isEnabled == true }
        if (changed && geyserEnabled) {
            plugin.logger.warning("[Bedrock] Geyser was already running, so it loads the new pack and mappings on the next restart")
        }
    }

    /** Every custom item look the plugin hands out, registered items first. */
    private fun sources(): List<GeyserMappings.Source> {
        val plain = PlainTextComponentSerializer.plainText()
        val out = ArrayList<GeyserMappings.Source>()
        for (item in plugin.itemManager.getAllItems().sortedBy { it.id }) {
            val stack = try {
                item.createItemStack()
            } catch (e: Exception) {
                plugin.logger.warning("[Bedrock] item:${item.id} couldn't be built (${e.message}); it keeps its vanilla Bedrock look")
                continue
            }
            val model = stack.itemMeta?.itemModel ?: continue
            out += GeyserMappings.Source(
                javaItem = stack.type.key.asString(),
                model = model.asString(),
                displayName = plain.serialize(item.displayName),
                origin = "item:${item.id}",
                maxStackSize = stack.maxStackSize.takeIf { it != stack.type.maxStackSize },
            )
        }
        for (fish in plugin.fishingManager.registeredFish().sortedBy { it.id }) {
            out += GeyserMappings.Source(fish.material.key.asString(), "joshymc:fish/${fish.id}", fish.name, "fish:${fish.id}")
        }
        for (crate in plugin.crateManager.getAllCrates().values.sortedBy { it.id }) {
            val model = crate.keyItemModel ?: continue
            val name = plain.serialize(plugin.commsManager.parseLegacy(crate.keyName))
            out += GeyserMappings.Source(crate.keyMaterial.key.asString(), model.asString(), name, "crate:${crate.id}")
        }
        return out
    }

    /** `bedrock-pack.geyser-folder` (relative to the server folder), else Geyser's own data folder. */
    private fun geyserFolder(section: ConfigurationSection?): File? {
        val configured = section?.getString("geyser-folder")?.trim().orEmpty()
        if (configured.isNotEmpty()) {
            val file = File(configured).let { if (it.isAbsolute) it else File(plugin.dataFolder.parentFile.parentFile, configured) }
            return file.takeIf { it.isDirectory }
                ?: null.also { plugin.logger.warning("[Bedrock] bedrock-pack.geyser-folder '$configured' isn't a folder; nothing written for Geyser") }
        }
        for (name in GEYSER_PLUGIN_NAMES) {
            Bukkit.getPluginManager().getPlugin(name)?.let { return it.dataFolder }
        }
        return null
    }

    /** Geyser ignores custom items when `add-non-bedrock-items` is false; say so, never edit it. */
    private fun checkGeyserConfig(geyser: File) {
        val file = File(geyser, "config.yml")
        if (!file.isFile) return
        val config = try { YamlConfiguration.loadConfiguration(file) } catch (_: Exception) { return }
        val disabled = config.getKeys(true).any { it.substringAfterLast('.') == "add-non-bedrock-items" && config.getString(it) == "false" }
        if (disabled) {
            plugin.logger.warning("[Bedrock] Geyser's config.yml has add-non-bedrock-items: false, which turns custom items off; " +
                "set it to true and restart for Bedrock players to see JoshyMC items")
        }
    }

    /** Writes through a temp file and a move, only when the bytes differ. True if written. */
    private fun writeIfChanged(file: File, bytes: ByteArray): Boolean {
        if (file.isFile && file.readBytes().contentEquals(bytes)) return false
        file.parentFile.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeBytes(bytes)
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        return true
    }

    private fun sha1(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
}
