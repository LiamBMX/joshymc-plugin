package com.liam.joshymc.lifesteal.hearts

/**
 * Player-facing heart messages (issue #1341), `&` colour codes like the rest of the plugin's
 * message constants. `{victim}`, `{killer}`, `{hearts}`, `{maximum}` and `{time}` are filled in
 * as plain text after the colours are parsed, so a name can never inject formatting.
 */
object HeartMessages {
    const val KILLER_STOLE = "&aYou stole 1 heart from &f{victim}&a! &7(&c{hearts}❤&7)"
    const val VICTIM_LOST = "&cYou lost 1 heart to &f{killer}&c! &7(&c{hearts}❤&7)"

    const val KILLER_VICTIM_LAST_HEART = "&7{victim} is on their last heart, so no heart was stolen."
    const val VICTIM_LAST_HEART = "&7You're on your last heart, so you kept it."

    const val KILLER_COOLDOWN = "&7You stole a heart from {victim} recently. You can steal from them again in {time}."
    const val VICTIM_COOLDOWN = "&7{killer} stole a heart from you recently, so you kept your heart."

    const val KILLER_AT_MAXIMUM = "&7You're at the maximum of {maximum} hearts, so no heart was stolen."
    const val VICTIM_KILLER_AT_MAXIMUM = "&7{killer} is at the maximum hearts, so you kept your heart."

    // Team PvP (issue #1348).
    const val KILLER_TEAMMATE = "&7{victim} is your teammate, so no heart was stolen."
    const val VICTIM_TEAMMATE = "&7{killer} is your teammate, so you kept your heart."
    const val TEAM_UNKNOWN = "&7Team membership couldn't be checked just now, so no heart was stolen."

    // Launch progression (issue #1352).
    const val GRACE_PERIOD = "&7It's the grace period, so no heart was stolen."
    const val NOT_LAUNCHED = "&7Lifesteal hasn't launched yet, so no heart was stolen."
    const val PROGRESSION_UNKNOWN = "&7The launch schedule couldn't be checked just now, so no heart was stolen."

    const val TRANSFER_FAILED = "&cYour hearts couldn't be updated just now, so nothing changed."

    const val ADMIN_CHANGED_TARGET = "&7Your hearts are now &c{hearts}❤&7."

    // Heart items (issue #1344). The six main ones are in lifesteal.yml > heart-items.messages.
    const val NOT_LIFESTEAL_SERVER = "&cHearts only exist on the Lifesteal server."
    const val NOT_IN_LIFESTEAL = "&cHeart items only work in Lifesteal."
    const val PAUSED = "&cHearts are paused right now. Please try again later."
    const val NOT_NOW = "&cYou can't do that right now."
    const val NOT_LOADED = "&cYour hearts haven't loaded yet. Please rejoin and try again."
    const val WITHDRAW_DISABLED = "&cHeart withdrawal is disabled right now."
    const val REDEEM_DISABLED = "&cRedeeming Heart items is disabled right now."
    const val WITHDRAW_FAILED = "&cYour heart couldn't be withdrawn just now, so nothing changed. Please try again."
    const val INVALID_ITEM = "&cThis Heart item isn't valid and can't be redeemed."
    const val ALREADY_REDEEMED = "&cThis Heart item has already been redeemed."
    const val VOIDED_ITEM = "&cThis Heart item was voided by staff and can't be redeemed."
}
