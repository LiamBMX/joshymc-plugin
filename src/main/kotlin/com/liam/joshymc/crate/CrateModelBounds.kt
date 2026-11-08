package com.liam.joshymc.crate

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Reads how much space a crate model fills from the bundled resource pack's JSON (issue #1451),
 * Bukkit-free, so the crate hitbox matches what players actually see.
 *
 * An item definition (`assets/<ns>/items/<path>.json`) names one or more models; each model's
 * `elements` are boxes in model units, optionally rotated about an origin (single axis + angle,
 * or 26.2's Euler `x`/`y`/`z`, applied X then Y then Z). The bounds are the box around every
 * rotated corner of every element of every model the definition can show.
 */
object CrateModelBounds {

    /** Every `minecraft:model` reference in an item definition, e.g. `joshymc:item/cabin_crate/main`. */
    fun modelRefs(itemDefinitionJson: String): List<String> {
        val root = runCatching { JsonParser.parseString(itemDefinitionJson) }.getOrNull() ?: return emptyList()
        val out = linkedSetOf<String>()
        collect(root, out)
        return out.toList()
    }

    private fun collect(node: JsonElement, out: MutableSet<String>) {
        when {
            node.isJsonObject -> {
                val obj = node.asJsonObject
                val type = obj.get("type")?.takeIf { it.isJsonPrimitive }?.asString?.removePrefix("minecraft:")
                val model = obj.get("model")
                if (type == "model" && model != null && model.isJsonPrimitive) out += model.asString
                for ((_, child) in obj.entrySet()) collect(child, out)
            }
            node.isJsonArray -> for (child in node.asJsonArray) collect(child, out)
        }
    }

    /** The pack path of a model reference: `joshymc:item/x/main` -> `assets/joshymc/models/item/x/main.json`. */
    fun modelPath(ref: String): String? {
        val (ns, path) = if (':' in ref) ref.split(":", limit = 2).let { it[0] to it[1] } else "minecraft" to ref
        if (ns.isEmpty() || path.isEmpty() || ".." in path) return null
        return "assets/$ns/models/$path.json"
    }

    /** The bounds of one model's own elements, or null when it has none (or can't be read). */
    fun elementBounds(modelJson: String): CrateModels.ModelBounds? {
        val root = runCatching { JsonParser.parseString(modelJson) }.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
            ?: return null
        val elements = root.get("elements")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        var bounds: CrateModels.ModelBounds? = null
        for (e in elements) {
            if (!e.isJsonObject) continue
            for (corner in corners(e.asJsonObject) ?: continue) bounds = include(bounds, corner)
        }
        return bounds
    }

    /** The union of several bounds (null entries skipped), or null when there are none. */
    fun union(all: Iterable<CrateModels.ModelBounds?>): CrateModels.ModelBounds? {
        var out: CrateModels.ModelBounds? = null
        for (b in all) {
            if (b == null) continue
            out = include(include(out, doubleArrayOf(b.minX, b.minY, b.minZ)), doubleArrayOf(b.maxX, b.maxY, b.maxZ))
        }
        return out
    }

    private fun include(b: CrateModels.ModelBounds?, p: DoubleArray): CrateModels.ModelBounds =
        if (b == null) CrateModels.ModelBounds(p[0], p[1], p[2], p[0], p[1], p[2])
        else CrateModels.ModelBounds(
            minOf(b.minX, p[0]), minOf(b.minY, p[1]), minOf(b.minZ, p[2]),
            maxOf(b.maxX, p[0]), maxOf(b.maxY, p[1]), maxOf(b.maxZ, p[2]),
        )

    /** The 8 corners of an element after its rotation, or null when it isn't a readable box. */
    private fun corners(element: JsonObject): List<DoubleArray>? {
        val from = vec(element.get("from")) ?: return null
        val to = vec(element.get("to")) ?: return null
        val pts = buildList {
            for (x in doubleArrayOf(from[0], to[0])) for (y in doubleArrayOf(from[1], to[1])) for (z in doubleArrayOf(from[2], to[2])) {
                add(doubleArrayOf(x, y, z))
            }
        }
        val rotation = element.get("rotation")?.takeIf { it.isJsonObject }?.asJsonObject ?: return pts
        val origin = vec(rotation.get("origin")) ?: doubleArrayOf(8.0, 8.0, 8.0)
        val m = matrix(rotation) ?: return pts
        return pts.map { p ->
            val v = doubleArrayOf(p[0] - origin[0], p[1] - origin[1], p[2] - origin[2])
            DoubleArray(3) { i -> m[i][0] * v[0] + m[i][1] * v[1] + m[i][2] * v[2] + origin[i] }
        }
    }

    private fun matrix(rotation: JsonObject): Array<DoubleArray>? {
        val axis = rotation.get("axis")?.takeIf { it.isJsonPrimitive }?.asString
        if (axis != null) {
            val angle = num(rotation.get("angle")) ?: return null
            return axisMatrix(axis, angle)
        }
        val x = num(rotation.get("x")) ?: 0.0
        val y = num(rotation.get("y")) ?: 0.0
        val z = num(rotation.get("z")) ?: 0.0
        // X first, then Y, then Z: Rz * Ry * Rx.
        return mul(axisMatrix("z", z), mul(axisMatrix("y", y), axisMatrix("x", x)))
    }

    private fun axisMatrix(axis: String, degrees: Double): Array<DoubleArray> {
        val a = Math.toRadians(degrees)
        val c = kotlin.math.cos(a)
        val s = kotlin.math.sin(a)
        return when (axis) {
            "x" -> arrayOf(doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, c, -s), doubleArrayOf(0.0, s, c))
            "y" -> arrayOf(doubleArrayOf(c, 0.0, s), doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(-s, 0.0, c))
            else -> arrayOf(doubleArrayOf(c, -s, 0.0), doubleArrayOf(s, c, 0.0), doubleArrayOf(0.0, 0.0, 1.0))
        }
    }

    private fun mul(a: Array<DoubleArray>, b: Array<DoubleArray>): Array<DoubleArray> =
        Array(3) { i -> DoubleArray(3) { j -> (0 until 3).sumOf { k -> a[i][k] * b[k][j] } } }

    private fun vec(node: JsonElement?): DoubleArray? {
        val arr = node?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        if (arr.size() != 3) return null
        return DoubleArray(3) { i -> num(arr[i]) ?: return null }
    }

    private fun num(node: JsonElement?): Double? =
        node?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble?.takeIf { it.isFinite() }
}
