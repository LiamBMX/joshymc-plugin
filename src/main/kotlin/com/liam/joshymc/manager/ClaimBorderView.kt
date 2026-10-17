package com.liam.joshymc.manager

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Player-relative claim border wall (issue #1191). Bukkit-free so the
 * geometry can be checked without a server.
 *
 * A claim covers blocks `minX..maxX` / `minZ..maxZ`, so its walls stand on
 * the block faces `x = minX`, `x = maxX + 1`, `z = minZ`, `z = maxZ + 1`.
 * Like the vanilla world border, only the patch of wall around the player is
 * shown: the points inside an ellipse of `reach` blocks horizontally and
 * `halfHeight` blocks vertically around the player, clamped to the world's
 * build height. Points sit one block apart. Nothing here reads blocks, so no
 * chunk is touched.
 */
object ClaimBorderView {

    data class Point(val x: Int, val y: Int, val z: Int, val corner: Boolean)

    /** True when some wall of the claim is within [reach] blocks of (px, pz). */
    fun isNear(minX: Int, maxX: Int, minZ: Int, maxZ: Int, px: Double, pz: Double, reach: Double): Boolean {
        val x0 = minX.toDouble(); val x1 = maxX + 1.0
        val z0 = minZ.toDouble(); val z1 = maxZ + 1.0
        if (px in x0..x1 && pz in z0..z1) return min(min(px - x0, x1 - px), min(pz - z0, z1 - pz)) <= reach
        val dx = max(max(x0 - px, 0.0), px - x1)
        val dz = max(max(z0 - pz, 0.0), pz - z1)
        return dx * dx + dz * dz <= reach * reach
    }

    /**
     * Points of the visible wall patch for one claim. Each wall position is
     * produced once: the four corner columns belong to the north/south walls
     * and are flagged [Point.corner].
     *
     * @param worldMinY lowest block y of the world (inclusive, `World.minHeight`)
     * @param worldMaxY top of the build height (exclusive, `World.maxHeight`)
     */
    fun points(
        minX: Int, maxX: Int, minZ: Int, maxZ: Int,
        px: Double, py: Double, pz: Double,
        worldMinY: Int, worldMaxY: Int,
        reach: Double, halfHeight: Double
    ): List<Point> {
        val out = ArrayList<Point>()
        val x0 = minX; val x1 = maxX + 1
        val z0 = minZ; val z1 = maxZ + 1

        fun column(x: Int, z: Int, corner: Boolean) {
            val dx = x - px; val dz = z - pz
            val h2 = (dx * dx + dz * dz) / (reach * reach)
            if (h2 > 1.0) return
            // Vertical extent of the ellipse at this horizontal distance.
            val span = halfHeight * sqrt(1.0 - h2)
            val lo = max(worldMinY, ceil(py - span).toInt())
            val hi = min(worldMaxY, floor(py + span).toInt())
            for (y in lo..hi) out.add(Point(x, y, z, corner))
        }

        val fromX = max(x0, ceil(px - reach).toInt()); val toX = min(x1, floor(px + reach).toInt())
        val fromZ = max(z0 + 1, ceil(pz - reach).toInt()); val toZ = min(z1 - 1, floor(pz + reach).toInt())

        // North (z = minZ) and south (z = maxZ + 1) walls, corners included.
        for (z in intArrayOf(z0, z1)) {
            if (abs(z - pz) > reach) continue
            for (x in fromX..toX) column(x, z, x == x0 || x == x1)
        }
        // West (x = minX) and east (x = maxX + 1) walls, corners left to the walls above.
        for (x in intArrayOf(x0, x1)) {
            if (abs(x - px) > reach) continue
            for (z in fromZ..toZ) column(x, z, false)
        }
        return out
    }

    /** Key for de-duplicating wall points that neighbouring claims share. */
    fun key(p: Point): Long =
        ((p.x.toLong() and 0x3FFFFFF) shl 38) or ((p.y.toLong() and 0xFFF) shl 26) or (p.z.toLong() and 0x3FFFFFF)
}
