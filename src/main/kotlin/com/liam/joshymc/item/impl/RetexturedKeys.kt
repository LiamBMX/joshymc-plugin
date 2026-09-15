package com.liam.joshymc.item.impl

import com.liam.joshymc.Joshymc
import com.liam.joshymc.item.CustomItem
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.meta.ItemMeta

// ── Retextured Trial Keys ───────────────────────────────────────────────────
// 19 retextured Material.TRIAL_KEY items: 12 monthly keys + 7 themed keys.
// Vanilla trial-key behavior, custom name/model only — no lore by default.

abstract class RetexturedKey(idValue: String, name: String, color: Int) : CustomItem() {
    override val id = idValue
    override val material = Material.TRIAL_KEY

    override val displayName: Component = Component.text(name, TextColor.color(color))
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)

    override val lore: List<Component> = emptyList()

    override fun applyMeta(meta: ItemMeta) {
        meta.setItemModel(NamespacedKey(Joshymc.instance, id))
    }
}

// Monthly keys

class JanuaryKey : RetexturedKey("january_key", "January Key", 0xA6E3FF)
class FebruaryKey : RetexturedKey("february_key", "February Key", 0xFF6FA0)
class MarchKey : RetexturedKey("march_key", "March Key", 0x4CD964)
class AprilKey : RetexturedKey("april_key", "April Key", 0x8FD1FF)
class MayKey : RetexturedKey("may_key", "May Key", 0x7CFC5A)
class JuneKey : RetexturedKey("june_key", "June Key", 0xFFD93D)
class JulyKey : RetexturedKey("july_key", "July Key", 0xFF4D4D)
class AugustKey : RetexturedKey("august_key", "August Key", 0xE8A93D)
class SeptemberKey : RetexturedKey("september_key", "September Key", 0xD2691E)
class OctoberKey : RetexturedKey("october_key", "October Key", 0xFF7518)
class NovemberKey : RetexturedKey("november_key", "November Key", 0xB87333)
class DecemberKey : RetexturedKey("december_key", "December Key", 0xC41E3A)

// Themed keys

class MoneyKey : RetexturedKey("money_key", "Money Key", 0x2ECC71)
class HarvestKey : RetexturedKey("harvest_key", "Harvest Key", 0xE8971E)
class StockpileKey : RetexturedKey("stockpile_key", "Stockpile Key", 0xA0A0A0)
class HomesteadKey : RetexturedKey("homestead_key", "Homestead Key", 0x7CA35C)
class CampfireKey : RetexturedKey("campfire_key", "Campfire Key", 0xE25822)
class CabinKey : RetexturedKey("cabin_key", "Cabin Key", 0xD4A24C)
class CreditKey : RetexturedKey("credit_key", "Credit Key", 0x55FFFF)
