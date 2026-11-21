"""Level 3 Lootbox (Cyan): the shared Lucky Block (art/items/_lootbox.py) in
cyan. The look of the Level 3 Lootbox item (issue #1603)."""
from __future__ import annotations

from art.items._lootbox import COUNTERPART, KIND, make  # noqa: F401

ID = "lootbox_level_3"
NAME = "Level 3 Lootbox"

textures, models = make(3, base="#2ee4f0", trim="#e4ebf0", glyph="#e0fdff", gem="#a4fbff")
