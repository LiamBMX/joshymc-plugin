package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.crate.CrateEditorAudit
import com.liam.joshymc.crate.CratePermissions
import com.liam.joshymc.gui.CustomGui
import com.liam.joshymc.manager.CrateManager
import com.liam.joshymc.staff.AdminAudit
import com.liam.joshymc.util.Msg
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
        /** [expected] is the reward the admin clicked; a different reward at [rewardIndex] is refused. */
        class EditWeight(val crateId: String, val rewardIndex: Int, val expected: CrateManager.CrateReward) : PendingCrateAction()
    }

    val pendingActions = ConcurrentHashMap<UUID, PendingCrateAction>()

    // --- Authorization + audit (issue #1541) ---

    /**
     * Re-checks `joshymc.crateeditor` on every click and chat step, not only on `/crateeditor`,
     * so an admin whose node was removed while a menu or chat prompt was open can't keep editing.
     * A refused change is audited as REJECTED when [op] names one.
     */
    private fun authorized(p: Player, op: CrateEditorAudit.Operation? = null, crateId: String? = null): Boolean {
        if (p.hasPermission(CratePermissions.EDITOR)) return true
        pendingActions.remove(p.uniqueId)
        p.closeInventory()
        Msg.noPermission(p)
        if (op != null && crateId != null) audit(p, op, crateId, CrateEditorAudit.Result.REJECTED, CrateEditorAudit.Reason.NO_PERMISSION)
        return false
    }

    private fun audit(
        p: Player,
        op: CrateEditorAudit.Operation,
        crateId: String,
        result: CrateEditorAudit.Result,
        reason: CrateEditorAudit.Reason? = null,
        reward: CrateEditorAudit.RewardRef? = null,
        previous: String? = null,
        new: String? = null,
        extra: Map<String, String> = emptyMap(),
    ) = AdminAudit.log(plugin, p, op.action, details = CrateEditorAudit.details(crateId, result, reason, reward, previous, new, extra))

    /** Runs one change and writes exactly one SUCCESS or FAILED row for it. */
    private fun applyAudited(
        p: Player,
        op: CrateEditorAudit.Operation,
        crateId: String,
        reward: CrateEditorAudit.RewardRef? = null,
        previous: String? = null,
        new: String? = null,
        extra: Map<String, String> = emptyMap(),
        change: () -> Boolean,
    ): Boolean {
        val ok = try {
            change()
        } catch (e: Exception) {
            plugin.logger.warning("[Crates] Crate editor ${op.action} on '$crateId' by ${p.name} failed: ${e.message}")
            false
        }
        audit(p, op, crateId, if (ok) CrateEditorAudit.Result.SUCCESS else CrateEditorAudit.Result.FAILED, null, reward, previous, new, extra)
        return ok
    }

    /** What the audit row says about [item] (a reward template or the held item). */
    private fun itemRef(index: Int?, item: ItemStack?, material: Material, amount: Int, weight: Int?, name: String, snapshot: String?) =
        CrateEditorAudit.RewardRef(
            index = index,
            material = material.name,
            amount = amount,
            weight = weight,
            name = name,
            customItem = item?.let { plugin.itemManager.getCustomItemId(it) },
            voucher = item != null && isVoucher(item),
            fingerprint = CrateEditorAudit.fingerprint(snapshot),
        )

    private fun rewardRef(index: Int?, reward: CrateManager.CrateReward) =
        itemRef(index, plugin.crateManager.deserializeItem(reward.itemBase64), reward.material, reward.amount, reward.weight,
            reward.displayName, reward.itemBase64)

    private fun isVoucher(item: ItemStack): Boolean =
        plugin.creditVoucherManager.isCreditVoucher(item) || plugin.rankVoucherManager.isRankVoucher(item) ||
            plugin.flyVoucherManager.isFlyVoucher(item) || plugin.chatTagVoucherManager.isChatTagVoucher(item)

    /**
     * The reward at [index] in [crateId] only when it is still exactly [expected] (what the admin
     * clicked); otherwise the refusal reason. Another edit (or a reload) can shift the list, and
     * acting on the index alone would remove or reweight a different reward.
     */
    private fun staleReason(crateId: String, index: Int, expected: CrateManager.CrateReward): CrateEditorAudit.Reason? {
        val crate = plugin.crateManager.getCrate(crateId) ?: return CrateEditorAudit.Reason.CRATE_MISSING
        return if (crate.rewards.getOrNull(index) == expected) null else CrateEditorAudit.Reason.STALE
    }

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
        if (sender !is Player) { Msg.playersOnly(sender); return true }
        if (!sender.hasPermission("joshymc.crateeditor")) {
            Msg.noPermission(sender)
            return true
        }

        openMainMenu(sender)
        return true
    }

    // --- Main Menu ---

    fun openMainMenu(player: Player) {
        if (!authorized(player)) return
        val crates = plugin.crateManager.getAllCrates()
        // 6-row inventory: top + bottom border, 4 inner rows of 7 = 28 crate slots.
        val size = 54

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

        // Crate slots: rows 1..4 (10..16, 19..25, 28..34, 37..43) = 28 slots.
        val crateSlots = listOf(
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43,
        )

        var slotIdx = 0
        for ((id, crate) in crates) {
            if (slotIdx >= crateSlots.size) break
            val slot = crateSlots[slotIdx]
            slotIdx++

            val icon = ItemStack(crate.keyMaterial)
            icon.editMeta { meta ->
                if (crate.keyItemModel != null) {
                    meta.setItemModel(crate.keyItemModel)
                }
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
        }

        // Create New Crate button (centered in the bottom border row)
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
        gui.setItem(49, createBtn) { p, _ ->
            if (!authorized(p)) return@setItem
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
        if (!authorized(player)) return
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

            // The same item the crate hands out, custom enchant lore included (issue #1300).
            val item = plugin.crateManager.rewardTemplate(reward)
                ?: ItemStack(reward.material, reward.amount.coerceIn(1, 64))
            item.amount = reward.amount.coerceIn(1, 64)
            val existingLore = item.itemMeta?.lore() ?: emptyList()
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
                if (existingLore.isNotEmpty()) {
                    lore.add(Component.empty())
                    lore.addAll(existingLore)
                } else if (reward.enchantments.isNotEmpty()) {
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
            val shown = reward
            gui.setItem(idx, item) { p, event ->
                val op = if (event.isRightClick) CrateEditorAudit.Operation.REWARD_REMOVE else CrateEditorAudit.Operation.REWARD_WEIGHT
                if (!authorized(p, op, crateId)) return@setItem
                staleReason(crateId, rewardIndex, shown)?.let { reason ->
                    audit(p, op, crateId, CrateEditorAudit.Result.REJECTED, reason, rewardRef(rewardIndex, shown))
                    plugin.commsManager.send(p, Component.text("This crate changed since the menu opened; nothing was changed.", NamedTextColor.RED))
                    openEditMenu(p, crateId)
                    return@setItem
                }
                if (event.isRightClick) {
                    // Remove reward
                    val removed = applyAudited(p, op, crateId, rewardRef(rewardIndex, shown)) {
                        plugin.crateManager.removeReward(crateId, rewardIndex)
                    }
                    if (removed) {
                        p.playSound(p.location, Sound.BLOCK_ANVIL_BREAK, 0.5f, 1.0f)
                        plugin.commsManager.send(p, Component.text("Reward removed.", NamedTextColor.RED))
                    } else {
                        plugin.commsManager.send(p, Component.text("Failed to remove reward.", NamedTextColor.RED))
                    }
                    openEditMenu(p, crateId)
                } else {
                    // Edit weight
                    pendingActions[p.uniqueId] = PendingCrateAction.EditWeight(crateId, rewardIndex, shown)
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
            if (!authorized(p, CrateEditorAudit.Operation.REWARD_ADD, crateId)) return@setItem
            val held = p.inventory.itemInMainHand
            if (held.type == Material.AIR) {
                plugin.commsManager.send(p, Component.text("Hold the item you want as a reward in your main hand!", NamedTextColor.RED))
                p.playSound(p.location, Sound.ENTITY_VILLAGER_NO, 0.7f, 1.0f)
                return@setItem
            }
            // Custom enchants travel in the snapshot's PDC; refuse ones the crate couldn't hand
            // out (unregistered, past max level, wrong item, conflicting) (issue #1300).
            val enchantProblems = plugin.customEnchantManager.enchantProblems(held)
            if (enchantProblems.isNotEmpty()) {
                audit(p, CrateEditorAudit.Operation.REWARD_ADD, crateId, CrateEditorAudit.Result.REJECTED, CrateEditorAudit.Reason.ENCHANT_PROBLEM,
                    itemRef(null, held, held.type, held.amount, null, held.type.name.lowercase(), null))
                plugin.commsManager.send(p, Component.text("Can't add that item: ${enchantProblems.joinToString("; ")}.", NamedTextColor.RED))
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
            org.bukkit.Particle.ENCHANT, org.bukkit.Particle.WITCH, org.bukkit.Particle.DUST
        )
        gui.setItem(46, idleParticleBtn) { p, _ ->
            if (!authorized(p, CrateEditorAudit.Operation.IDLE_PARTICLE, crateId)) return@setItem
            val curIdx = idleParticles.indexOf(crate.idleParticle).coerceAtLeast(0)
            val next = idleParticles[(curIdx + 1) % idleParticles.size]
            applyAudited(p, CrateEditorAudit.Operation.IDLE_PARTICLE, crateId, previous = crate.idleParticle.name, new = next.name) {
                plugin.crateManager.setCrateIdleParticle(crateId, next)
            }
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
            if (!authorized(p, CrateEditorAudit.Operation.RENAME, crateId)) return@setItem
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
            if (!authorized(p, CrateEditorAudit.Operation.KEY_ITEM, crateId)) return@setItem
            val held = p.inventory.itemInMainHand
            if (held.type == Material.AIR) {
                plugin.commsManager.send(p, Component.text("Hold the item you want as the key material!", NamedTextColor.RED))
                p.playSound(p.location, Sound.ENTITY_VILLAGER_NO, 0.7f, 1.0f)
                return@setItem
            }
            val heldItemModel = held.itemMeta?.itemModel
            applyAudited(p, CrateEditorAudit.Operation.KEY_ITEM, crateId,
                previous = "${crate.keyMaterial.name}/${crate.keyItemModel?.asString() ?: "none"}",
                new = "${held.type.name}/${heldItemModel?.asString() ?: "none"}") {
                plugin.crateManager.setCrateKeyMaterial(crateId, held.type, "${crate.displayName} Key", heldItemModel)
            }
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
            if (!authorized(p, CrateEditorAudit.Operation.ANIMATION_GLASS, crateId)) return@setItem
            val held = p.inventory.itemInMainHand
            if (held.type == Material.AIR || !held.type.name.contains("STAINED_GLASS_PANE")) {
                plugin.commsManager.send(p, Component.text("Hold a stained glass pane!", NamedTextColor.RED))
                p.playSound(p.location, Sound.ENTITY_VILLAGER_NO, 0.7f, 1.0f)
                return@setItem
            }
            applyAudited(p, CrateEditorAudit.Operation.ANIMATION_GLASS, crateId, previous = crate.animationGlass.name, new = held.type.name) {
                plugin.crateManager.setCrateAnimationGlass(crateId, held.type)
            }
            p.playSound(p.location, Sound.BLOCK_ANVIL_USE, 0.5f, 1.0f)
            plugin.commsManager.send(p, Component.text("Animation glass set to ${held.type.name}.", NamedTextColor.GREEN))
            openEditMenu(p, crateId)
        }

        // Mode Toggle (slot 51)
        val modeBtn = ItemStack(when (crate.mode) {
            CrateManager.CrateMode.RANDOM -> Material.HOPPER
            CrateManager.CrateMode.SELECT -> Material.CHEST
            CrateManager.CrateMode.SELECT_3 -> Material.ENDER_CHEST
        })
        modeBtn.editMeta { meta ->
            val modeLabel = when (crate.mode) {
                CrateManager.CrateMode.RANDOM -> "Random"
                CrateManager.CrateMode.SELECT -> "Select"
                CrateManager.CrateMode.SELECT_3 -> "Select 3"
            }
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
                Component.text("  Select 3: player chooses three rewards", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false),
                Component.empty(),
                Component.text("  Click to cycle", NamedTextColor.YELLOW)
                    .decoration(TextDecoration.ITALIC, false),
                Component.empty()
            ))
        }
        gui.setItem(51, modeBtn) { p, _ ->
            val op = CrateEditorAudit.Operation.MODE
            if (!authorized(p, op, crateId)) return@setItem
            val newMode = when (crate.mode) {
                CrateManager.CrateMode.RANDOM -> CrateManager.CrateMode.SELECT
                CrateManager.CrateMode.SELECT -> CrateManager.CrateMode.SELECT_3
                CrateManager.CrateMode.SELECT_3 -> CrateManager.CrateMode.RANDOM
            }
            val current = plugin.crateManager.getCrate(crateId)
            if (current == null || (newMode == CrateManager.CrateMode.SELECT_3 && current.rewards.size < 3)) {
                audit(p, op, crateId, CrateEditorAudit.Result.REJECTED,
                    if (current == null) CrateEditorAudit.Reason.CRATE_MISSING else CrateEditorAudit.Reason.NEEDS_THREE_REWARDS,
                    previous = crate.mode.name, new = newMode.name)
                p.playSound(p.location, Sound.ENTITY_VILLAGER_NO, 0.7f, 1.0f)
                plugin.commsManager.send(p, Component.text(if (current == null) "Crate not found." else "Select 3 requires at least 3 rewards.", NamedTextColor.RED))
                return@setItem
            }
            if (!applyAudited(p, op, crateId, previous = current.mode.name, new = newMode.name) { plugin.crateManager.setCrateMode(crateId, newMode) }) {
                p.playSound(p.location, Sound.ENTITY_VILLAGER_NO, 0.7f, 1.0f)
                plugin.commsManager.send(p, Component.text("Failed to change the mode.", NamedTextColor.RED))
                return@setItem
            }
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
            if (!authorized(p, CrateEditorAudit.Operation.ANIMATION, crateId)) return@setItem
            val types = CrateManager.AnimationType.entries
            val nextIdx = (types.indexOf(crate.animationType) + 1) % types.size
            val next = types[nextIdx]
            applyAudited(p, CrateEditorAudit.Operation.ANIMATION, crateId, previous = crate.animationType.name, new = next.name) {
                plugin.crateManager.setCrateAnimationType(crateId, next)
            }
            p.playSound(p.location, Sound.BLOCK_ANVIL_USE, 0.5f, 1.0f)
            plugin.commsManager.send(p, Component.text("Animation set to ${next.name.lowercase()}.", NamedTextColor.GREEN))
            openEditMenu(p, crateId)
        }

        // Win Particle (slot 52)
        val colorEntry = com.liam.joshymc.util.MinecraftColors.ALL.firstOrNull { it.color == crate.particleColor }
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
                Component.text("  Color: ", NamedTextColor.GRAY)
                    .append(Component.text(colorEntry?.label ?: "Default", colorEntry?.textColor ?: NamedTextColor.WHITE))
                    .decoration(TextDecoration.ITALIC, false),
                Component.empty(),
                Component.text("  Left-click: cycle particle", NamedTextColor.YELLOW)
                    .decoration(TextDecoration.ITALIC, false),
                Component.text("  Right-click: pick color", NamedTextColor.YELLOW)
                    .decoration(TextDecoration.ITALIC, false),
                Component.empty()
            ))
        }
        val winParticles = listOf(
            org.bukkit.Particle.FIREWORK, org.bukkit.Particle.TOTEM_OF_UNDYING,
            org.bukkit.Particle.EXPLOSION, org.bukkit.Particle.HEART,
            org.bukkit.Particle.HAPPY_VILLAGER, org.bukkit.Particle.FLAME,
            org.bukkit.Particle.DUST
        )
        gui.setItem(52, winParticleBtn) { p, event ->
            if (event.isRightClick) {
                openParticleColorGui(p, crateId)
                return@setItem
            }
            if (!authorized(p, CrateEditorAudit.Operation.WIN_PARTICLE, crateId)) return@setItem
            val curIdx = winParticles.indexOf(crate.winParticle).coerceAtLeast(0)
            val next = winParticles[(curIdx + 1) % winParticles.size]
            applyAudited(p, CrateEditorAudit.Operation.WIN_PARTICLE, crateId, previous = crate.winParticle.name, new = next.name) {
                plugin.crateManager.setCrateWinParticle(crateId, next)
            }
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
                val op = CrateEditorAudit.Operation.DELETE
                if (!authorized(p, op, crateId)) return@setItem
                val current = plugin.crateManager.getCrate(crateId)
                if (current == null) {
                    audit(p, op, crateId, CrateEditorAudit.Result.REJECTED, CrateEditorAudit.Reason.CRATE_MISSING)
                    plugin.commsManager.send(p, Component.text("Crate not found.", NamedTextColor.RED))
                } else {
                    val locations = plugin.crateManager.getAllLocations().count { it.crateType == crateId }
                    val deleted = applyAudited(p, op, crateId, previous = current.displayName,
                        extra = mapOf("rewards" to current.rewards.size.toString(), "locations" to locations.toString())) {
                        plugin.crateManager.deleteCrate(crateId)
                    }
                    if (deleted) {
                        p.playSound(p.location, Sound.ENTITY_GENERIC_EXPLODE, 0.5f, 1.0f)
                        plugin.commsManager.send(p, Component.text("Crate '${crate.displayName}' deleted.", NamedTextColor.RED))
                    } else {
                        plugin.commsManager.send(p, Component.text("Failed to delete crate.", NamedTextColor.RED))
                    }
                }
            } else {
                p.playSound(p.location, Sound.UI_BUTTON_CLICK, 0.5f, 1.0f)
            }
            openMainMenu(p)
        }

        plugin.guiManager.open(player, gui)
    }

    // --- Particle Color Picker ---

    private fun openParticleColorGui(player: Player, crateId: String) {
        if (!authorized(player)) return
        val crate = plugin.crateManager.getCrate(crateId) ?: run {
            plugin.commsManager.send(player, Component.text("Crate not found.", NamedTextColor.RED))
            return
        }

        val gui = CustomGui(
            Component.text("Particle Color: ", NamedTextColor.WHITE)
                .append(Component.text(crate.displayName, TextColor.color(0x55FFFF)))
                .decoration(TextDecoration.ITALIC, false),
            27
        )

        val filler = ItemStack(Material.GRAY_STAINED_GLASS_PANE)
        filler.editMeta { it.displayName(Component.empty()) }
        gui.fill(filler)

        for ((idx, entry) in com.liam.joshymc.util.MinecraftColors.ALL.withIndex()) {
            val isCurrent = crate.particleColor == entry.color
            val item = ItemStack(entry.icon)
            item.editMeta { meta ->
                meta.displayName(
                    Component.text(entry.label, entry.textColor)
                        .decoration(TextDecoration.ITALIC, false)
                        .decoration(TextDecoration.BOLD, true)
                )
                val lore = mutableListOf(Component.empty())
                if (isCurrent) {
                    lore.add(Component.text("  Currently selected", NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false))
                } else {
                    lore.add(Component.text("  Click to select", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false))
                }
                lore.add(Component.empty())
                meta.lore(lore)
            }
            gui.setItem(idx, item) { p, _ ->
                if (!authorized(p, CrateEditorAudit.Operation.PARTICLE_COLOR, crateId)) return@setItem
                val previousColor = com.liam.joshymc.util.MinecraftColors.ALL.firstOrNull { it.color == plugin.crateManager.getCrate(crateId)?.particleColor }?.id
                applyAudited(p, CrateEditorAudit.Operation.PARTICLE_COLOR, crateId, previous = previousColor ?: "default", new = entry.id) {
                    plugin.crateManager.setCrateParticleColor(crateId, entry.id)
                }
                p.playSound(p.location, Sound.BLOCK_ANVIL_USE, 0.5f, 1.0f)
                plugin.commsManager.send(p, Component.text("Particle color set to ", NamedTextColor.GREEN).append(Component.text(entry.label, entry.textColor)).append(Component.text(".", NamedTextColor.GREEN)))
                openEditMenu(p, crateId)
            }
        }

        val backBtn = ItemStack(Material.ARROW)
        backBtn.editMeta { meta ->
            meta.displayName(
                Component.text("Back", NamedTextColor.WHITE)
                    .decoration(TextDecoration.ITALIC, false)
                    .decoration(TextDecoration.BOLD, true)
            )
            meta.lore(listOf(
                Component.empty(),
                Component.text("  Click: back without changes", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false),
                Component.empty()
            ))
        }
        gui.setItem(26, backBtn) { p, _ ->
            p.playSound(p.location, Sound.UI_BUTTON_CLICK, 0.5f, 1.0f)
            openEditMenu(p, crateId)
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

        // Everything below runs on the main thread, re-checks the editor node first and writes
        // one audit row per attempted change (issue #1541).
        Bukkit.getScheduler().runTask(plugin, Runnable { handlePending(player, pending, message) })
    }

    private fun handlePending(player: Player, pending: PendingCrateAction, message: String) {
        when (pending) {
            is PendingCrateAction.CreateCrate -> {
                val name = message
                val id = name.lowercase().replace(Regex("[^a-z0-9_]"), "_")
                if (!authorized(player, CrateEditorAudit.Operation.CREATE, id)) return
                if (plugin.crateManager.getCrate(id) != null) {
                    audit(player, CrateEditorAudit.Operation.CREATE, id, CrateEditorAudit.Result.REJECTED, CrateEditorAudit.Reason.DUPLICATE_ID, new = name)
                    plugin.commsManager.send(player, Component.text("A crate with that ID already exists.", NamedTextColor.RED))
                    return
                }
                if (applyAudited(player, CrateEditorAudit.Operation.CREATE, id, new = name) { plugin.crateManager.createCrate(id, name) }) {
                    plugin.commsManager.send(player, Component.text("Crate '$name' created (id: $id).", NamedTextColor.GREEN))
                    openEditMenu(player, id)
                } else {
                    plugin.commsManager.send(player, Component.text("Failed to create the crate.", NamedTextColor.RED))
                }
            }

            is PendingCrateAction.RenameCrate -> {
                val crateId = pending.crateId
                if (!authorized(player, CrateEditorAudit.Operation.RENAME, crateId)) return
                val current = plugin.crateManager.getCrate(crateId)
                if (current == null) {
                    audit(player, CrateEditorAudit.Operation.RENAME, crateId, CrateEditorAudit.Result.REJECTED, CrateEditorAudit.Reason.CRATE_MISSING, new = message)
                    plugin.commsManager.send(player, Component.text("Failed to rename crate.", NamedTextColor.RED))
                    return
                }
                if (applyAudited(player, CrateEditorAudit.Operation.RENAME, crateId, previous = current.displayName, new = message) {
                        plugin.crateManager.setCrateDisplayName(crateId, message)
                    }) {
                    plugin.commsManager.send(player, Component.text("Crate renamed to '$message'.", NamedTextColor.GREEN))
                    openEditMenu(player, crateId)
                } else {
                    plugin.commsManager.send(player, Component.text("Failed to rename crate.", NamedTextColor.RED))
                }
            }

            is PendingCrateAction.AddReward -> {
                val crateId = pending.crateId
                val item = pending.item
                if (!authorized(player, CrateEditorAudit.Operation.REWARD_ADD, crateId)) return
                // Use the item's display name if it has one (for custom items),
                // otherwise prettify the material name.
                val displayName = item.itemMeta?.takeIf { it.hasDisplayName() }?.let { meta ->
                    net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                        .serialize(meta.displayName()!!)
                } ?: item.type.name.lowercase().replace("_", " ").replaceFirstChar { it.uppercase() }

                val weight = message.toIntOrNull()
                if (weight == null || weight < 1 || weight > 1000) {
                    audit(player, CrateEditorAudit.Operation.REWARD_ADD, crateId, CrateEditorAudit.Result.REJECTED, CrateEditorAudit.Reason.INVALID_WEIGHT,
                        itemRef(null, item, item.type, item.amount, null, displayName, null), new = message)
                    plugin.commsManager.send(player, Component.text("Invalid weight. Must be 1-1000.", NamedTextColor.RED))
                    return
                }
                val crate = plugin.crateManager.getCrate(crateId)
                if (crate == null) {
                    audit(player, CrateEditorAudit.Operation.REWARD_ADD, crateId, CrateEditorAudit.Result.REJECTED, CrateEditorAudit.Reason.CRATE_MISSING,
                        itemRef(null, item, item.type, item.amount, weight, displayName, null))
                    plugin.commsManager.send(player, Component.text("Failed to add reward.", NamedTextColor.RED))
                    return
                }

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

                val ref = itemRef(crate.rewards.size, item, item.type, item.amount, weight, displayName, itemBase64)
                if (applyAudited(player, CrateEditorAudit.Operation.REWARD_ADD, crateId, ref) { plugin.crateManager.addReward(crateId, reward) }) {
                    plugin.commsManager.send(player, Component.text("Reward added: $displayName x${item.amount} (weight: $weight).", NamedTextColor.GREEN))
                    openEditMenu(player, crateId)
                } else {
                    plugin.commsManager.send(player, Component.text("Failed to add reward.", NamedTextColor.RED))
                }
            }

            is PendingCrateAction.EditWeight -> {
                val crateId = pending.crateId
                val ref = rewardRef(pending.rewardIndex, pending.expected)
                if (!authorized(player, CrateEditorAudit.Operation.REWARD_WEIGHT, crateId)) return
                val weight = message.toIntOrNull()
                if (weight == null || weight < 1 || weight > 1000) {
                    audit(player, CrateEditorAudit.Operation.REWARD_WEIGHT, crateId, CrateEditorAudit.Result.REJECTED, CrateEditorAudit.Reason.INVALID_WEIGHT,
                        ref, previous = pending.expected.weight.toString(), new = message)
                    plugin.commsManager.send(player, Component.text("Invalid weight. Must be 1-1000.", NamedTextColor.RED))
                    return
                }
                staleReason(crateId, pending.rewardIndex, pending.expected)?.let { reason ->
                    audit(player, CrateEditorAudit.Operation.REWARD_WEIGHT, crateId, CrateEditorAudit.Result.REJECTED, reason, ref,
                        previous = pending.expected.weight.toString(), new = weight.toString())
                    plugin.commsManager.send(player, Component.text("This crate changed since you picked the reward; the weight was not changed.", NamedTextColor.RED))
                    return
                }
                if (weight == pending.expected.weight) {
                    // Nothing to change, so nothing to log.
                    plugin.commsManager.send(player, Component.text("Weight is already $weight.", NamedTextColor.GRAY))
                    openEditMenu(player, crateId)
                    return
                }
                if (applyAudited(player, CrateEditorAudit.Operation.REWARD_WEIGHT, crateId, ref,
                        previous = pending.expected.weight.toString(), new = weight.toString(),
                        extra = mapOf("total_weight_before" to plugin.crateManager.getCrate(crateId)!!.rewards.sumOf { it.weight }.toString())) {
                        plugin.crateManager.updateRewardWeight(crateId, pending.rewardIndex, weight)
                    }) {
                    plugin.commsManager.send(player, Component.text("Weight updated to $weight.", NamedTextColor.GREEN))
                    openEditMenu(player, crateId)
                } else {
                    plugin.commsManager.send(player, Component.text("Failed to update weight.", NamedTextColor.RED))
                }
            }
        }
    }
}
