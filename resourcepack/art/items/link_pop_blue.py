"""Blue Link Pop: the shared Link Pop jewel ring (art/items/_link_pop.py) in deep sapphire
blue with cyan highlights. A retextured Cookie (issue #1207)."""
from __future__ import annotations

from art.items._link_pop import COUNTERPART, KIND, make  # noqa: F401

ID = "link_pop_blue"
NAME = "Blue Link Pop"

# Candy, darkest -> lightest.
CANDY = ["#08113f", "#0c1d6b", "#10309a", "#1447c4", "#1b63e2", "#2a85f2", "#43aaf8", "#7fd2fc", "#c4f0ff"]

textures, models = make(CANDY)
