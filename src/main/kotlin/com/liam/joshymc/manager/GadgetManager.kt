package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.FireworkEffect
import org.bukkit.Instrument
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Note
import org.bukkit.Particle
import org.bukkit.Particle.DustOptions
import org.bukkit.Particle.DustTransition
import org.bukkit.Sound
import org.bukkit.block.data.BlockData
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Bat
import org.bukkit.entity.EntityType
import org.bukkit.entity.Firework
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Mob
import org.bukkit.entity.Parrot
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.persistence.PersistentDataType
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.util.Vector
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class GadgetManager(private val plugin: Joshymc) : Listener {

    data class Gadget(
        val id: String,
        val name: String,
        val description: String,
        val permission: String,
        val icon: Material,
        val color: String,
        val cooldownSeconds: Int
    )

    private val gadgetKey = NamespacedKey(plugin, "gadget_id")

    private val gadgets = linkedMapOf<String, Gadget>()
    private val cooldowns = ConcurrentHashMap<UUID, MutableMap<String, Long>>()

    // Active disguise mobs tracked per player for early cancel
    private val activeDisguises = ConcurrentHashMap<UUID, Int>() // taskId

    private val FILLER = ItemStack(Material.BLACK_STAINED_GLASS_PANE).apply {
        editMeta { it.displayName(Component.empty()) }
    }
    private val LOCKED_PANE = ItemStack(Material.RED_STAINED_GLASS_PANE).apply {
        editMeta { meta ->
            meta.displayName(
                Component.text("Locked", NamedTextColor.RED)
                    .decoration(TextDecoration.ITALIC, false)
                    .decoration(TextDecoration.BOLD, true)
            )
            meta.lore(listOf(
                Component.text("You don't have permission", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false)
            ))
        }
    }

    private val GUI_TITLE = Component.text("       ")
        .append(Component.text("G", TextColor.color(0xFF6B6B)))
        .append(Component.text("a", TextColor.color(0xFF8E6B)))
        .append(Component.text("d", TextColor.color(0xFFB16B)))
        .append(Component.text("g", TextColor.color(0xFFD46B)))
        .append(Component.text("e", TextColor.color(0xFFF76B)))
        .append(Component.text("t", TextColor.color(0xD4FF6B)))
        .append(Component.text("s", TextColor.color(0x6BFFB1)))
        .decoration(TextDecoration.BOLD, true)
        .decoration(TextDecoration.ITALIC, false)

    private val DUST_COLORS = listOf(
        Color.RED, Color.ORANGE, Color.YELLOW, Color.LIME,
        Color.AQUA, Color.BLUE, Color.PURPLE, Color.FUCHSIA
    )

    private val WOOL_MATERIALS = listOf(
        Material.WHITE_WOOL, Material.ORANGE_WOOL, Material.MAGENTA_WOOL,
        Material.LIGHT_BLUE_WOOL, Material.YELLOW_WOOL, Material.LIME_WOOL,
        Material.PINK_WOOL, Material.CYAN_WOOL, Material.PURPLE_WOOL,
        Material.BLUE_WOOL, Material.GREEN_WOOL, Material.RED_WOOL
    )

    // ── Melodies for Boom Box ────────────────────────────────────────
    // Each melody is a list of (Instrument, Note tone 0-24, delay in ticks from start)
    private data class NoteEntry(val instrument: Instrument, val note: Int, val delayTicks: Long)

    private val melodies = listOf(
        // Melody 1: ascending pling scale
        listOf(
            NoteEntry(Instrument.PLING, 0, 0), NoteEntry(Instrument.PLING, 3, 4),
            NoteEntry(Instrument.PLING, 5, 8), NoteEntry(Instrument.PLING, 7, 12),
            NoteEntry(Instrument.PLING, 10, 16), NoteEntry(Instrument.PLING, 12, 20),
            NoteEntry(Instrument.PLING, 15, 24), NoteEntry(Instrument.PLING, 17, 28),
            NoteEntry(Instrument.PLING, 20, 32), NoteEntry(Instrument.PLING, 24, 36)
        ),
        // Melody 2: bell chime pattern
        listOf(
            NoteEntry(Instrument.BELL, 12, 0), NoteEntry(Instrument.BELL, 15, 4),
            NoteEntry(Instrument.BELL, 12, 8), NoteEntry(Instrument.BELL, 10, 12),
            NoteEntry(Instrument.BELL, 7, 16), NoteEntry(Instrument.BELL, 10, 20),
            NoteEntry(Instrument.BELL, 12, 24), NoteEntry(Instrument.BELL, 15, 28),
            NoteEntry(Instrument.BELL, 19, 32), NoteEntry(Instrument.BELL, 24, 36)
        ),
        // Melody 3: harp bounce
        listOf(
            NoteEntry(Instrument.PIANO, 5, 0), NoteEntry(Instrument.PIANO, 12, 3),
            NoteEntry(Instrument.PIANO, 5, 6), NoteEntry(Instrument.PIANO, 17, 9),
            NoteEntry(Instrument.PIANO, 5, 12), NoteEntry(Instrument.PIANO, 19, 15),
            NoteEntry(Instrument.PIANO, 12, 18), NoteEntry(Instrument.PIANO, 5, 21),
            NoteEntry(Instrument.PIANO, 12, 30), NoteEntry(Instrument.PIANO, 24, 36)
        ),
        // Melody 4: xylophone staccato
        listOf(
            NoteEntry(Instrument.XYLOPHONE, 24, 0), NoteEntry(Instrument.XYLOPHONE, 20, 3),
            NoteEntry(Instrument.XYLOPHONE, 17, 6), NoteEntry(Instrument.XYLOPHONE, 15, 9),
            NoteEntry(Instrument.XYLOPHONE, 12, 12), NoteEntry(Instrument.XYLOPHONE, 10, 15),
            NoteEntry(Instrument.XYLOPHONE, 7, 18), NoteEntry(Instrument.XYLOPHONE, 5, 21),
            NoteEntry(Instrument.XYLOPHONE, 3, 30), NoteEntry(Instrument.XYLOPHONE, 0, 36)
        ),
        // Melody 5: flute trill
        listOf(
            NoteEntry(Instrument.FLUTE, 12, 0), NoteEntry(Instrument.FLUTE, 14, 3),
            NoteEntry(Instrument.FLUTE, 12, 6), NoteEntry(Instrument.FLUTE, 14, 9),
            NoteEntry(Instrument.FLUTE, 17, 12), NoteEntry(Instrument.FLUTE, 19, 15),
            NoteEntry(Instrument.FLUTE, 17, 18), NoteEntry(Instrument.FLUTE, 19, 21),
            NoteEntry(Instrument.FLUTE, 22, 28), NoteEntry(Instrument.FLUTE, 24, 36)
        )
    )

    // ── Lifecycle ────────────────────────────────────────────────────

    fun start() {
        registerGadgets()
        plugin.server.pluginManager.registerEvents(this, plugin)
        plugin.logger.info("[Gadgets] Registered ${gadgets.size} gadget(s).")
    }

    private fun registerGadgets() {
        // Movement
        register(Gadget("grappling_hook", "Grappling Hook", "Launch toward where you're looking", "joshymc.gadget.grappling_hook", Material.FISHING_ROD, "#AAAAAA", 10))
        register(Gadget("launch_pad", "Launch Pad", "Place a temporary launch pad", "joshymc.gadget.launch_pad", Material.SLIME_BALL, "#55FF55", 15))
        register(Gadget("rocket_boots", "Rocket Boots", "Fly with flames for 3 seconds", "joshymc.gadget.rocket_boots", Material.LEATHER_BOOTS, "#FF5500", 30))
        register(Gadget("ender_warp", "Ender Warp", "Warp forward with ender particles", "joshymc.gadget.ender_warp", Material.ENDER_PEARL, "#AA00FF", 8))

        // Social/Troll
        register(Gadget("boom_box", "Boom Box", "Play a random melody for everyone", "joshymc.gadget.boom_box", Material.JUKEBOX, "#FFAA00", 20))
        register(Gadget("selfie_stick", "Selfie Stick", "Spawn a spinning clone of yourself", "joshymc.gadget.selfie_stick", Material.STICK, "#55AAFF", 15))
        register(Gadget("disguise", "Disguise", "Go invisible with a decoy mob", "joshymc.gadget.disguise", Material.CARVED_PUMPKIN, "#FF8800", 45))
        register(Gadget("snow_globe", "Snow Globe", "Surround yourself in a snow globe", "joshymc.gadget.snow_globe", Material.SNOW_BLOCK, "#FFFFFF", 20))

        // Spectacle
        register(Gadget("firework_show", "Firework Show", "Launch a dazzling firework display", "joshymc.gadget.firework_show", Material.FIREWORK_ROCKET, "#FF55FF", 20))
        register(Gadget("lightning_storm", "Lightning Storm", "Summon cosmetic lightning bolts", "joshymc.gadget.lightning_storm", Material.LIGHTNING_ROD, "#FFFF55", 25))
        register(Gadget("meteor_strike", "Meteor Strike", "Call down a meteor at your target", "joshymc.gadget.meteor_strike", Material.FIRE_CHARGE, "#FF3300", 20))
        register(Gadget("black_hole", "Black Hole", "Open a particle vortex", "joshymc.gadget.black_hole", Material.ENDER_EYE, "#5500AA", 30))
        register(Gadget("disco_floor", "Disco Floor", "Turn the ground into a dance floor", "joshymc.gadget.disco_floor", Material.GLOWSTONE, "#FFD700", 15))

        // Pet/Companion
        register(Gadget("bat_swarm", "Bat Swarm", "Spawn a swarm of bats", "joshymc.gadget.bat_swarm", Material.BAT_SPAWN_EGG, "#333333", 20))
        register(Gadget("parrot_party", "Parrot Party", "Summon dancing parrots", "joshymc.gadget.parrot_party", Material.PARROT_SPAWN_EGG, "#00FF55", 20))
        register(Gadget("phantom_wings", "Phantom Wings", "Glow with ethereal wings", "joshymc.gadget.phantom_wings", Material.PHANTOM_MEMBRANE, "#BBBBFF", 30))
        register(Gadget("tornado", "Tornado", "Spawn a whirling tornado", "joshymc.gadget.tornado", Material.FEATHER, "#CCCCCC", 20))
        register(Gadget("earthquake", "Earthquake", "Shake the ground around you", "joshymc.gadget.earthquake", Material.COBBLESTONE, "#886644", 25))
    }

    private fun register(gadget: Gadget) {
        gadgets[gadget.id] = gadget
    }

    fun getGadget(id: String): Gadget? = gadgets[id]

    // ── Gadget Item Creation ─────────────────────────────────────────

    fun createGadgetItem(gadget: Gadget): ItemStack {
        val item = ItemStack(gadget.icon)
        val meta = item.itemMeta!!

        val nameColor = TextColor.color(java.lang.Long.decode(gadget.color.replace("#", "0x")).toInt())
        meta.displayName(
            Component.text(gadget.name, nameColor)
                .decoration(TextDecoration.ITALIC, false)
                .decoration(TextDecoration.BOLD, true)
        )

        meta.lore(listOf(
            Component.empty(),
            Component.text(gadget.description, NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false),
            Component.empty(),
            Component.text("Right-click to use", NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false),
            Component.text("Cooldown: ${gadget.cooldownSeconds}s", NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false)
        ))

        meta.setEnchantmentGlintOverride(true)
        meta.persistentDataContainer.set(gadgetKey, PersistentDataType.STRING, gadget.id)

        item.itemMeta = meta
        return item
    }

    private fun getGadgetId(item: ItemStack?): String? {
        if (item == null || item.type == Material.AIR) return null
        val meta = item.itemMeta ?: return null
        return meta.persistentDataContainer.get(gadgetKey, PersistentDataType.STRING)
    }

    // ── Equipping ────────────────────────────────────────────────────

    fun equipGadget(player: Player, gadget: Gadget) {
        unequipGadget(player)
        val item = createGadgetItem(gadget)
        player.inventory.addItem(item)
        val nameColor = TextColor.color(java.lang.Long.decode(gadget.color.replace("#", "0x")).toInt())
        plugin.commsManager.send(player, Component.text("Equipped ", NamedTextColor.GREEN)
            .append(Component.text(gadget.name, nameColor).decoration(TextDecoration.BOLD, true))
            .append(Component.text("!", NamedTextColor.GREEN)))
    }

    fun unequipGadget(player: Player) {
        val inv = player.inventory
        for (i in 0 until inv.size) {
            val item = inv.getItem(i) ?: continue
            if (getGadgetId(item) != null) {
                inv.setItem(i, null)
            }
        }
    }

    fun getEquippedGadgetId(player: Player): String? {
        val inv = player.inventory
        for (i in 0 until inv.size) {
            val id = getGadgetId(inv.getItem(i))
            if (id != null) return id
        }
        return null
    }

    // ── Cooldowns ────────────────────────────────────────────────────

    private fun isOnCooldown(player: Player, gadgetId: String): Boolean {
        if (player.hasPermission("joshymc.gadget.nocooldown")) return false
        val map = cooldowns[player.uniqueId] ?: return false
        val expiry = map[gadgetId] ?: return false
        return System.currentTimeMillis() < expiry
    }

    private fun getRemainingCooldown(player: Player, gadgetId: String): Long {
        val map = cooldowns[player.uniqueId] ?: return 0
        val expiry = map[gadgetId] ?: return 0
        return ((expiry - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
    }

    private fun setCooldown(player: Player, gadgetId: String, seconds: Int) {
        if (player.hasPermission("joshymc.gadget.nocooldown")) return
        cooldowns.getOrPut(player.uniqueId) { mutableMapOf() }[gadgetId] =
            System.currentTimeMillis() + (seconds * 1000L)
    }

    // ── GUI ──────────────────────────────────────────────────────────

    fun openGadgetMenu(player: Player, fromCosmetics: Boolean = false) {
        val gui = CustomGui(GUI_TITLE, 54)
        gui.fill(FILLER.clone())

        val equippedId = getEquippedGadgetId(player)

        // Gadget slots: rows 1-3 (slots 10-16, 19-25, 28-34) = 21 slots for 18 gadgets
        val allSlots = listOf(
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31
        )

        val gadgetList = gadgets.values.toList()
        for ((idx, gadget) in gadgetList.withIndex()) {
            if (idx >= allSlots.size) break
            val slot = allSlots[idx]
            val hasPermission = player.hasPermission(gadget.permission)

            if (!hasPermission) {
                gui.setItem(slot, LOCKED_PANE.clone()) { p, _ ->
                    p.playSound(p.location, Sound.ENTITY_VILLAGER_NO, 0.7f, 1.0f)
                }
                continue
            }

            val isEquipped = equippedId == gadget.id
            val item = buildGadgetMenuItem(gadget, isEquipped)

            gui.setItem(slot, item) { p, _ ->
                if (isEquipped) {
                    unequipGadget(p)
                    plugin.commsManager.send(p, Component.text("Unequipped gadget.", NamedTextColor.GRAY))
                    p.playSound(p.location, Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 0.5f)
                } else {
                    equipGadget(p, gadget)
                    p.playSound(p.location, Sound.BLOCK_NOTE_BLOCK_PLING, 0.7f, 2.0f)
                }
                p.closeInventory()
            }
        }

        // Unequip button
        val unequipItem = ItemStack(Material.BARRIER).apply {
            editMeta { meta ->
                meta.displayName(
                    Component.text("Unequip Gadget", NamedTextColor.RED)
                        .decoration(TextDecoration.ITALIC, false)
                        .decoration(TextDecoration.BOLD, true)
                )
            }
        }
        gui.setItem(45, unequipItem) { p, _ ->
            unequipGadget(p)
            plugin.commsManager.send(p, Component.text("Unequipped gadget.", NamedTextColor.GRAY))
            p.playSound(p.location, Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 0.5f)
            p.closeInventory()
        }

        // Back button
        if (fromCosmetics) {
            val backItem = ItemStack(Material.ARROW).apply {
                editMeta { meta ->
                    meta.displayName(
                        Component.text("Back", NamedTextColor.WHITE)
                            .decoration(TextDecoration.ITALIC, false)
                    )
                }
            }
            gui.setItem(49, backItem) { p, _ ->
                p.closeInventory()
                p.performCommand("cosmetics")
            }
        }

        plugin.guiManager.open(player, gui)
        player.playSound(player.location, Sound.BLOCK_CHEST_OPEN, 0.5f, 1.2f)
    }

    private fun buildGadgetMenuItem(gadget: Gadget, equipped: Boolean): ItemStack {
        val item = ItemStack(gadget.icon)
        val meta = item.itemMeta!!

        val nameColor = TextColor.color(java.lang.Long.decode(gadget.color.replace("#", "0x")).toInt())
        meta.displayName(
            Component.text(gadget.name, nameColor)
                .decoration(TextDecoration.ITALIC, false)
                .decoration(TextDecoration.BOLD, true)
        )

        val lore = mutableListOf(
            Component.empty(),
            Component.text(gadget.description, NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false),
            Component.text("Cooldown: ${gadget.cooldownSeconds}s", NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false),
            Component.empty()
        )

        if (equipped) {
            lore.add(Component.text("  EQUIPPED", NamedTextColor.GREEN)
                .decoration(TextDecoration.BOLD, true)
                .decoration(TextDecoration.ITALIC, false))
            lore.add(Component.text("  Click to unequip", NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false))
        } else {
            lore.add(Component.text("  Click to equip", NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false))
        }

        meta.lore(lore)
        if (equipped) meta.setEnchantmentGlintOverride(true)

        item.itemMeta = meta
        return item
    }

    // ── Event Listeners ──────────────────────────────────────────────

    @EventHandler
    fun onInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        if (event.action != Action.RIGHT_CLICK_AIR && event.action != Action.RIGHT_CLICK_BLOCK) return

        val player = event.player
        val item = player.inventory.itemInMainHand
        val gadgetId = getGadgetId(item) ?: return

        event.isCancelled = true

        val gadget = gadgets[gadgetId] ?: return

        if (!player.hasPermission(gadget.permission)) {
            plugin.commsManager.send(player, Component.text("You don't have permission to use this gadget.", NamedTextColor.RED))
            return
        }

        // Disguise early cancel: right-click again while disguised
        if (gadgetId == "disguise" && activeDisguises.containsKey(player.uniqueId)) {
            val taskId = activeDisguises.remove(player.uniqueId)
            if (taskId != null) Bukkit.getScheduler().cancelTask(taskId)
            player.removePotionEffect(PotionEffectType.INVISIBILITY)
            plugin.commsManager.send(player, Component.text("Disguise removed.", NamedTextColor.GRAY))
            return
        }

        if (isOnCooldown(player, gadgetId)) {
            val remaining = getRemainingCooldown(player, gadgetId)
            plugin.commsManager.send(player, Component.text("Cooldown: ${remaining}s remaining.", NamedTextColor.RED))
            player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASS, 0.5f, 0.5f)
            return
        }

        setCooldown(player, gadgetId, gadget.cooldownSeconds)
        executeGadget(player, gadgetId)
    }

    @EventHandler
    fun onDrop(event: PlayerDropItemEvent) {
        if (getGadgetId(event.itemDrop.itemStack) != null) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onDeath(event: PlayerDeathEvent) {
        event.drops.removeIf { getGadgetId(it) != null }
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onFallDamage(event: EntityDamageEvent) {
        if (event.cause != EntityDamageEvent.DamageCause.FALL) return
        val player = event.entity as? Player ?: return
        if (player.hasMetadata("gadget_no_fall")) {
            event.isCancelled = true
        }
    }

    // ── Gadget Effect Dispatch ───────────────────────────────────────

    private fun executeGadget(player: Player, gadgetId: String) {
        // Vanished players can't use gadgets — the particles, mobs, and
        // fireworks these spawn would immediately give away their position
        // even though the player entity itself is hidden.
        if (plugin.vanishCommand.isVanished(player)) {
            plugin.commsManager.send(
                player,
                net.kyori.adventure.text.Component.text(
                    "Gadgets are disabled while vanished.",
                    net.kyori.adventure.text.format.NamedTextColor.RED
                )
            )
            return
        }
        when (gadgetId) {
            "grappling_hook" -> effectGrapplingHook(player)
            "launch_pad" -> effectLaunchPad(player)
            "rocket_boots" -> effectRocketBoots(player)
            "ender_warp" -> effectEnderWarp(player)
            "boom_box" -> effectBoomBox(player)
            "selfie_stick" -> effectSelfieStick(player)
            "disguise" -> effectDisguise(player)
            "snow_globe" -> effectSnowGlobe(player)
            "firework_show" -> effectFireworkShow(player)
            "lightning_storm" -> effectLightningStorm(player)
            "meteor_strike" -> effectMeteorStrike(player)
            "black_hole" -> effectBlackHole(player)
            "disco_floor" -> effectDiscoFloor(player)
            "bat_swarm" -> effectBatSwarm(player)
            "parrot_party" -> effectParrotParty(player)
            "phantom_wings" -> effectPhantomWings(player)
            "tornado" -> effectTornado(player)
            "earthquake" -> effectEarthquake(player)
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // MOVEMENT GADGETS
    // ══════════════════════════════════════════════════════════════════

    // 1. Grappling Hook — launch + CRIT line + fall damage immunity
    private fun effectGrapplingHook(player: Player) {
        val world = player.world
        val origin = player.eyeLocation
        val direction = origin.direction.normalize()

        // Raytrace particle line (30 blocks)
        val rayResult = player.rayTraceBlocks(30.0)
        val maxDist = rayResult?.hitPosition?.distance(origin.toVector()) ?: 30.0

        for (i in 0..(maxDist * 2).toInt()) {
            val pos = origin.clone().add(direction.clone().multiply(i * 0.5))
            world.spawnParticle(Particle.CRIT, pos, 1, 0.0, 0.0, 0.0, 0.0)
        }

        // Launch player
        player.velocity = direction.clone().multiply(2.5)
        world.playSound(player.location, Sound.ENTITY_FISHING_BOBBER_RETRIEVE, 1.0f, 0.8f)

        // Fall damage immunity for 2 seconds
        player.setMetadata("gadget_no_fall", FixedMetadataValue(plugin, true))
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            player.removeMetadata("gadget_no_fall", plugin)
        }, 40L)
    }

    // 2. Launch Pad — invisible armor stand with slime block, launches nearby players
    private fun effectLaunchPad(player: Player) {
        val target = player.getTargetBlockExact(5)?.location?.add(0.5, 0.0, 0.5)
            ?: player.location.clone()

        val padLoc = target.clone()

        val stand = player.world.spawn(padLoc.clone().add(0.0, -1.4, 0.0), ArmorStand::class.java) { a ->
            a.isVisible = false
            a.isMarker = false
            a.setGravity(false)
            a.isInvulnerable = true
            a.isSilent = true
            val helmet = ItemStack(Material.SLIME_BLOCK)
            a.equipment.helmet = helmet
            a.isPersistent = false
        }

        player.world.playSound(padLoc, Sound.BLOCK_SLIME_BLOCK_PLACE, 1.0f, 1.0f)

        // Tick task: particle ring + launch nearby players
        var ticks = 0
        val task = object : BukkitRunnable() {
            override fun run() {
                if (ticks >= 200 || !stand.isValid) { // 10 seconds
                    if (stand.isValid) stand.remove()
                    cancel()
                    return
                }

                // Green dust ring
                for (i in 0 until 12) {
                    val angle = Math.toRadians(i * 30.0 + ticks * 6.0)
                    val x = cos(angle) * 1.0
                    val z = sin(angle) * 1.0
                    padLoc.world!!.spawnParticle(
                        Particle.DUST, padLoc.clone().add(x, 0.2, z), 1, 0.0, 0.0, 0.0, 0.0,
                        DustOptions(Color.LIME, 0.8f)
                    )
                }

                // Launch nearby players
                for (nearby in padLoc.world!!.getNearbyEntities(padLoc, 1.5, 1.5, 1.5)) {
                    if (nearby is Player && nearby.isOnGround) {
                        nearby.velocity = Vector(0.0, 2.0, 0.0)
                        nearby.world.playSound(nearby.location, Sound.BLOCK_SLIME_BLOCK_BREAK, 1.0f, 1.0f)
                        // Brief fall immunity
                        nearby.setMetadata("gadget_no_fall", FixedMetadataValue(plugin, true))
                        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                            nearby.removeMetadata("gadget_no_fall", plugin)
                        }, 60L)
                    }
                }

                ticks += 2
            }
        }
        task.runTaskTimer(plugin, 0L, 2L)
    }

    // 3. Rocket Boots — 3s flight, flame particles, then slow falling
    private fun effectRocketBoots(player: Player) {
        player.allowFlight = true
        player.isFlying = true
        player.world.playSound(player.location, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1.0f, 1.0f)

        var ticks = 0
        val flightTask = object : BukkitRunnable() {
            override fun run() {
                if (ticks >= 60 || !player.isOnline) { // 3 seconds
                    // Disable flight, apply slow falling
                    if (player.isOnline) {
                        if (player.gameMode != org.bukkit.GameMode.CREATIVE && player.gameMode != org.bukkit.GameMode.SPECTATOR) {
                            player.allowFlight = false
                            player.isFlying = false
                        }
                        player.addPotionEffect(PotionEffect(PotionEffectType.SLOW_FALLING, 60, 0, true, true))
                    }
                    cancel()
                    return
                }

                if (player.isOnline) {
                    val feet = player.location
                    player.world.spawnParticle(Particle.FLAME, feet, 5, 0.15, 0.0, 0.15, 0.02)
                    player.world.spawnParticle(Particle.SOUL_FIRE_FLAME, feet, 3, 0.1, 0.0, 0.1, 0.02)
                }

                ticks++
            }
        }
        flightTask.runTaskTimer(plugin, 0L, 1L)
    }

    // 4. Ender Warp — velocity throw + END_ROD trail + landing burst
    private fun effectEnderWarp(player: Player) {
        val direction = player.eyeLocation.direction.normalize()
        player.velocity = direction.clone().multiply(1.5)
        player.world.playSound(player.location, Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 1.2f)

        // Fall damage immunity
        player.setMetadata("gadget_no_fall", FixedMetadataValue(plugin, true))

        var ticks = 0
        val trailTask = object : BukkitRunnable() {
            override fun run() {
                if (ticks >= 30 || !player.isOnline) { // 1.5 seconds
                    // Landing burst
                    if (player.isOnline) {
                        val loc = player.location
                        for (i in 0 until 50) {
                            val offset = Vector(
                                (Math.random() - 0.5) * 2.0,
                                Math.random() * 2.0,
                                (Math.random() - 0.5) * 2.0
                            )
                            loc.world!!.spawnParticle(Particle.PORTAL, loc.clone().add(offset), 1, 0.0, 0.0, 0.0, 0.0)
                        }
                        loc.world!!.playSound(loc, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 0.8f)
                    }
                    Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                        player.removeMetadata("gadget_no_fall", plugin)
                    }, 20L)
                    cancel()
                    return
                }

                if (player.isOnline) {
                    player.world.spawnParticle(Particle.END_ROD, player.location.add(0.0, 1.0, 0.0), 3, 0.1, 0.1, 0.1, 0.0)
                }

                ticks++
            }
        }
        trailTask.runTaskTimer(plugin, 0L, 1L)
    }

    // ══════════════════════════════════════════════════════════════════
    // SOCIAL/TROLL GADGETS
    // ══════════════════════════════════════════════════════════════════

    // 5. Boom Box — play a random melody via note block sounds
    private fun effectBoomBox(player: Player) {
        val melody = melodies.random()
        val loc = player.location

        for (entry in melody) {
            Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                if (!player.isOnline) return@Runnable
                val currentLoc = player.location
                // Play for everyone within 30 blocks
                val pitch = noteToPitch(entry.note)
                for (nearby in currentLoc.world!!.getNearbyEntities(currentLoc, 30.0, 30.0, 30.0)) {
                    if (nearby is Player) {
                        nearby.playNote(currentLoc, entry.instrument, Note(entry.note))
                    }
                }
                currentLoc.world!!.spawnParticle(Particle.NOTE, currentLoc.clone().add(0.0, 2.2, 0.0), 3, 0.3, 0.2, 0.3)
            }, entry.delayTicks)
        }
    }

    private fun noteToPitch(note: Int): Float {
        return 2.0f.let { Math.pow(2.0, (note - 12).toDouble() / 12.0).toFloat() }
    }

    // 6. Selfie Stick — spinning armor stand clone
    private fun effectSelfieStick(player: Player) {
        val loc = player.location.clone()
        val world = player.world

        val stand = world.spawn(loc.clone().add(0.0, 0.0, 0.0), ArmorStand::class.java) { a ->
            a.isVisible = true
            a.setGravity(false)
            a.isInvulnerable = true
            a.isSilent = true
            a.isPersistent = false

            // Copy player armor
            a.equipment.helmet = player.inventory.helmet?.clone() ?: ItemStack(Material.PLAYER_HEAD).apply {
                editMeta { meta ->
                    if (meta is org.bukkit.inventory.meta.SkullMeta) {
                        meta.owningPlayer = player
                    }
                }
            }
            // Always set skull head
            val skull = ItemStack(Material.PLAYER_HEAD)
            skull.editMeta { meta ->
                if (meta is org.bukkit.inventory.meta.SkullMeta) {
                    meta.owningPlayer = player
                }
            }
            a.equipment.helmet = skull
            a.equipment.chestplate = player.inventory.chestplate?.clone()
            a.equipment.leggings = player.inventory.leggings?.clone()
            a.equipment.boots = player.inventory.boots?.clone()
        }

        world.playSound(loc, Sound.UI_BUTTON_CLICK, 1.0f, 1.5f)

        // Broadcast selfie message
        val msg = Component.text("${player.name} took a selfie!", NamedTextColor.YELLOW)
            .decoration(TextDecoration.ITALIC, true)
        for (nearby in loc.world!!.getNearbyEntities(loc, 20.0, 20.0, 20.0)) {
            if (nearby is Player) {
                nearby.sendMessage(msg)
            }
        }

        // Slow spin for 5 seconds
        var ticks = 0
        object : BukkitRunnable() {
            override fun run() {
                if (ticks >= 100 || !stand.isValid) { // 5 seconds
                    if (stand.isValid) stand.remove()
                    cancel()
                    return
                }

                val newLoc = stand.location.clone()
                newLoc.yaw += 14.4f // Full rotation over 5 seconds (360 / 25 = 14.4 per 2 ticks)
                stand.teleport(newLoc)

                ticks += 2
            }
        }.runTaskTimer(plugin, 0L, 2L)
    }

    // 7. Disguise — invisibility + decoy mob that follows
    private fun effectDisguise(player: Player) {
        val world = player.world
        val loc = player.location.clone()

        val mobTypes = listOf(EntityType.CHICKEN, EntityType.COW, EntityType.PIG, EntityType.SHEEP, EntityType.CREEPER)
        val mobType = mobTypes.random()

        val mob = world.spawnEntity(loc, mobType) as? LivingEntity ?: return
        mob.isPersistent = false
        mob.isSilent = true
        if (mob is org.bukkit.entity.Ageable) mob.setAdult()

        // Apply invisibility
        player.addPotionEffect(PotionEffect(PotionEffectType.INVISIBILITY, 600, 0, true, false)) // 30 seconds

        plugin.commsManager.send(player, Component.text("You are now disguised! Right-click again to end.", NamedTextColor.GREEN))

        var ticks = 0
        val task = object : BukkitRunnable() {
            override fun run() {
                if (ticks >= 600 || !player.isOnline || !activeDisguises.containsKey(player.uniqueId)) {
                    // Cleanup
                    if (mob.isValid) mob.remove()
                    if (player.isOnline) {
                        player.removePotionEffect(PotionEffectType.INVISIBILITY)
                    }
                    activeDisguises.remove(player.uniqueId)
                    cancel()
                    return
                }

                // Teleport mob to player every 10 ticks
                if (ticks % 10 == 0 && mob.isValid && player.isOnline) {
                    val target = player.location.clone().add(
                        (Math.random() - 0.5) * 1.5,
                        0.0,
                        (Math.random() - 0.5) * 1.5
                    )
                    mob.teleport(target)
                }

                ticks++
            }
        }
        val taskId = task.runTaskTimer(plugin, 0L, 1L).taskId
        activeDisguises[player.uniqueId] = taskId
    }

    // 8. Snow Globe — snowflake + dust sphere for 10 seconds
    private fun effectSnowGlobe(player: Player) {
        player.world.playSound(player.location, Sound.WEATHER_RAIN, 0.5f, 1.5f)

        var ticks = 0
        object : BukkitRunnable() {
            override fun run() {
                if (ticks >= 200 || !player.isOnline) { // 10 seconds
                    cancel()
                    return
                }

                val center = player.location.add(0.0, 1.5, 0.0)
                val radius = 5.0

                // Snowflakes inside the sphere
                for (i in 0 until 8) {
                    val rx = (Math.random() - 0.5) * radius * 2
                    val ry = (Math.random() - 0.5) * radius * 2
                    val rz = (Math.random() - 0.5) * radius * 2
                    if (rx * rx + ry * ry + rz * rz <= radius * radius) {
                        center.world!!.spawnParticle(Particle.SNOWFLAKE, center.clone().add(rx, ry, rz), 1, 0.0, -0.05, 0.0, 0.0)
                    }
                }

                // Falling dust inside
                for (i in 0 until 4) {
                    val angle = Math.random() * Math.PI * 2
                    val r = Math.random() * radius
                    val x = cos(angle) * r
                    val z = sin(angle) * r
                    val y = (Math.random() - 0.5) * radius * 2
                    center.world!!.spawnParticle(
                        Particle.FALLING_DUST, center.clone().add(x, y, z), 1, 0.0, 0.0, 0.0, 0.0,
                        Material.SNOW_BLOCK.createBlockData()
                    )
                }

                // White dust ring at sphere edge
                for (i in 0 until 16) {
                    val angle = Math.toRadians(i * 22.5 + ticks * 3.0)
                    val x = cos(angle) * radius
                    val z = sin(angle) * radius
                    center.world!!.spawnParticle(
                        Particle.DUST, center.clone().add(x, 0.0, z), 1, 0.0, 0.0, 0.0, 0.0,
                        DustOptions(Color.WHITE, 0.6f)
                    )
                }

                ticks += 2
            }
        }.runTaskTimer(plugin, 0L, 2L)
    }

    // ══════════════════════════════════════════════════════════════════
    // SPECTACLE GADGETS
    // ══════════════════════════════════════════════════════════════════

    // 9. Firework Show — 5 fireworks staggered over 3 seconds
    private fun effectFireworkShow(player: Player) {
        val loc = player.location.clone()
        val shapes = listOf(
            FireworkEffect.Type.BALL, FireworkEffect.Type.BURST, FireworkEffect.Type.STAR,
            FireworkEffect.Type.BALL_LARGE, FireworkEffect.Type.CREEPER
        )
        val allColors = listOf(Color.RED, Color.ORANGE, Color.YELLOW, Color.LIME, Color.AQUA, Color.BLUE, Color.PURPLE, Color.FUCHSIA, Color.WHITE)

        for (i in 0 until 5) {
            Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                if (!player.isOnline) return@Runnable
                val offset = loc.clone().add(
                    (Math.random() - 0.5) * 6.0,
                    0.0,
                    (Math.random() - 0.5) * 6.0
                )
                val world = loc.world ?: return@Runnable
                world.spawn(offset, Firework::class.java) { fw ->
                    val fwMeta = fw.fireworkMeta
                    fwMeta.addEffect(
                        FireworkEffect.builder()
                            .with(shapes[i])
                            .withColor(allColors.shuffled().take(3))
                            .withFade(allColors.shuffled().take(2))
                            .trail(true)
                            .flicker(i % 2 == 0)
                            .build()
                    )
                    fwMeta.power = 1
                    fw.fireworkMeta = fwMeta
                }
            }, (i * 12).toLong())
        }
    }

    // 10. Lightning Storm — 7 cosmetic lightning bolts over 3 seconds
    private fun effectLightningStorm(player: Player) {
        val loc = player.location.clone()
        val world = loc.world ?: return

        for (i in 0 until 7) {
            val delay = (i * (6 + (Math.random() * 4).toInt())).toLong()
            Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                if (!player.isOnline) return@Runnable
                val strikeLoc = loc.clone().add(
                    (Math.random() - 0.5) * 16.0,
                    0.0,
                    (Math.random() - 0.5) * 16.0
                )
                // Find ground level
                strikeLoc.y = world.getHighestBlockYAt(strikeLoc).toDouble() + 1.0
                world.strikeLightningEffect(strikeLoc)
                world.playSound(strikeLoc, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1.0f, 0.8f + (Math.random() * 0.4).toFloat())
            }, delay)
        }
    }

    // 11. Meteor Strike — animated falling meteor + impact
    private fun effectMeteorStrike(player: Player) {
        val rayResult = player.rayTraceBlocks(50.0)
        val targetLoc = rayResult?.hitPosition?.toLocation(player.world)
            ?: player.eyeLocation.add(player.eyeLocation.direction.multiply(30.0))

        val world = player.world
        val startY = targetLoc.y + 30.0
        val endY = targetLoc.y

        var tick = 0
        object : BukkitRunnable() {
            override fun run() {
                if (tick >= 20) { // 1 second animation
                    // Impact
                    for (i in 0 until 100) {
                        val offset = Vector(
                            (Math.random() - 0.5) * 4.0,
                            Math.random() * 3.0,
                            (Math.random() - 0.5) * 4.0
                        )
                        world.spawnParticle(Particle.EXPLOSION, targetLoc.clone().add(offset), 1, 0.0, 0.0, 0.0, 0.0)
                    }
                    world.spawnParticle(Particle.LAVA, targetLoc, 30, 2.0, 0.5, 2.0, 0.0)
                    world.playSound(targetLoc, Sound.ENTITY_GENERIC_EXPLODE, 2.0f, 0.6f)
                    cancel()
                    return
                }

                // Falling meteor position
                val progress = tick / 20.0
                val currentY = startY - (startY - endY) * progress
                val meteorLoc = targetLoc.clone()
                meteorLoc.y = currentY

                // Flame trail
                world.spawnParticle(Particle.FLAME, meteorLoc, 10, 0.3, 0.3, 0.3, 0.05)
                world.spawnParticle(Particle.LAVA, meteorLoc, 3, 0.2, 0.2, 0.2, 0.0)
                world.spawnParticle(Particle.SMOKE, meteorLoc, 5, 0.2, 0.2, 0.2, 0.02)

                tick++
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    // 12. Black Hole — vortex particles, pull items, end burst
    private fun effectBlackHole(player: Player) {
        val rayResult = player.rayTraceBlocks(15.0)
        val center = rayResult?.hitPosition?.toLocation(player.world)?.add(0.0, 1.0, 0.0)
            ?: player.eyeLocation.add(player.eyeLocation.direction.multiply(10.0))

        val world = center.world ?: return

        var ticks = 0
        object : BukkitRunnable() {
            override fun run() {
                if (ticks >= 100) { // 5 seconds
                    // End burst
                    for (i in 0 until 30) {
                        val dir = Vector(
                            (Math.random() - 0.5) * 2.0,
                            (Math.random() - 0.5) * 2.0,
                            (Math.random() - 0.5) * 2.0
                        ).normalize().multiply(0.3)
                        world.spawnParticle(Particle.END_ROD, center, 1, dir.x, dir.y, dir.z, 0.1)
                    }
                    world.playSound(center, Sound.ENTITY_ENDER_DRAGON_GROWL, 1.0f, 1.5f)
                    cancel()
                    return
                }

                // Spiraling PORTAL + DUST_COLOR_TRANSITION particles
                val time = ticks * 0.1
                for (i in 0 until 12) {
                    val angle = Math.toRadians(i * 30.0) + time * 3.0
                    val radius = 3.0 - (ticks % 20) * 0.15 // spiral inward
                    val r = radius.coerceAtLeast(0.3)
                    val x = cos(angle) * r
                    val z = sin(angle) * r
                    val y = sin(time + i) * 0.5

                    world.spawnParticle(Particle.PORTAL, center.clone().add(x, y, z), 1, 0.0, 0.0, 0.0, 0.0)
                    world.spawnParticle(
                        Particle.DUST_COLOR_TRANSITION, center.clone().add(x, y, z), 1, 0.0, 0.0, 0.0, 0.0,
                        DustTransition(Color.PURPLE, Color.fromRGB(20, 0, 20), 1.2f)
                    )
                }

                // Pull nearby dropped items toward center
                for (entity in world.getNearbyEntities(center, 5.0, 5.0, 5.0)) {
                    if (entity is org.bukkit.entity.Item) {
                        val dir = center.toVector().subtract(entity.location.toVector()).normalize().multiply(0.3)
                        entity.velocity = dir
                    }
                }

                ticks += 2
            }
        }.runTaskTimer(plugin, 0L, 2L)
    }

    // 13. Disco Floor — swap 5x5 floor blocks to random wool colors
    private fun effectDiscoFloor(player: Player) {
        val world = player.world
        val baseLoc = player.location.clone()
        val baseY = baseLoc.blockY - 1

        // Save original blocks in a 5x5 area
        val savedBlocks = mutableMapOf<Location, BlockData>()
        val baseX = baseLoc.blockX - 2
        val baseZ = baseLoc.blockZ - 2

        for (dx in 0 until 5) {
            for (dz in 0 until 5) {
                val loc = Location(world, (baseX + dx).toDouble(), baseY.toDouble(), (baseZ + dz).toDouble())
                val block = loc.block
                if (block.type.isSolid) {
                    savedBlocks[loc] = block.blockData.clone()
                }
            }
        }

        if (savedBlocks.isEmpty()) {
            plugin.commsManager.send(player, Component.text("No solid ground to disco on!", NamedTextColor.RED))
            cooldowns[player.uniqueId]?.remove("disco_floor")
            return
        }

        world.playSound(baseLoc, Sound.BLOCK_NOTE_BLOCK_BELL, 1.0f, 1.0f)

        var ticks = 0
        object : BukkitRunnable() {
            override fun run() {
                if (ticks >= 200) { // 10 seconds
                    // Restore original blocks
                    for ((loc, data) in savedBlocks) {
                        loc.block.blockData = data
                    }
                    cancel()
                    return
                }

                // Change to random wool every 4 ticks
                if (ticks % 4 == 0) {
                    for (loc in savedBlocks.keys) {
                        loc.block.type = WOOL_MATERIALS.random()
                    }
                    // Note particles above the floor
                    val center = baseLoc.clone()
                    center.y = (baseY + 1).toDouble()
                    world.spawnParticle(Particle.NOTE, center.add(0.0, 0.5, 0.0), 5, 2.0, 0.3, 2.0)
                    world.playSound(center, Sound.BLOCK_NOTE_BLOCK_BELL, 0.4f, 0.5f + (Math.random() * 1.5).toFloat())
                }

                ticks += 2
            }
        }.runTaskTimer(plugin, 0L, 2L)
    }

    // ══════════════════════════════════════════════════════════════════
    // PET/COMPANION GADGETS
    // ══════════════════════════════════════════════════════════════════

    // 14. Bat Swarm — 10 bats for 15 seconds
    private fun effectBatSwarm(player: Player) {
        val loc = player.location.add(0.0, 1.0, 0.0)
        player.world.playSound(loc, Sound.ENTITY_BAT_AMBIENT, 0.6f, 1.0f)

        val bats = mutableListOf<Bat>()
        for (i in 0 until 10) {
            val spawnLoc = loc.clone().add(
                (Math.random() - 0.5) * 3.0,
                Math.random() * 2.0,
                (Math.random() - 0.5) * 3.0
            )
            val bat = player.world.spawn(spawnLoc, Bat::class.java) { b ->
                b.isAwake = true
                b.isPersistent = false
                b.removeWhenFarAway = true
                b.isSilent = true
            }
            bats.add(bat)
        }

        // Soft ambient sound periodically
        var ticks = 0
        object : BukkitRunnable() {
            override fun run() {
                if (ticks >= 300) { // 15 seconds
                    bats.forEach { if (it.isValid) it.remove() }
                    cancel()
                    return
                }
                if (ticks % 40 == 0 && player.isOnline) {
                    player.world.playSound(player.location, Sound.ENTITY_BAT_AMBIENT, 0.3f, 1.0f)
                }
                ticks += 2
            }
        }.runTaskTimer(plugin, 0L, 2L)
    }

    // 15. Parrot Party — 3 parrots that follow for 15 seconds
    private fun effectParrotParty(player: Player) {
        val loc = player.location.clone()
        val world = player.world
        val variants = Parrot.Variant.entries.toTypedArray()

        val parrots = mutableListOf<Parrot>()
        for (i in 0 until 3) {
            val spawnLoc = loc.clone().add(
                (Math.random() - 0.5) * 2.0,
                0.0,
                (Math.random() - 0.5) * 2.0
            )
            val parrot = world.spawn(spawnLoc, Parrot::class.java) { p ->
                p.variant = variants[i % variants.size]
                p.isPersistent = false
                p.isSilent = false
                p.setAdult()
                p.isTamed = true
                p.owner = player
                p.isInvulnerable = true
            }
            parrots.add(parrot)
        }

        // Play a jukebox-like sound to make them dance
        world.playSound(loc, Sound.MUSIC_DISC_CAT, 1.0f, 1.0f)

        // Follow player + cleanup after 15s
        var ticks = 0
        object : BukkitRunnable() {
            override fun run() {
                if (ticks >= 300 || !player.isOnline) { // 15 seconds
                    parrots.forEach { if (it.isValid) it.remove() }
                    cancel()
                    return
                }

                // Teleport parrots near player if they fall behind
                if (ticks % 20 == 0) {
                    for (parrot in parrots) {
                        if (parrot.isValid && parrot.location.distance(player.location) > 5.0) {
                            parrot.teleport(player.location.clone().add(
                                (Math.random() - 0.5) * 2.0,
                                0.0,
                                (Math.random() - 0.5) * 2.0
                            ))
                        }
                    }
                }

                // Note particles
                if (ticks % 10 == 0) {
                    world.spawnParticle(Particle.NOTE, player.location.add(0.0, 2.0, 0.0), 3, 1.0, 0.3, 1.0)
                }

                ticks += 2
            }
        }.runTaskTimer(plugin, 0L, 2L)
    }

    // 16. Phantom Wings — END_ROD wing shape behind player for 30 seconds
    private fun effectPhantomWings(player: Player) {
        player.world.playSound(player.location, Sound.ENTITY_PHANTOM_FLAP, 0.8f, 1.2f)

        var ticks = 0
        object : BukkitRunnable() {
            override fun run() {
                if (ticks >= 600 || !player.isOnline) { // 30 seconds
                    cancel()
                    return
                }

                val loc = player.location
                val yawRad = Math.toRadians(loc.yaw.toDouble() + 90) // perpendicular to look direction
                val backX = -cos(Math.toRadians(loc.yaw.toDouble())) * 0.3
                val backZ = -sin(Math.toRadians(loc.yaw.toDouble())) * 0.3
                val base = loc.clone().add(backX, 1.2, backZ)

                // Wing beat animation
                val flapAngle = sin(ticks * 0.15) * 0.3

                // Left wing
                for (i in 1..5) {
                    val spread = i * 0.35
                    val wx = cos(yawRad) * spread
                    val wz = sin(yawRad) * spread
                    val wy = (5 - i) * 0.08 + flapAngle * i * 0.1
                    player.world.spawnParticle(
                        Particle.END_ROD,
                        base.clone().add(wx, wy, wz),
                        1, 0.0, 0.0, 0.0, 0.0
                    )
                }

                // Right wing
                for (i in 1..5) {
                    val spread = i * 0.35
                    val wx = -cos(yawRad) * spread
                    val wz = -sin(yawRad) * spread
                    val wy = (5 - i) * 0.08 + flapAngle * i * 0.1
                    player.world.spawnParticle(
                        Particle.END_ROD,
                        base.clone().add(wx, wy, wz),
                        1, 0.0, 0.0, 0.0, 0.0
                    )
                }

                ticks += 2
            }
        }.runTaskTimer(plugin, 0L, 2L)
    }

    // 17. Tornado — spinning particle column at target
    private fun effectTornado(player: Player) {
        val rayResult = player.rayTraceBlocks(20.0)
        val baseLoc = rayResult?.hitPosition?.toLocation(player.world)?.add(0.0, 0.5, 0.0)
            ?: player.location.clone().add(player.location.direction.multiply(5.0))

        val world = baseLoc.world ?: return
        world.playSound(baseLoc, Sound.ENTITY_ENDER_DRAGON_FLAP, 1.0f, 0.5f)

        var ticks = 0
        object : BukkitRunnable() {
            override fun run() {
                if (ticks >= 100) { // 5 seconds
                    cancel()
                    return
                }

                val time = ticks * 0.2

                // Spinning column: 8 blocks tall
                for (y in 0 until 8) {
                    val radius = 0.5 + (y * 0.25) + sin(time + y) * 0.2 // expands upward + oscillates
                    val particlesAtLevel = 4 + y
                    for (p in 0 until particlesAtLevel) {
                        val angle = Math.toRadians(p * (360.0 / particlesAtLevel)) + time * 3.0 + y * 0.5
                        val x = cos(angle) * radius
                        val z = sin(angle) * radius
                        val pos = baseLoc.clone().add(x, y.toDouble(), z)

                        if (y < 4) {
                            world.spawnParticle(Particle.CLOUD, pos, 1, 0.0, 0.0, 0.0, 0.0)
                        } else {
                            world.spawnParticle(
                                Particle.DUST, pos, 1, 0.0, 0.0, 0.0, 0.0,
                                DustOptions(Color.fromRGB(200, 200, 200), 1.0f)
                            )
                        }
                    }
                }

                // Pull nearby items toward base
                for (entity in world.getNearbyEntities(baseLoc, 5.0, 8.0, 5.0)) {
                    if (entity is org.bukkit.entity.Item) {
                        val dir = baseLoc.toVector().subtract(entity.location.toVector()).normalize().multiply(0.2)
                        entity.velocity = dir
                    }
                }

                // Sound
                if (ticks % 20 == 0) {
                    world.playSound(baseLoc, Sound.ENTITY_ENDER_DRAGON_FLAP, 0.8f, 0.5f)
                }

                ticks += 2
            }
        }.runTaskTimer(plugin, 0L, 2L)
    }

    // 18. Earthquake — screen shake + ground crack particles + player nudge
    private fun effectEarthquake(player: Player) {
        val loc = player.location.clone()
        val world = loc.world ?: return

        world.playSound(loc, Sound.BLOCK_ANVIL_LAND, 0.8f, 0.3f)

        var ticks = 0
        object : BukkitRunnable() {
            override fun run() {
                if (ticks >= 60) { // 3 seconds
                    cancel()
                    return
                }

                val center = player.location

                // Screen shake sound for nearby players
                for (nearby in world.getNearbyEntities(center, 10.0, 10.0, 10.0)) {
                    if (nearby is Player) {
                        nearby.playSound(nearby.location, Sound.BLOCK_ANVIL_LAND, 0.3f, 0.2f + (Math.random() * 0.3).toFloat())

                        // Small random velocity nudge
                        val nudge = Vector(
                            (Math.random() - 0.5) * 0.3,
                            0.05,
                            (Math.random() - 0.5) * 0.3
                        )
                        nearby.velocity = nearby.velocity.add(nudge)
                    }
                }

                // Ground crack particles in 10-block radius
                for (i in 0 until 20) {
                    val rx = (Math.random() - 0.5) * 20.0
                    val rz = (Math.random() - 0.5) * 20.0
                    val groundLoc = center.clone().add(rx, 0.0, rz)
                    groundLoc.y = world.getHighestBlockYAt(groundLoc).toDouble() + 0.1

                    world.spawnParticle(
                        Particle.BLOCK, groundLoc, 5, 0.3, 0.1, 0.3, 0.0,
                        Material.STONE.createBlockData()
                    )
                }

                // Occasional boulder particles
                if (ticks % 10 == 0) {
                    for (i in 0 until 5) {
                        val rx = (Math.random() - 0.5) * 14.0
                        val rz = (Math.random() - 0.5) * 14.0
                        val groundLoc = center.clone().add(rx, 0.0, rz)
                        groundLoc.y = world.getHighestBlockYAt(groundLoc).toDouble() + 0.5
                        world.spawnParticle(
                            Particle.BLOCK, groundLoc, 15, 0.5, 0.5, 0.5, 0.1,
                            Material.COBBLESTONE.createBlockData()
                        )
                    }
                }

                ticks += 2
            }
        }.runTaskTimer(plugin, 0L, 2L)
    }

    // ── Cleanup ──────────────────────────────────────────────────────

    fun evictCooldowns(uuid: UUID) {
        cooldowns.remove(uuid)
        val taskId = activeDisguises.remove(uuid)
        if (taskId != null) Bukkit.getScheduler().cancelTask(taskId)
    }
}
