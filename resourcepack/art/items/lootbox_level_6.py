"""Level 6 Lootbox (Neon Pink): the shared Lucky Block (art/items/_lootbox.py) in
neon pink. The look of the Level 6 Lootbox item (issue #1603)."""
from __future__ import annotations

from art.items._lootbox import COUNTERPART, KIND, make  # noqa: F401

ID = "lootbox_level_6"
NAME = "Level 6 Lootbox"

textures, models = make(6, base="#ff3cb4", trim="#f6cf5a", glyph="#ffe6f5", gem="#ff8fd0")
