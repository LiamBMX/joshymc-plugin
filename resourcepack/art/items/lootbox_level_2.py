"""Level 2 Lootbox (Emerald Green): the shared Lucky Block (art/items/_lootbox.py) in
emerald green. The look of the Level 2 Lootbox item (issue #1603)."""
from __future__ import annotations

from art.items._lootbox import COUNTERPART, KIND, make  # noqa: F401

ID = "lootbox_level_2"
NAME = "Level 2 Lootbox"

textures, models = make(2, base="#2bc95f", trim="#dfe3e8", glyph="#e6ffd6", gem="#6dffa0")
