"""Level 4 Lootbox (Sapphire Blue): the shared Lucky Block (art/items/_lootbox.py) in
sapphire blue. The look of the Level 4 Lootbox item (issue #1603)."""
from __future__ import annotations

from art.items._lootbox import COUNTERPART, KIND, make  # noqa: F401

ID = "lootbox_level_4"
NAME = "Level 4 Lootbox"

textures, models = make(4, base="#2f62f0", trim="#f0c24f", glyph="#eaf2ff", gem="#7fc0ff")
