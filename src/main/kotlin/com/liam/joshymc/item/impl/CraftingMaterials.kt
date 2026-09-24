package com.liam.joshymc.item.impl

// Custom crafting materials are admin-granted only (via /joshymc give) — they must
// never enter the economy through /sell or the Sell Wand just because they share a
// vanilla Material with a sellable item. No crafting materials are currently
// registered; add new item ids here as they're introduced.
val CRAFTING_MATERIAL_IDS = emptySet<String>()
