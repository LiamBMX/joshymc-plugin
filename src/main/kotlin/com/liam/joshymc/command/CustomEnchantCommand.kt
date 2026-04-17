package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.enchant.CustomEnchantManager
import com.liam.joshymc.manager.CommunicationsManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

class CustomEnchantCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED))
            return true
        }

        if (!sender.hasPermission("joshymc.customenchant")) {
            plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED))
            return true
        }

        val sub = args.getOrNull(0)?.lowercase() ?: "help"

        when (sub) {
            "add", "apply" -> handleApply(sender, args)
            "remove" -> handleRemove(sender, args)
            "list" -> handleList(sender)
            "info" -> handleInfo(sender, args)
            "test" -> handleTest(sender)
            else -> showHelp(sender)
        }

        return true
    }

    private fun handleApply(player: Player, args: Array<out String>) {
        val item = player.inventory.itemInMainHand
        if (item.type == Material.AIR) {
            plugin.commsManager.send(player, Component.text("Hold the item you want to enchant.", NamedTextColor.RED))
            return
        }

        val enchantId = args.getOrNull(1)?.lowercase()
        if (enchantId == null) {
            plugin.commsManager.send(player, Component.text("Usage: /ce add <enchant> [level]", NamedTextColor.RED))
            return
        }

        val enchant = plugin.customEnchantManager.getEnchant(enchantId)
        if (enchant == null) {
            plugin.commsManager.send(player, Component.text("Unknown enchant: $enchantId", NamedTextColor.RED))
            return
        }

        val level = args.getOrNull(2)?.toIntOrNull() ?: 1
        if (level < 1 || level > enchant.maxLevel) {
            plugin.commsManager.send(player, Component.text("Level must be 1-${enchant.maxLevel}.", NamedTextColor.RED))
            return
        }

        val success = plugin.customEnchantManager.applyEnchant(item, enchantId, level)
        if (success) {
            val levelText = if (enchant.maxLevel == 1) "" else " ${CustomEnchantManager.toRoman(level)}"
            plugin.commsManager.send(
                player,
                Component.text("Applied ", NamedTextColor.GREEN)
                    .append(Component.text("${enchant.displayName}$levelText", NamedTextColor.GOLD))
                    .append(Component.text(" to your held item.", NamedTextColor.GREEN))
            )
        } else {
            plugin.commsManager.send(player, Component.text("Cannot apply that enchant to this item (wrong type or conflict).", NamedTextColor.RED))
        }
    }

    private fun handleRemove(player: Player, args: Array<out String>) {
        val item = player.inventory.itemInMainHand
        if (item.type == Material.AIR) {
            plugin.commsManager.send(player, Component.text("Hold the item you want to modify.", NamedTextColor.RED))
            return
        }

        val enchantId = args.getOrNull(1)?.lowercase()
        if (enchantId == null) {
            plugin.commsManager.send(player, Component.text("Usage: /ce remove <enchant>", NamedTextColor.RED))
            return
        }

        if (enchantId == "all") {
            plugin.customEnchantManager.removeAllEnchants(item)
            plugin.commsManager.send(player, Component.text("Removed all custom enchants.", NamedTextColor.GREEN))
            return
        }

        val enchant = plugin.customEnchantManager.getEnchant(enchantId)
        if (enchant == null) {
            plugin.commsManager.send(player, Component.text("Unknown enchant: $enchantId", NamedTextColor.RED))
            return
        }

        plugin.customEnchantManager.removeEnchant(item, enchantId)
        plugin.commsManager.send(
            player,
            Component.text("Removed ", NamedTextColor.GREEN)
                .append(Component.text(enchant.displayName, NamedTextColor.GOLD))
                .append(Component.text(" from your held item.", NamedTextColor.GREEN))
        )
    }

    private fun handleList(player: Player) {
        val enchants = plugin.customEnchantManager.getAllEnchants()
        val gold = TextColor.color(0xFFD700)

        val msg = Component.text()
            .append(Component.text("--- Custom Enchants (${enchants.size}) ---", gold).decoration(TextDecoration.BOLD, true))
            .append(Component.newline())

        for (enchant in enchants.sortedBy { it.target.name + it.displayName }) {
            val levelText = if (enchant.maxLevel == 1) "" else " (max ${CustomEnchantManager.toRoman(enchant.maxLevel)})"
            msg.append(Component.newline())
                .append(Component.text("  ${enchant.displayName}$levelText", NamedTextColor.GOLD))
                .append(Component.text(" [${enchant.target.name.lowercase()}]", NamedTextColor.DARK_GRAY))
        }

        plugin.commsManager.send(player, msg.build())
    }

    private fun handleInfo(player: Player, args: Array<out String>) {
        val enchantId = args.getOrNull(1)?.lowercase()
        if (enchantId == null) {
            plugin.commsManager.send(player, Component.text("Usage: /ce info <enchant>", NamedTextColor.RED))
            return
        }

        val enchant = plugin.customEnchantManager.getEnchant(enchantId)
        if (enchant == null) {
            plugin.commsManager.send(player, Component.text("Unknown enchant: $enchantId", NamedTextColor.RED))
            return
        }

        val msg = Component.text()
            .append(Component.text("--- ${enchant.displayName} ---", TextColor.color(0xFFD700)).decoration(TextDecoration.BOLD, true))
            .append(Component.newline())
            .append(Component.text("  ID: ", NamedTextColor.GRAY))
            .append(Component.text(enchant.id, NamedTextColor.WHITE))
            .append(Component.newline())
            .append(Component.text("  Max Level: ", NamedTextColor.GRAY))
            .append(Component.text(CustomEnchantManager.toRoman(enchant.maxLevel), NamedTextColor.WHITE))
            .append(Component.newline())
            .append(Component.text("  Target: ", NamedTextColor.GRAY))
            .append(Component.text(enchant.target.name.lowercase(), NamedTextColor.WHITE))
            .append(Component.newline())
            .append(Component.text("  ${enchant.description}", NamedTextColor.GRAY))

        if (enchant.conflicts.isNotEmpty()) {
            msg.append(Component.newline())
                .append(Component.text("  Conflicts: ", NamedTextColor.GRAY))
                .append(Component.text(enchant.conflicts.joinToString(", "), NamedTextColor.RED))
        }

        plugin.commsManager.send(player, msg.build())
    }

    private fun handleTest(player: Player) {
        val cem = plugin.customEnchantManager
        var passed = 0
        var failed = 0
        val results = mutableListOf<String>()

        // Test 1: Apply/read/remove on a sword
        val sword = ItemStack(Material.DIAMOND_SWORD)
        if (cem.applyEnchant(sword, "lifesteal", 3)) {
            val readLevel = cem.getLevel(sword, "lifesteal")
            if (readLevel == 3) {
                passed++
                results.add("lifesteal apply+read: PASS (level=$readLevel)")
            } else {
                failed++
                results.add("lifesteal read: FAIL (expected 3, got $readLevel)")
            }
        } else {
            failed++
            results.add("lifesteal apply: FAIL (returned false)")
        }

        // Test 2: Apply multiple enchants
        cem.applyEnchant(sword, "execute", 2)
        cem.applyEnchant(sword, "bleed", 1)
        val allEnchants = cem.getEnchants(sword)
        if (allEnchants.size == 3 && allEnchants["lifesteal"] == 3 && allEnchants["execute"] == 2 && allEnchants["bleed"] == 1) {
            passed++
            results.add("multi-enchant: PASS (${allEnchants.size} enchants)")
        } else {
            failed++
            results.add("multi-enchant: FAIL (got ${allEnchants})")
        }

        // Test 3: Wrong target should fail
        val failResult = cem.applyEnchant(sword, "gears", 1) // boots enchant on sword
        if (!failResult) {
            passed++
            results.add("wrong target reject: PASS")
        } else {
            failed++
            results.add("wrong target reject: FAIL (should have been rejected)")
        }

        // Test 4: Remove enchant
        cem.removeEnchant(sword, "bleed")
        if (!cem.hasEnchant(sword, "bleed")) {
            passed++
            results.add("remove enchant: PASS")
        } else {
            failed++
            results.add("remove enchant: FAIL (still present)")
        }

        // Test 5: Lore rendering
        cem.updateLore(sword)
        val lore = sword.itemMeta?.lore() ?: emptyList()
        if (lore.isNotEmpty()) {
            passed++
            results.add("lore rendering: PASS (${lore.size} lines)")
        } else {
            failed++
            results.add("lore rendering: FAIL (no lore)")
        }

        // Test 6: All enchant targets
        val targets = mapOf(
            Material.DIAMOND_SWORD to listOf("lifesteal", "execute", "bleed", "adrenaline", "striker"),
            Material.DIAMOND_AXE to listOf("cleave", "berserk", "paralysis", "blizzard"),
            Material.DIAMOND_HELMET to listOf("night_vision", "clarity", "focus", "xray"),
            Material.DIAMOND_CHESTPLATE to listOf("overload", "dodge", "guardian"),
            Material.DIAMOND_LEGGINGS to listOf("shockwave", "valor", "curse_swap"),
            Material.DIAMOND_BOOTS to listOf("gears", "springs", "featherweight", "rockets"),
            Material.DIAMOND_PICKAXE to listOf("autosmelt", "experience", "condenser", "explosive", "magnet"),
            Material.DIAMOND_SHOVEL to listOf("glass_breaker"),
            Material.DIAMOND_HOE to listOf("ground_pound", "great_harvest", "blessing"),
        )

        for ((material, enchantIds) in targets) {
            val testItem = ItemStack(material)
            for (id in enchantIds) {
                val applied = cem.applyEnchant(testItem, id, 1)
                if (applied) {
                    val readBack = cem.getLevel(testItem, id)
                    if (readBack == 1) {
                        passed++
                    } else {
                        failed++
                        results.add("$id on ${material.name}: FAIL read (expected 1, got $readBack)")
                    }
                } else {
                    failed++
                    results.add("$id on ${material.name}: FAIL apply")
                }
            }
        }

        // Send results
        val color = if (failed == 0) NamedTextColor.GREEN else NamedTextColor.RED
        plugin.commsManager.send(player, Component.text("--- Enchant Tests: $passed passed, $failed failed ---", color))
        for (result in results) {
            val lineColor = if (result.contains("FAIL")) NamedTextColor.RED else NamedTextColor.GREEN
            plugin.commsManager.send(player, Component.text("  $result", lineColor))
        }

        if (failed == 0) {
            // Give the player a test sword with all sword enchants
            val testSword = ItemStack(Material.DIAMOND_SWORD)
            cem.applyEnchant(testSword, "lifesteal", 3)
            cem.applyEnchant(testSword, "execute", 3)
            cem.applyEnchant(testSword, "bleed", 3)
            testSword.editMeta { it.displayName(Component.text("Test Sword", NamedTextColor.GOLD)) }
            player.inventory.addItem(testSword)

            val testPick = ItemStack(Material.DIAMOND_PICKAXE)
            cem.applyEnchant(testPick, "magnet", 1)
            cem.applyEnchant(testPick, "autosmelt", 1)
            cem.applyEnchant(testPick, "experience", 3)
            testPick.editMeta { it.displayName(Component.text("Test Pickaxe", NamedTextColor.GOLD)) }
            player.inventory.addItem(testPick)

            val testBoots = ItemStack(Material.DIAMOND_BOOTS)
            cem.applyEnchant(testBoots, "gears", 2)
            cem.applyEnchant(testBoots, "springs", 2)
            cem.applyEnchant(testBoots, "featherweight", 1)
            testBoots.editMeta { it.displayName(Component.text("Test Boots", NamedTextColor.GOLD)) }
            player.inventory.addItem(testBoots)

            val testHelmet = ItemStack(Material.DIAMOND_HELMET)
            cem.applyEnchant(testHelmet, "night_vision", 1)
            cem.applyEnchant(testHelmet, "xray", 1)
            testHelmet.editMeta { it.displayName(Component.text("Test Helmet", NamedTextColor.GOLD)) }
            player.inventory.addItem(testHelmet)

            plugin.commsManager.send(player, Component.text("Test items added to inventory!", NamedTextColor.GREEN))
        }
    }

    private fun showHelp(player: Player) {
        val gold = TextColor.color(0xFFD700)
        val msg = Component.text()
            .append(Component.text("--- Custom Enchants ---", gold).decoration(TextDecoration.BOLD, true))
            .append(Component.newline())
            .append(Component.text("/ce add <enchant> [level]", NamedTextColor.YELLOW))
            .append(Component.text(" — Apply enchant to held item", NamedTextColor.GRAY))
            .append(Component.newline())
            .append(Component.text("/ce remove <enchant|all>", NamedTextColor.YELLOW))
            .append(Component.text(" — Remove enchant from held item", NamedTextColor.GRAY))
            .append(Component.newline())
            .append(Component.text("/ce list", NamedTextColor.YELLOW))
            .append(Component.text(" — List all custom enchants", NamedTextColor.GRAY))
            .append(Component.newline())
            .append(Component.text("/ce info <enchant>", NamedTextColor.YELLOW))
            .append(Component.text(" — Show enchant details", NamedTextColor.GRAY))
            .append(Component.newline())
            .append(Component.text("/ce test", NamedTextColor.YELLOW))
            .append(Component.text(" — Run all enchant tests + give test items", NamedTextColor.GRAY))

        plugin.commsManager.send(player, msg.build())
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        return when (args.size) {
            1 -> listOf("add", "remove", "list", "info", "test").filter { it.startsWith(args[0].lowercase()) }
            2 -> when (args[0].lowercase()) {
                "add", "apply", "info" -> plugin.customEnchantManager.getEnchantIds()
                    .filter { it.startsWith(args[1].lowercase()) }
                "remove" -> (plugin.customEnchantManager.getEnchantIds() + "all")
                    .filter { it.startsWith(args[1].lowercase()) }
                else -> emptyList()
            }
            3 -> when (args[0].lowercase()) {
                "add", "apply" -> {
                    val enchant = plugin.customEnchantManager.getEnchant(args[1].lowercase())
                    if (enchant != null) (1..enchant.maxLevel).map { it.toString() }
                    else emptyList()
                }
                else -> emptyList()
            }
            else -> emptyList()
        }
    }
}
