"""Green Link Pop: the shared Link Pop jewel ring (art/items/_link_pop.py) in emerald green
with lime highlights. A retextured Cookie (issue #1207)."""
from __future__ import annotations

from art.items._link_pop import COUNTERPART, KIND, make  # noqa: F401

ID = "link_pop_green"
NAME = "Green Link Pop"

# Candy, darkest -> lightest.
CANDY = ["#032a1a", "#06422a", "#0a5e36", "#0f7d3f", "#199d43", "#33bb45", "#63d64a", "#a2ec6a", "#dafbaa"]

textures, models = make(CANDY)
