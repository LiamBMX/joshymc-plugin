package com.liam.joshymc.util

import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.logging.Logger

/**
 * Shared safety net for the "read user file, merge in missing default keys" pattern used by
 * ChatTagManager, SellPriceManager, ServerShopManager, SpawnerManager, QuestCycleManager and
 * Joshymc.migrateConfig. Bukkit's YamlConfiguration.loadConfiguration swallows parse errors —
 * on invalid YAML it logs a SEVERE line and hands back an *empty* config instead of throwing.
 * Without this check, callers treat every default key as "missing" and merge/save the whole
 * defaults section over the user's file, destroying it. Detecting the empty-parse symptom lets
 * callers bail out and preserve the original file untouched.
 */
object ConfigUtil {

    /** True when [file] has real content on disk but [loaded] parsed with zero root keys. */
    fun looksLikeParseFailure(file: File, loaded: YamlConfiguration): Boolean =
        file.exists() && file.length() > 0 && loaded.getKeys(false).isEmpty()

    /** Ensures `<pluginDataFolder>/backups/` exists and returns it. */
    fun backupsDir(pluginDataFolder: File): File =
        File(pluginDataFolder, "backups").apply { if (!exists()) mkdirs() }

    /**
     * Copies [file] into `backups/` (a sibling of [file]'s parent folder) as a timestamped
     * `<name>.bak-yyyy-MM-dd-HHmmss` before a merge/migration rewrite. Never touches the
     * original file — on failure it just logs and leaves [file] untouched.
     */
    fun backup(file: File, logger: Logger, tag: String) {
        if (!file.exists()) return
        try {
            val dir = backupsDir(file.parentFile)
            val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss").format(LocalDateTime.now())
            val backupFile = File(dir, "${file.name}.bak-$stamp")
            file.copyTo(backupFile, overwrite = true)
            logger.info("[$tag] Backup created: backups/${backupFile.name}")
        } catch (e: Exception) {
            logger.warning("[$tag] Failed to create backup for ${file.name}. Original file was NOT modified: ${e.message}")
        }
    }
}
