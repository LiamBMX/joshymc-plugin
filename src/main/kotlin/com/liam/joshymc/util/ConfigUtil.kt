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

    /** Copies [file] to a timestamped `.bak-yyyy-MM-dd-HHmm` sibling before a merge/migration rewrite. */
    fun backup(file: File, logger: Logger, tag: String) {
        if (!file.exists()) return
        try {
            val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm").format(LocalDateTime.now())
            file.copyTo(File(file.parentFile, "${file.name}.bak-$stamp"), overwrite = true)
        } catch (e: Exception) {
            logger.warning("[$tag] Failed to back up ${file.name} before rewrite: ${e.message}")
        }
    }
}
