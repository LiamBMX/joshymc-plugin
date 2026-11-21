"""Level 8 Lootbox (Inferno Orange): the shared Lucky Block (art/items/_lootbox.py) in
inferno orange. The look of the Level 8 Lootbox item (issue #1603)."""
from __future__ import annotations

from art.items._lootbox import COUNTERPART, KIND, make  # noqa: F401

ID = "lootbox_level_8"
NAME = "Level 8 Lootbox"

textures, models = make(8, base="#ff7418", trim="#4b3a3a", glyph="#fff3a8", gem="#ffd040")
