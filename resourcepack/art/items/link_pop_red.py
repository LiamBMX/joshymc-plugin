"""Red Link Pop: the shared Link Pop jewel ring (art/items/_link_pop.py) in deep cherry red
with ruby highlights. A retextured Cookie (issue #1207)."""
from __future__ import annotations

from art.items._link_pop import COUNTERPART, KIND, make  # noqa: F401

ID = "link_pop_red"
NAME = "Red Link Pop"

# Candy, darkest -> lightest.
CANDY = ["#3a0410", "#5c0716", "#80091c", "#a50f22", "#c8182a", "#e32b36", "#f5525a", "#ff8a8a", "#ffc6c0"]

textures, models = make(CANDY)
