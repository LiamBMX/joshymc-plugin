"""Orange Link Pop: the shared Link Pop jewel ring (art/items/_link_pop.py) in warm orange
with amber highlights. A retextured Cookie (issue #1207)."""
from __future__ import annotations

from art.items._link_pop import COUNTERPART, KIND, make  # noqa: F401

ID = "link_pop_orange"
NAME = "Orange Link Pop"

# Candy, darkest -> lightest.
CANDY = ["#4a1203", "#722006", "#9c320a", "#c4480d", "#e66211", "#f88022", "#ffa03d", "#ffc46e", "#ffe6b0"]

textures, models = make(CANDY)
