"""Level 5 Lootbox (Amethyst Purple): the shared Lucky Block (art/items/_lootbox.py) in
amethyst purple. The look of the Level 5 Lootbox item (issue #1603)."""
from __future__ import annotations

from art.items._lootbox import COUNTERPART, KIND, make  # noqa: F401

ID = "lootbox_level_5"
NAME = "Level 5 Lootbox"

textures, models = make(5, base="#9a4ee8", trim="#f0c24f", glyph="#f6e8ff", gem="#e9a8ff")
