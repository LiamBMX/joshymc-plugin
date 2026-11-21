"""Level 7 Lootbox (Crimson Red): the shared Lucky Block (art/items/_lootbox.py) in
crimson red. The look of the Level 7 Lootbox item (issue #1603)."""
from __future__ import annotations

from art.items._lootbox import COUNTERPART, KIND, make  # noqa: F401

ID = "lootbox_level_7"
NAME = "Level 7 Lootbox"

textures, models = make(7, base="#d01c30", trim="#f2c450", glyph="#fff0c8", gem="#ff2050")
