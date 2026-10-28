package com.liam.joshymc.potion

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gamemode.ServerGamemode
import com.liam.joshymc.util.Msg
import io.papermc.paper.potion.PotionMix
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.BrewEvent
import org.bukkit.event.player.PlayerItemConsumeEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.RecipeChoice

/**
 * Drinkable Haste and Resistance potions (issue #1318), brewed in ordinary brewing stands
 * in both Survival and Lifesteal.
 *
 * The recipes are Paper [PotionMix]es (Awkward + ingredient, normal + Redstone, normal +
 * Glowstone), so brewing time, fuel, hoppers and ingredient consumption are vanilla. Each
 * gamemode configures its own ingredients, durations, levels and availability, so [onBrew]
 * rebuilds every beacon potion result for the stand's gamemode and keeps a slot unchanged
 * whenever a brew would turn a beacon potion into anything else (splash, lingering, a
 * wrong-gamemode or disabled potion). [onDrink] refuses potions brewed for another gamemode
 * or disabled where they're drunk.
 */
class BeaconPotionManager(private val plugin: Joshymc) : Listener {

    private val mixKeys = mutableListOf<NamespacedKey>()
    private val settings = mutableMapOf<ServerGamemode, BeaconPotionSettings>()

    fun start() {
        stop()
        val problems = mutableListOf<String>()
        ServerGamemode.entries.forEach { settings[it] = BeaconPotionConfig.read(plugin.config, it, problems) }
        problems.forEach { plugin.logger.warning("[BeaconPotions] $it") }
        registerMixes()
    }

    fun stop() {
        val brewer = Bukkit.getPotionBrewer()
        mixKeys.forEach { runCatching { brewer.removePotionMix(it) } }
        mixKeys.clear()
        settings.clear()
    }

    fun settingsFor(gamemode: ServerGamemode): BeaconPotionSettings =
        settings.getOrPut(gamemode) { BeaconPotionConfig.read(plugin.config, gamemode) }

    fun settingsFor(world: World?): BeaconPotionSettings = settingsFor(plugin.gamemodeLockManager.gamemodeOf(world))

    fun create(effect: BeaconPotionEffect, variant: BeaconPotionVariant, gamemode: ServerGamemode, amount: Int = 1): ItemStack =
        BeaconPotionStacks.create(plugin, effect, variant, settingsFor(gamemode), amount)

    /**
     * One mix per ingredient any gamemode uses, so a stand starts brewing everywhere a
     * recipe exists; [onBrew] then decides what the stand's own gamemode makes. Inputs are
     * predicate choices (deprecated in 26.2 but still supported) because a beacon potion is
     * matched by its id, whatever duration its gamemode gave it.
     */
    @Suppress("DEPRECATION")
    private fun registerMixes() {
        val brewer = Bukkit.getPotionBrewer()
        val server = plugin.gamemodeLockManager.serverGamemode
        for (effect in BeaconPotionEffect.entries) {
            val ingredients = settings.values.filter { it.isEnabled(effect) }.map { it.effect(effect).ingredient }.toSet()
            if (ingredients.isEmpty()) continue
            for (ingredient in ingredients) {
                val material = Material.matchMaterial(ingredient) ?: continue
                add(
                    brewer, "beacon_${effect.id}_${material.key.key}",
                    create(effect, BeaconPotionVariant.NORMAL, server),
                    PotionMix.createPredicateChoice { BeaconPotionStacks.isAwkward(it) },
                    RecipeChoice.MaterialChoice(material),
                )
            }
            val isNormal = PotionMix.createPredicateChoice {
                BeaconPotionStacks.identify(it)?.let { p -> p.effect == effect && p.variant == BeaconPotionVariant.NORMAL } == true
            }
            add(brewer, "beacon_${effect.id}_extended", create(effect, BeaconPotionVariant.EXTENDED, server), isNormal,
                RecipeChoice.MaterialChoice(Material.REDSTONE))
            add(brewer, "beacon_${effect.id}_enhanced", create(effect, BeaconPotionVariant.ENHANCED, server), isNormal,
                RecipeChoice.MaterialChoice(Material.GLOWSTONE_DUST))
        }
        plugin.logger.info("Registered ${mixKeys.size} beacon potion brewing mix(es).")
    }

    private fun add(brewer: org.bukkit.potion.PotionBrewer, name: String, result: ItemStack, input: RecipeChoice, ingredient: RecipeChoice) {
        val key = NamespacedKey(plugin, name)
        try {
            brewer.addPotionMix(PotionMix(key, result, input, ingredient))
            mixKeys += key
        } catch (e: Exception) {
            plugin.logger.warning("[BeaconPotions] Couldn't register brewing mix $name: ${e.message}")
        }
    }

    /**
     * HIGH so later listeners see the final results. Only slots whose input or result is a
     * beacon potion are touched; vanilla brews pass through as they are. If no slot ends up
     * changing, the brew is cancelled so the ingredient isn't used up for nothing.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBrew(event: BrewEvent) {
        val contents = event.contents
        val ingredient = contents.ingredient?.type?.name ?: return
        val results = event.results
        val settings = settingsFor(event.block.world)
        var touched = false
        for (slot in 0 until minOf(3, results.size)) {
            val input = contents.getItem(slot)
            val result = results[slot]
            val beaconInput = BeaconPotionStacks.identify(input)
            if (beaconInput == null && !BeaconPotionStacks.isTagged(result)) continue
            touched = true
            val kind = when {
                beaconInput != null -> beaconInput
                BeaconPotionStacks.isAwkward(input) -> BrewInput.Awkward
                else -> BrewInput.Other
            }
            results[slot] = when (val outcome = BeaconPotionRules.brew(kind, ingredient, settings)) {
                is BrewOutcome.Make -> BeaconPotionStacks.create(plugin, outcome.effect, outcome.variant, settings)
                BrewOutcome.Keep -> input?.clone() ?: ItemStack.empty()
            }
        }
        if (!touched) return
        val anyChange = (0 until minOf(3, results.size)).any { slot ->
            val input = contents.getItem(slot)
            val result = results[slot]
            val inputEmpty = input == null || input.isEmpty
            val resultEmpty = result == null || result.isEmpty
            if (inputEmpty && resultEmpty) false else inputEmpty != resultEmpty || !input!!.isSimilar(result)
        }
        if (!anyChange) event.isCancelled = true
    }

    /** LOW so a refused drink is cancelled before anything reacts to it. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onDrink(event: PlayerItemConsumeEvent) {
        val potion = BeaconPotionStacks.identify(event.item) ?: return
        val problem = BeaconPotionRules.drinkProblem(potion.effect, potion.gamemode, settingsFor(event.player.world)) ?: return
        event.isCancelled = true
        Msg.error(event.player, problem)
    }
}
