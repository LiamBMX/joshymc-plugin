"""Validate item modules. Run from resourcepack/:

    py -m art.check <id>       one item
    py -m art.check --all      every module in art/items (examples starting with _ included)

Checks the module contract, every texture, the models Minecraft 26.2 would reject
(element bounds, rotations, UVs, missing textures, display limits), whether the icon
fits its inventory slot, and the worn layers wearables need.
"""
from __future__ import annotations

import importlib
import math
import pkgutil
import sys
import tempfile
from pathlib import Path

from PIL import Image

from art import kit

HERE = Path(__file__).resolve().parent
ITEM_SIZES = (16, 32, 64, 128)
LAYER_SIZES = ((64, 32), (128, 64), (256, 128))
CONTEXTS = ("thirdperson_righthand", "firstperson_righthand", "gui", "ground", "fixed")
MAX_ELEMENTS, WARN_ELEMENTS = 500, 320


def _num(v) -> bool:
    return isinstance(v, (int, float)) and not isinstance(v, bool) and math.isfinite(v)


def check_model(name: str, model: dict, painted: set[str], kind: str, oversized: bool,
                errors: list, warnings: list) -> set[str]:
    used: set[str] = set()
    where = f"model {name!r}"
    if not isinstance(model, dict):
        errors.append(f"{where} is not a dict")
        return used
    if "elements" not in model:
        layer = model.get("textures", {}).get("layer0", "")
        if not str(layer).startswith("#") or layer[1:] not in painted:
            errors.append(f"{where}: sprite layer0 {layer!r} is not a painted texture")
        else:
            used.add(layer[1:])
        return used
    elements = model["elements"]
    if not elements:
        errors.append(f"{where} has no elements")
        return used
    if len(elements) > MAX_ELEMENTS:
        errors.append(f"{where} has {len(elements)} elements; keep it under {MAX_ELEMENTS}")
    elif len(elements) > WARN_ELEMENTS:
        warnings.append(f"{where} has {len(elements)} elements; over {WARN_ELEMENTS} starts to cost frames")
    for i, e in enumerate(elements):
        label = f"{where} element {i}"
        frm, to = e.get("from"), e.get("to")
        if not (isinstance(frm, list) and isinstance(to, list) and len(frm) == 3 and len(to) == 3
                and all(_num(v) for v in frm + to)):
            errors.append(f"{label}: from/to must be three numbers each")
            continue
        if any(v < kit.LIMIT[0] - 1e-6 or v > kit.LIMIT[1] + 1e-6 for v in frm + to):
            errors.append(f"{label}: from/to {frm} {to} leave Minecraft's [-16, 32] range")
        if any(a > b for a, b in zip(frm, to)):
            errors.append(f"{label}: from > to on some axis")
        r = e.get("rotation")
        if r is not None:
            origin = r.get("origin")
            if not (isinstance(origin, list) and len(origin) == 3 and all(_num(v) for v in origin)):
                errors.append(f"{label}: rotation needs an origin of three numbers")
            if "axis" in r or "angle" in r:
                if r.get("axis") not in ("x", "y", "z") or not _num(r.get("angle")):
                    errors.append(f"{label}: rotation needs axis x/y/z and a numeric angle")
            elif not any(k in r for k in ("x", "y", "z")) or not all(_num(r.get(k, 0)) for k in ("x", "y", "z")):
                errors.append(f"{label}: rotation needs axis+angle or numeric x/y/z")
        if "light_emission" in e and (not isinstance(e["light_emission"], int) or not 0 <= e["light_emission"] <= 15):
            errors.append(f"{label}: light_emission must be an integer 0-15")
        if "shade" in e and not isinstance(e["shade"], bool):
            errors.append(f"{label}: shade must be true/false")
        faces = e.get("faces")
        if not isinstance(faces, dict) or not faces:
            errors.append(f"{label}: needs 1-6 faces")
            continue
        for side, face in faces.items():
            if side not in kit.SIDES:
                errors.append(f"{label}: unknown face {side!r}")
                continue
            tex = str(face.get("texture", ""))
            if not tex.startswith("#") or tex[1:] not in painted:
                errors.append(f"{label} {side}: texture {tex!r} was never painted with save()")
            else:
                used.add(tex[1:])
            uv = face.get("uv")
            if not (isinstance(uv, list) and len(uv) == 4 and all(_num(v) and -1e-6 <= v <= 16 + 1e-6 for v in uv)):
                errors.append(f"{label} {side}: uv must be four numbers in 0..16")
            if face.get("rotation", 0) not in (0, 90, 180, 270):
                errors.append(f"{label} {side}: face rotation must be 0, 90, 180 or 270")
    display = model.get("display")
    if not isinstance(display, dict):
        errors.append(f"{where} has no display transforms (use kit.display())")
        return used
    needed = CONTEXTS + (("head",) if kind == "helmet" else ())
    for context in needed:
        if context not in display:
            errors.append(f"{where}: display is missing {context!r}")
    for context, d in display.items():
        rot, tr, sc = d.get("rotation", [0, 0, 0]), d.get("translation", [0, 0, 0]), d.get("scale", [1, 1, 1])
        if not all(isinstance(x, list) and len(x) == 3 and all(_num(v) for v in x) for x in (rot, tr, sc)):
            errors.append(f"{where} display {context}: rotation/translation/scale need three numbers each")
            continue
        if any(abs(v) > 80 for v in tr):
            errors.append(f"{where} display {context}: translation {tr} is clamped to +-80 in game")
        if any(abs(v) > 4 or abs(v) < 1e-4 for v in sc):
            errors.append(f"{where} display {context}: scale {sc} must be non-zero and within +-4")
    if "gui" in display and name in ("main", "idle", "gui"):
        gui_fit(where, elements, display["gui"], oversized, errors, warnings)
    return used


def gui_fit(where, elements, d, oversized, errors, warnings) -> None:
    pts = []
    for e in elements:
        for p in kit.corners(e):
            q = kit._transform_point(d.get("rotation", [0, 0, 0]), d.get("translation", [0, 0, 0]),
                                     d.get("scale", [1, 1, 1])[0], p)
            pts.append((8 + q[0] * 16, 8 + q[1] * 16))
    xs, ys = [p[0] for p in pts], [p[1] for p in pts]
    lo, hi = min(min(xs), min(ys)), max(max(xs), max(ys))
    if not oversized and (lo < -0.3 or hi > 16.3):
        errors.append(f"{where}: the GUI icon spills out of its slot ({min(xs):.1f}..{max(xs):.1f} x "
                      f"{min(ys):.1f}..{max(ys):.1f} of 0..16); shrink the gui transform or set OVERSIZED_GUI")
    span = max(max(xs) - min(xs), max(ys) - min(ys))
    if span < 10:
        warnings.append(f"{where}: the GUI icon only spans {span:.1f} of 16 px; it may read small")


def check(item_id: str) -> tuple[list[str], list[str], dict]:
    errors: list[str] = []
    warnings: list[str] = []
    info: dict = {}
    module = importlib.import_module(f"art.items.{item_id}")
    for attr in ("ID", "NAME", "KIND", "textures", "models"):
        if not hasattr(module, attr):
            errors.append(f"missing {attr}")
    if errors:
        return errors, warnings, info
    if module.ID != item_id:
        errors.append(f"ID {module.ID!r} must equal the file name {item_id!r}")
    if module.KIND not in kit.KINDS:
        errors.append(f"KIND {module.KIND!r} must be one of {sorted(kit.KINDS)}")
        return errors, warnings, info

    tmp = Path(tempfile.mkdtemp(prefix=f"artcheck_{item_id}_"))
    kit.begin(item_id, tmp / "textures", tmp / "layers")
    module.textures()
    painted = kit.painted()
    for name in sorted(painted):
        size = Image.open(tmp / "textures" / f"{name}.png").size
        if size[0] not in ITEM_SIZES or size[1] not in ITEM_SIZES:
            errors.append(f"texture {name!r} is {size}; each side must be 16, 32, 64 or 128")
    layers = kit.painted_layers()
    for layer in layers:
        size = Image.open(tmp / "layers" / layer / f"{item_id}.png").size
        if size not in LAYER_SIZES:
            errors.append(f"worn layer {layer!r} is {size}; use 64x32, 128x64 or 256x128")
    for layer in kit.REQUIRED_LAYERS.get(module.KIND, ()):
        if layer not in layers:
            errors.append(f"KIND {module.KIND!r} needs a worn {layer!r} layer painted with save_layer()")

    models = module.models()
    if not isinstance(models, dict):
        errors.append("models() must return a dict of name -> model")
        return errors, warnings, info
    required = kit.KINDS[module.KIND]
    for name in required:
        if name not in models:
            errors.append(f"models() is missing {name!r} (KIND {module.KIND} needs {', '.join(required)})")
    for name in models:
        if name not in required and name not in kit.OPTIONAL_MODELS:
            errors.append(f"unexpected model {name!r}; allowed extras are {kit.OPTIONAL_MODELS}")
    oversized = bool(getattr(module, "OVERSIZED_GUI", False))
    used: set[str] = set()
    for name, m in models.items():
        used |= check_model(name, m, painted, module.KIND, oversized, errors, warnings)
    for name in sorted(painted - used):
        warnings.append(f"texture {name!r} is painted but never used")
    main = models.get(required[0], {})
    if main.get("elements"):
        lo, hi = kit.bounds(main["elements"])
        length = max(hi[i] - lo[i] for i in range(3))
        scale = main.get("display", {}).get("thirdperson_righthand", {}).get("scale", [1])[0]
        info["elements"] = len(main["elements"])
        info["held_blocks"] = round(length / 16 * scale * 0.9375, 2)
    info["models"] = len(models)
    info["textures"] = len(painted)
    return errors, warnings, info


def main(argv: list[str]) -> int:
    if not argv:
        print(__doc__)
        return 2
    ids = sorted(m.name for m in pkgutil.iter_modules([str(HERE / "items")])) if argv[0] == "--all" else argv
    failed = 0
    for item_id in ids:
        try:
            errors, warnings, info = check(item_id)
        except Exception as error:  # report a broken module instead of stopping the sweep
            errors, warnings, info = [f"crashed: {type(error).__name__}: {error}"], [], {}
        if errors:
            failed += 1
            print(f"FAIL {item_id}")
            for e in errors:
                print(f"  - {e}")
        else:
            extra = ""
            if "elements" in info:
                extra = (f": {info['elements']} elements, {info['models']} model(s), {info['textures']} textures, "
                         f"held length about {info['held_blocks']} blocks (a vanilla sword is about 1.0)")
            print(f"OK {item_id}{extra}")
        for w in warnings:
            print(f"  ! {w}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
