"""Checks for the placed crate chest models (issue #1389). Run from resourcepack/:

    python3 -m unittest discover -s art/tests -t .

The plugin draws each model with an ItemDisplay over the real crate block (a vanilla chest),
so every model must hide that chest completely and stay inside its block so crates placed
side by side never overlap. They also read the committed pack, so they fail when a module
changed and the pack wasn't rebuilt, and when the bundled config points at a model the pack
doesn't have.
"""
from __future__ import annotations

import importlib
import json
import re
import unittest
from pathlib import Path

from art import check, kit
from art.items import _crate_chest as chest

PACK = Path(__file__).resolve().parents[2]
REPO = PACK.parent
ITEMS = PACK / "assets" / kit.NAMESPACE / "items"
CONFIG = REPO / "src" / "main" / "resources" / "config.yml"

# issue #1464: the first monthly models (January-March); issue #1482: April-June; issue #1495: July-September;
# issue #1497: October-December
MONTHLY = ("january_chest", "february_chest", "march_chest", "april_chest", "may_chest", "june_chest",
           "july_chest", "august_chest", "september_chest", "october_chest", "november_chest", "december_chest")
CRATES = ("cabin_crate", "homestead_crate", "campfire_crate", "harvest_chest", "stockpile_chest",
          "homestead_chest", "campfire_chest", "cabin_chest") + MONTHLY
# the crates redesign (issues #1447, #1454): models that must look different from one another
REDESIGN = ("harvest_chest", "stockpile_chest", "homestead_chest", "campfire_chest", "cabin_chest")
# issue #1454: crate id -> model the bundled config uses, and the legacy model kept for rollback
REDESIGNED_1454 = {"homestead": ("homestead_chest", "homestead_crate"), "campfire": ("campfire_chest", "campfire_crate"),
                   "cabin": ("cabin_chest", "cabin_crate")}


class CrateChestModelsTest(unittest.TestCase):

    def _main(self, item_id: str) -> list[dict]:
        module = importlib.import_module(f"art.items.{item_id}")
        self.assertEqual(module.KIND, "furniture")
        return module.models()["main"]["elements"]

    def test_modules_pass_art_check(self):
        for item_id in CRATES:
            errors, _, _ = check.check(item_id)
            self.assertEqual(errors, [], item_id)

    def test_models_hide_a_vanilla_chest(self):
        for item_id in CRATES:
            elements = self._main(item_id)
            self.assertTrue(chest.covers_vanilla_chest(elements), item_id)
            body, lid = elements[0], elements[1]
            # the body/lid footprint encloses the chest's x/z 1..15 and its top at y 14
            self.assertLessEqual(body["from"][2], 1, item_id)
            self.assertGreaterEqual(body["to"][2], 15, item_id)
            self.assertGreaterEqual(lid["to"][1], 14, item_id)
        # the lock plate covers the vanilla latch (x 7..9, y 7..11, out to z 16)
        self.assertGreaterEqual(chest.PLATE_FRONT, 16.0)
        self.assertLessEqual(chest.SEAM - 3.6, 7.0)
        self.assertGreaterEqual(chest.SEAM + 0.6, 11.0 - 1.5)

    def test_models_stay_inside_their_block(self):
        for item_id in CRATES:
            self.assertTrue(chest.within_block(self._main(item_id)), item_id)

    def test_models_stand_on_the_floor(self):
        for item_id in CRATES:
            lo, hi = kit.bounds(self._main(item_id))
            self.assertGreaterEqual(lo[1], -0.01, item_id)
            self.assertLessEqual(hi[1], 26.0, item_id)      # well under the crate's idle particles

    def test_glow_only_on_accents(self):
        for item_id in CRATES:
            elements = self._main(item_id)
            glowing = [e for e in elements if e.get("light_emission")]
            self.assertTrue(glowing, item_id)
            self.assertLess(len(glowing), len(elements) // 3, item_id)
            self.assertNotIn("light_emission", elements[0], item_id)
            self.assertNotIn("light_emission", elements[1], item_id)

    def test_built_item_definitions_exist(self):
        for item_id in CRATES:
            path = ITEMS / f"{item_id}.json"
            self.assertTrue(path.exists(), f"{path} missing: run python3 -m art.build {item_id}")
            json.loads(path.read_text())

    def test_redesign_models_are_distinct(self):
        """The redesign chests share the skeleton but no texture, and no two have the same
        silhouette height (vault, stepped lid, crown, horns, cottage lid)."""
        textures = PACK / "assets" / kit.NAMESPACE / "textures" / "item"
        painted = {i: {p.read_bytes() for p in (textures / i).glob("*.png")} for i in REDESIGN}
        tops = {i: kit.bounds(self._main(i))[1][1] for i in REDESIGN}
        for n, a in enumerate(REDESIGN):
            self.assertTrue(painted[a], f"run python3 -m art.build {a}")
            for b in REDESIGN[n + 1:]:
                self.assertFalse(painted[a] & painted[b], f"{a} and {b} share a texture")
                self.assertGreater(abs(tops[a] - tops[b]), 0.5, f"{a} and {b} are the same height")

    def test_monthly_models_are_distinct(self):
        """The monthly chests share no texture with each other or with the permanent chests,
        and each has its own silhouette height (crystal spire, bow, standing clover, umbrella,
        sunflowers, straw hat and palm, rockets, sunset vault, wheat bundle, pumpkin stem, lantern,
        star-topped tree)."""
        textures = PACK / "assets" / kit.NAMESPACE / "textures" / "item"
        painted = {i: {p.read_bytes() for p in (textures / i).glob("*.png")} for i in MONTHLY + REDESIGN}
        tops = {i: kit.bounds(self._main(i))[1][1] for i in MONTHLY}
        for n, a in enumerate(MONTHLY):
            self.assertTrue(painted[a], f"run python3 -m art.build {a}")
            for b in MONTHLY[n + 1:] + REDESIGN:
                self.assertFalse(painted[a] & painted[b], f"{a} and {b} share a texture")
            for b in MONTHLY[n + 1:]:
                self.assertGreater(abs(tops[a] - tops[b]), 0.5, f"{a} and {b} are the same height")

    def test_bundled_config_maps_built_monthly_models(self):
        """config.yml maps all twelve months to the models this pack now has."""
        text = CONFIG.read_text()
        section = text[text.index("    monthly:"):]
        for item_id in MONTHLY:
            month = item_id.split("_")[0]
            self.assertIn(f'        {month}: "joshymc:{item_id}"', section)
            self.assertTrue((ITEMS / f"{item_id}.json").exists(), item_id)

    def test_1454_models_replace_the_legacy_ones_without_removing_them(self):
        """Homestead, Campfire and Cabin got new models; the old ones stay built for rollback,
        and the new ones share no texture with the old ones (not recolours)."""
        textures = PACK / "assets" / kit.NAMESPACE / "textures" / "item"
        for crate, (new, old) in REDESIGNED_1454.items():
            self.assertTrue((ITEMS / f"{old}.json").exists(), old)
            self.assertTrue((ITEMS / f"{new}.json").exists(), new)
            a = {p.read_bytes() for p in (textures / new).glob("*.png")}
            b = {p.read_bytes() for p in (textures / old).glob("*.png")}
            self.assertFalse(a & b, f"{new} reuses a texture of {old}")

    def test_bundled_config_maps_1454_crates(self):
        text = CONFIG.read_text()
        section = text[text.index("  custom-models:"):].split("\n\n")[0]
        for crate, (new, old) in REDESIGNED_1454.items():
            entry = section[section.index(f"      {crate}:\n"):]
            model_line = next(line for line in entry.splitlines() if line.strip().startswith("model:"))
            self.assertIn(f'"joshymc:{new}"', model_line, crate)

    def test_bundled_config_maps_redesign_crates(self):
        text = CONFIG.read_text()
        section = text[text.index("  custom-models:"):].split("\n\n")[0]
        for crate, model in (("harvest", "harvest_chest"), ("stockpile", "stockpile_chest")):
            self.assertIn(f"      {crate}:\n", section)
            self.assertIn(f'model: "joshymc:{model}"', section)

    def test_bundled_config_points_at_built_models(self):
        text = CONFIG.read_text()
        section = text[text.index("  custom-models:"):]
        models = re.findall(r'model:\s*"?([a-z0-9_.-]+:[a-z0-9_./-]+)"?', section.split("\n\n")[0])
        self.assertTrue(models)
        for key in models:
            ns, path = key.split(":", 1)
            self.assertTrue((PACK / "assets" / ns / "items" / f"{path}.json").exists(), key)


if __name__ == "__main__":
    unittest.main()
