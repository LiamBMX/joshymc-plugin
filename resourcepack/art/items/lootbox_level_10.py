"""Level 10 Lootbox (Prismatic Rainbow): the shared Lucky Block (art/items/_lootbox.py) in
prismatic rainbow. The look of the Level 10 Lootbox item (issue #1603)."""
from __future__ import annotations

from art.items._lootbox import COUNTERPART, KIND, make  # noqa: F401

ID = "lootbox_level_10"
NAME = "Level 10 Lootbox"

textures, models = make(10, base="#ff4040", trim="#ffffff", glyph="#ffffff", gem="#ffffff")
