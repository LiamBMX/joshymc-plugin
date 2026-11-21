"""Level 9 Lootbox (Gold): the shared Lucky Block (art/items/_lootbox.py) in
gold. The look of the Level 9 Lootbox item (issue #1603)."""
from __future__ import annotations

from art.items._lootbox import COUNTERPART, KIND, make  # noqa: F401

ID = "lootbox_level_9"
NAME = "Level 9 Lootbox"

textures, models = make(9, base="#ffd432", trim="#8a5a1c", glyph="#fffbe8", gem="#e8fbff")
