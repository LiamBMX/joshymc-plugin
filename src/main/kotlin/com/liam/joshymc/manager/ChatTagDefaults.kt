package com.liam.joshymc.manager

import org.bukkit.configuration.ConfigurationSection

/**
 * Merges the bundled `tags.yml` into a server's saved one (no server needed, so
 * `scripts/VoucherTagCheck.kt` can run it). Missing categories and tags are added and
 * existing entries are never changed, so admin edits survive.
 *
 * Issue #1391 bundles voucher tags. A bundled voucher tag an admin deleted with
 * `/voucher tag delete` is listed under [REMOVED_VOUCHER_DEFAULTS] and is not merged back in.
 */
object ChatTagDefaults {

    const val VOUCHER_CATEGORY = "voucher"
    const val VOUCHER_PERMISSION_PREFIX = "joshymc.tag.voucher."

    /** Root-level list of voucher tag ids deleted by an admin, so the bundled copy stays gone. */
    const val REMOVED_VOUCHER_DEFAULTS = "removed-voucher-defaults"

    data class MergeResult(val categoriesAdded: Int, val tagsAdded: Int) {
        val changed: Boolean get() = categoriesAdded > 0 || tagsAdded > 0
    }

    /** Adds the categories and tags in [defaults] that are missing from [user] (both are file roots). */
    fun merge(defaults: ConfigurationSection, user: ConfigurationSection): MergeResult {
        val defaultsSection = defaults.getConfigurationSection("tags") ?: return MergeResult(0, 0)
        val userSection = user.getConfigurationSection("tags") ?: user.createSection("tags")
        val removed = user.getStringList(REMOVED_VOUCHER_DEFAULTS).toSet()

        var categoriesAdded = 0
        var tagsAdded = 0
        for (categoryId in defaultsSection.getKeys(false)) {
            val defaultCat = defaultsSection.getConfigurationSection(categoryId) ?: continue
            val skipped = if (categoryId == VOUCHER_CATEGORY) removed else emptySet()
            val missing = defaultCat.getKeys(false).filter { it !in skipped }
            var userCat = userSection.getConfigurationSection(categoryId)
            if (userCat == null) {
                if (missing.isEmpty()) continue
                userCat = userSection.createSection(categoryId)
                categoriesAdded++
            }
            for (tagId in missing) {
                if (!userCat.contains(tagId)) {
                    userCat.set(tagId, defaultCat.get(tagId))
                    tagsAdded++
                }
            }
        }
        return MergeResult(categoriesAdded, tagsAdded)
    }

    /** Records [tagId] as deleted so [merge] doesn't bring its bundled copy back. */
    fun markVoucherRemoved(user: ConfigurationSection, tagId: String) {
        val list = user.getStringList(REMOVED_VOUCHER_DEFAULTS)
        if (tagId !in list) user.set(REMOVED_VOUCHER_DEFAULTS, list + tagId)
    }

    /** Takes [tagId] off the deleted list, for a voucher tag that is created again. */
    fun clearVoucherRemoved(user: ConfigurationSection, tagId: String) {
        val list = user.getStringList(REMOVED_VOUCHER_DEFAULTS)
        if (tagId in list) {
            val rest = list - tagId
            user.set(REMOVED_VOUCHER_DEFAULTS, rest.ifEmpty { null })
        }
    }
}
