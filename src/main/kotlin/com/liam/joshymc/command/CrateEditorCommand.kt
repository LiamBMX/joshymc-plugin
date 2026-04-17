package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import com.liam.joshymc.manager.CrateManager
import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.ItemStack
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class CrateEditorCommand(private val plugin: Joshymc) : CommandExecutor, Listener {

    // --- Pending actions for chat input ---

    sealed class PendingCrateAction(val createdAt: Long = System.currentTimeMillis()) {
        class CreateCrate : PendingCrateAction()
        class RenameCrate(val crateId: String) : PendingCrateAction()
        class AddReward(val crateId: String, val item: ItemStack) : PendingCrateAction()
        class EditWeight(val crateId: String, val rewardIndex: Int) : PendingCrateAction()
    }

    val pendingActions = ConcurrentHashMap<UUID, PendingCrateAction>()

    fun start() {
        // Expire pending actions after 30 seconds
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, Runnable {
            val now = System.currentTimeMillis()
            val iter = pendingActions.entries.iterator()
            while (iter.hasNext()) {
                val entry = iter.next()
                if (now - entry.value.createdAt > 30_000) {
                    iter.remove()
                    val player = Bukkit.getPlayer(entry.key)
                    if (player != null) {
                        Bukkit.getScheduler().runTask(plugin, Runnable {
                            plugin.commsManager.send(player, Component.text("Crate editor action timed out.", NamedTextColor.RED))
                        })
                    }
                }
            }
        }, 20L, 20L)
    }

    // --- Command entry ---

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) { sender.sendMessage("Players only."); return true }
        if (!sender.hasPermission("joshymc.crateeditor")) {
            plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED))
            return true
        }

        openMainMenu(sender)
        return true
    }

    // --- Main Menu ---

    fun openMainMenu(player: Player) {
        val crates = plugin.crateManager.getAllCrates()
        val size = 27

        val gui = CustomGui(
            Component.text("Crate Editor", TextColor.color(0x55FFFF))
                .decoration(TextDecoration.ITALIC, false)
                .decoration(TextDecoration.BOLD, true),
            size
        )

        // Fill border with gray glass
        val filler = ItemStack(Material.GRAY_STAINED_GLASS_PANE)
        filler.editMeta { it.displayName(Component.empty()) }
        gui.border(filler)

        // Place each crate type as its icon
        var slot = 10
        for ((id, crate) in crates) {
            if (slot > 16) break // max 7 crates in one row

            val icon = ItemStack(crate.keyMaterial)
            icon.editMeta { meta ->
                meta.displayName(
                    Component.text(crate.displayName, TextColor.color(0xFFAA00))
                        .decoration(TextDecoration.ITALIC, false)
                        .decoration(TextDecoration.BOLD, true)
                )
                meta.lore(listOf(
                    Component.empty(),
                    Component.text("  ID: ", NamedTextColor.GRAY)
                        .append(Component.text(id, NamedTextColor.WHITE))
                        .decoration(TextDecoration.ITALIC, false),
                    Component.text("  Rewards: ", NamedTextColor.GRAY)
                        .append(Component.text("${crate.rewards.size}", NamedTextColor.WHITE))
                        .decoration(TextDecoration.ITALIC, false),
                    Component.empty(),
                    Component.text("  Click to edit", NamedTextColor.YELLOW)
                        .decoration(TextDecoration.ITALIC, false),
                    Component.empty()
                ))
            }

            val crateId = id
            gui.setItem(slot, icon) { p, _ ->
                p.playSound(p.location, Sound.UI_BUTTON_CLICK, 0.5f, 1.0f)
                openEditMenu(p, crateId)
            }
            slot++
        }

        // Create New Crate button
        val createBtn = ItemStack(Material.LIME_WOOL)
        createBtn.editMeta { meta ->
            meta.displayName(
                Component.text("Create New Crate", NamedTextColor.GREEN)
                    .decoration(TextDecoration.ITALIC, false)
                    .decoration(TextDecoration.BOLD, true)
            )
            meta.lore(listOf(
                Component.empty(),
                Component.text("  Click to create a new crate type", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false),
                Component.empty()
            ))
        }
        gui.setItem(22, createBtn) { p, _ ->
            p.playSound(p.location, Sound.UI_BUTTON_CLICK, 0.5f, 1.0f)
            pendingActions[p.uniqueId] = PendingCrateAction.CreateCrate()
            p.closeInventory()
            plugin.commsManager.send(p, Component.text("Type the new crate name in chat (or 'cancel'):", NamedTextColor.YELLOW))
        }

        plugin.guiManager.open(player, gui)
        player.playSound(player.location, Sound.BLOCK_CHEST_OPEN, 0.5f, 1.2f)
    }

    // --- Crate Edit Menu ---

    fun openEditMenu(player: Player, crateId: String) {
        val crate = plugin.crateManager.getCrate(crateId) ?: run {
            plugin.commsManager.send(player, Component.text("Crate not found.", NamedTextColor.RED))
            return
        }

        val size = 54
        val gui = CustomGui(
            Component.text("Edit: ", NamedTextColor.WHITE)
                .append(Component.text(crate.displayName, TextColor.color(0x55FFFF)))
                .decoration(TextDecoration.ITALIC, false),
            size
        )

        // Fill border
        val filler = ItemStack(Material.GRAY_STAINED_GLASS_PANE)
        filler.editMeta { it.displayName(Component.empty()) }
        gui.fill(filler)

        // Show rewards in slots 0-44 (top area)
        val totalWeight = crate.rewards.sumOf { it.weight }.coerceAtLeast(1)
        for ((idx, reward) in crate.rewards.withIndex()) {
            if (idx >= 45) break
            val percentage = "%.1f".format(reward.weight.toDouble() / totalWeight * 100)

            // Use serialized item if present (preserves trims/custom items),
            // otherwise build a plain item from the material.
            val item = plugin.crateManager.deserializeItem(reward.itemBase64)
                ?: ItemStack(reward.material, reward.amount.coerceIn(1, 64))
            item.amount = reward.amount.coerceIn(1, 64)
            item.editMeta { meta ->
                meta.displayName(
                    Component.text(reward.displayName, TextColor.color(0xFFAA00))
                        .decoration(TextDecoration.ITALIC, false)
                        .decoration(TextDecoration.BOLD, true)
                )
                val lore = mutableListOf<Component>()
                lore.add(Component.empty())
                lore.add(
                    Component.text("  Amount: ", NamedTextColor.GRAY)
                        .append(Component.text("${reward.amount}", NamedTextColor.WHITE))
                        .decoration(TextDecoration.ITALIC, false)
                )
                lore.add(
                    Component.text("  Weight: ", NamedTextColor.GRAY)
                        .append(Component.text("${reward.weight}", NamedTextColor.WHITE))
                        .append(Component.text(" ($percentage%)", NamedTextColor.YELLOW))
                        .decoration(TextDecoration.ITALIC, false)
                )
                if (reward.enchantments.isNotEmpty()) {
                    lore.add(Component.empty())
                    lore.add(Component.text("  Enchantments:", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false))
                    for ((ench, level) in reward.enchantments) {
                        lore.add(
                            Component.text("  - ${ench.key.key.replace("_", " ")} $level", NamedTextColor.DARK_GRAY)
                                .decoration(TextDecoration.ITALIC, false)
                        )
                    }
                }
                lore.add(Component.empty())
                lore.add(Component.text("  Left-click: edit weight", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false))
                lore.add(Component.text("  Right-click: remove", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false))
                lore.add(Component.empty())
                meta.lore(lore)

                if (reward.enchantments.isNotEmpty()) {
                    for ((ench, level) in reward.enchantments) {
                        meta.addEnchant(ench, level, true)
                    }
                }
            }

            val rewardIndex = idx
            gui.setItem(idx, item) { p, event ->
                if (event.isRightClick) {
                    // Remove reward
                    plugin.crateManager.removeReward(crateId, rewardIndex)
                    p.playSound(p.location, Sound.BLOCK_ANVIL_BREAK, 0.5f, 1.0f)
                    plugin.commsManager.send(p, Component.text("Reward removed.", NamedTextColor.RED))
                    openEditMenu(p, crateId)
                } else {
                    // Edit weight
                    pendingActions[p.uniqueId] = PendingCrateAction.EditWeight(crateId, rewardIndex)
                    p.closeInventory()
                    plugin.commsManager.send(p, Component.text("Type the new weight (1-1000) in chat (or 'cancel'):", NamedTextColor.YELLOW))
                }
            }
        }

        // --- Bottom row buttons (slots 45-53) ---

        // Add Reward (slot 45)
        val addRewardBtn = ItemStack(Material.EMERALD)
        addRewardBtn.editMeta { meta ->
            meta.displayName(
                Component.text("Add Reward", NamedTextColor.GREEN)
                    .decoration(TextDecoration.ITALIC, false)
                    .decoration(TextDecoration.BOLD, true)
            )
            meta.lore(listOf(
                Component.empty(),
                Component.text("  Hold the reward item, then click", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false),
                Component.text("  Type the weight in chat after", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false),
                Component.empty()
            ))
        }
        gui.setItem(45, addRewardBtn) { p, _ ->
            val held = p.inventory.itemInMainHand
            if (held.type == Material.AIR) {
                plugin.commsManager.send(p, Component.text("Hold the item you want as a reward in your main hand!", NamedTextColor.RED))
                p.playSound(p.location, Sound.ENTITY_VILLAGER_NO, 0.7f, 1.0f)
                return@setItem
            }
            // Store a clean copy of the item
            val rewardItem = held.clone().apply { amount = held.amount }
            pendingActions[p.uniqueId] = PendingCrateAction.AddReward(crateId, rewardItem)
            p.closeInventory()
            plugin.commsManager.send(p, Component.text("Type the weight for this reward (1-1000) in chat (or 'cancel'):", NamedTextColor.YELLOW))
        }

        // Idle Particle (slot 46)
        val idleParticleBtn = ItemStack(Material.BLAZE_POWDER)
        idleParticleBtn.editMeta { meta ->
            meta.displayName(
                Component.text("Idle Particle: ${crate.idleParticle.name}", NamedTextColor.YELLOW)
                    .decoration(TextDecoration.ITALIC, false)
                    .decoration(TextDecoration.BOLD, true)
            )
            meta.lore(listOf(
                Component.empty(),
                Component.text("  Particle shown above crate", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false),
                Component.text("  Click to cycle", NamedTextColor.YELLOW)
                    .decoration(TextDecoration.ITALIC, false),
                Component.empty()
            ))
        }
        val idleParticles = listOf(
            org.bukkit.Particle.END_ROD, org.bukkit.Particle.FLAME, org.bukkit.Particle.SOUL_FIRE_FLAME,
            org.bukkit.Particle.CHERRY_LEAVES, org.bukkit.Particle.HEART, org.bukkit.Particle.HAPPY_VILLAGER,
            org.bukkit.Particle.ENCHANT, org.bukkit.Particle.WITCH
        )
        gui.setItem(46, idleParticleBtn) { p, _ ->
            val curIdx = idleParticles.indexOf(crate.idleParticle).coerceAtLeast(0)
            val next = idleParticles[(curIdx + 1) % idleParticles.size]
            plugin.crateManager.setCrateIdleParticle(crateId, next)
            p.playSound(p.location, Sound.BLOCK_ANVIL_USE, 0.5f, 1.0f)
            plugin.commsManager.send(p, Component.text("Idle particle set to ${next.name}.", NamedTextColor.GREEN))
            openEditMenu(p, crateId)
        }

        // Set Name (slot 47)
        val nameBtn = ItemStack(Material.PAPER)
        nameBtn.editMeta { meta ->
            meta.displayName(
                Component.text("Set Name", NamedTextColor.AQUA)
                    .decoration(TextDecoration.ITALIC, false)
                    .decoration(TextDecoration.BOLD, true)
            )
            meta.lore(listOf(
                Component.empty(),
                Component.text("  Current: ", NamedTextColor.GRAY)
                    .append(Component.text(crate.displayName, NamedTextColor.WHITE))
                    .decoration(TextDecoration.ITALIC, false),
                Component.text("  Click to rename", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false),
                Component.empty()
            ))
        }
        gui.setItem(47, nameBtn) { p, _ ->
            pendingActions[p.uniqueId] = PendingCrateAction.RenameCrate(crateId)
            p.closeInventory()
            plugin.commsManager.send(p, Component.text("Type the new crate name in chat (or 'cancel'):", NamedTextColor.YELLOW))
        }

        // Set Key Item (slot 49)
        val keyBtn = ItemStack(Material.EXPERIENCE_BOTTLE)
        keyBtn.editMeta { meta ->
            meta.displayName(
                Component.text("Set Key Item", TextColor.color(0xFFAA00))
                    .decoration(TextDecoration.ITALIC, false)
                    .decoration(TextDecoration.BOLD, true)
            )
            meta.lore(listOf(
                Component.empty(),
                Component.text("  Current: ", NamedTextColor.GRAY)
                    .append(Component.text(crate.keyMaterial.name, NamedTextColor.WHITE))
                    .decoration(TextDecoration.ITALIC, false),
                Component.text("  Hold item + click to set", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false),
                Component.empty()
            ))
        }
        gui.setItem(49, keyBtn) { p, _ ->
            val held = p.inventory.itemInMainHand
            if (held.type == Material.AIR) {
                plugin.commsManager.send(p, Component.text("Hold the item you want as the key material!", NamedTextColor.RED))
                p.playSound(p.location, Sound.ENTITY_VILLAGER_NO, 0.7f, 1.0f)
                return@setItem
            }
            plugin.crateManager.setCrateKeyMaterial(crateId, held.type, "${crate.displayName} Key")
            p.playSound(p.location, Sound.BLOCK_ANVIL_USE, 0.5f, 1.0f)
            plugin.commsManager.send(p, Component.text("Key material set to ${held.type.name}.", NamedTextColor.GREEN))
            openEditMenu(p, crateId)
        }

        // Set Animation Glass (slot 48) — hold a stained glass pane + click
        val glassBtn = ItemStack(crate.animationGlass)
        glassBtn.editMeta { meta ->
            meta.displayName(
                Component.text("Set Animation Glass", NamedTextColor.AQUA)
                    .decoration(TextDecoration.ITALIC, false)
                    .decoration(TextDecoration.BOLD, true)
            )
            meta.lore(listOf(
                Component.empty(),
                Component.text("  Current: ", NamedTextColor.GRAY)
                    .append(Component.text(crate.animationGlass.name, NamedTextColor.WHITE))
                    .decoration(TextDecoration.ITALIC, false),
                Component.text("  Hold a stained glass pane + click", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false),
                Component.empty()
            ))
        }
        gui.setItem(48, glassBtn) { p, _ ->
            val held = p.inventory.itemInMainHand
            if (held.type == Material.AIR || !held.type.name.contains("STAINED_GLASS_PANE")) {
                plugin.commsManager.send(p, Component.text("Hold a stained glass pane!", NamedTextColor.RED))
                p.playSound(p.location, Sound.ENTITY_VILLAGER_NO, 0.7f, 1.0f)
                return@setItem
            }
            plugin.crateManager.setCrateAnimationGlass(crateId, held.type)
            p.playSound(p.location, Sound.BLOCK_ANVIL_USE, 0.5f, 1.0f)
            plugin.commsManager.send(p, Component.text("Animation glass set to ${held.type.name}.", NamedTextColor.GREEN))
            openEditMenu(p, crateId)
        }

        // Mode Toggle (slot 51)
        val modeBtn = ItemStack(if (crate.mode == CrateManager.CrateMode.RANDOM) Material.HOPPER else Material.CHEST)
        modeBtn.editMeta { meta ->
            val modeLabel = if (crate.mode == CrateManager.CrateMode.RANDOM) "Random" else "Select"
            meta.displayName(
                Component.text("Mode: $modeLabel", NamedTextColor.LIGHT_PURPLE)
                    .decoration(TextDecoration.ITALIC, false)
                    .decoration(TextDecoration.BOLD, true)
            )
            meta.lore(listOf(
                Component.empty(),
                Component.text("  Random: auto-picks a weighted reward", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false),
                Component.text("  Select: player chooses one reward", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false),
                Component.empty(),
                Component.text("  Click to toggle", NamedTextColor.YELLOW)
                    .decoration(TextDecoration.ITALIC, false),
                Component.empty()
            ))
        }
        gui.setItem(51, modeBtn) { p, _ ->
            val newMode = if (crate.mode == CrateManager.CrateMode.RANDOM) CrateManager.CrateMode.SELECT else CrateManager.CrateMode.RANDOM
            plugin.crateManager.setCrateMode(crateId, newMode)
            p.playSound(p.location, Sound.BLOCK_ANVIL_USE, 0.5f, 1.0f)
            plugin.commsManager.send(p, Component.text("Mode set to ${newMode.name.lowercase()}.", NamedTextColor.GREEN))
            openEditMenu(p, crateId)
        }

        // Animation Type (slot 50)
        val animBtn = ItemStack(Material.CLOCK)
        animBtn.editMeta { meta ->
            meta.displayName(
                Component.text("Animation: ${crate.animationType.name.lowercase()}", NamedTextColor.GOLD)
                    .decoration(TextDecoration.ITALIC, false)
                    .decoration(TextDecoration.BOLD, true)
            )
            meta.lore(listOf(
                Component.empty(),
                Component.text("  spin / pulse / instant", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false),
                Component.text("  Click to cycle", NamedTextColor.YELLOW)
                    .decoration(TextDecoration.ITALIC, false),
                Component.empty()
            ))
        }
        gui.setItem(50, animBtn) { p, _ ->
            val types = CrateManager.AnimationType.entries
            val nextIdx = (types.indexOf(crate.animationType) + 1) % types.size
            val next = types[nextIdx]
            plugin.crateManager.setCrateAnimationType(crateId, next)
            p.playSound(p.location, Sound.BLOCK_ANVIL_USE, 0.5f, 1.0f)
            plugin.commsManager.send(p, Component.text("Animation set to ${next.name.lowercase()}.", NamedTextColor.GREEN))
            openEditMenu(p, crateId)
        }

        // Win Particle (slot 52)
        val winParticleBtn = ItemStack(Material.FIREWORK_ROCKET)
        winParticleBtn.editMeta { meta ->
            meta.displayName(
                Component.text("Win Particle: ${crate.winParticle.name}", TextColor.color(0xFF5555))
                    .decoration(TextDecoration.ITALIC, false)
                    .decoration(TextDecoration.BOLD, true)
            )
            meta.lore(listOf(
                Component.empty(),
                Component.text("  Particle burst on reward", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false),
                Component.text("  Click to cycle", NamedTextColor.YELLOW)
                    .decoration(TextDecoration.ITALIC, false),
                Component.empty()
            ))
        }
        val winParticles = listOf(
            org.bukkit.Particle.FIREWORK, org.bukkit.Particle.TOTEM_OF_UNDYING,
            org.bukkit.Particle.EXPLOSION, org.bukkit.Particle.HEART,
            org.bukkit.Particle.HAPPY_VILLAGER, org.bukkit.Particle.FLAME
        )
        gui.setItem(52, winParticleBtn) { p, _ ->
            val curIdx = winParticles.indexOf(crate.winParticle).coerceAtLeast(0)
            val next = winParticles[(curIdx + 1) % winParticles.size]
            plugin.crateManager.setCrateWinParticle(crateId, next)
            p.playSound(p.location, Sound.BLOCK_ANVIL_USE, 0.5f, 1.0f)
            plugin.commsManager.send(p, Component.text("Win particle set to ${next.name}.", NamedTextColor.GREEN))
            openEditMenu(p, crateId)
        }

        // Back / Delete (slot 53)
        val backBtn = ItemStack(Material.ARROW)
        backBtn.editMeta { meta ->
            meta.displayName(
                Component.text("Back", NamedTextColor.WHITE)
                    .decoration(TextDecoration.ITALIC, false)
                    .decoration(TextDecoration.BOLD, true)
            )
            meta.lore(listOf(
                Component.empty(),
                Component.text("  Click: back to menu", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false),
                Component.text("  Shift-click: delete crate", NamedTextColor.RED)
                    .decoration(TextDecoration.ITALIC, false),
                Component.empty()
            ))
        }
        gui.setItem(53, backBtn) { p, event ->
            if (event.isShiftClick) {
                plugin.crateManager.deleteCrate(crateId)
                p.playSound(p.location, Sound.ENTITY_GENERIC_EXPLODE, 0.5f, 1.0f)
                plugin.commsManager.send(p, Component.text("Crate '${crate.displayName}' deleted.", NamedTextColor.RED))
            } else {
                p.playSound(p.location, Sound.UI_BUTTON_CLICK, 0.5f, 1.0f)
            }
            openMainMenu(p)
        }

        plugin.guiManager.open(player, gui)
    }

    // --- Chat input handler ---

    @EventHandler(priority = EventPriority.LOWEST)
    fun onChat(event: AsyncChatEvent) {
        val player = event.player
        val pending = pendingActions.remove(player.uniqueId) ?: return

        event.isCancelled = true
        val message = PlainTextComponentSerializer.plainText().serialize(event.message()).trim()

        if (message.equals("cancel", ignoreCase = true)) {
            Bukkit.getScheduler().runTask(plugin, Runnable {
                plugin.commsManager.send(player, Component.text("Cancelled.", NamedTextColor.GRAY))
            })
            return
        }

        when (pending) {
            is PendingCrateAction.CreateCrate -> {
                val name = message
                val id = name.lowercase().replace(Regex("[^a-z0-9_]"), "_")
                Bukkit.getScheduler().runTask(plugin, Runnable {
                    if (plugin.crateManager.createCrate(id, name)) {
                        plugin.commsManager.send(player, Component.text("Crate '$name' created (id: $id).", NamedTextColor.GREEN))
                        openEditMenu(player, id)
                    } else {
                        plugin.commsManager.send(player, Component.text("A crate with that ID already exists.", NamedTextColor.RED))
                    }
                })
            }

            is PendingCrateAction.RenameCrate -> {
                Bukkit.getScheduler().runTask(plugin, Runnable {
                    if (plugin.crateManager.setCrateDisplayName(pending.crateId, message)) {
                        plugin.commsManager.send(player, Component.text("Crate renamed to '$message'.", NamedTextColor.GREEN))
                        openEditMenu(player, pending.crateId)
                    } else {
                        plugin.commsManager.send(player, Component.text("Failed to rename crate.", NamedTextColor.RED))
                    }
                })
            }

            is PendingCrateAction.AddReward -> {
                val weight = message.toIntOrNull()
                if (weight == null || weight < 1 || weight > 1000) {
                    Bukkit.getScheduler().runTask(plugin, Runnable {
                        plugin.commsManager.send(player, Component.text("Invalid weight. Must be 1-1000.", NamedTextColor.RED))
                    })
                    return
                }
                val item = pending.item
                // Use the item's display name if it has one (for custom items),
                // otherwise prettify the material name.
                val displayName = item.itemMeta?.takeIf { it.hasDisplayName() }?.let { meta ->
                    net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                        .serialize(meta.displayName()!!)
                } ?: item.type.name.lowercase().replace("_", " ").replaceFirstChar { it.uppercase() }

                val enchantments = mutableMapOf<org.bukkit.enchantments.Enchantment, Int>()
                item.enchantments.forEach { (ench, level) -> enchantments[ench] = level }

                // Serialize the full ItemStack to preserve trims, custom items, PDC, etc.
                val itemBase64 = plugin.crateManager.serializeItem(item)

                val reward = CrateManager.CrateReward(
                    material = item.type,
                    amount = item.amount,
                    weight = weight,
                    displayName = displayName,
                    enchantments = enchantments,
                    itemBase64 = itemBase64
                )

                Bukkit.getScheduler().runTask(plugin, Runnable {
                    if (plugin.crateManager.addReward(pending.crateId, reward)) {
                        plugin.commsManager.send(player, Component.text("Reward added: $displayName x${item.amount} (weight: $weight).", NamedTextColor.GREEN))
                        openEditMenu(player, pending.crateId)
                    } else {
                        plugin.commsManager.send(player, Component.text("Failed to add reward.", NamedTextColor.RED))
                    }
                })
            }

            is PendingCrateAction.EditWeight -> {
                val weight = message.toIntOrNull()
                if (weight == null || weight < 1 || weight > 1000) {
                    Bukkit.getScheduler().runTask(plugin, Runnable {
                        plugin.commsManager.send(player, Component.text("Invalid weight. Must be 1-1000.", NamedTextColor.RED))
                    })
                    return
                }

                Bukkit.getScheduler().runTask(plugin, Runnable {
                    if (plugin.crateManager.updateRewardWeight(pending.crateId, pending.rewardIndex, weight)) {
                        plugin.commsManager.send(player, Component.text("Weight updated to $weight.", NamedTextColor.GREEN))
                        openEditMenu(player, pending.crateId)
                    } else {
                        plugin.commsManager.send(player, Component.text("Failed to update weight.", NamedTextColor.RED))
                    }
                })
            }
        }
    }
}
