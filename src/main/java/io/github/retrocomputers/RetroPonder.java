package io.github.retrocomputers;

import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.registration.PonderPlugin;
import net.createmod.ponder.api.registration.PonderSceneRegistrationHelper;
import net.createmod.ponder.api.registration.PonderTagRegistrationHelper;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import java.util.List;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ItemLike;

import static io.github.retrocomputers.RetroComputers.id;

final class RetroPonder implements PonderPlugin {
    private static final BlockPos CASE = new BlockPos(2, 1, 3);
    private static final BlockPos KEYBOARD = new BlockPos(3, 1, 3);
    private static final BlockPos SCREEN = new BlockPos(3, 3, 2);
    private static final BlockPos DISPLAY_STAND = SCREEN.below(2);
    private static final BlockPos FIRST_SCREEN = SCREEN.below();
    private static final BlockPos MOTOR = new BlockPos(1, 1, 1);
    private static final BlockPos SHAFT = MOTOR.south();

    @Override
    public String getModId() {
        return RetroComputers.ID;
    }

    @Override
    public void registerTags(PonderTagRegistrationHelper<ResourceLocation> helper) {
        ResourceLocation tag = id("computers");
        helper.registerTag(tag).item(RetroComputers.PC.get()).addToIndex()
            .title("Retro Computers").description("From raw materials to a working terminal").register();
        RetroComputers.ITEMS.getEntries().forEach(item -> helper.addTagToComponent(item.getId(), tag));
    }

    @Override
    public void registerScenes(PonderSceneRegistrationHelper<ResourceLocation> helper) {
        helper.forComponents(id("computer"), id("monitor"), id("keyboard"), id("linker"))
            .addStoryBoard("first_computer", RetroPonder::firstComputer);
        helper.forComponents(id("tilt_sensor"), id("motion_speedometer"), id("altimeter"),
            id("incomplete_tilt_sensor"), id("incomplete_motion_speedometer"), id("incomplete_altimeter"), id("interface_module"))
            .addStoryBoard("modules", RetroPonder::sensors);
        helper.forComponents(ResourceLocation.fromNamespaceAndPath("create", "speedometer"),
            ResourceLocation.fromNamespaceAndPath("create", "stressometer")).addStoryBoard("modules", RetroPonder::sensors);
        helper.forComponents(id("modem"), id("interface_module"), id("incomplete_modem"), id("incomplete_interface_module"),
            id("computer"), id("linker")).addStoryBoard("modules", RetroPonder::modules);
        helper.forComponents(id("silicon_ore"), id("tin_ore"), id("raw_silicon"), id("raw_tin"),
            id("crushed_silicon"), id("crushed_tin"), id("silicon_dust"), id("tin_dust"), id("tin_ingot"), id("tin_plate"))
            .addStoryBoard("mining", RetroPonder::mining);
        helper.forComponents(id("silicon_dust"), id("tin_plate"), id("ceramic"), id("board"), id("incomplete_board"),
            id("resin"), id("solder"), id("copper_trace"), id("silicon_blank"), id("silicon_boule"),
            id("silicon_wafer"), id("wiring_harness"))
            .addStoryBoard("boards", RetroPonder::boards);
        helper.forComponents(RetroComputers.ASSEMBLED_PARTS.stream().map(RetroComputers::id).toList())
            .addStoryBoard("parts", RetroPonder::parts)
            .addStoryBoard("assembly", RetroPonder::assembly);
        helper.forComponents(RetroComputers.ASSEMBLED_PARTS.stream().map(name -> id("incomplete_" + name)).toList())
            .addStoryBoard("parts", RetroPonder::parts);
        helper.forComponents(id("ferrite_core"), id("magnetic_tape")).addStoryBoard("parts", RetroPonder::parts);
        helper.forComponents(id("crt_glass"), id("chassis_frame"), id("keycaps"),
            id("incomplete_computer"), id("incomplete_monitor"), id("incomplete_keyboard"), id("incomplete_linker"))
            .addStoryBoard("equipment", RetroPonder::equipment);
        helper.forComponents(id("computer"), id("monitor"), id("keyboard"), id("linker"))
            .addStoryBoard("equipment", RetroPonder::equipment);
        helper.forComponents(id("computer")).addStoryBoard("assembly", RetroPonder::assembly);
        helper.forComponents(id("computer"), id("psu"), id("psu_advanced"))
            .addStoryBoard("power", RetroPonder::power);
        helper.forComponents(id("monitor"), id("keyboard"), id("linker"), id("computer"))
            .addStoryBoard("terminal", RetroPonder::terminal)
            .addStoryBoard("files", RetroPonder::files)
            .addStoryBoard("python", RetroPonder::python);
    }

    private static void start(SceneBuilder scene, String id, String title) {
        scene.title(id, title);
        scene.configureBasePlate(0, 0, 6);
        scene.scaleSceneView(id.equals("terminal") ? 1.16F : 1.22F);
        scene.setSceneOffsetY(.3F);
        scene.showBasePlate();
        scene.rotateCameraY(-130);
        scene.idle(30);
    }

    private static void show(SceneBuilder scene, SceneBuildingUtil util, BlockPos pos) {
        scene.world().showSection(util.select().position(pos), Direction.DOWN);
        if (pos.equals(KEYBOARD)) scene.world().showSection(util.select().position(pos.east()), Direction.DOWN);
        scene.idle(8);
    }

    private static void note(SceneBuilder scene, BlockPos pos, String text, ItemLike item) {
        int duration = Math.max(150, 65 + text.length());
        if (item != null) {
            scene.overlay().showControls(pos.getCenter().add(.68, .20, 0), Pointing.LEFT, 65)
                .withItem(new ItemStack(item));
        }
        scene.overlay().chaseBoundingBoxOutline(PonderPalette.OUTPUT, new Object(), new net.minecraft.world.phys.AABB(pos), duration);
        scene.overlay().showText(duration).attachKeyFrame().independent(32).text(text);
        scene.idle(duration + 10);
    }

    private static void click(SceneBuilder scene, BlockPos pos, ItemLike item, boolean sneak) {
        var controls = scene.overlay().showControls(pos.getCenter().add(.55, .15, 0), Pointing.LEFT, 75).rightClick();
        if (item != null) controls.withItem(new ItemStack(item));
        if (sneak) controls.whileSneaking();
    }

    private static ItemLike part(String name) {
        return RetroComputers.PARTS.get(name).get();
    }

    private static void firstComputer(SceneBuilder scene, SceneBuildingUtil util) {
        start(scene, "first_computer", "Your first working computer");
        show(scene, util, CASE);
        note(scene, CASE, "This is the computer chassis. To make it in survival, start with the Mining and processing ores guide, then follow the board and equipment guides.", RetroComputers.PC.get());
        note(scene, CASE, "In JEI, hover over an item and press R to see how to make it. In Creative, find the blocks and parts in the Retro Computers tab.", null);
        click(scene, CASE, part("cpu"), false);
        note(scene, CASE, "Place the chassis. Hold a basic CPU, then right-click the chassis. Repeat with basic RAM, a cassette drive and a 12 FE/t power supply.", null);
        BlockPos alternator = CASE.west();
        showAlternator(scene, util, alternator);
        note(scene, alternator, "Put an Alternator against the chassis. Connect a spinning Create shaft to the Alternator and reach 32 RPM. The power setup guide shows the details.", null);
        show(scene, util, DISPLAY_STAND);
        show(scene, util, FIRST_SCREEN);
        show(scene, util, KEYBOARD);
        note(scene, FIRST_SCREEN, "Place one monitor with its green face toward you. Place the keyboard where you can use it while looking at that screen.", null);
        click(scene, CASE, RetroComputers.LINKER.get(), false);
        note(scene, CASE, "Hold the Linking Probe and right-click the chassis first. A message confirms the computer is selected.", null);
        click(scene, FIRST_SCREEN, RetroComputers.LINKER.get(), false);
        click(scene, KEYBOARD, RetroComputers.LINKER.get(), false);
        linkDemo(scene, FIRST_SCREEN, 1, 1);
        note(scene, FIRST_SCREEN, "With the same probe, right-click the monitor and then the keyboard. The powered monitor shows READY. TYPE HELP.", null);
        click(scene, KEYBOARD, null, false);
        note(scene, KEYBOARD, "Open the keyboard. On the first boot enter a name, such as lab, and press RETURN. Then type HELP to list the commands.", null);
        scene.world().modifyBlockEntity(CASE, Device.class,
            computer -> computer.output = List.of(TerminalOutput.message("title"), TerminalOutput.message("ready"),
                Component.literal(">HELP"), TerminalOutput.message("help")));
        scene.idle(75);
        scene.markAsFinished();
    }

    private static void mining(SceneBuilder scene, SceneBuildingUtil util) {
        start(scene, "mining", "Mining and processing ores");
        BlockPos silicon = new BlockPos(2, 1, 3);
        BlockPos tin = new BlockPos(3, 1, 3);
        show(scene, util, silicon);
        show(scene, util, tin);
        note(scene, silicon, "Find silicon in stone at Y 0–96 in new Overworld chunks. Mine it with an iron pickaxe or better.", Items.IRON_PICKAXE);
        note(scene, tin, "Tin forms in stone at Y 0–64. Use an iron pickaxe or better here as well.", null);
        BlockPos wheels = new BlockPos(2, 2, 2);
        scene.world().showSection(util.select().fromTo(wheels, wheels.east(2)), Direction.DOWN);
        scene.idle(8);
        note(scene, wheels.east(), "Feed raw tin or raw silicon between rotating Crushing Wheels to get two pieces of crushed ore.", part("raw_tin"));
        BlockPos fan = new BlockPos(2, 1, 5);
        scene.world().showSection(util.select().fromTo(fan, fan.east(2)), Direction.DOWN);
        scene.idle(8);
        note(scene, fan.east(2), "Blow air from an Encased Fan through water onto the crushed ore. Bulk washing turns it into clean dust.", part("crushed_silicon"));
        BlockPos furnace = new BlockPos(4, 1, 3);
        show(scene, util, furnace);
        note(scene, furnace, "Smelt tin dust into tin ingots.", part("tin_dust"));
        BlockPos press = new BlockPos(5, 3, 2);
        scene.world().showSection(util.select().fromTo(press, press.below(2)), Direction.DOWN);
        scene.idle(8);
        note(scene, press, "Run each tin ingot under a Mechanical Press to make a plate.", part("tin_ingot"));
        scene.markAsFinished();
    }

    private static void boards(SceneBuilder scene, SceneBuildingUtil util) {
        start(scene, "boards", "Ceramic substrates and circuit boards");
        BlockPos mixer = new BlockPos(2, 3, 3);
        scene.world().showSection(util.select().fromTo(mixer, mixer.below(2)), Direction.DOWN);
        scene.idle(8);
        note(scene, mixer.below(), "Heat a Basin and mix silicon dust, clay and quartz for ceramic. Mix tin and copper ingots in the heated Basin for solder.", part("silicon_dust"));
        note(scene, mixer.below(), "Mix dried kelp, paper and clay in the heated Basin for resin. Use a saw to make copper traces from a copper sheet.", part("resin"));
        BlockPos press = new BlockPos(3, 3, 2);
        scene.world().showSection(util.select().fromTo(press, press.below(2)), Direction.DOWN);
        scene.idle(8);
        note(scene, press, "Compact two silicon dust and one quartz in a Basin under a Press. Smelt the blank and cut the ingot on a saw to get four wafers.", part("silicon_wafer"));
        BlockPos deployer = new BlockPos(4, 3, 3);
        scene.world().showSection(util.select().fromTo(deployer, deployer.below(2)), Direction.DOWN);
        scene.idle(8);
        note(scene, deployer, "Start with ceramic on a Depot or Belt. Add a tin plate and a copper trace with Deployers, press the piece, then add solder with a Deployer.", part("tin_plate"));
        note(scene, deployer, "Run that same four-step cycle twice to finish a circuit board. To make wiring harnesses, apply resin to a copper trace in a Deployer.", part("wiring_harness"));
        scene.markAsFinished();
    }

    private static void parts(SceneBuilder scene, SceneBuildingUtil util) {
        start(scene, "parts", "Manufacturing and upgrading components");
        BlockPos deployer = new BlockPos(3, 3, 3);
        scene.world().showSection(util.select().fromTo(deployer, deployer.below(2)), Direction.DOWN);
        scene.idle(8);
        note(scene, deployer, "Start each basic part from a circuit board. The first Deployer selects the line: wafer for CPU, redstone for RAM, tape for the drive, ferrite for power.", part("board"));
        note(scene, deployer, "A heated Mixer makes magnetic tape from resin, paper and an iron nugget. A Press compacts two iron nuggets and redstone into ferrite cores.", part("magnetic_tape"));
        note(scene, deployer, "Hover over the finished part in JEI and press R. Follow the machines in order; the item stays unfinished until both loops are complete.", part("cpu"));
        note(scene, deployer, "Upgrades start with the matching basic part. They take two more loops. The CPU uses a Precision Mechanism and diamond; the PSU uses an Electron Tube and Sturdy Sheet.", part("psu_advanced"));
        scene.markAsFinished();
    }

    private static void equipment(SceneBuilder scene, SceneBuildingUtil util) {
        start(scene, "equipment", "Crafting terminal equipment");
        BlockPos table = new BlockPos(3, 1, 4);
        show(scene, util, table);
        show(scene, util, CASE);
        note(scene, CASE, "Start with an Equipment Frame: four planks, two iron sheets and an Andesite Casing. Add iron, tin, wiring and brass, then press and saw the chassis.", RetroComputers.PC.get());
        var panel = SCREEN.below(2);
        show(scene, util, panel);
        note(scene, panel, "Heat-mix glass, glowstone and silicon dust. Add a frame, a circuit board, an Electron Tube and wiring to the Phosphor Glass, then press it into four panels.", RetroComputers.PANEL.get());
        show(scene, util, KEYBOARD);
        note(scene, KEYBOARD, "Cut resin on a saw for keycaps. Add them, a stone button, a circuit board, wiring and solder to a frame. Finish it in the Press.", RetroComputers.KEYBOARD.get());
        note(scene, table, "Start the probe from a copper trace. Add polished rose quartz, resin, wiring, gold and solder, then press. Press R over equipment in JEI to see each line.", RetroComputers.LINKER.get());
        scene.markAsFinished();
    }

    private static void assembly(SceneBuilder scene, SceneBuildingUtil util) {
        start(scene, "assembly", "Assembling the computer");
        show(scene, util, CASE);
        scene.rotateCameraY(-65);
        scene.idle(24);
        note(scene, CASE, "Look through either open side of the chassis. The two shelves hold the four parts.", null);
        click(scene, CASE, part("cpu"), false);
        installDemoPart(scene, Device.Slot.CPU, "cpu");
        scene.idle(12);
        installDemoPart(scene, Device.Slot.RAM, "ram");
        scene.idle(12);
        installDemoPart(scene, Device.Slot.DRIVE, "drive");
        scene.idle(12);
        installDemoPart(scene, Device.Slot.PSU, "psu");
        note(scene, CASE, "Right-click the chassis with a CPU, RAM, drive and power supply to fill its four slots.", null);
        click(scene, CASE, part("ram_advanced"), false);
        installDemoPart(scene, Device.Slot.RAM, "ram_advanced");
        note(scene, CASE, "Installing a new part returns the old one. A faster CPU runs commands sooner, and more RAM lets you type longer lines.", null);
        installDemoPart(scene, Device.Slot.DRIVE, "drive_advanced");
        note(scene, CASE, "A disk drive stores 128 KiB of files and 1024 lines of terminal history. A cassette drive stores 16 KiB and 768 lines.", part("drive_advanced"));
        click(scene, CASE, null, true);
        scene.world().modifyBlockEntity(CASE, Device.class, computer -> computer.parts[Device.Slot.PSU.ordinal()] = ItemStack.EMPTY);
        note(scene, CASE, "Right-click with an empty hand for status. Sneak and right-click with an empty hand to remove a part.", null);
        scene.markAsFinished();
    }

    private static void installDemoPart(SceneBuilder scene, Device.Slot slot, String name) {
        scene.world().modifyBlockEntity(CASE, Device.class, computer -> computer.parts[slot.ordinal()] = new ItemStack(part(name)));
    }

    private static void power(SceneBuilder scene, SceneBuildingUtil util) {
        start(scene, "power", "Supplying Forge Energy");
        show(scene, util, CASE);
        BlockPos alternator = CASE.west();
        showAlternator(scene, util, alternator);
        note(scene, alternator, "Put a Create Crafts & Additions Alternator against the chassis and drive it at 32 RPM or above.", null);
        note(scene, CASE, "The base build draws 6 FE/t. A fully upgraded build draws 17 FE/t and needs the 32 FE/t power supply.", part("psu_advanced"));
        note(scene, CASE, "The basic power supply supports up to 12 FE/t. Check the chassis status if the computer will not start.", part("psu"));
        show(scene, util, SHAFT);
        show(scene, util, MOTOR);
        note(scene, MOTOR, "In Creative, connect a Creative Motor to the Alternator shaft for a quick test. Set the motor to 32 RPM or more.", null);
        note(scene, alternator, "In survival, use a water wheel. A large cogwheel driving a small one doubles the speed. Repeat until a Speedometer shows at least 32 RPM.", null);
        scene.markAsFinished();
    }

    private static void showAlternator(SceneBuilder scene, SceneBuildingUtil util, BlockPos pos) {
        var alternator = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("createaddition", "alternator"));
        if (alternator != net.minecraft.world.level.block.Blocks.AIR) {
            scene.world().setBlock(pos, alternator.defaultBlockState()
                .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING, Direction.SOUTH), false);
            show(scene, util, pos);
        }
    }

    private static void linkDemo(SceneBuilder scene, BlockPos origin, int width, int height) {
        scene.world().modifyBlockEntity(KEYBOARD, Device.class, keyboard -> {
            if (keyboard.getLevel().getBlockEntity(CASE) instanceof Device computer) keyboard.bind(computer);
        });
        scene.world().modifyBlockEntity(CASE, Device.class, computer -> {
            computer.powered = true;
            computer.output = TerminalOutput.greeting();
        });
        BlockPos corner = origin.east(width - 1).below(height - 1);
        for (BlockPos position : BlockPos.betweenClosed(origin, corner)) {
            scene.world().modifyBlockEntity(position.immutable(), Device.class, panel -> {
                if (panel.getLevel().getBlockEntity(CASE) instanceof Device computer) {
                    panel.bind(computer);
                    panel.origin = origin;
                    panel.width = width;
                    panel.height = height;
                }
            });
        }
    }

    private static void sensors(SceneBuilder scene, SceneBuildingUtil util) {
        start(scene, "sensors", "Motion sensors and Create instruments");
        show(scene, util, CASE);
        installDemoPart(scene, Device.Slot.MODEM, "modem");
        BlockPos tilt = new BlockPos(1, 1, 1), speed = tilt.east(), altitude = speed.east();
        var blocks = List.of(RetroComputers.TILT.get(), RetroComputers.MOTION.get(), RetroComputers.ALTIMETER.get());
        var positions = List.of(tilt, speed, altitude);
        for (int i = 0; i < blocks.size(); i++) {
            scene.world().setBlock(positions.get(i), blocks.get(i).defaultBlockState(), false);
            show(scene, util, positions.get(i));
        }
        note(scene, tilt, "These three passive instruments need no electricity or shaft. Make them with sequenced assembly; press R in JEI to see the complete recipe.", null);
        note(scene, tilt, "The cyan Tilt Sensor measures pitch and roll in degrees. The dial face defines forward. Upright in the ordinary world both readings are zero.", RetroComputers.TILT.get());
        note(scene, speed, "The orange Motion Speedometer measures movement in meters per second and kilometers per hour. It is not shaft RPM. A stationary block reads zero.", RetroComputers.MOTION.get());
        note(scene, altitude, "The green Altimeter measures the world Y coordinate of its center, not clearance above the ground. At block Y=64 it reads 64.50 meters.", RetroComputers.ALTIMETER.get());
        note(scene, altitude, "Wear Engineer's Goggles and look at an instrument to see its readings. On a Sable structure the sensors use its world pose and actual point velocity, including rotation.", null);
        BlockPos adapter = altitude.above();
        scene.world().setBlock(adapter, RetroComputers.INTERFACE.get().defaultBlockState(), false);
        show(scene, util, adapter);
        note(scene, adapter, "Mount an Interface Module on the sensor. Install a modem in a powered computer, select that computer with the Linking Probe, then click the module.", RetroComputers.INTERFACE.get());
        note(scene, CASE, "PORTS lists the connections; READ m1 shows the readings. In Python: import computer, then data = computer.read('m1'). Use data['altitude_m'], data['speed_mps'], or data['roll_deg'] for the corresponding sensor.", null);
        note(scene, adapter, "The same module reads Create's Speedometer and Stressometer: rpm is shaft speed, stress_su is load, capacity_su is capacity, stress_percent is usage. Mount and link them exactly like a sensor.", null);
        scene.markAsFinished();
    }

    private static void modules(SceneBuilder scene, SceneBuildingUtil util) {
        start(scene, "modules", "Connecting external blocks");
        show(scene, util, CASE);
        installDemoPart(scene, Device.Slot.MODEM, "modem");
        note(scene, CASE, "Install a Wired Modem by right-clicking the chassis. The computer works without it, but external modules require one.", part("modem"));
        BlockPos target = new BlockPos(4, 1, 3);
        show(scene, util, target);
        show(scene, util, target.above());
        scene.world().modifyBlockEntity(target.above(), Device.class, module -> {
            if (module.getLevel().getBlockEntity(CASE) instanceof Device computer) {
                computer.network.connect(module);
                module.bind(computer);
                module.portName = "m1";
            }
        });
        note(scene, target.above(), "Place an Interface Module on the face of a block. Select the computer with the Linking Probe, then click the module. Each module gets a name such as m1.", RetroComputers.INTERFACE.get());
        note(scene, CASE, "Type PORTS to list modules and READ m1 to read its block. Depending on the block, you see redstone, Create RPM, item counts and tank contents.", null);
        note(scene, target, "SET m1 15 outputs redstone from the module. SET m1 0 switches it off. Use a lamp, a clutch or a gearshift to control machines.", null);
        note(scene, target.above(), "Blue cables show the module connections. Spark traces appear when linking. UNLINK m1 disconnects a module. Removing the modem disables its outputs.", null);
        scene.markAsFinished();
    }

    private static void python(SceneBuilder scene, SceneBuildingUtil util) {
        start(scene, "python", "Naming a computer and running Python");
        show(scene, util, CASE);
        show(scene, util, KEYBOARD);
        scene.world().showSection(util.select().fromTo(SCREEN, DISPLAY_STAND.east()), Direction.DOWN);
        linkDemo(scene, SCREEN, 2, 2);
        note(scene, KEYBOARD, "At the first boot enter a name, for example lab. The name stays with this computer. NAME new_name renames it later.", null);
        note(scene, CASE, "Open mods/retrocomputers/computers/lab in your real Minecraft folder. Each computer has its own folder. Old files are migrated here after naming.", null);
        note(scene, KEYBOARD, "Try RUN /hello.py. To create another program, save a UTF-8 .py file in the folder with a text editor. RUN reads the file when you start it.", null);
        note(scene, CASE, "In Python use import computer. computer.read('m1') returns block data; computer.set('m1', 15) outputs redstone. A modem and linked module are required.", null);
        note(scene, KEYBOARD, "Use open('log.txt', 'w') to write text files, and print() for the monitor. STOP ends a program. Stop it before renaming the computer.", null);
        scene.markAsFinished();
    }

    private static void files(SceneBuilder scene, SceneBuildingUtil util) {
        start(scene, "files", "Files and directories");
        show(scene, util, CASE);
        show(scene, util, KEYBOARD);
        scene.world().showSection(util.select().fromTo(SCREEN, DISPLAY_STAND.east()), Direction.DOWN);
        linkDemo(scene, SCREEN, 2, 2);
        note(scene, KEYBOARD, "Open the keyboard. Type MKDIR /notes and press Enter, then CD /notes to enter that folder. PWD shows your current folder.", null);
        note(scene, KEYBOARD, "Type WRITE readme.txt Hello world to create a text file. CAT readme.txt reads it. APPEND readme.txt adds more text.", null);
        note(scene, SCREEN, "DIR lists a folder, TREE / shows the whole disk. Scroll with the mouse wheel while the keyboard is open to read older output.", null);
        note(scene, CASE, "Files live in mods/retrocomputers/computers/ followed by the computer name. Edit them in a text editor. DF shows the capacity: cassette 16 KiB, disk 128 KiB.", null);
        note(scene, KEYBOARD, "RM removes a file; RMDIR removes an empty folder. CD / goes to the root. Use names without spaces. File names ignore case; text keeps its case.", null);
        scene.markAsFinished();
    }

    private static void terminal(SceneBuilder scene, SceneBuildingUtil util) {
        start(scene, "terminal", "Linking and using a terminal");
        show(scene, util, CASE);
        show(scene, util, KEYBOARD);
        scene.world().showSection(util.select().fromTo(SCREEN, DISPLAY_STAND.east()), Direction.DOWN);
        scene.idle(8);
        note(scene, SCREEN, "Build a filled rectangle, up to 50 by 50 panels. All panels must face the same way in the same plane.", null);
        click(scene, CASE, RetroComputers.LINKER.get(), false);
        note(scene, CASE, "Right-click the computer with the probe to select it.", null);
        click(scene, KEYBOARD, RetroComputers.LINKER.get(), false);
        note(scene, KEYBOARD, "Right-click the keyboard with the same probe.", null);
        click(scene, SCREEN, RetroComputers.LINKER.get(), false);
        linkDemo(scene, SCREEN, 2, 2);
        note(scene, SCREEN, "Click one panel to link the whole display. Keep the devices within 64 blocks of the computer and link again after resizing.", null);
        click(scene, KEYBOARD, null, false);
        scene.world().cycleBlockProperty(KEYBOARD, DeviceBlock.PRESSED);
        scene.idle(4);
        scene.world().cycleBlockProperty(KEYBOARD, DeviceBlock.PRESSED);
        note(scene, KEYBOARD, "Open the keyboard with a right-click. Type HELP and press Enter. Try INFO, ECHO HELLO and CLS.", null);
        scene.rotateCameraY(150);
        scene.idle(24);
        note(scene, CASE, "The cables appear when you connect the devices. Look behind the chassis for the green monitor cable and the brown keyboard cable.", null);
        scene.markAsFinished();
    }
}
