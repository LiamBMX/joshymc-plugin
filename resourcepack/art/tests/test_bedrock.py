"""Tests for art.bedrock (issue #1314). Run from resourcepack/:

    python3 -m unittest discover -s art/tests -t .
"""
from __future__ import annotations

import json
import re
import tempfile
import unittest
from pathlib import Path

from PIL import Image

from art import bedrock

REPO = bedrock.ROOT.parent


def _write(path: Path, data) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    if isinstance(data, Image.Image):
        data.save(path)
    else:
        path.write_text(json.dumps(data), encoding="utf-8")


def _sprite_item(assets: Path, key: str, colour=(200, 40, 40, 255)) -> None:
    """A flat item: definition -> item/generated model -> 16x16 texture."""
    _write(assets / "joshymc" / "items" / f"{key}.json",
           {"model": {"type": "minecraft:model", "model": f"joshymc:item/{key}/main"}})
    _write(assets / "joshymc" / "models" / "item" / key / "main.json",
           {"parent": "minecraft:item/generated", "textures": {"layer0": f"joshymc:item/{key}/main"}})
    image = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    for x in range(4, 12):
        for y in range(4, 12):
            image.putpixel((x, y), colour)
    _write(assets / "joshymc" / "textures" / "item" / key / "main.png", image)


class Names(unittest.TestCase):
    def test_bedrock_name(self):
        self.assertEqual(bedrock.bedrock_name("joshymc:frostbite"), "frostbite")
        self.assertEqual(bedrock.bedrock_name("joshymc:fish/cod"), "fish_cod")
        self.assertEqual(bedrock.bedrock_name("other:Thing-1"), "other__thing_1")


class GuiModel(unittest.TestCase):
    def ref(self, name):
        return {"type": "minecraft:model", "model": name}

    def test_display_context_gui_case_wins(self):
        tree = {"type": "minecraft:select", "property": "minecraft:display_context",
                "cases": [{"when": ["gui"], "model": self.ref("x:gui")}], "fallback": self.ref("x:main")}
        self.assertEqual(bedrock.gui_model_ref(tree), "x:gui")

    def test_state_trees_use_the_resting_model(self):
        bow = {"type": "minecraft:condition", "property": "minecraft:using_item", "on_false": self.ref("x:idle"),
               "on_true": {"type": "minecraft:range_dispatch", "fallback": self.ref("x:pull_0"), "entries": []}}
        self.assertEqual(bedrock.gui_model_ref(bow), "x:idle")
        cake = {"type": "minecraft:select", "property": "minecraft:custom_model_data",
                "cases": [{"when": "bite_1", "model": self.ref("x:bite_1")}], "fallback": self.ref("x:main")}
        self.assertEqual(bedrock.gui_model_ref(cake), "x:main")

    def test_unknown_type_is_a_problem(self):
        with self.assertRaises(bedrock.Problem):
            bedrock.gui_model_ref({"type": "minecraft:special"})


class SyntheticPack(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="bedrock_test_"))
        self.assets = self.tmp / "assets"
        self.out = self.tmp / "out"

    def test_sprite_icon_keeps_transparency(self):
        _sprite_item(self.assets, "ruby")
        index, images, problems = bedrock.generate(self.assets, self.out)
        self.assertEqual(problems, [])
        entry = index["items"]["joshymc:ruby"]
        self.assertEqual(entry["bedrock_name"], "ruby")
        icon = Image.open(self.out / "icons" / entry["icon"]).convert("RGBA")
        self.assertEqual(icon.size, (64, 64))
        self.assertEqual(icon.getpixel((0, 0))[3], 0)          # outside the sprite: transparent
        self.assertGreater(icon.getpixel((32, 32))[3], 200)     # the sprite itself: opaque
        self.assertEqual(bedrock.verify(self.assets, self.out), [])

    def test_animated_texture_uses_first_frame(self):
        _sprite_item(self.assets, "glow")
        tex = self.assets / "joshymc" / "textures" / "item" / "glow" / "main.png"
        strip = Image.new("RGBA", (16, 32), (0, 0, 255, 255))
        strip.paste(Image.new("RGBA", (16, 16), (255, 0, 0, 255)), (0, 16))
        strip.save(tex)
        _write(tex.with_name("main.png.mcmeta"), {"animation": {"frametime": 2, "frames": [1, 0]}})
        index, _, problems = bedrock.generate(self.assets, self.out)
        self.assertEqual(problems, [])
        r, g, b, a = Image.open(self.out / "icons" / "joshymc/glow.png").convert("RGBA").getpixel((32, 32))
        self.assertGreater(r, b)  # frame 1 (red) is shown first, not frame 0 (blue)

    def test_missing_texture_is_reported_and_skipped(self):
        _sprite_item(self.assets, "broken")
        (self.assets / "joshymc" / "textures" / "item" / "broken" / "main.png").unlink()
        index, _, problems = bedrock.generate(self.assets, self.out)
        self.assertNotIn("joshymc:broken", index["items"])
        self.assertTrue(any("joshymc:broken" in p and "missing" in p for p in problems), problems)

    def test_missing_model_is_reported(self):
        _write(self.assets / "joshymc" / "items" / "ghost.json",
               {"model": {"type": "minecraft:model", "model": "joshymc:item/ghost/main"}})
        _, _, problems = bedrock.generate(self.assets, self.out)
        self.assertTrue(any("joshymc:ghost" in p and "model" in p for p in problems), problems)

    def test_vanilla_texture_is_reported(self):
        _sprite_item(self.assets, "plain")
        _write(self.assets / "joshymc" / "models" / "item" / "plain" / "main.json",
               {"parent": "minecraft:item/generated", "textures": {"layer0": "minecraft:item/stick"}})
        _, _, problems = bedrock.generate(self.assets, self.out)
        self.assertTrue(any("vanilla texture" in p for p in problems), problems)

    def test_duplicate_bedrock_names_are_rejected(self):
        _sprite_item(self.assets, "fish_cod")
        _sprite_item(self.assets, "fish/cod")
        index, _, problems = bedrock.generate(self.assets, self.out)
        self.assertEqual(len(index["items"]), 1)
        self.assertTrue(any("already used" in p for p in problems), problems)

    def test_verify_finds_missing_orphaned_and_stale_icons(self):
        _sprite_item(self.assets, "ruby")
        _sprite_item(self.assets, "opal", colour=(40, 200, 40, 255))
        bedrock.generate(self.assets, self.out)
        self.assertEqual(bedrock.verify(self.assets, self.out), [])
        _sprite_item(self.assets, "ruby", colour=(40, 40, 200, 255))               # repainted
        _sprite_item(self.assets, "pearl")                                         # new item
        for p in (self.assets / "joshymc").rglob("*opal*"):                        # removed item
            if p.is_file():
                p.unlink()
        problems = bedrock.verify(self.assets, self.out)
        self.assertTrue(any("joshymc:ruby" in p and "stale" in p for p in problems), problems)
        self.assertTrue(any("joshymc:pearl" in p and "no committed" in p for p in problems), problems)
        self.assertTrue(any("joshymc:opal" in p and "no item definition" in p for p in problems), problems)


class CommittedPack(unittest.TestCase):
    """The icons in src/main/resources/bedrock match the Java pack as built."""

    @classmethod
    def setUpClass(cls):
        cls.index = json.loads((bedrock.OUT / "icons.json").read_text(encoding="utf-8"))

    def test_committed_icons_are_current(self):
        self.assertEqual(bedrock.verify(), [])

    def test_every_java_item_definition_has_an_icon(self):
        self.assertEqual(sorted(bedrock.item_definitions()), sorted(self.index["items"]))

    def test_bedrock_names_are_unique(self):
        names = [e["bedrock_name"] for e in self.index["items"].values()]
        self.assertEqual(len(names), len(set(names)))

    def test_item_models_used_by_the_plugin_have_icons(self):
        """Literal item model keys in the Kotlin sources and crates.yml all have an icon, so
        the Geyser mappings the plugin writes never point at a missing texture."""
        keys = set()
        for path in (REPO / "src" / "main" / "kotlin").rglob("*.kt"):
            text = path.read_text(encoding="utf-8")
            keys |= {f"joshymc:{m}" for m in re.findall(r'setItemModel\((?:org\.bukkit\.)?NamespacedKey\(Joshymc\.instance, "([a-z0-9_/]+)"\)\)', text)}
            keys |= {f"joshymc:{m}" for m in re.findall(r'RetexturedKey\("([a-z0-9_]+)"', text)}
        crates = (REPO / "src" / "main" / "resources" / "crates.yml").read_text(encoding="utf-8")
        keys |= set(re.findall(r'^\s*key-item-model:\s*"?(joshymc:[a-z0-9_/]+)', crates, re.M))
        self.assertGreater(len(keys), 40)
        self.assertEqual(sorted(keys - set(self.index["items"])), [])


if __name__ == "__main__":
    unittest.main()
