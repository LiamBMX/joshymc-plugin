"""Tests for the Christmas Tree Elytra (issue #1397). Run from resourcepack/:

    python3 -m unittest discover -s art/tests -t .

They check the art module, the built pack files it must ship with and the Kotlin item
that points at them, so a rename on either side or a stale build fails here.
"""
from __future__ import annotations

import json
import re
import unittest
from pathlib import Path

from PIL import Image

from art import check, kit
from art.items import christmas_tree_elytra as tree

ID = "christmas_tree_elytra"
PACK = Path(__file__).resolve().parents[2]
REPO = PACK.parent
ASSETS = PACK / "assets" / kit.NAMESPACE
KOTLIN = REPO / "src" / "main" / "kotlin" / "com" / "liam" / "joshymc"
ITEM_SOURCE = KOTLIN / "item" / "impl" / "ChristmasTreeElytra.kt"


def _texture_file(ref: str) -> Path:
    ns, path = ref.split(":", 1) if ":" in ref else ("minecraft", ref)
    return PACK / "assets" / ns / "textures" / f"{path}.png"


class ArtModule(unittest.TestCase):
    def test_check_passes(self):
        errors, _, _ = check.check(ID)
        self.assertEqual(errors, [])

    def test_is_an_elytra_with_a_broken_model(self):
        self.assertEqual(tree.ID, ID)
        self.assertEqual(tree.KIND, "elytra")
        self.assertEqual(set(tree.models()), {"main", "broken"})

    def test_worn_wing_is_cut_into_a_tree_on_the_folded_seam(self):
        _, mask = tree.paint_wing_face()
        frame = tree.WORN_FRAME
        # the star sits on the seam at the top
        p, q, _ = tree.WORN_STAR
        u, v = frame.fwd(p, q)
        self.assertTrue(mask[int(v), int(u)])
        # beside the pointed top, well outside the top tier, the wing is cut away
        top = tree.WORN_TIERS[0]
        u, v = frame.fwd(top.width_at(top.top + 2) + 6, top.top + 2)
        self.assertFalse(mask[int(v), int(u)])
        # the bottom tier reaches far out (the tree widens downward)
        low = tree.WORN_TIERS[-1]
        q = low.top + (low.bottom - low.top) * 0.7
        u, v = frame.fwd(low.width_at(q) * 0.9, q)
        self.assertTrue(mask[min(int(v), tree.FACE_H - 1), max(int(u), 0)])

    def test_decorations_use_the_brief_palette(self):
        self.assertEqual(set(tree.BAUBLE_PALETTES), {"red", "blue", "purple", "gold"})
        used = {colour for *_, colour in tree.WORN_BAUBLES} | {colour for *_, colour in tree.ITEM_BAUBLES}
        self.assertEqual(used, {"red", "blue", "purple", "gold"})

    def test_item_model_is_symmetric(self):
        elements = tree.models()["main"]["elements"]
        lo, hi = kit.bounds(elements)
        self.assertAlmostEqual((lo[0] + hi[0]) / 2, 8.0, places=2)
        xs = sorted(e["from"][0] + e["to"][0] for e in elements)
        mirrored = sorted(16 - x + 16 for x in xs)
        for a, b in zip(xs, mirrored):
            self.assertAlmostEqual(a, b, delta=0.05)

    def test_only_the_lights_and_star_glow(self):
        for e in tree.models()["main"]["elements"]:
            if "light_emission" in e:
                textures = {f["texture"] for f in e["faces"].values()}
                self.assertTrue(textures <= {"#star", "#bulb_a", "#bulb_b", "#bulb_c"}, textures)
        for e in tree.models()["broken"]["elements"]:
            self.assertNotIn("light_emission", e)


class BuiltPack(unittest.TestCase):
    def test_item_definition_and_models_exist(self):
        definition = json.loads((ASSETS / "items" / f"{ID}.json").read_text(encoding="utf-8"))
        text = json.dumps(definition)
        self.assertIn(f"joshymc:item/{ID}/main", text)
        self.assertIn(f"joshymc:item/{ID}/broken", text)
        for name in ("main", "broken"):
            model = json.loads((ASSETS / "models" / "item" / ID / f"{name}.json").read_text(encoding="utf-8"))
            for ref in model["textures"].values():
                self.assertTrue(_texture_file(ref).exists(), ref)

    def test_equipment_asset_has_wings_and_harness(self):
        asset = json.loads((ASSETS / "equipment" / f"{ID}.json").read_text(encoding="utf-8"))
        self.assertEqual(set(asset["layers"]), {"wings", "humanoid"})
        for layer, entries in asset["layers"].items():
            self.assertEqual(entries, [{"texture": f"joshymc:{ID}"}])
            png = ASSETS / "textures" / "entity" / "equipment" / layer / f"{ID}.png"
            with Image.open(png) as image:
                self.assertEqual(image.size, (256, 128))

    def test_vanilla_elytra_is_untouched(self):
        self.assertFalse((PACK / "assets" / "minecraft" / "equipment" / "elytra.json").exists())
        self.assertFalse((PACK / "assets" / "minecraft" / "items" / "elytra.json").exists())


class KotlinItem(unittest.TestCase):
    def setUp(self):
        self.source = ITEM_SOURCE.read_text(encoding="utf-8")

    def test_points_at_the_built_assets(self):
        self.assertIn(f'override val id = "{ID}"', self.source)
        self.assertIn("Material.ELYTRA", self.source)
        self.assertIn("setItemModel(NamespacedKey(Joshymc.instance, id))", self.source)
        self.assertIn("equippable.model = NamespacedKey(Joshymc.instance, id)", self.source)

    def test_keeps_vanilla_elytra_behaviour(self):
        self.assertNotIn("isUnbreakable", self.source)
        self.assertIn("EquipmentSlot.CHEST", self.source)
        self.assertIn("ITEM_ARMOR_EQUIP_ELYTRA", self.source)
        self.assertIn("isDamageOnHurt = false", self.source)
        self.assertIn("GamemodeScope.SURVIVAL", self.source)

    def test_registered_once_and_not_handed_out_anywhere(self):
        manager = (KOTLIN / "manager" / "ItemManager.kt").read_text(encoding="utf-8")
        self.assertEqual(manager.count("register(ChristmasTreeElytra())"), 1)
        ids = []
        for path in KOTLIN.rglob("*.kt"):
            ids += re.findall(r'override val id\s*=\s*"([a-z0-9_]+)"', path.read_text(encoding="utf-8"))
        self.assertEqual(ids.count(ID), 1)
        # only /joshymc give hands it out: no crate, kit, shop, quest or store config names it
        resources = REPO / "src" / "main" / "resources"
        for path in resources.rglob("*.yml"):
            self.assertNotIn(ID, path.read_text(encoding="utf-8"), path.name)
        mentions = [p.name for p in KOTLIN.rglob("*.kt") if ID in p.read_text(encoding="utf-8")
                    or "ChristmasTreeElytra" in p.read_text(encoding="utf-8")]
        self.assertEqual(sorted(mentions), ["ChristmasTreeElytra.kt", "ItemManager.kt"])


if __name__ == "__main__":
    unittest.main()
