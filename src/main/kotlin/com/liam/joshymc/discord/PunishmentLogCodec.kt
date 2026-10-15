package com.liam.joshymc.discord

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.liam.joshymc.manager.PunishmentManager.PunishmentLog
import com.liam.joshymc.manager.PunishmentManager.PunishmentRecord
import java.util.UUID

/**
 * Stores a [PunishmentLog] as JSON in the punishment Discord outbox (issue #1157), so the
 * exact embed can be rebuilt after a restart. Written field by field: no reflection, and a
 * row this version can't read decodes to null instead of throwing.
 */
internal object PunishmentLogCodec {

    const val VERSION = 1

    /**
     * Stable identity of one logging event. A new punishment is keyed by its row id; a
     * revocation by the punishments it lifted, which can each only be lifted once.
     */
    fun eventKey(entry: PunishmentLog): String = when {
        entry.reversed.isNotEmpty() -> "${entry.action}:${entry.reversed.map { it.id }.sorted().joinToString(",")}"
        entry.punishmentId != null -> "PUNISHMENT:${entry.punishmentId}"
        else -> "${entry.action}:${entry.targetUuid}:${entry.timestamp}"
    }

    fun encode(entry: PunishmentLog): String {
        val json = JsonObject()
        json.addProperty("v", VERSION)
        json.addProperty("action", entry.action)
        json.addProperty("targetUuid", entry.targetUuid.toString())
        json.addProperty("targetName", entry.targetName)
        json.addProperty("staffName", entry.staffName)
        json.addProperty("reason", entry.reason)
        json.addProperty("timestamp", entry.timestamp)
        json.addProperty("punishmentId", entry.punishmentId)
        json.addProperty("durationMs", entry.durationMs)
        json.addProperty("expiresAt", entry.expiresAt)
        json.addProperty("previousCount", entry.previousCount)
        val reversed = JsonArray()
        for (record in entry.reversed) {
            val obj = JsonObject()
            obj.addProperty("id", record.id)
            obj.addProperty("type", record.type)
            obj.addProperty("reason", record.reason)
            obj.addProperty("punisherName", record.punisherName)
            obj.addProperty("createdAt", record.createdAt)
            obj.addProperty("expiresAt", record.expiresAt)
            obj.addProperty("active", record.active)
            reversed.add(obj)
        }
        json.add("reversed", reversed)
        return json.toString()
    }

    fun decode(payload: String): PunishmentLog? = try {
        val json = JsonParser.parseString(payload).asJsonObject
        PunishmentLog(
            action = json.string("action")!!,
            targetUuid = UUID.fromString(json.string("targetUuid")),
            targetName = json.string("targetName")!!,
            staffName = json.string("staffName")!!,
            reason = json.string("reason"),
            timestamp = json.long("timestamp")!!,
            punishmentId = json.long("punishmentId")?.toInt(),
            durationMs = json.long("durationMs"),
            expiresAt = json.long("expiresAt"),
            previousCount = json.long("previousCount")?.toInt(),
            reversed = json.getAsJsonArray("reversed")?.map { element ->
                val obj = element.asJsonObject
                PunishmentRecord(
                    id = obj.long("id")!!.toInt(),
                    type = obj.string("type")!!,
                    reason = obj.string("reason"),
                    punisherName = obj.string("punisherName")!!,
                    createdAt = obj.long("createdAt")!!,
                    expiresAt = obj.long("expiresAt"),
                    active = obj.get("active")?.takeUnless { it.isJsonNull }?.asBoolean ?: false
                )
            } ?: emptyList()
        )
    } catch (_: Exception) {
        null
    }

    private fun JsonObject.value(key: String): JsonElement? = get(key)?.takeUnless { it.isJsonNull }
    private fun JsonObject.string(key: String): String? = value(key)?.asString
    private fun JsonObject.long(key: String): Long? = value(key)?.asLong
}
