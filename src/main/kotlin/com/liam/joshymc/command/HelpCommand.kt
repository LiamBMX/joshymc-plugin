package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

class HelpCommand(private val plugin: Joshymc) : CommandExecutor {

    private data class HelpEntry(
        val command: String,
        val description: String,
        val staffOnly: Boolean = false
    )

    private data class Category(
        val icon: Material,
        val name: String,
        val color: NamedTextColor,
        val entries: List<HelpEntry>
    )

    private val categories = listOf(
        // Row 1 (slots 10-16)
        Category(Material.COMPASS, "General", NamedTextColor.GREEN, listOf(
            HelpEntry("/spawn", "Teleport to the server spawn"),
            HelpEntry("/back", "Return to your previous location"),
            HelpEntry("/top", "Teleport to the highest block above you"),
            HelpEntry("/rtp", "Randomly teleport to a new location"),
            HelpEntry("/hat", "Wear the item in your hand as a hat"),
            HelpEntry("/craft", "Open a portable crafting table"),
            HelpEntry("/anvil", "Open a portable anvil"),
            HelpEntry("/msg <player> <message>", "Send a private message"),
            HelpEntry("/r <message>", "Reply to the last private message")
        )),
        Category(Material.ENDER_PEARL, "Teleportation", NamedTextColor.DARK_PURPLE, listOf(
            HelpEntry("/home [name]", "Teleport to your home"),
            HelpEntry("/sethome <name>", "Set a home at your current location"),
            HelpEntry("/delhome <name>", "Delete a home"),
            HelpEntry("/warp <name>", "Teleport to a server warp"),
            HelpEntry("/pwarp", "Open the player warps menu"),
            HelpEntry("/tpa <player>", "Request to teleport to a player"),
            HelpEntry("/tpahere <player>", "Request a player to teleport to you"),
            HelpEntry("/tpaccept", "Accept an incoming teleport request"),
            HelpEntry("/tpdeny", "Deny an incoming teleport request"),
            HelpEntry("/tp <player>", "Teleport to a player", staffOnly = true),
            HelpEntry("/tphere <player>", "Teleport a player to you", staffOnly = true)
        )),
        Category(Material.GOLD_INGOT, "Economy", NamedTextColor.GOLD, listOf(
            HelpEntry("/balance", "Check your balance"),
            HelpEntry("/pay <player> <amount>", "Pay another player"),
            HelpEntry("/baltop", "View the richest players"),
            HelpEntry("/shop", "Open the server shop"),
            HelpEntry("/sell", "Sell items in your hand"),
            HelpEntry("/ah", "Open the auction house")
        )),
        Category(Material.DIAMOND_SWORD, "Combat", NamedTextColor.RED, listOf(
            HelpEntry("/pvp", "Toggle PvP on or off"),
            HelpEntry("/team", "Manage your team"),
            HelpEntry("/bounty", "Place or view bounties on players")
        )),
        Category(Material.GOLDEN_SHOVEL, "Claims", NamedTextColor.YELLOW, listOf(
            HelpEntry("/claim", "Claim the chunk you are standing in"),
            HelpEntry("/claim area", "Claim a rectangular area"),
            HelpEntry("/claim show", "Visualize nearby claims"),
            HelpEntry("/claim team", "Manage team claim permissions"),
            HelpEntry("/claim map", "View a map of nearby claims"),
            HelpEntry("/unclaim", "Unclaim the chunk you are standing in"),
            HelpEntry("/subclaim", "Create a subclaim within your claim")
        )),
        Category(Material.CHEST, "Storage", NamedTextColor.AQUA, listOf(
            HelpEntry("/pv [number]", "Open a player vault"),
            HelpEntry("/kit [name]", "View or claim a kit"),
            HelpEntry("/crate", "View crate information"),
            HelpEntry("/enderchest", "Open your ender chest remotely")
        )),
        Category(Material.ENCHANTED_BOOK, "Enchants", NamedTextColor.LIGHT_PURPLE, listOf(
            HelpEntry("/ce add <enchant> [level]", "Add a custom enchantment"),
            HelpEntry("/ce remove <enchant>", "Remove a custom enchantment"),
            HelpEntry("/ce list", "List all custom enchantments"),
            HelpEntry("/ce info <enchant>", "View info about an enchantment")
        )),
        // Row 2 (slots 19-25)
        Category(Material.OAK_SIGN, "Shops", NamedTextColor.DARK_GREEN, listOf(
            HelpEntry("/chestshop", "Create a chest shop"),
            HelpEntry("/sellwand", "Use a sell wand on a chest")
        )),
        Category(Material.PAPER, "Info", NamedTextColor.WHITE, listOf(
            HelpEntry("/playtime", "View your playtime"),
            HelpEntry("/playtimetop", "View the top playtimes"),
            HelpEntry("/settings", "Open the player settings GUI")
        )),
        Category(Material.NETHER_STAR, "Admin", NamedTextColor.DARK_RED, listOf(
            HelpEntry("/gamemode <mode>", "Change your gamemode", staffOnly = true),
            HelpEntry("/fly", "Toggle flight", staffOnly = true),
            HelpEntry("/heal", "Heal yourself", staffOnly = true),
            HelpEntry("/feed", "Feed yourself", staffOnly = true),
            HelpEntry("/god", "Toggle god mode", staffOnly = true),
            HelpEntry("/speed <amount>", "Set your walk/fly speed", staffOnly = true),
            HelpEntry("/vanish", "Toggle vanish", staffOnly = true),
            HelpEntry("/invsee <player>", "View a player's inventory", staffOnly = true),
            HelpEntry("/sudo <player> <command>", "Force a player to run a command", staffOnly = true),
            HelpEntry("/smite <player>", "Strike a player with lightning", staffOnly = true)
        )),
        Category(Material.BARRIER, "Moderation", NamedTextColor.DARK_RED, listOf(
            HelpEntry("/ban <player> [reason]", "Permanently ban a player", staffOnly = true),
            HelpEntry("/tempban <player> <duration> [reason]", "Temporarily ban a player", staffOnly = true),
            HelpEntry("/unban <player>", "Unban a player", staffOnly = true),
            HelpEntry("/mute <player> [reason]", "Permanently mute a player", staffOnly = true),
            HelpEntry("/tempmute <player> <duration> [reason]", "Temporarily mute a player", staffOnly = true),
            HelpEntry("/unmute <player>", "Unmute a player", staffOnly = true),
            HelpEntry("/warn <player> [reason]", "Warn a player", staffOnly = true),
            HelpEntry("/pkick <player> [reason]", "Kick a player", staffOnly = true),
            HelpEntry("/history <player>", "View a player's punishment history", staffOnly = true),
            HelpEntry("/report <player> <reason>", "Report a player"),
            HelpEntry("/reports", "View pending reports", staffOnly = true)
        )),
        Category(Material.GRASS_BLOCK, "World", NamedTextColor.GREEN, listOf(
            HelpEntry("/resource", "Teleport to the resource world"),
            HelpEntry("/claim map", "View a map of nearby claims")
        )),
        Category(Material.NAME_TAG, "Ranks", NamedTextColor.GOLD, listOf(
            HelpEntry("/rank set <player> <rank>", "Set a player's rank", staffOnly = true),
            HelpEntry("/rank remove <player>", "Remove a player's rank", staffOnly = true),
            HelpEntry("/rank list", "List all available ranks", staffOnly = true)
        )),
        Category(Material.PLAYER_HEAD, "Teams", NamedTextColor.BLUE, listOf(
            HelpEntry("/team create <name>", "Create a new team"),
            HelpEntry("/team invite <player>", "Invite a player to your team"),
            HelpEntry("/team accept", "Accept a team invitation"),
            HelpEntry("/team kick <player>", "Kick a player from your team"),
            HelpEntry("/team promote <player>", "Promote a team member"),
            HelpEntry("/team deposit <amount>", "Deposit money into the team bank"),
            HelpEntry("/team withdraw <amount>", "Withdraw money from the team bank"),
            HelpEntry("/team echest", "Open the team ender chest"),
            HelpEntry("/team chat", "Toggle team chat")
        ))
    )

    // ── Feature Guides ─────────────────────────────────────
    // These are detailed explanation pages, not command lists.

    private data class GuideEntry(
        val icon: Material,
        val title: String,
        val description: String,
        val color: TextColor,
        val pages: List<GuidePage>
    )

    private data class GuidePage(
        val icon: Material,
        val title: String,
        val lines: List<String>
    )

    private val featureGuides = listOf(
        GuideEntry(Material.DIAMOND_PICKAXE, "Skills", "Level up 9 unique skills by playing!", TextColor.color(0x55FFFF), listOf(
            GuidePage(Material.DIAMOND_PICKAXE, "Mining", listOf(
                "&bMining &7levels up by breaking ores and stone.",
                "",
                "&7XP is earned based on ore rarity:",
                "&8  Stone/Cobble: &f1 XP",
                "&8  Coal/Copper/Lapis: &f3-5 XP",
                "&8  Iron/Gold: &f5-8 XP",
                "&8  Diamond/Emerald: &f12-16 XP",
                "&8  Ancient Debris: &f30 XP",
                "",
                "&7Perks:",
                "&a  Lv10: &f+5% ore drops",
                "&a  Lv25: &fChance to find money (\$100-\$1000)",
                "&a  Lv50: &fDouble ore drops (10% chance)",
                "&a  Lv75: &fAuto-smelt ores (25% chance)",
                "&a  Lv100: &fPermanent Haste I with pickaxe"
            )),
            GuidePage(Material.GOLDEN_HOE, "Farming", listOf(
                "&aFarming &7levels up by harvesting fully-grown crops.",
                "",
                "&7Crops must be fully grown to earn XP.",
                "&8  Wheat/Carrots/Potatoes: &f2 XP",
                "&8  Sugar Cane/Melon/Pumpkin: &f3 XP",
                "&8  Nether Wart: &f5 XP",
                "&8  Chorus: &f8 XP",
                "",
                "&7Perks:",
                "&a  Lv10: &f+5% crop drops",
                "&a  Lv25: &fCrops occasionally drop seeds",
                "&a  Lv50: &fDouble harvest (10% chance)",
                "&a  Lv75: &fAuto-replant (25% chance)",
                "&a  Lv100: &f3x3 harvest radius"
            )),
            GuidePage(Material.DIAMOND_SWORD, "Combat", listOf(
                "&cCombat &7levels up by killing mobs and players.",
                "",
                "&7Stronger mobs give more XP:",
                "&8  Zombies/Skeletons/Spiders: &f5 XP",
                "&8  Endermen/Witches: &f12 XP",
                "&8  Blazes/Wither Skeletons: &f15-18 XP",
                "&8  Players: &f25 XP",
                "&8  Wither: &f200 XP",
                "&8  Ender Dragon: &f500 XP",
                "",
                "&7Perks:",
                "&a  Lv10: &f+5% damage",
                "&a  Lv25: &f+5% damage reduction",
                "&a  Lv50: &f+10% damage",
                "&a  Lv75: &fLifesteal (2% of damage healed)",
                "&a  Lv100: &f+15% damage, +10% damage reduction"
            )),
            GuidePage(Material.FISHING_ROD, "Fishing", listOf(
                "&3Fishing &7levels up by catching fish.",
                "",
                "&7Each successful catch gives &f5 XP&7.",
                "",
                "&7Perks:",
                "&a  Lv10: &f+5% fish catch rate",
                "&a  Lv25: &fChance to catch double fish",
                "&a  Lv50: &fIncreased rare fish chance",
                "&a  Lv75: &fAuto-cook fish",
                "&a  Lv100: &fLegendary fish chance doubled"
            )),
            GuidePage(Material.IRON_AXE, "Woodcutting", listOf(
                "&6Woodcutting &7levels up by chopping logs.",
                "",
                "&7Any type of log or wood gives XP:",
                "&8  Regular logs: &f2 XP",
                "&8  Stripped logs: &f3 XP",
                "&8  Nether stems: &f2 XP",
                "",
                "&7Perks:",
                "&a  Lv10: &f+5% log drops",
                "&a  Lv25: &fChance to get apples from leaves",
                "&a  Lv50: &fDouble log drops (10% chance)",
                "&a  Lv75: &fAuto-strip logs",
                "&a  Lv100: &fEntire tree falls on chop"
            )),
            GuidePage(Material.IRON_SHOVEL, "Excavation", listOf(
                "&eExcavation &7levels up by digging dirt, sand, gravel, etc.",
                "",
                "&8  Dirt/Sand/Gravel: &f1 XP",
                "&8  Soul Sand/Soul Soil: &f2 XP",
                "&8  Clay/Mycelium: &f3 XP",
                "",
                "&7Perks:",
                "&a  Lv10: &f+5% drops",
                "&a  Lv25: &fChance to find treasure (money, gems)",
                "&a  Lv50: &fDouble drops (10% chance)",
                "&a  Lv75: &f3x3 dig radius",
                "&a  Lv100: &fFind ancient artifacts (rare items)"
            )),
            GuidePage(Material.ENCHANTING_TABLE, "Enchanting", listOf(
                "&dEnchanting &7levels up by enchanting items at a table.",
                "",
                "&7XP is based on levels spent (10-50 XP per enchant).",
                "",
                "&7Perks:",
                "&a  Lv10: &f5% discount on enchant levels",
                "&a  Lv25: &fChance to not consume lapis",
                "&a  Lv50: &fOccasional bonus enchantment",
                "&a  Lv75: &fHigher-level enchants available",
                "&a  Lv100: &fNever consume XP levels (10% chance)"
            )),
            GuidePage(Material.BREWING_STAND, "Alchemy", listOf(
                "&5Alchemy &7levels up by brewing potions.",
                "",
                "&7Stand near a brewing stand when it finishes.",
                "&7XP scales with the number of potions brewed (5-15 XP).",
                "",
                "&7Perks:",
                "&a  Lv10: &f10% longer potion duration",
                "&a  Lv25: &fChance for double brew output",
                "&a  Lv50: &f25% longer duration",
                "&a  Lv75: &fSplash potions have larger radius",
                "&a  Lv100: &fChance for enhanced potions (+1 amplifier)"
            )),
            GuidePage(Material.LEAD, "Taming", listOf(
                "&2Taming &7levels up by breeding and taming animals.",
                "",
                "&8  Breeding an animal: &f5 XP",
                "&8  Taming an animal: &f15 XP",
                "",
                "&7Perks:",
                "&a  Lv10: &fAnimals breed faster",
                "&a  Lv25: &fTamed animals have +10% HP",
                "&a  Lv50: &fChance for twin babies on breed",
                "&a  Lv75: &fTamed animals deal +25% damage",
                "&a  Lv100: &fAll tamed animals have double HP"
            ))
        )),
        GuideEntry(Material.ENCHANTED_BOOK, "Custom Enchants", "Powerful enchants beyond vanilla!", TextColor.color(0xAA55FF), listOf(
            GuidePage(Material.DIAMOND_SWORD, "Sword Enchants", listOf(
                "&cSword Enchantments",
                "",
                "&6Lifesteal &7(Max 3): &fHeal a % of damage dealt",
                "&6Execute &7(Max 3): &fMore damage to low HP targets",
                "&6Bleed &7(Max 3): &fCauses damage over time",
                "&6Adrenaline &7(Max 3): &fMore kills = more damage",
                "&6Striker &7(Max 3): &fChance to strike lightning"
            )),
            GuidePage(Material.DIAMOND_AXE, "Axe Enchants", listOf(
                "&cAxe Enchantments",
                "",
                "&6Cleave &7(Max 3): &fSweeping edge on axes",
                "&6Berserk &7(Max 3): &fStrength boost with a downside",
                "&6Paralysis &7(Max 3): &fChance to stun the target",
                "&6Blizzard &7(Max 3): &fApplies freezing slowness"
            )),
            GuidePage(Material.DIAMOND_HELMET, "Armor Enchants", listOf(
                "&bArmor Enchantments",
                "",
                "&eHelmet:",
                "&6  Night Vision &7(Max 1): &fPermanent night vision",
                "&6  Clarity &7(Max 3): &fImmune to blindness/nausea",
                "&6  Focus &7(Max 3): &fReduces axe damage taken",
                "&6  Xray &7(Max 5): &fOres glow while crouching",
                "",
                "&eChestplate:",
                "&6  Overload &7(Max 3): &fIncreases max hearts",
                "&6  Dodge &7(Max 3): &fChance to negate damage",
                "&6  Guardian &7(Max 3): &fRegen when at low health",
                "",
                "&eLeggings:",
                "&6  Shockwave &7(Max 3): &fReduces knockback taken",
                "&6  Valor &7(Max 3): &fReduces crystal/explosion damage",
                "&6  Curse &7(Max 3): &fChance to swap health with attacker",
                "",
                "&eBoots:",
                "&6  Gears &7(Max 3): &fPermanent speed boost",
                "&6  Springs &7(Max 3): &fPermanent jump boost",
                "&6  Featherweight &7(Max 1): &fNo fall damage",
                "&6  Rockets &7(Max 1): &fLevitation when below 2 hearts"
            )),
            GuidePage(Material.DIAMOND_PICKAXE, "Tool Enchants", listOf(
                "&bTool Enchantments",
                "",
                "&ePickaxe:",
                "&6  Autosmelt &7(Max 1): &fAutomatically smelts mined ores",
                "&6  Experience &7(Max 3): &fGain XP from mining stone",
                "&6  Condenser &7(Max 1): &fAuto-condenses ores into blocks",
                "&6  Explosive &7(Max 3): &fChance to blow up a 3x3x3 area",
                "",
                "&eAll Tools (Pickaxe/Shovel/Axe):",
                "&6  Magnet &7(Max 1): &fMined blocks go to inventory",
                "",
                "&eShovel:",
                "&6  Glass Breaker &7(Max 1): &fBreaks glass instantly",
                "",
                "&eHoe:",
                "&6  Ground Pound &7(Max 1): &fBreaks crops in 3x3",
                "&6  Great Harvest &7(Max 1): &fAuto-replants crops",
                "&6  Blessing &7(Max 1): &fAuto-regrows replanted crops"
            ))
        )),
        GuideEntry(Material.GOLDEN_SHOVEL, "Land Claims", "Protect your builds from griefers!", TextColor.color(0xFFFF55), listOf(
            GuidePage(Material.GOLDEN_SHOVEL, "How to Claim", listOf(
                "&eLand Claiming Basics",
                "",
                "&7Use &6/claim &7to claim the chunk you're standing in.",
                "&7Use &6/claim area &7to claim a rectangular area.",
                "&7Use &6/claim show &7to visualize nearby claims.",
                "&7Use &6/unclaim &7to unclaim land.",
                "",
                "&7You start with &f500 claim blocks&7.",
                "&7Earn &f100 blocks per hour &7of playtime.",
                "&7Maximum: &f50,000 blocks&7.",
                "",
                "&cClaimed land cannot be broken, built on,",
                "&cor looted by other players."
            )),
            GuidePage(Material.CHEST, "Permissions & Teams", listOf(
                "&eClaim Permissions",
                "",
                "&7Use &6/claim team &7to allow your team",
                "&7to build in your claims.",
                "",
                "&7Use &6/subclaim &7to create areas within your",
                "&7claim with different permissions.",
                "",
                "&7Use &6/claim map &7to see a visual map of",
                "&7nearby claims in chat."
            ))
        )),
        GuideEntry(Material.CHEST, "Crates", "Open crates for awesome rewards!", TextColor.color(0x55FFFF), listOf(
            GuidePage(Material.TRIPWIRE_HOOK, "How Crates Work", listOf(
                "&bCrate System",
                "",
                "&7Crates are found at spawn. Each crate type",
                "&7has different rewards and rarities.",
                "",
                "&6How to use:",
                "&f  1. &7Get a crate key (voting, events, shop)",
                "&f  2. &7Right-click the crate with the matching key",
                "&f  3. &7Watch the animation and win your reward!",
                "",
                "&eLeft-click &7a crate to preview all rewards.",
                "&eSneak + right-click &7to preview or mass open.",
                "",
                "&7Some crates let you &apick &7your reward.",
                "&7Others are &crandom &7with weighted chances."
            ))
        )),
        GuideEntry(Material.EMERALD, "Economy", "Earn, spend, and trade money!", TextColor.color(0x55FF55), listOf(
            GuidePage(Material.GOLD_INGOT, "Earning Money", listOf(
                "&aEconomy System",
                "",
                "&7Ways to earn money:",
                "&6  /sell &7- Sell items from your hand",
                "&6  /shop &7- Buy and sell from the server shop",
                "&6  /ah &7- List items on the auction house",
                "&6  Voting &7- Earn \$25,000 per vote",
                "&6  Skills &7- Mining perk finds money",
                "&6  Bounties &7- Collect bounties on players",
                "",
                "&7Use &6/balance &7to check your balance.",
                "&7Use &6/pay <player> <amount> &7to send money.",
                "&7Use &6/baltop &7to see the richest players."
            ))
        )),
        GuideEntry(Material.COMPASS, "Teleportation", "Getting around the server!", TextColor.color(0xAA55FF), listOf(
            GuidePage(Material.ENDER_PEARL, "Teleport Commands", listOf(
                "&dTeleportation Guide",
                "",
                "&6/spawn &7- Go to the server spawn",
                "&6/home &7- Teleport to your homes (max 3)",
                "&6/sethome <name> &7- Set a home",
                "&6/warp <name> &7- Server warps",
                "&6/pwarp &7- Player-created warps",
                "&6/rtp &7- Random teleport (pick a world!)",
                "&6/tpa <player> &7- Request to teleport to someone",
                "&6/back &7- Return to previous location",
                "",
                "&7There is a &f3-second warmup &7for teleports.",
                "&7Moving cancels the teleport.",
                "&cYou cannot teleport while in combat!"
            ))
        ))
    )

    // Slots for row 1, row 2, and row 3
    private val row1Slots = listOf(10, 11, 12, 13, 14, 15, 16)
    private val row2Slots = listOf(19, 20, 21, 22, 23, 24, 25)
    private val row3Slots = listOf(28, 29, 30, 31, 32, 33, 34)

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED))
            return true
        }

        openMainMenu(sender)
        return true
    }

    private fun isStaff(player: Player): Boolean =
        player.isOp ||
                player.hasPermission("joshymc.admin") ||
                player.hasPermission("joshymc.admin.moderate") ||
                player.hasPermission("joshymc.admin.helper")

    private fun visibleEntries(player: Player, category: Category): List<HelpEntry> {
        val staff = isStaff(player)
        return category.entries.filter { !it.staffOnly || staff }
    }

    private val legacy = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand()

    private fun openMainMenu(player: Player) {
        val gui = CustomGui(
            title = Component.text("JoshyMC Help", NamedTextColor.AQUA)
                .decoration(TextDecoration.BOLD, true)
                .decoration(TextDecoration.ITALIC, false),
            size = 54
        )

        val blackPane = createPane(Material.BLACK_STAINED_GLASS_PANE)
        val cyanPane = createPane(Material.CYAN_STAINED_GLASS_PANE)

        // Fill all slots with black pane
        gui.fill(blackPane)

        // Border top and bottom rows with cyan pane
        for (i in 0 until 9) {
            gui.setItem(i, cyanPane)
            gui.setItem(45 + i, cyanPane)
        }

        // Place command categories (rows 1-2). Hide categories with no visible entries.
        val allSlots = row1Slots + row2Slots
        val visibleCategories = categories.filter { visibleEntries(player, it).isNotEmpty() }
        for ((index, category) in visibleCategories.withIndex()) {
            if (index >= allSlots.size) break
            val slot = allSlots[index]
            val entries = visibleEntries(player, category)
            val item = createCategoryItem(category, entries)
            gui.setItem(slot, item) { p, _ ->
                openCategoryMenu(p, category)
            }
        }

        // Separator
        val goldPane = createPane(Material.GOLD_NUGGET)
        for (i in 0 until 9) {
            gui.setItem(36 + i, goldPane)
        }

        // Feature guide label
        val guideLabel = ItemStack(Material.BOOK).apply {
            editMeta { meta ->
                meta.displayName(Component.text("Feature Guides", NamedTextColor.GOLD)
                    .decoration(TextDecoration.BOLD, true)
                    .decoration(TextDecoration.ITALIC, false))
                meta.lore(listOf(
                    Component.empty(),
                    Component.text("  Learn how the server's features work!", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                    Component.empty()
                ))
            }
        }
        gui.setItem(36, guideLabel)

        // Place feature guides (row 4 — bottom area, slots 37-43)
        val guideSlots = listOf(37, 38, 39, 40, 41, 42, 43)
        for ((index, guide) in featureGuides.withIndex()) {
            if (index >= guideSlots.size) break
            val slot = guideSlots[index]

            val item = ItemStack(guide.icon).apply {
                editMeta { meta ->
                    meta.displayName(Component.text(guide.title, guide.color)
                        .decoration(TextDecoration.BOLD, true)
                        .decoration(TextDecoration.ITALIC, false))
                    meta.lore(listOf(
                        Component.empty(),
                        Component.text("  ${guide.description}", NamedTextColor.GRAY)
                            .decoration(TextDecoration.ITALIC, false),
                        Component.empty(),
                        Component.text("  Click to learn more!", NamedTextColor.YELLOW)
                            .decoration(TextDecoration.ITALIC, false),
                        Component.empty()
                    ))
                }
            }

            val guideRef = guide
            gui.setItem(slot, item) { p, _ ->
                p.playSound(p.location, Sound.UI_BUTTON_CLICK, 0.5f, 1.0f)
                openGuide(p, guideRef)
            }
        }

        plugin.guiManager.open(player, gui)
    }

    // ── Feature Guide GUI ─────────────────────────────
    private fun openGuide(player: Player, guide: GuideEntry) {
        val size = if (guide.pages.size <= 7) 27 else 54
        val gui = CustomGui(
            title = Component.text(guide.title, guide.color)
                .decoration(TextDecoration.BOLD, true)
                .decoration(TextDecoration.ITALIC, false),
            size = size
        )

        val filler = createPane(Material.BLACK_STAINED_GLASS_PANE)
        gui.fill(filler)
        val borderPane = ItemStack(Material.CYAN_STAINED_GLASS_PANE).apply {
            editMeta { it.displayName(Component.empty()) }
        }
        gui.border(borderPane)

        // Place guide pages in center slots
        val contentSlots = if (size == 27) {
            listOf(10, 11, 12, 13, 14, 15, 16)
        } else {
            listOf(10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25)
        }

        for ((index, page) in guide.pages.withIndex()) {
            if (index >= contentSlots.size) break
            val slot = contentSlots[index]

            val lore = mutableListOf<Component>()
            lore.add(Component.empty())
            for (line in page.lines) {
                lore.add(legacy.deserialize(line).decoration(TextDecoration.ITALIC, false))
            }
            lore.add(Component.empty())

            val item = ItemStack(page.icon).apply {
                editMeta { meta ->
                    meta.displayName(legacy.deserialize("&6&l${page.title}")
                        .decoration(TextDecoration.ITALIC, false))
                    meta.lore(lore)
                }
            }
            gui.setItem(slot, item)
        }

        // Back button
        val backSlot = if (size == 27) 22 else 49
        val backItem = ItemStack(Material.ARROW).apply {
            editMeta { meta ->
                meta.displayName(Component.text("Back", NamedTextColor.WHITE)
                    .decoration(TextDecoration.ITALIC, false)
                    .decoration(TextDecoration.BOLD, true))
            }
        }
        gui.setItem(backSlot, backItem) { p, _ ->
            p.playSound(p.location, Sound.UI_BUTTON_CLICK, 0.5f, 1.0f)
            openMainMenu(p)
        }

        plugin.guiManager.open(player, gui)
        player.playSound(player.location, Sound.ITEM_BOOK_PAGE_TURN, 0.7f, 1.0f)
    }

    private fun openCategoryMenu(player: Player, category: Category) {
        val gui = CustomGui(
            title = Component.text(category.name, category.color)
                .decoration(TextDecoration.BOLD, true)
                .decoration(TextDecoration.ITALIC, false),
            size = 54
        )

        val blackPane = createPane(Material.BLACK_STAINED_GLASS_PANE)
        val cyanPane = createPane(Material.CYAN_STAINED_GLASS_PANE)

        // Fill all slots with black pane
        gui.fill(blackPane)

        // Border top and bottom rows with cyan pane
        for (i in 0 until 9) {
            gui.setItem(i, cyanPane)
            gui.setItem(45 + i, cyanPane)
        }

        // Place command entries starting at slot 10 — only entries the player can see
        val contentSlots = (9 until 45).filter { it % 9 != 0 && it % 9 != 8 }
        val entries = visibleEntries(player, category)
        for ((index, entry) in entries.withIndex()) {
            if (index >= contentSlots.size) break
            val slot = contentSlots[index]
            val item = createCommandItem(entry)
            gui.setItem(slot, item)
        }

        // Back button at slot 49
        val backItem = ItemStack(Material.BARRIER).apply {
            editMeta { meta ->
                meta.displayName(
                    Component.text("Back", NamedTextColor.RED)
                        .decoration(TextDecoration.ITALIC, false)
                        .decoration(TextDecoration.BOLD, true)
                )
            }
        }
        gui.setItem(49, backItem) { p, _ ->
            openMainMenu(p)
        }

        plugin.guiManager.open(player, gui)
    }

    private fun createPane(material: Material): ItemStack {
        return ItemStack(material).apply {
            editMeta { meta ->
                meta.displayName(Component.empty())
            }
        }
    }

    private fun createCategoryItem(category: Category, entries: List<HelpEntry> = category.entries): ItemStack {
        return ItemStack(category.icon).apply {
            editMeta { meta ->
                meta.displayName(
                    Component.text(category.name, category.color)
                        .decoration(TextDecoration.ITALIC, false)
                        .decoration(TextDecoration.BOLD, true)
                )

                val lore = mutableListOf<Component>()
                lore.add(Component.empty())
                lore.add(
                    Component.text("Commands:", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false)
                )
                for (entry in entries) {
                    lore.add(
                        Component.text(" ${entry.command}", NamedTextColor.YELLOW)
                            .decoration(TextDecoration.ITALIC, false)
                    )
                }
                lore.add(Component.empty())
                lore.add(
                    Component.text("Click to view details", NamedTextColor.DARK_GRAY)
                        .decoration(TextDecoration.ITALIC, false)
                )
                meta.lore(lore)
            }
        }
    }

    private fun createCommandItem(entry: HelpEntry): ItemStack {
        return ItemStack(Material.PAPER).apply {
            editMeta { meta ->
                meta.displayName(
                    Component.text(entry.command, NamedTextColor.YELLOW)
                        .decoration(TextDecoration.ITALIC, false)
                )

                val lore = mutableListOf<Component>()
                lore.add(Component.empty())
                lore.add(
                    Component.text(entry.description, NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false)
                )
                lore.add(
                    Component.text(
                        if (entry.staffOnly) "Staff only" else "Everyone",
                        NamedTextColor.DARK_GRAY
                    ).decoration(TextDecoration.ITALIC, false)
                )
                meta.lore(lore)
            }
        }
    }
}
