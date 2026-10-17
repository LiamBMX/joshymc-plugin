"""Purple Link Pop: the shared Link Pop jewel ring (art/items/_link_pop.py) in violet with
lavender highlights. A retextured Cookie (issue #1207)."""
from __future__ import annotations

from art.items._link_pop import COUNTERPART, KIND, make  # noqa: F401

ID = "link_pop_purple"
NAME = "Purple Link Pop"

# Candy, darkest -> lightest.
CANDY = ["#1e0838", "#320d57", "#4a1479", "#631d9a", "#7e2db8", "#9a47d1", "#b56ee3", "#d19ef0", "#ecd2fa"]

textures, models = make(CANDY)
