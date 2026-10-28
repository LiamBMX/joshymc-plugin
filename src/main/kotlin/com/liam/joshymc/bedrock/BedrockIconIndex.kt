package com.liam.joshymc.bedrock

import com.google.gson.JsonParser

/**
 * `bedrock/icons.json` (issue #1314), written by `py -m art.bedrock` from the built Java
 * pack: for every Java item model key, the Bedrock-safe name and the icon PNG under
 * `bedrock/icons/` in the plugin JAR. Bukkit-free.
 */
class BedrockIconIndex(val iconSize: Int, val items: Map<String, Icon>) {

    data class Icon(val bedrockName: String, val icon: String, val sha1: String)

    companion object {
        const val FORMAT_VERSION = 1

        fun parse(json: String): BedrockIconIndex {
            val root = JsonParser.parseString(json).asJsonObject
            val format = root.get("format_version")?.asInt
            require(format == FORMAT_VERSION) { "icons.json format_version $format, expected $FORMAT_VERSION" }
            val items = root.getAsJsonObject("items").entrySet().associate { (key, value) ->
                val o = value.asJsonObject
                key to Icon(o.get("bedrock_name").asString, o.get("icon").asString, o.get("sha1").asString)
            }
            return BedrockIconIndex(root.get("icon_size")?.asInt ?: 0, items.toSortedMap())
        }
    }
}
