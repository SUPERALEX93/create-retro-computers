"""Generate Create processing recipes and sequenced assembly lines."""
from pathlib import Path
import json

NAMESPACE = "retrocomputers"
OUTPUT = Path(__file__).resolve().parents[1] / "src/main/resources/data" / NAMESPACE / "recipe"

def identifier(name):
    return name if ":" in name else f"{NAMESPACE}:{name}"

def ingredient(name):
    return {"tag": name[1:]} if name.startswith("#") else {"item": identifier(name)}

def result(name, count=1):
    return {"id": identifier(name), **({"count": count} if count != 1 else {})}

def save(name, recipe):
    OUTPUT.mkdir(parents=True, exist_ok=True)
    (OUTPUT / f"{name}.json").write_text(json.dumps(recipe, indent=2) + "\n")

def process(machine, name, inputs, count=1, **settings):
    save(name, {"type": f"create:{machine}", "ingredients": [ingredient(item) for item in inputs],
                "results": [result(name, count)], **settings})

def smelt(name, source, ticks, xp):
    save(name, {"type": "minecraft:smelting", "ingredient": ingredient(source),
                "result": result(name), "cookingtime": ticks, "experience": xp})

PRESS = ("pressing",)
SAW = ("cutting",)

def assembly(name, base, stages, loops=1, count=1):
    unfinished = f"incomplete_{name}"
    sequence = []
    for stage in stages:
        if stage == PRESS or stage == SAW:
            machine, materials = stage[0], []
        else:
            machine, materials = "deploying", [stage]
        sequence.append({"type": f"create:{machine}", "ingredients": [ingredient(unfinished)] +
                         [ingredient(item) for item in materials], "results": [result(unfinished)]})
    save(name, {"type": "create:sequenced_assembly", "ingredient": ingredient(base), "loops": loops,
                "results": [result(name, count)], "sequence": sequence, "transitional_item": result(unfinished)})

for ore in ("silicon", "tin"):
    process("crushing", f"crushed_{ore}", [f"raw_{ore}"], 2, processing_time=200)
    process("splashing", f"{ore}_dust", [f"crushed_{ore}"])
smelt("tin_ingot", "tin_dust", 200, .3)
process("pressing", "tin_plate", ["tin_ingot"])
process("mixing", "solder", ["tin_ingot", "minecraft:copper_ingot"], 4, heat_requirement="heated")
process("mixing", "ceramic", ["silicon_dust", "minecraft:clay_ball", "minecraft:quartz"], 2,
        heat_requirement="heated")
process("mixing", "resin", ["minecraft:dried_kelp", "minecraft:paper", "minecraft:clay_ball"], 3,
        heat_requirement="heated")
process("mixing", "magnetic_tape", ["resin", "minecraft:paper", "minecraft:iron_nugget"], 3,
        heat_requirement="heated")
process("compacting", "ferrite_core", ["minecraft:iron_nugget", "minecraft:iron_nugget", "minecraft:redstone"], 2)
process("compacting", "silicon_blank", ["silicon_dust", "silicon_dust", "minecraft:quartz"])
smelt("silicon_boule", "silicon_blank", 320, .5)
process("cutting", "silicon_wafer", ["silicon_boule"], 4, processing_time=140)
process("cutting", "copper_trace", ["create:copper_sheet"], 4, processing_time=100)
process("deploying", "wiring_harness", ["copper_trace", "resin"], 2)
process("cutting", "keycaps", ["resin"], 4, processing_time=100)
process("mixing", "crt_glass", ["minecraft:glass", "minecraft:glowstone_dust", "silicon_dust"], 2,
        heat_requirement="heated")
save("chassis_frame", {"type": "minecraft:crafting_shaped", "pattern": ["PIP", " A ", "PIP"],
                       "key": {"P": ingredient("#minecraft:planks"), "I": ingredient("create:iron_sheet"),
                               "A": ingredient("create:andesite_casing")}, "result": result("chassis_frame")})

assembly("board", "ceramic", ["tin_plate", "copper_trace", PRESS, "solder"], loops=2)
assembly("cpu", "board", ["silicon_wafer", "create:golden_sheet", "minecraft:redstone", "solder", PRESS], loops=2)
assembly("ram", "board", ["minecraft:redstone", "silicon_wafer", "copper_trace", "resin", "solder", PRESS], loops=2)
assembly("drive", "board", ["magnetic_tape", "create:shaft", "wiring_harness", "create:iron_sheet", PRESS], loops=2)
assembly("psu", "board", ["ferrite_core", "wiring_harness", "create:iron_sheet", "solder", PRESS], loops=2)

assembly("cpu_advanced", "cpu", ["create:precision_mechanism", "silicon_wafer", "minecraft:diamond",
                                 "create:brass_sheet", "solder", PRESS], loops=2)
assembly("ram_advanced", "ram", ["minecraft:lapis_lazuli", "silicon_wafer", "create:golden_sheet",
                                 "resin", "solder", PRESS], loops=2)
assembly("drive_advanced", "drive", ["create:brass_sheet", "create:precision_mechanism", "magnetic_tape",
                                     "wiring_harness", PRESS, SAW], loops=2)
assembly("psu_advanced", "psu", ["create:electron_tube", "ferrite_core", "create:sturdy_sheet",
                                 "wiring_harness", "solder", PRESS], loops=2)

assembly("computer", "chassis_frame", ["create:iron_sheet", "tin_plate", "wiring_harness",
                                       "create:brass_sheet", PRESS, SAW])
assembly("monitor", "crt_glass", ["chassis_frame", "board", "create:electron_tube", "wiring_harness", PRESS], count=4)
assembly("keyboard", "chassis_frame", ["keycaps", "minecraft:stone_button", "board", "wiring_harness", "solder", PRESS])
assembly("linker", "copper_trace", ["create:polished_rose_quartz", "resin", "wiring_harness",
                                    "create:golden_sheet", "solder", PRESS])

assembly("modem", "board", ["create:electron_tube", "silicon_wafer", "wiring_harness", "create:brass_sheet", "solder", PRESS], loops=2)
assembly("interface_module", "chassis_frame", ["board", "wiring_harness", "minecraft:comparator", "solder", PRESS], count=2)

# Passive motion instruments: mechanically calibrated, with a computer-readable board.
for instrument, reference in (("tilt_sensor", "minecraft:compass"),
                              ("motion_speedometer", "create:speedometer"),
                              ("altimeter", "minecraft:clock")):
    assembly(instrument, "chassis_frame", [reference, "create:precision_mechanism", "board",
             "wiring_harness", "create:brass_sheet", "crt_glass", PRESS], loops=2)
