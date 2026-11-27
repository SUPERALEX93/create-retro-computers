"""Generate matching placed/held module models without duplicating geometry by hand."""
import copy
import json
import os
from zipfile import ZipFile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1] / "src/main/resources/assets/retrocomputers"
TEXTURES = {
    "0": "create:block/link_base_unpowered",
    "1": "create:block/link_details",
    "brass": "create:block/brass_block",
    "iron": "create:block/industrial_iron_block",
    "particle": "create:block/brass_casing",
}
elements = []


def box(start, end, texture, top=None):
    faces = {side: {"texture": "#" + texture}
             for side in ("up", "down", "north", "south", "east", "west")}
    if top:
        faces["up"] = {"texture": "#" + top, "uv": [0, 0, 16, 16]}
    elements.append({"from": start, "to": end, "faces": faces})


# Adapt the actual Stock Link model and UVs, using Create's own texture atlas.
# No copied high-resolution artwork: texture resources stay in the Create mod.
create_jar = Path(os.environ.get("CREATE_JAR", str(
    Path.home() / ".local/share/PrismLauncher/instances/1.21.1/minecraft/mods/create-1.21.1-6.0.10.jar")))
with ZipFile(create_jar) as archive:
    stock = json.loads(archive.read("assets/create/models/block/stock_link/block_vertical.json"))
# Keep only the Stock Link mounting base and its two separated terminal blocks.
# The planar antenna/coil meshes are omitted entirely.
for index in (0, 1, 2, 3):
    element = copy.deepcopy(stock["children"]["base"]["elements"][index])
    for corner in ("from", "to"):
        x, y, z = element[corner]
        element[corner] = [8 + (x - 8) * .625, (y + 1.05) * .625, 8 + (z - 8) * .625]
    if index == 1:
        element["to"][1] = (1 + 1.05) * .625
    element.pop("rotation", None)
    elements.append(element)
# Separate face ranges: terminal blocks end at z=7.375; the port starts at 8.
box([6.5, 3.15625, 8], [9.5, 5, 11], "brass")
box([7, 5, 8.5], [9, 5.5, 10.5], "iron")
box([4.5, 3.15625, 8], [6, 3.75, 10.5], "iron")

placed = {"parent": "minecraft:block/block", "render_type": "minecraft:cutout", "ambientocclusion": False, "textures": TEXTURES, "elements": elements}
(ROOT / "models/block/interface_module.json").write_text(json.dumps(placed, indent=2) + "\n")

# The placed module is at y=0. Lift it to the item pivot before applying transforms.
held = copy.deepcopy(placed)
for element in held["elements"]:
    for corner in ("from", "to"):
        element[corner][1] += 5.25
held["display"] = {
    "gui": {"rotation": [40, 225, 0], "translation": [0, 0, 0], "scale": [.95] * 3},
    "ground": {"rotation": [0, 0, 0], "translation": [0, 2, 0], "scale": [.7] * 3},
    "fixed": {"rotation": [90, 0, 0], "translation": [0, 0, 0], "scale": [1] * 3},
    "firstperson_righthand": {"rotation": [55, -25, 0], "translation": [-3, 7, 0], "scale": [.65] * 3},
    "firstperson_lefthand": {"rotation": [55, 25, 0], "translation": [-3, 7, 0], "scale": [.65] * 3},
    "thirdperson_righthand": {"rotation": [65, 0, 0], "translation": [0, 2.5, 0], "scale": [.8] * 3},
    "thirdperson_lefthand": {"rotation": [65, 0, 0], "translation": [0, 2.5, 0], "scale": [.8] * 3},
}
(ROOT / "models/item/interface_module.json").write_text(json.dumps(held, indent=2) + "\n")
