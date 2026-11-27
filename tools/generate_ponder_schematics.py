"""Generate the vanilla structure files used as Ponder scenes."""
from pathlib import Path
import gzip
import struct

OUTPUT = Path(__file__).resolve().parents[1] / "src/main/resources/assets/retrocomputers/ponder"
OUTPUT.mkdir(parents=True, exist_ok=True)

def string(value):
    data = value.encode("utf-8")
    return struct.pack(">H", len(data)) + data

def integer(value):
    return struct.pack(">i", value)

def compound(fields):
    return b"".join(bytes([kind]) + string(name) + value for kind, name, value in fields) + b"\0"

def collection(kind, values):
    return bytes([kind]) + integer(len(values)) + b"".join(values)

def structure(name, blocks):
    floor = {(x, 0, z): "minecraft:smooth_stone" for x in range(7) for z in range(7)}
    locations = floor | blocks
    palette = list(dict.fromkeys(locations.values()))
    states = []
    for entry in palette:
        identifier, _, properties = entry.partition("[")
        fields = [(8, "Name", string(identifier))]
        if properties:
            pairs = (item.split("=") for item in properties.rstrip("]").split(","))
            fields.append((10, "Properties", compound([(8, key, string(value)) for key, value in pairs])))
        states.append(compound(fields))
    values = [compound([(9, "pos", collection(3, list(map(integer, pos)))),
                        (3, "state", integer(palette.index(block)))])
              for pos, block in sorted(locations.items(), key=lambda entry: entry[0][1])]
    payload = compound([(3, "DataVersion", integer(3955)),
                        (9, "size", collection(3, [integer(7), integer(5), integer(7)])),
                        (9, "palette", collection(10, states)),
                        (9, "blocks", collection(10, values)),
                        (9, "entities", collection(10, []))])
    (OUTPUT / f"{name}.nbt").write_bytes(gzip.compress(b"\x0a\x00\x00" + payload, mtime=0))

chassis = {(2, 1, 3): "retrocomputers:computer[facing=south,powered=false]"}
keyboard = {(3, 1, 3): "retrocomputers:keyboard[facing=south,powered=false,right=false]",
            (4, 1, 3): "retrocomputers:keyboard[facing=south,powered=false,right=true]"}
panel = "retrocomputers:monitor[facing=south,powered=false]"

structure("mining", {(2, 1, 3): "retrocomputers:silicon_ore", (3, 1, 3): "retrocomputers:tin_ore",
                     (2, 2, 2): "create:crushing_wheel[axis=z]",
                     (4, 2, 2): "create:crushing_wheel[axis=z]", (4, 1, 3): "minecraft:furnace",
                     (2, 1, 5): "create:encased_fan[facing=east]", (3, 1, 5): "minecraft:water",
                     (4, 1, 5): "create:depot", (5, 3, 2): "create:mechanical_press", (5, 1, 2): "create:depot"})
structure("boards", {(2, 3, 3): "create:mechanical_mixer", (2, 2, 3): "create:basin",
                     (2, 1, 3): "create:blaze_burner[blaze=kindled]",
                     (3, 3, 2): "create:mechanical_press", (3, 1, 2): "create:basin",
                     (4, 3, 3): "create:deployer[facing=down]", (4, 1, 3): "create:depot"})
structure("parts", {(3, 3, 3): "create:deployer[facing=down]", (3, 1, 3): "create:depot"})
structure("equipment", chassis | keyboard | {(3, 1, 4): "minecraft:crafting_table", (3, 1, 2): panel})
structure("assembly", chassis)
structure("power", chassis | {(1, 1, 1): "create:creative_motor[facing=south]",
                              (1, 1, 2): "create:shaft[axis=z]"})
stand = {(x, 1, 2): "minecraft:spruce_planks" for x in (3, 4)}
structure("terminal", chassis | keyboard | stand | {(x, y, 2): panel for x in (3, 4) for y in (2, 3)})
structure("first_computer", chassis | keyboard | {(3, 1, 2): "minecraft:spruce_planks", (3, 2, 2): panel})

structure("files", chassis | keyboard | stand | {(x, y, 2): panel for x in (3, 4) for y in (2, 3)})

structure("modules", chassis | {(4, 1, 3): "minecraft:redstone_lamp", (4, 2, 3): "retrocomputers:interface_module[facing=up]"})

structure("python", chassis | keyboard | stand | {(x, y, 2): panel for x in (3, 4) for y in (2, 3)})
