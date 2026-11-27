package io.github.retrocomputers;

import com.simibubi.create.content.processing.sequenced.SequencedAssemblyItem;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.network.PacketDistributor;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DropExperienceBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.util.valueproviders.ConstantInt;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.registries.*;

@Mod(RetroComputers.ID)
public final class RetroComputers {
    public static final String ID = "retrocomputers";
    static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(ID);
    static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(ID);
    static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(Registries.SOUND_EVENT, ID);
    static final DeferredHolder<SoundEvent, SoundEvent> KEY_TAP = SOUNDS.register("keyboard.tap",
        () -> SoundEvent.createVariableRangeEvent(id("keyboard.tap")));
    static final DeferredHolder<SoundEvent, SoundEvent> KEY_BAR = SOUNDS.register("keyboard.bar",
        () -> SoundEvent.createVariableRangeEvent(id("keyboard.bar")));
    static final DeferredRegister<BlockEntityType<?>> ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, ID);
    static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, ID);
    static final Map<String, DeferredItem<Item>> PARTS = new LinkedHashMap<>();
    static final List<String> ASSEMBLED_PARTS = List.of("cpu", "ram", "drive", "psu",
        "cpu_advanced", "ram_advanced", "drive_advanced", "psu_advanced");
    static final List<String> ASSEMBLED_EQUIPMENT = List.of("computer", "monitor", "keyboard", "linker");

    static final DeferredBlock<DeviceBlock> PC = device("computer", DeviceBlock.Kind.COMPUTER);
    static final DeferredBlock<DeviceBlock> PANEL = device("monitor", DeviceBlock.Kind.MONITOR);
    static final DeferredBlock<DeviceBlock> KEYBOARD = device("keyboard", DeviceBlock.Kind.KEYBOARD);
    static final DeferredBlock<InterfaceBlock> INTERFACE = BLOCKS.register("interface_module",
        () -> new InterfaceBlock(BlockBehaviour.Properties.of().strength(1.5F).noOcclusion()));
    static final List<String> SENSORS = List.of("tilt_sensor", "motion_speedometer", "altimeter");
    static final DeferredBlock<SensorBlock> TILT = sensor("tilt_sensor");
    static final DeferredBlock<SensorBlock> MOTION = sensor("motion_speedometer");
    static final DeferredBlock<SensorBlock> ALTIMETER = sensor("altimeter");
    static final DeferredHolder<BlockEntityType<?>, BlockEntityType<SensorBlockEntity>> SENSOR = ENTITIES.register("sensor",
        () -> BlockEntityType.Builder.of(SensorBlockEntity::new, TILT.get(), MOTION.get(), ALTIMETER.get()).build(null));
    static final DeferredBlock<DropExperienceBlock> SILICON = ore("silicon_ore");
    static final DeferredBlock<DropExperienceBlock> TIN = ore("tin_ore");
    static final DeferredItem<Item> LINKER = ITEMS.registerSimpleItem("linker", new Item.Properties().stacksTo(1));
    static final DeferredHolder<BlockEntityType<?>, BlockEntityType<Device>> DEVICE = ENTITIES.register("device",
        () -> BlockEntityType.Builder.of(Device::new, PC.get(), PANEL.get(), KEYBOARD.get(), INTERFACE.get()).build(null));

    static {
        ITEMS.registerSimpleBlockItem("interface_module", INTERFACE);
        PARTS.put("modem", ITEMS.registerSimpleItem("modem"));
        for (String name : new String[]{"raw_silicon", "raw_tin", "silicon_dust", "tin_dust", "tin_ingot",
                "tin_plate", "ceramic", "board", "crushed_silicon", "crushed_tin", "solder", "resin",
                "magnetic_tape", "ferrite_core", "silicon_blank", "silicon_boule", "silicon_wafer",
                "copper_trace", "wiring_harness", "keycaps", "crt_glass", "chassis_frame"}) {
            PARTS.put(name, ITEMS.registerSimpleItem(name));
        }
        ASSEMBLED_PARTS.forEach(name -> PARTS.put(name, ITEMS.registerSimpleItem(name)));
        var lines = new java.util.ArrayList<>(ASSEMBLED_PARTS);
        lines.add("board");
        lines.add("modem");
        lines.add("interface_module");
        lines.addAll(ASSEMBLED_EQUIPMENT);
        lines.addAll(SENSORS);
        for (String line : lines) {
            ITEMS.registerItem("incomplete_" + line, SequencedAssemblyItem::new, new Item.Properties());
        }
        TABS.register("equipment", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.retrocomputers"))
            .icon(() -> new ItemStack(PC.get()))
            .displayItems((parameters, output) -> ITEMS.getEntries().stream()
                .filter(item -> !item.getId().getPath().startsWith("incomplete_"))
                .forEach(item -> output.accept(item.get())))
            .build());
    }

    public RetroComputers(IEventBus bus) {
        BLOCKS.register(bus);
        ITEMS.register(bus);
        SOUNDS.register(bus);
        ENTITIES.register(bus);
        TABS.register(bus);
        bus.addListener(this::capabilities);
        bus.addListener(this::payloads);
    }

    static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(ID, path);
    }

    private static DeferredBlock<DeviceBlock> device(String name, DeviceBlock.Kind kind) {
        var block = BLOCKS.register(name, () -> new DeviceBlock(BlockBehaviour.Properties.of().strength(2).noOcclusion(), kind));
        ITEMS.registerSimpleBlockItem(name, block);
        return block;
    }

    private static DeferredBlock<SensorBlock> sensor(String name) {
        var block = BLOCKS.register(name, () -> new SensorBlock(BlockBehaviour.Properties.of().strength(2).noOcclusion()));
        ITEMS.registerSimpleBlockItem(name, block);
        return block;
    }

    private static DeferredBlock<DropExperienceBlock> ore(String name) {
        var block = BLOCKS.register(name, () -> new DropExperienceBlock(ConstantInt.of(0),
            BlockBehaviour.Properties.of().strength(3).requiresCorrectToolForDrops()));
        ITEMS.registerSimpleBlockItem(name, block);
        return block;
    }

    private void capabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, DEVICE.get(),
            (device, side) -> device.getBlockState().is(PC.get()) ? device.energy : null);
    }

    private void payloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("3");
        registrar.playBidirectional(KeyInput.TYPE, KeyInput.CODEC, (message, context) -> {
            var player = context.player();
            var level = player.level();
            BlockPos pos = message.keyboard();
            if (!KeyboardLayout.accepts(message.key()) || !level.hasChunkAt(pos)) return;
            if (!(level.getBlockEntity(pos) instanceof Device keyboard) || !keyboard.getBlockState().is(KEYBOARD.get())) return;
            if (context.flow() == PacketFlow.CLIENTBOUND) {
                if (!player.getUUID().equals(message.typist())) keyboard.animateKey(message.key());
                return;
            }
            if (!MovingDevices.inReach(player, pos) || !keyboard.allowKeystroke()) return;
            Device computer = keyboard.computer();
            if (computer != null && computer.powered) computer.key(message.key());
            DeviceBlock.playKeySound(level, pos, message.key());
            PacketDistributor.sendToPlayersTrackingChunk((ServerLevel) level, new ChunkPos(pos),
                new KeyInput(pos, message.key(), player.getUUID()));
        });
        registrar.playToServer(EditorPaste.TYPE, EditorPaste.CODEC, (message, context) -> {
            var player = context.player();
            var level = player.level();
            if (!level.hasChunkAt(message.keyboard()) || !MovingDevices.inReach(player, message.keyboard())) return;
            if (!(level.getBlockEntity(message.keyboard()) instanceof Device keyboard)
                    || !keyboard.getBlockState().is(KEYBOARD.get()) || !keyboard.allowKeystroke()) return;
            Device computer = keyboard.computer();
            if (computer != null && computer.powered && computer.editor.active()) computer.editor.paste(message.text());
        });
    }

    record EditorPaste(BlockPos keyboard, String text) implements CustomPacketPayload {
        static final Type<EditorPaste> TYPE = new Type<>(id("editor_paste"));
        static final StreamCodec<RegistryFriendlyByteBuf, EditorPaste> CODEC = new StreamCodec<>() {
            public EditorPaste decode(RegistryFriendlyByteBuf buffer) { return new EditorPaste(buffer.readBlockPos(), buffer.readUtf(8192)); }
            public void encode(RegistryFriendlyByteBuf buffer, EditorPaste value) { buffer.writeBlockPos(value.keyboard()); buffer.writeUtf(value.text(), 8192); }
        };
        public Type<EditorPaste> type() { return TYPE; }
    }

    record KeyInput(BlockPos keyboard, int key, java.util.UUID typist) implements CustomPacketPayload {
        static final Type<KeyInput> TYPE = new Type<>(id("key"));
        static final StreamCodec<RegistryFriendlyByteBuf, KeyInput> CODEC = new StreamCodec<>() {
            public KeyInput decode(RegistryFriendlyByteBuf buffer) {
                return new KeyInput(buffer.readBlockPos(), buffer.readVarInt(), buffer.readUUID());
            }
            public void encode(RegistryFriendlyByteBuf buffer, KeyInput value) {
                buffer.writeBlockPos(value.keyboard());
                buffer.writeVarInt(value.key());
                buffer.writeUUID(value.typist());
            }
        };
        public Type<KeyInput> type() { return TYPE; }
    }
}
