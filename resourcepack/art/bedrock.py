"""Bedrock inventory icons for every custom item in the Java pack (issue #1314).
Run from resourcepack/:

    py -m art.bedrock            regenerate every icon (art.build runs this too)
    py -m art.bedrock --check    only verify the committed icons; exit 1 if any are
                                 missing, stale or orphaned

Bedrock (Geyser) can't read Java models, so each Bedrock custom item needs a flat icon.
This reads the *built* Java pack (assets/<ns>/items/**.json, the models and textures they
point at), so every item definition gets one, whether it came from an art module or not,
and nothing is painted twice. The icon is the item's real inventory view: the `gui`
case of a display_context select when there is one, otherwise the model it falls back
to, rendered through its `gui` display transform with art.render's rasteriser, on a
transparent background. Animated textures show their first frame (Bedrock item icons
can't animate).

Output goes into the plugin's resources so it ships inside the JAR:

    src/main/resources/bedrock/icons/<ns>/<path>.png
    src/main/resources/bedrock/icons.json   model key -> bedrock name, icon, sha1

BedrockPackManager builds the .mcpack and the Geyser item mappings from these at
startup. Problems (a model or texture that's missing, a vanilla parent or texture
this can't draw, two keys that would share one Bedrock name) are printed with the file
to fix and fail the run; that item gets no icon instead of a broken one.
"""
from __future__ import annotations

import hashlib
import io
import json
import re
import sys
from pathlib import Path

import numpy as np
from PIL import Image

from art import render

ROOT = Path(__file__).resolve().parent.parent          # resourcepack/
ASSETS = ROOT / "assets"
OUT = ROOT.parent / "src" / "main" / "resources" / "bedrock"
SCALE = 4                                               # 16 px slot -> 64 px icon
INDEX_FORMAT = 1
FLAT_PARENTS = {"minecraft:item/generated", "minecraft:item/handheld", "item/generated", "item/handheld"}
_NAME_OK = re.compile(r"[^a-z0-9_]")


class Problem(Exception):
    """An item that can't get a correct icon; the message says what to fix."""


def bedrock_name(model_key: str) -> str:
    """The Bedrock-safe name for a Java item model key: 'joshymc:fish/cod' -> 'fish_cod'.
    Bedrock identifiers and texture keys only take [a-z0-9_]; the namespace is added by
    the mappings, so a non-joshymc namespace keeps its own prefix to stay unique."""
    namespace, _, path = model_key.partition(":")
    name = _NAME_OK.sub("_", path.lower())
    return name if namespace == "joshymc" else f"{_NAME_OK.sub('_', namespace.lower())}__{name}"


def item_definitions(assets: Path = ASSETS) -> dict[str, Path]:
    """Every item definition in the pack, keyed by the item model key it registers."""
    out = {}
    for ns_dir in sorted(p for p in assets.iterdir() if p.is_dir()):
        items = ns_dir / "items"
        if not items.is_dir():
            continue
        for path in sorted(items.rglob("*.json")):
            out[f"{ns_dir.name}:{path.relative_to(items).with_suffix('').as_posix()}"] = path
    return out


def gui_model_ref(node: dict) -> str:
    """The model the inventory shows for an item definition tree."""
    kind = node.get("type", "").removeprefix("minecraft:")
    if kind == "model":
        return node["model"]
    if kind == "select":
        if node.get("property", "").removeprefix("minecraft:") == "display_context":
            for case in node.get("cases", []):
                when = case.get("when")
                if when == "gui" or (isinstance(when, list) and "gui" in when):
                    return gui_model_ref(case["model"])
        if "fallback" in node:
            return gui_model_ref(node["fallback"])
        return gui_model_ref(node["cases"][0]["model"])
    if kind == "condition":
        return gui_model_ref(node["on_false"])
    if kind == "range_dispatch":
        if "fallback" in node:
            return gui_model_ref(node["fallback"])
        return gui_model_ref(node["entries"][0]["model"])
    if kind == "composite":
        return gui_model_ref(node["models"][0])
    raise Problem(f"item model type '{node.get('type')}' isn't supported (no single model to draw)")


def _resource(assets: Path, ref: str, folder: str, suffix: str) -> Path:
    namespace, _, path = ref.partition(":") if ":" in ref else ("minecraft", "", ref)
    return assets / namespace / folder / f"{path}{suffix}"


def load_model(assets: Path, ref: str) -> dict:
    """A model with its custom parents merged in (vanilla flat parents become the
    display a sprite inherits)."""
    path = _resource(assets, ref, "models", ".json")
    if not path.is_file():
        raise Problem(f"model {ref} is missing ({path.relative_to(assets.parent)})")
    model = json.loads(path.read_text(encoding="utf-8"))
    parent = model.get("parent")
    if parent and parent not in FLAT_PARENTS:
        if parent.startswith("minecraft:") or ":" not in parent:
            raise Problem(f"model {ref} uses vanilla parent {parent}, which can't be drawn without the client jar")
        base = load_model(assets, parent)
        merged = {**base, **{k: v for k, v in model.items() if k not in ("parent", "textures", "display")}}
        merged["textures"] = {**base.get("textures", {}), **model.get("textures", {})}
        merged["display"] = {**base.get("display", {}), **model.get("display", {})}
        model = merged
    elif parent:
        if "elements" in model:
            raise Problem(f"model {ref} mixes a flat parent with elements")
        model = dict(model)
        model["display"] = render._sprite_display(model)
    return model


def load_texture(assets: Path, ref: str) -> np.ndarray:
    """A texture as float RGBA; the first shown frame of an animated strip."""
    if ref.startswith("minecraft:") or ":" not in ref:
        raise Problem(f"texture {ref} is a vanilla texture, which can't be drawn without the client jar")
    path = _resource(assets, ref, "textures", ".png")
    if not path.is_file():
        raise Problem(f"texture {ref} is missing ({path.relative_to(assets.parent)})")
    array = render.to_array(Image.open(path))
    meta_path = path.with_name(path.name + ".mcmeta")
    if meta_path.is_file():
        animation = json.loads(meta_path.read_text(encoding="utf-8")).get("animation")
        if animation is not None:
            w = array.shape[1]
            frames = animation.get("frames") or [0]
            first = frames[0]["index"] if isinstance(frames[0], dict) else frames[0]
            array = array[first * w:(first + 1) * w]
    return array


def textures_for(assets: Path, model: dict, ref: str) -> dict[str, np.ndarray]:
    """Texture name (as faces reference it, without '#') -> pixels."""
    declared = model.get("textures", {})

    def follow(name: str, seen=()) -> str:
        value = declared.get(name)
        if value is None:
            raise Problem(f"model {ref} references texture #{name}, which it never declares")
        if value.startswith("#"):
            if value[1:] in seen:
                raise Problem(f"model {ref} has a texture reference loop at #{name}")
            return follow(value[1:], seen + (name,))
        return value

    used = {face["texture"][1:] for e in model.get("elements", []) for face in e["faces"].values()}
    if "elements" not in model:
        layer = declared.get("layer0")
        if not layer:
            raise Problem(f"model {ref} has neither elements nor a layer0 texture")
        used = {"layer0"}
    return {name: load_texture(assets, follow(name)) for name in sorted(used)}


def draw_icon(model: dict, textures: dict) -> Image.Image:
    """The inventory view on a transparent background: rendered once on black and once
    on white, alpha = 1 - (white - black), colour = black / alpha."""
    size = 16 * SCALE
    m = render.item_transform(model.get("display", {}).get("gui"))
    quads = render.model_quads(model, textures, m)
    cam = render.Camera(size, size, np.eye(4), ortho_scale=size)
    black = np.asarray(render.render(quads, cam, background=(0, 0, 0), light_space="view"), dtype=np.float32) / 255
    white = np.asarray(render.render(quads, cam, background=(1, 1, 1), light_space="view"), dtype=np.float32) / 255
    alpha = np.clip(1 - (white - black).mean(axis=2), 0, 1)
    rgb = np.where(alpha[..., None] > 1e-3, black / np.maximum(alpha[..., None], 1e-3), 0)
    alpha = np.where(alpha < 0.1, 0, alpha)  # the game's cutout threshold
    out = np.dstack([np.clip(rgb, 0, 1), alpha])
    out[alpha == 0] = 0
    return Image.fromarray(np.round(out * 255).astype(np.uint8), "RGBA")


def icon_for(assets: Path, key: str, definition: Path) -> Image.Image:
    tree = json.loads(definition.read_text(encoding="utf-8")).get("model")
    if not isinstance(tree, dict):
        raise Problem(f"{definition.relative_to(assets.parent)} has no 'model'")
    ref = gui_model_ref(tree)
    model = load_model(assets, ref)
    textures = textures_for(assets, model, ref)
    if "elements" not in model:  # art.render reads a sprite's layer0 as a '#name' reference
        model = {**model, "textures": {"layer0": "#layer0"}}
    image = draw_icon(model, textures)
    if image.getextrema()[3][1] == 0:
        raise Problem(f"model {ref} draws nothing in the inventory (check its gui display transform)")
    return image


def png_bytes(image: Image.Image) -> bytes:
    buffer = io.BytesIO()
    image.save(buffer, "PNG", optimize=True)
    return buffer.getvalue()


def generate(assets: Path = ASSETS, out: Path = OUT, write: bool = True):
    """Render every icon. Returns (index, icons {relative path: Image}, problems)."""
    problems: list[str] = []
    entries: dict[str, dict] = {}
    images: dict[str, Image.Image] = {}
    owners: dict[str, str] = {}
    for key, definition in item_definitions(assets).items():
        name = bedrock_name(key)
        if name in owners:
            problems.append(f"{key}: Bedrock name '{name}' is already used by {owners[name]}; rename one of the items")
            continue
        try:
            image = icon_for(assets, key, definition)
        except Problem as problem:
            problems.append(f"{key}: {problem}")
            continue
        except (KeyError, IndexError, ValueError, TypeError) as error:
            problems.append(f"{key}: malformed item definition or model ({type(error).__name__}: {error})")
            continue
        owners[name] = key
        namespace, _, path = key.partition(":")
        rel = f"{namespace}/{path}.png"
        images[rel] = image
        entries[key] = {"bedrock_name": name, "icon": rel, "sha1": hashlib.sha1(png_bytes(image)).hexdigest()}
    index = {"format_version": INDEX_FORMAT, "icon_size": 16 * SCALE, "items": entries}
    if write:
        icons = out / "icons"
        for stale in sorted(icons.rglob("*.png")) if icons.is_dir() else []:
            if stale.relative_to(icons).as_posix() not in images:
                stale.unlink()
        for rel, image in images.items():
            path = icons / rel
            path.parent.mkdir(parents=True, exist_ok=True)
            data = png_bytes(image)
            if not path.is_file() or path.read_bytes() != data:
                path.write_bytes(data)
        out.mkdir(parents=True, exist_ok=True)
        (out / "icons.json").write_text(json.dumps(index, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return index, images, problems


def _close(a: Image.Image, b: Image.Image) -> bool:
    """Same icon, allowing last-bit float differences between machines."""
    if a.size != b.size:
        return False
    x = np.asarray(a.convert("RGBA"), dtype=np.int16)
    y = np.asarray(b.convert("RGBA"), dtype=np.int16)
    return bool((np.abs(x - y).max(axis=2) > 8).mean() <= 0.01)


def verify(assets: Path = ASSETS, out: Path = OUT) -> list[str]:
    """What's wrong with the committed icons, or [] when they match the pack."""
    index, images, problems = generate(assets, out, write=False)
    index_path = out / "icons.json"
    if not index_path.is_file():
        return problems + [f"{index_path} is missing; run py -m art.bedrock"]
    committed = json.loads(index_path.read_text(encoding="utf-8"))
    have, want = committed.get("items", {}), index["items"]
    for key in sorted(set(want) - set(have)):
        problems.append(f"{key}: no committed Bedrock icon; run py -m art.bedrock")
    for key in sorted(set(have) - set(want)):
        problems.append(f"{key}: committed Bedrock icon has no item definition any more; run py -m art.bedrock")
    for key in sorted(set(want) & set(have)):
        rel = want[key]["icon"]
        path = out / "icons" / rel
        if have[key].get("icon") != rel or have[key].get("bedrock_name") != want[key]["bedrock_name"]:
            problems.append(f"{key}: icons.json entry is out of date; run py -m art.bedrock")
        elif not path.is_file():
            problems.append(f"{key}: icon file {path} is missing; run py -m art.bedrock")
        elif hashlib.sha1(path.read_bytes()).hexdigest() != have[key].get("sha1"):
            problems.append(f"{key}: {path.name} doesn't match its sha1 in icons.json; run py -m art.bedrock")
        elif not _close(Image.open(path), images[rel]):
            problems.append(f"{key}: icon is stale (the Java model changed); run py -m art.bedrock")
    return problems


def main(argv: list[str]) -> int:
    if "--check" in argv:
        problems = verify()
        for p in problems:
            print("ERROR", p)
        print("Bedrock icons are up to date" if not problems else f"{len(problems)} Bedrock icon problem(s)")
        return 1 if problems else 0
    index, _, problems = generate()
    for p in problems:
        print("ERROR", p)
    print(f"{len(index['items'])} Bedrock icon(s) written to {OUT.relative_to(ROOT.parent)}"
          + (f", {len(problems)} item(s) skipped" if problems else ""))
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
