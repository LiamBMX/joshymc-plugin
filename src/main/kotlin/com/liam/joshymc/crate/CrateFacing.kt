package com.liam.joshymc.crate

/**
 * Per-location crate facing (issue #1472), the Bukkit-free part.
 *
 * Before this, a crate model's direction came only from its crate type's
 * `crates.custom-models.models.<id>.rotation` (one value for every crate of that type) or, with
 * `auto`, from the crate block's own `facing`, which admins can't change without breaking and
 * re-placing the block. Now each `crate_locations` row may carry its own facing (column
 * `facing`, NULL = automatic), which wins over both.
 *
 * Every bundled crate model is built on `art/items/_crate_chest.py`, whose front (the lock) is
 * +Z (south) in model space; [CrateModels.displayYaw] already accounts for that, so a facing is
 * just a fixed [CrateModels.Rotation] in the same yaw convention as `rotation:` degrees.
 */
object CrateFacing {

    /** The four directions a crate's front (its lock) can face, in quick-rotate order. */
    enum class Direction {
        NORTH, EAST, SOUTH, WEST;

        /** Yaw the front faces, in the `rotation:` convention (0 = south, 90 = west, 180 = north, 270 = east). */
        val yaw: Float
            get() = CrateModels.facingYaw(name)!!

        /** Quick-rotate order: NORTH → EAST → SOUTH → WEST → NORTH. */
        fun next(): Direction = entries[(ordinal + 1) % entries.size]

        val id: String get() = name.lowercase()
    }

    /** What `/crate rotate <arg>` asks for. */
    sealed interface Choice {
        /** Clear the location's facing: the model follows the crate type's `rotation` again. */
        data object Auto : Choice
        data class Face(val direction: Direction) : Choice
        /** One step on from the facing currently shown (same as quick rotation). */
        data object Next : Choice
    }

    /** Words `/crate rotate` accepts, for usage and tab completion. */
    val CHOICES: List<String> = Direction.entries.map { it.id } + listOf("auto", "next")

    /** `/crate rotate` argument: north/south/east/west (or n/s/e/w), auto, next. Anything else = null. */
    fun parseChoice(text: String): Choice? = when (val t = text.trim().lowercase()) {
        "auto" -> Choice.Auto
        "next" -> Choice.Next
        "n" -> Choice.Face(Direction.NORTH)
        "e" -> Choice.Face(Direction.EAST)
        "s" -> Choice.Face(Direction.SOUTH)
        "w" -> Choice.Face(Direction.WEST)
        else -> Direction.entries.firstOrNull { it.id == t }?.let { Choice.Face(it) }
    }

    /** Result of reading the stored `facing` column. */
    sealed interface Stored {
        /** NULL or blank: automatic (every location registered before issue #1472). */
        data object Auto : Stored
        data class Face(val direction: Direction) : Stored
        /** Anything else: treated as automatic, reported, and left in the database untouched. */
        data class Invalid(val raw: String) : Stored
    }

    fun parseStored(raw: String?): Stored {
        val t = raw?.trim().orEmpty()
        if (t.isEmpty()) return Stored.Auto
        val d = Direction.entries.firstOrNull { it.name.equals(t, ignoreCase = true) }
        return if (d != null) Stored.Face(d) else Stored.Invalid(t)
    }

    /** The value written to the `facing` column (NULL for automatic). */
    fun storedValue(direction: Direction?): String? = direction?.name

    /**
     * The rotation a crate location's model is drawn with: its own facing when it has one,
     * otherwise the crate type's configured rotation (`auto` or degrees), exactly as before.
     */
    fun effectiveRotation(configured: CrateModels.Rotation, facing: Direction?): CrateModels.Rotation =
        if (facing != null) CrateModels.Rotation.Fixed(facing.yaw) else configured

    /** The cardinal direction nearest to a front yaw (`rotation:` convention). */
    fun nearest(yaw: Float): Direction {
        val n = (((yaw % 360f) + 360f) % 360f)
        val step = (kotlin.math.round(n / 90f).toInt()) % 4
        return when (step) {
            0 -> Direction.SOUTH
            1 -> Direction.WEST
            2 -> Direction.NORTH
            else -> Direction.EAST
        }
    }

    /**
     * The direction the crate's front currently faces: its own facing, else the configured fixed
     * rotation (nearest cardinal), else the block's horizontal facing, else south (the model
     * default for blocks without a facing).
     */
    fun currentDirection(facing: Direction?, configured: CrateModels.Rotation?, blockFacing: String?): Direction {
        if (facing != null) return facing
        if (configured is CrateModels.Rotation.Fixed) return nearest(configured.degrees)
        return Direction.entries.firstOrNull { it.name.equals(blockFacing, ignoreCase = true) } ?: Direction.SOUTH
    }

    /** What `/crate rotate <choice>` stores for a crate currently facing [current]. Null = automatic. */
    fun target(choice: Choice, current: Direction): Direction? = when (choice) {
        Choice.Auto -> null
        is Choice.Face -> choice.direction
        Choice.Next -> current.next()
    }

    /** Whether the vanilla crate block may be turned to match, and why not. */
    enum class BlockAlign {
        /** The block was turned to the new facing. */
        ROTATED,
        /** The block already faced that way. */
        ALREADY,
        /** `crates.rotation.rotate-block: false`. */
        DISABLED,
        /** The block has no horizontal facing (or can't face that way): only the model turns. */
        NO_FACING,
        /** Half of a double chest: turning one half would split the pair, so only the model turns. */
        DOUBLE_CHEST,
        /** Automatic mode: the block is left as it is and the model follows it. */
        NOT_REQUESTED,
    }

    /**
     * Decides whether the crate block itself is turned. [faces] are the facings the block
     * supports (empty when it isn't directional), [blockFacing] its current one, [chestType]
     * the chest `type` (SINGLE/LEFT/RIGHT) or null for non-chests.
     */
    fun blockAlign(
        target: Direction?,
        enabled: Boolean,
        faces: Set<String>,
        blockFacing: String?,
        chestType: String?,
    ): BlockAlign = when {
        target == null -> BlockAlign.NOT_REQUESTED
        !enabled -> BlockAlign.DISABLED
        faces.none { it.equals(target.name, ignoreCase = true) } -> BlockAlign.NO_FACING
        chestType != null && !chestType.equals("SINGLE", ignoreCase = true) -> BlockAlign.DOUBLE_CHEST
        target.name.equals(blockFacing, ignoreCase = true) -> BlockAlign.ALREADY
        else -> BlockAlign.ROTATED
    }

    /**
     * Whether a sneak + right-click is a quick rotation instead of the normal crate flow: only
     * when the feature is on, the player is a crate admin who turned on rotate mode
     * (`/crate rotate mode`), is sneaking and has an empty main hand. A held key (or anything
     * else) always runs the normal preview / mass-open path, and players never get here.
     */
    fun isQuickRotate(
        featureEnabled: Boolean,
        isAdmin: Boolean,
        inRotateMode: Boolean,
        sneaking: Boolean,
        rightClick: Boolean,
        emptyHand: Boolean,
    ): Boolean = featureEnabled && isAdmin && inRotateMode && sneaking && rightClick && emptyHand
}
