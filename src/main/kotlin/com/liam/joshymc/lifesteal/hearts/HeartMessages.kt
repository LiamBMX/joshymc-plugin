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

    const val TRANSFER_FAILED = "&cYour hearts couldn't be updated just now, so nothing changed."

    const val ADMIN_CHANGED_TARGET = "&7Your hearts are now &c{hearts}❤&7."
}
