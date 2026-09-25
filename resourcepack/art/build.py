"""Build every item module into the resource pack. Run from resourcepack/:

    py -m art.build            all items in art/items (examples starting with _ are skipped)
    py -m art.build <id> ...   just these

For each item it writes textures to assets/joshymc/textures/item/<id>/, models to
assets/joshymc/models/item/<id>/, the item definition assets/joshymc/items/<id>.json
and, for wearables, assets/joshymc/equipment/<id>.json plus its worn textures.
Items that fail art.check are skipped so one broken module never blocks the rest.
Gradle zips resourcepack/ into the plugin JAR, so run this before building the plugin.
"""
from __future__ import annotations

import json
import pkgutil
import shutil
import sys
from pathlib import Path

from art import check, kit

HERE = Path(__file__).resolve().parent
ASSETS = HERE.parent / "assets" / kit.NAMESPACE


def write_json(path: Path, data: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")


def build(item_id: str) -> bool:
    errors, warnings, _ = check.check(item_id)
    if errors:
        print(f"SKIPPED {item_id}: {len(errors)} check error(s); run py -m art.check {item_id}")
        return False
    module = __import__(f"art.items.{item_id}", fromlist=["x"])
    model_key = getattr(module, "MODEL_KEY", item_id)
    equipment_key = getattr(module, "EQUIPMENT_KEY", item_id)
    textures = ASSETS / "textures" / "item" / item_id
    models_dir = ASSETS / "models" / "item" / item_id
    for stale in (textures, models_dir):
        shutil.rmtree(stale, ignore_errors=True)
    layer_root = ASSETS / "textures" / "entity" / "equipment"
    for layer in kit.LAYERS:
        for key in {item_id, equipment_key}:
            (layer_root / layer / f"{key}.png").unlink(missing_ok=True)
    (ASSETS / "equipment" / f"{equipment_key}.json").unlink(missing_ok=True)

    kit.begin(item_id, textures, layer_root)
    module.textures()
    for name, meta in kit.animations().items():
        write_json(textures / f"{name}.png.mcmeta", {"animation": meta})
    models = module.models()
    for name, m in models.items():
        write_json(models_dir / f"{name}.json", kit.resolve(m, item_id))
    write_json(ASSETS / "items" / f"{model_key}.json",
               kit.item_definition(item_id, module.KIND, set(models), bool(getattr(module, "OVERSIZED_GUI", False))))
    layers = kit.painted_layers()
    if layers:
        if equipment_key != item_id:
            for layer in layers:
                (layer_root / layer / f"{item_id}.png").replace(layer_root / layer / f"{equipment_key}.png")
        write_json(ASSETS / "equipment" / f"{equipment_key}.json",
                   {"layers": {layer: [{"texture": f"{kit.NAMESPACE}:{equipment_key}"}] for layer in sorted(layers)}})
    animated = len(kit.animations())
    print(f"built {item_id}: {len(models)} model(s), {len(kit.painted())} textures"
          + (f" ({animated} animated)" if animated else "")
          + (f", worn {', '.join(sorted(layers))}" if layers else ""))
    return True


def main(argv: list[str]) -> int:
    ids = argv or sorted(m.name for m in pkgutil.iter_modules([str(HERE / "items")]) if not m.name.startswith("_"))
    built = sum(build(i) for i in ids)
    print(f"{built}/{len(ids)} items built")
    return 0 if built == len(ids) else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
