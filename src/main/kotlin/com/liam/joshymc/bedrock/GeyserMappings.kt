package com.liam.joshymc.bedrock

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * Geyser custom item mappings (issue #1314), Bukkit-free.
 *
 * Geyser's v2 mapping format matches a Java stack by its base item plus its
 * `minecraft:item_model` component (`"type": "definition"`, `"model"`), which is exactly
 * how JoshyMC tells its custom items apart (`setItemModel`), so no CustomModelData is
 * needed or touched. Each mapping registers one Bedrock item, drawn with the icon of the
 * same model key from [BedrockIconIndex].
 *
 * Rules:
 *  - one entry per (base item, model); repeats from other sources (e.g. a crate whose
 *    key uses a retextured key's model on the same base) are dropped, first one wins;
 *  - the Bedrock identifier is `joshymc:<icon name>`; when the same model sits on more
 *    than one base item, every base after the first gets `_<base item>` appended;
 *  - a model with no icon, an excluded base item, a `minecraft:` model (a vanilla look),
 *    and anything that would reuse a Bedrock identifier are skipped (and, except for the
 *    vanilla looks, reported), so the item keeps its vanilla Bedrock look instead of
 *    mapping to a broken one.
 */
object GeyserMappings {

    const val NAMESPACE = "joshymc"

    /** One custom item look, as the plugin hands it out. */
    data class Source(
        /** Java base item id, e.g. `minecraft:cookie`. */
        val javaItem: String,
        /** The stack's item model key, e.g. `joshymc:fish/cod`. */
        val model: String,
        val displayName: String,
        /** Where it came from, for reports: `item:<id>`, `fish:<id>`, `crate:<id>`. */
        val origin: String,
        /** Max stack size when it differs from the base item's vanilla value. */
        val maxStackSize: Int? = null,
    )

    data class Entry(
        val source: Source,
        val bedrockIdentifier: String,
        val iconKey: String,
        val iconPath: String,
        val handheld: Boolean,
    )

    /** [skipped]: deliberate (excluded base items); [problems]: something to fix. */
    data class Result(val entries: List<Entry>, val skipped: List<String>, val problems: List<String>) {
        /** Bedrock texture key -> icon resource path, for item_texture.json. */
        val icons: Map<String, String> get() = entries.associate { it.iconKey to it.iconPath }.toSortedMap()
    }

    /** Builds the entries in a stable order, so the same items always give the same files. */
    fun build(sources: List<Source>, icons: BedrockIconIndex, excludedJavaItems: Set<String> = emptySet()): Result {
        val problems = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val seen = HashSet<Pair<String, String>>()
        val unique = ArrayList<Source>()
        for (source in sources) {
            val javaItem = source.javaItem.lowercase()
            val pair = javaItem to source.model
            if (!seen.add(pair)) continue
            when {
                javaItem in excludedJavaItems ->
                    skipped += "${source.origin}: base item $javaItem is excluded (bedrock-pack.excluded-base-items), keeps its vanilla Bedrock look"
                source.model.startsWith("minecraft:") -> continue // a vanilla look: nothing to map
                icons.items[source.model] == null ->
                    problems += "${source.origin}: no Bedrock icon for model ${source.model}; run `py -m art.bedrock` in resourcepack/ and rebuild"
                else -> unique += source.copy(javaItem = javaItem)
            }
        }

        val usedIds = HashSet<String>()
        val basesByModel = HashMap<String, Int>()
        val entries = ArrayList<Entry>()
        for (source in unique) {
            val icon = icons.items.getValue(source.model)
            val nth = basesByModel.merge(source.model, 1, Int::plus)!!
            val name = if (nth == 1) icon.bedrockName else "${icon.bedrockName}_${baseName(source.javaItem)}"
            val identifier = "$NAMESPACE:$name"
            if (!usedIds.add(identifier)) {
                problems += "${source.origin}: Bedrock identifier $identifier is already taken; give the model a different key"
                continue
            }
            entries += Entry(
                source = source,
                bedrockIdentifier = identifier,
                iconKey = "${NAMESPACE}_${icon.bedrockName}",
                iconPath = icon.icon,
                handheld = isHandheld(source.javaItem),
            )
        }
        return Result(entries, skipped, problems)
    }

    /** Swords, tools, maces, tridents and rods are held upright on Bedrock, like vanilla. */
    fun isHandheld(javaItem: String): Boolean {
        val path = baseName(javaItem)
        return HANDHELD_SUFFIXES.any { path.endsWith(it) } || path in HANDHELD_ITEMS
    }

    private val HANDHELD_SUFFIXES = listOf("_sword", "_axe", "_pickaxe", "_shovel", "_hoe")
    private val HANDHELD_ITEMS = setOf("mace", "trident", "blaze_rod", "breeze_rod", "stick", "bone", "fishing_rod")

    private fun baseName(javaItem: String) = javaItem.substringAfter(':').replace(Regex("[^a-z0-9_]"), "_")

    /** The `custom_mappings/<file>.json` Geyser reads at startup. */
    fun toJson(result: Result): String {
        val root = JsonObject()
        root.addProperty("format_version", 2)
        val items = JsonObject()
        for ((javaItem, group) in result.entries.groupBy { it.source.javaItem }.toSortedMap()) {
            val list = JsonArray()
            for (entry in group.sortedBy { it.source.model }) {
                val def = JsonObject()
                def.addProperty("type", "definition")
                def.addProperty("model", entry.source.model)
                def.addProperty("bedrock_identifier", entry.bedrockIdentifier)
                def.addProperty("display_name", entry.source.displayName)
                val options = JsonObject()
                options.addProperty("icon", entry.iconKey)
                options.addProperty("allow_offhand", true)
                options.addProperty("display_handheld", entry.handheld)
                def.add("bedrock_options", options)
                entry.source.maxStackSize?.let {
                    val components = JsonObject()
                    components.addProperty("minecraft:max_stack_size", it)
                    def.add("components", components)
                }
                list.add(def)
            }
            items.add(javaItem, list)
        }
        root.add("items", items)
        return GSON.toJson(root) + "\n"
    }

    internal val GSON = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
}
