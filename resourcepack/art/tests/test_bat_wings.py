"""Tests for the Bat Wings elytra (issue #1317). Run from resourcepack/:

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
from art.items import bat_wings

PACK = Path(__file__).resolve().parents[2]
REPO = PACK.parent
ASSETS = PACK / "assets" / kit.NAMESPACE
KOTLIN = REPO / "src" / "main" / "kotlin" / "com" / "liam" / "joshymc"
ITEM_SOURCE = KOTLIN / "item" / "impl" / "BatWings.kt"


def _texture_file(ref: str) -> Path:
    ns, path = ref.split(":", 1) if ":" in ref else ("minecraft", ref)
    return PACK / "assets" / ns / "textures" / f"{path}.png"


class ArtModule(unittest.TestCase):
    def test_check_passes(self):
        errors, _, _ = check.check("bat_wings")
        self.assertEqual(errors, [])

    def test_is_an_elytra_with_a_broken_model(self):
        self.assertEqual(bat_wings.ID, "bat_wings")
        self.assertEqual(bat_wings.KIND, "elytra")
        self.assertEqual(set(bat_wings.models()), {"main", "broken"})

    def test_worn_wing_is_cut_into_a_bat_outline(self):
        img, mask = bat_wings.paint_wing_face()
        tips = [t for _, t in bat_wings.WORN_FINGERS]
        for (u, v) in tips:                     # every finger ends in a solid point
            self.assertTrue(mask[min(int(v), bat_wings.FACE_H - 1), int(u)], (u, v))
        for a, b in zip(tips, tips[1:]):        # and the hem between two points is cut away
            u, v = (a[0] + b[0]) / 2, (a[1] + b[1]) / 2
            self.assertFalse(mask[int(v), int(u)], (u, v))
        self.assertTrue(mask[2, 1])            # the hinge edge is solid
        self.assertFalse(mask[bat_wings.FACE_H - 1, bat_wings.FACE_W - 1])

    def test_item_model_is_symmetric(self):
        elements = bat_wings.models()["main"]["elements"]
        lo, hi = kit.bounds(elements)
        self.assertAlmostEqual((lo[0] + hi[0]) / 2, 8.0, places=2)
        # Every element's x centre has a partner mirrored about x = 8 (the yoke sits on it).
        xs = sorted(e["from"][0] + e["to"][0] for e in elements)
        mirrored = sorted(32 - x for x in xs)
        for a, b in zip(xs, mirrored):
            self.assertAlmostEqual(a, b, delta=0.05)


class BuiltPack(unittest.TestCase):
    def test_item_definition_and_models_exist(self):
        definition = json.loads((ASSETS / "items" / "bat_wings.json").read_text(encoding="utf-8"))
        text = json.dumps(definition)
        self.assertIn("joshymc:item/bat_wings/main", text)
        self.assertIn("joshymc:item/bat_wings/broken", text)
        for name in ("main", "broken"):
            model = json.loads((ASSETS / "models" / "item" / "bat_wings" / f"{name}.json").read_text(encoding="utf-8"))
            for ref in model["textures"].values():
                self.assertTrue(_texture_file(ref).exists(), ref)

    def test_equipment_asset_has_wings_and_harness(self):
        asset = json.loads((ASSETS / "equipment" / "bat_wings.json").read_text(encoding="utf-8"))
        self.assertEqual(set(asset["layers"]), {"wings", "humanoid"})
        for layer, entries in asset["layers"].items():
            self.assertEqual(entries, [{"texture": "joshymc:bat_wings"}])
            png = ASSETS / "textures" / "entity" / "equipment" / layer / "bat_wings.png"
            with Image.open(png) as image:
                self.assertEqual(image.size, (256, 128))

    def test_vanilla_elytra_is_untouched(self):
        self.assertFalse((PACK / "assets" / "minecraft" / "equipment" / "elytra.json").exists())
        self.assertFalse((PACK / "assets" / "minecraft" / "items" / "elytra.json").exists())


class KotlinItem(unittest.TestCase):
    def setUp(self):
        self.source = ITEM_SOURCE.read_text(encoding="utf-8")

    def test_points_at_the_built_assets(self):
        self.assertIn('override val id = "bat_wings"', self.source)
        self.assertIn("Material.ELYTRA", self.source)
        self.assertIn("setItemModel(NamespacedKey(Joshymc.instance, id))", self.source)
        self.assertIn("equippable.model = NamespacedKey(Joshymc.instance, id)", self.source)

    def test_keeps_vanilla_elytra_behaviour(self):
        self.assertNotIn("isUnbreakable", self.source)          # normal elytra durability
        self.assertIn("EquipmentSlot.CHEST", self.source)
        self.assertIn("ITEM_ARMOR_EQUIP_ELYTRA", self.source)
        self.assertIn("isDamageOnHurt = false", self.source)
        self.assertIn("GamemodeScope.SURVIVAL", self.source)

    def test_registered_once_with_a_unique_id(self):
        manager = (KOTLIN / "manager" / "ItemManager.kt").read_text(encoding="utf-8")
        self.assertEqual(manager.count("register(BatWings())"), 1)
        ids = []
        for path in KOTLIN.rglob("*.kt"):
            ids += re.findall(r'override val id\s*=\s*"([a-z0-9_]+)"', path.read_text(encoding="utf-8"))
        self.assertEqual(ids.count("bat_wings"), 1)


if __name__ == "__main__":
    unittest.main()
