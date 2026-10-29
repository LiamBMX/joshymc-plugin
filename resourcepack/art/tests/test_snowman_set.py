"""Checks for the Snowman armor set's built assets and Kotlin wiring (issue #1316). Run from
resourcepack/:

    python3 -m unittest discover -s art/tests -t .

They read the committed pack (what art.build wrote), so they fail if a module changed and
the pack wasn't rebuilt, if a model points at a missing texture, or if the Kotlin pieces
drift from the assets they reference.
"""
from __future__ import annotations

import json
import re
import unittest
from pathlib import Path

from PIL import Image

from art import check, kit

PACK = Path(__file__).resolve().parents[2]
ASSETS = PACK / "assets" / kit.NAMESPACE
REPO = PACK.parent
KOTLIN = REPO / "src" / "main" / "kotlin" / "com" / "liam" / "joshymc"
SET_KT = KOTLIN / "item" / "impl" / "SnowmanSet.kt"
ITEM_MANAGER = KOTLIN / "manager" / "ItemManager.kt"
BEDROCK_ICONS = REPO / "src" / "main" / "resources" / "bedrock" / "icons"

# id -> (Kotlin class, base material, equipment slot, worn layer or None for the 3D helmet)
PIECES = {
    "snowman_helmet": ("SnowmanHelmet", "NETHERITE_HELMET", "HEAD", None),
    "snowman_chestplate": ("SnowmanChestplate", "NETHERITE_CHESTPLATE", "CHEST", "humanoid"),
    "snowman_leggings": ("SnowmanLeggings", "NETHERITE_LEGGINGS", "LEGS", "humanoid_leggings"),
    "snowman_boots": ("SnowmanBoots", "NETHERITE_BOOTS", "FEET", "humanoid"),
}


def _models(node) -> list[str]:
    """Every model reference in an item definition tree."""
    out = []
    if isinstance(node, dict):
        if node.get("type") == "minecraft:model":
            out.append(node["model"])
        for value in node.values():
            out += _models(value)
    elif isinstance(node, list):
        for value in node:
            out += _models(value)
    return out


def _texture_path(ref: str) -> Path:
    ns, path = ref.split(":", 1)
    return PACK / "assets" / ns / "textures" / f"{path}.png"


class SnowmanAssetsTest(unittest.TestCase):

    def test_modules_pass_art_check(self):
        for item_id in PIECES:
            errors, _, _ = check.check(item_id)
            self.assertEqual(errors, [], f"{item_id}: {errors}")

    def test_item_definitions_resolve_to_models_and_textures(self):
        for item_id in PIECES:
            definition = json.loads((ASSETS / "items" / f"{item_id}.json").read_text(encoding="utf-8"))
            refs = _models(definition["model"])
            self.assertTrue(refs, item_id)
            for ref in refs:
                ns, path = ref.split(":", 1)
                self.assertEqual(ns, kit.NAMESPACE)
                self.assertTrue(path.startswith(f"item/{item_id}/"), f"{item_id} borrows {ref}")
                model = json.loads((PACK / "assets" / ns / "models" / f"{path}.json").read_text(encoding="utf-8"))
                self.assertGreaterEqual(len(model.get("elements", [])), 60, f"{ref} is not a real 3D model")
                textures = model["textures"]
                for element in model["elements"]:
                    for face in element["faces"].values():
                        self.assertIn(face["texture"][1:], textures, f"{ref}: unbound {face['texture']}")
                for tex in textures.values():
                    self.assertTrue(_texture_path(tex).is_file(), f"{ref}: missing texture {tex}")
                    self.assertTrue(tex.startswith(f"{kit.NAMESPACE}:item/{item_id}/"), f"{ref}: borrows {tex}")

    def test_animated_textures_have_valid_strips(self):
        for item_id in PIECES:
            folder = ASSETS / "textures" / "item" / item_id
            metas = list(folder.glob("*.png.mcmeta"))
            self.assertTrue(metas, f"{item_id} has no animated texture")
            for meta in metas:
                json.loads(meta.read_text(encoding="utf-8"))["animation"]
                with Image.open(meta.with_suffix("")) as image:
                    w, h = image.size
                self.assertIn(w, kit.ANIMATION_SIZES)
                self.assertEqual(h % w, 0)

    def test_worn_layers(self):
        for item_id, (_, _, _, layer) in PIECES.items():
            equipment = ASSETS / "equipment" / f"{item_id}.json"
            if layer is None:
                # the helmet is drawn from its 3D item model on the head
                self.assertFalse(equipment.exists(), f"{item_id} must not have an equipment asset")
                continue
            data = json.loads(equipment.read_text(encoding="utf-8"))
            self.assertEqual(list(data["layers"]), [layer])
            self.assertEqual(data["layers"][layer], [{"texture": f"{kit.NAMESPACE}:{item_id}"}])
            png = ASSETS / "textures" / "entity" / "equipment" / layer / f"{item_id}.png"
            with Image.open(png) as image:
                self.assertEqual(image.size, (256, 128), f"{png.name} is not the 4x humanoid layout")
                self.assertIsNotNone(image.getbbox(), f"{png.name} is empty")

    def test_bedrock_icons_exist(self):
        for item_id in PIECES:
            self.assertTrue((BEDROCK_ICONS / kit.NAMESPACE / f"{item_id}.png").is_file(), item_id)


class SnowmanKotlinTest(unittest.TestCase):

    def test_pieces_match_assets(self):
        source = SET_KT.read_text(encoding="utf-8")
        for item_id, (cls, material, slot, _) in PIECES.items():
            pattern = (rf'class {cls}\s*:\s*SnowmanPiece\("{item_id}",\s*Material\.{material},'
                       rf'\s*"[^"]+",\s*EquipmentSlot\.{slot}\)')
            self.assertRegex(source, pattern, f"{cls} wiring")
        self.assertIn("GamemodeScope.SURVIVAL", source)
        self.assertIn("isUnbreakable = true", source)

    def test_pieces_registered_once(self):
        manager = ITEM_MANAGER.read_text(encoding="utf-8")
        for cls, *_ in PIECES.values():
            self.assertEqual(manager.count(f"register({cls}())"), 1, cls)

    def test_ids_are_unique(self):
        literals = []
        for path in (KOTLIN / "item").rglob("*.kt"):
            literals += re.findall(r'"(snowman_[a-z_]+)"', path.read_text(encoding="utf-8"))
        for item_id in PIECES:
            self.assertEqual(literals.count(item_id), 1, f"{item_id} is declared {literals.count(item_id)} times")


if __name__ == "__main__":
    unittest.main()
