"""Level 1 Lootbox (Stone Gray): the shared Lucky Block (art/items/_lootbox.py) in
stone gray. The look of the Level 1 Lootbox item (issue #1603)."""
from __future__ import annotations

from art.items._lootbox import COUNTERPART, KIND, make  # noqa: F401

ID = "lootbox_level_1"
NAME = "Level 1 Lootbox"

textures, models = make(1, base="#8a8e94", trim="#c9ced6", glyph="#dfe4ee", gem="#9aa0a8")
