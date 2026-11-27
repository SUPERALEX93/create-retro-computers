package io.github.retrocomputers;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import net.minecraft.ChatFormatting;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.energy.EnergyStorage;

final class Device extends BlockEntity implements IHaveGoggleInformation {
    enum Slot { CPU, RAM, DRIVE, PSU, MODEM }

    final ItemStack[] parts = {ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY};
    final Buffer energy = new Buffer();

    final class Buffer extends EnergyStorage {
        Buffer() {
            super(4000, 256, 0);
        }

        void consume(int cost) {
            energy -= cost;
            setChanged();
        }

        @Override
        public int receiveEnergy(int amount, boolean simulate) {
            int received = super.receiveEnergy(amount, simulate);
            if (!simulate && received > 0) setChanged();
            return received;
        }
    }

    UUID identity = UUID.randomUUID();
    UUID computerId;
    BlockPos computerPos;
    BlockPos origin;
    int width = 1;
    int height = 1;
    boolean powered;
    String input = "";
    List<Component> output = List.of();
    boolean programRunning;
    boolean programWaiting;
    String programPrompt = "";
    String programPartial = "";
    List<Component> consoleBeforeProgram = List.of();
    private final List<String> commandHistory = new ArrayList<>();
    private int historyIndex;
    private String historyDraft = "";
    final ComputerFiles files = new ComputerFiles();
    final DeviceNetwork network = new DeviceNetwork(this);
    final PythonPrograms python = new PythonPrograms(this);
    final TerminalEditor editor = new TerminalEditor(this);
    String computerName = "";
    private boolean setupPrompted;

    int diskCapacity() { return parts[Slot.DRIVE.ordinal()].isEmpty() ? 0 : advanced(Slot.DRIVE) ? 131072 : 16384; }

    private void ensureStorage() throws java.io.IOException {
        if (files.root() == null) files.mount(ComputerStorage.claim(computerName, identity));
    }

    void appendOutput(Component line) {
        appendOutputs(List.of(line));
    }

    void appendOutputs(List<Component> added) {
        if (added.isEmpty()) return;
        List<Component> lines = new ArrayList<>(output);
        lines.addAll(added);
        int limit = advanced(Slot.DRIVE) ? 1024 : 768;
        output = List.copyOf(lines.subList(Math.max(0, lines.size() - limit), lines.size()));
        refresh();
    }

    @Override
    public void setRemoved() {
        python.stop();
        super.setRemoved();
    }
    int requestedSignal;
    int activeSignal;
    String portName = "";

    boolean hasModem() { return !parts[Slot.MODEM.ordinal()].isEmpty(); }

    net.minecraft.core.Direction facing() {
        return getBlockState().is(RetroComputers.INTERFACE.get())
            ? getBlockState().getValue(InterfaceBlock.FACE) : getBlockState().getValue(DeviceBlock.FACING);
    }
    int scrollRows;
    int zoomStep = TerminalZoom.DEFAULT;
    private String syncedDirectory = "/";

    String directory() { return level != null && level.isClientSide ? syncedDirectory : files.directory(); }
    private final ArrayDeque<Integer> keys = new ArrayDeque<>();
    private final ArrayDeque<Command> commands = new ArrayDeque<>();

    private record Command(String line, long readyAt) {}
    private long keystrokeTick = Long.MIN_VALUE;
    private int keystrokesThisTick;
    private long[] keyAnimation;

    Device(BlockPos pos, BlockState state) {
        super(RetroComputers.DEVICE.get(), pos, state);
    }

    static void tick(Level level, BlockPos pos, BlockState state, Device computer) {
        computer.python.updateCapacity(computer.diskCapacity());
        if (computer.programRunning && !computer.python.running()) {
            computer.programRunning = false;
            computer.programWaiting = false;
            computer.programPrompt = computer.programPartial = computer.input = "";
            computer.output = computer.consoleBeforeProgram;
            computer.consoleBeforeProgram = List.of();
            computer.appendOutput(PythonPrograms.text("stopped"));
        }
        if (level.getGameTime() % 5 == 0) computer.network.reconcile();
        int cost = computer.cost();
        boolean powered = cost > 0 && cost <= computer.capacity() && computer.energy.getEnergyStored() >= cost;
        if (powered) computer.energy.consume(cost);
        boolean changed = computer.powered != powered;
        computer.powered = powered;
        if (changed) {
            if (powered && computer.output.isEmpty()) {
                computer.output = computer.computerName.isEmpty()
                    ? List.of(TerminalOutput.message("name_prompt")) : TerminalOutput.greeting();
            }
            if (!powered) {
                computer.python.stop();
                computer.input = "";
                computer.keys.clear();
                computer.commands.clear();
            }
        }
        if (powered && computer.computerName.isEmpty() && !computer.setupPrompted) {
            computer.output = List.of(TerminalOutput.message("name_prompt"));
            computer.setupPrompted = true;
            changed = true;
        }
        if (powered) changed |= computer.updateTerminal(level.getGameTime());
        if (changed) computer.refresh();
    }

    private boolean updateTerminal(long now) {
        String oldInput = input;
        List<Component> oldOutput = output;
        Command command = commands.peek();
        if (command != null && command.readyAt() <= now) runCommand(commands.remove().line());
        while (!keys.isEmpty()) accept(keys.remove(), now);
        return !input.equals(oldInput) || !output.equals(oldOutput);
    }

    boolean advanced(Slot slot) {
        if (slot == Slot.MODEM) return false;
        return parts[slot.ordinal()].is(RetroComputers.PARTS.get(slot.name().toLowerCase(java.util.Locale.ROOT) + "_advanced").get());
    }

    int capacity() {
        return parts[Slot.PSU.ordinal()].isEmpty() ? 0 : advanced(Slot.PSU) ? 32 : 12;
    }

    int cost() {
        for (Slot slot : new Slot[]{Slot.CPU, Slot.RAM, Slot.DRIVE, Slot.PSU})
            if (parts[slot.ordinal()].isEmpty()) return 0;
        return 6 + (advanced(Slot.CPU) ? 6 : 0) + (advanced(Slot.RAM) ? 3 : 0) + (advanced(Slot.DRIVE) ? 2 : 0) + (hasModem() ? 2 : 0);
    }

    Device computer() {
        if (level != null && getBlockState().is(RetroComputers.KEYBOARD.get()) && getBlockState().getValue(DeviceBlock.RIGHT)) {
            BlockPos main = DeviceBlock.keyboardMain(worldPosition, getBlockState());
            if (level.hasChunkAt(main) && level.getBlockEntity(main) instanceof Device keyboard
                    && keyboard.getBlockState().is(RetroComputers.KEYBOARD.get())
                    && !keyboard.getBlockState().getValue(DeviceBlock.RIGHT)
                    && keyboard.getBlockState().getValue(DeviceBlock.FACING) == getBlockState().getValue(DeviceBlock.FACING)) {
                return keyboard.computer();
            }
            return null;
        }
        if (level == null || computerPos == null || !level.hasChunkAt(computerPos)) return null;
        if (level.getBlockEntity(computerPos) instanceof Device device
                && device.getBlockState().is(RetroComputers.PC.get()) && device.identity.equals(computerId)) return device;
        return null;
    }

    void bind(Device computer) {
        computerId = computer.identity;
        computerPos = computer.getBlockPos();
    }

    boolean allowKeystroke() {
        long now = level.getGameTime();
        if (keystrokeTick != now) {
            keystrokeTick = now;
            keystrokesThisTick = 0;
        }
        if (keystrokesThisTick >= 6) return false;
        keystrokesThisTick++;
        return true;
    }

    void animateKey(int key) {
        if (keyAnimation == null) keyAnimation = new long[128];
        int physical = KeyboardLayout.physicalKey(key);
        if (physical >= 0 && physical < keyAnimation.length) keyAnimation[physical] = Util.getMillis();
    }

    float keyDepth(int key, long now) {
        if (keyAnimation == null || keyAnimation[key] == 0) return 0;
        long age = now - keyAnimation[key];
        if (age < 25) return age / 25F;
        if (age < 80) return 1;
        return Math.max(0, 1 - (age - 80) / 90F);
    }

    void key(int key) {
        if (keys.size() < 64 && KeyboardLayout.accepts(key)) keys.add(key);
    }

    private void accept(int key, long now) {
        if (editor.active()) { editor.key(key); return; }
        if (key == KeyboardLayout.INTERRUPT) { python.stop(); return; }
        if (programRunning && !programWaiting) return;
        if (key == KeyboardLayout.PREVIOUS || key == KeyboardLayout.NEXT) {
            if (programRunning || commandHistory.isEmpty()) return;
            if (historyIndex == commandHistory.size()) historyDraft = input;
            historyIndex = Math.clamp(historyIndex + (key == KeyboardLayout.PREVIOUS ? -1 : 1), 0, commandHistory.size());
            input = historyIndex == commandHistory.size() ? historyDraft : commandHistory.get(historyIndex);
            return;
        }
        if (key == KeyboardLayout.DELETE) {
            if (!input.isEmpty()) input = input.substring(0, input.length() - 1);
            return;
        }
        if (key >= KeyboardLayout.PREVIOUS) return;
        if (key != KeyboardLayout.RETURN) {
            if (input.length() < (advanced(Slot.RAM) ? 1024 : 256)) input += (char) key;
            return;
        }
        if (programWaiting) { python.submitInput(input); return; }
        String command = input.strip();
        input = "";
        if (command.isEmpty() || commands.size() >= 64) return;
        if (commandHistory.isEmpty() || !commandHistory.getLast().equals(command)) commandHistory.add(command);
        if (commandHistory.size() > 100) commandHistory.removeFirst();
        historyIndex = commandHistory.size();
        historyDraft = "";
        int duration = advanced(Slot.CPU) ? 1 : 4;
        Command previous = commands.peekLast();
        long start = previous == null ? now : Math.max(now, previous.readyAt());
        commands.add(new Command(command, start + duration));
    }

    private void runCommand(String command) {
        if (programRunning) return;
        String[] words = command.split("\s+", 2);
        String operation = words[0].toUpperCase(java.util.Locale.ROOT);
        String arguments = words.length > 1 ? words[1] : "";
        if (computerName.isEmpty()) {
            if (operation.equals("HELP")) {
                appendOutput(TerminalOutput.message("name_prompt"));
                return;
            }
            if (operation.equals("NAME") || words.length == 1) {
                assignName(operation.equals("NAME") ? arguments : command);
            } else appendOutput(TerminalOutput.message("name_prompt"));
            return;
        }
        if (operation.equals("NAME")) { assignName(arguments); return; }
        if (operation.equals("STOP")) {
            python.stop();
            appendOutput(PythonPrograms.text("stopped"));
            return;
        }
        if (operation.equals("CLS") && arguments.isEmpty()) {
            output = List.of();
            return;
        }
        appendOutput(Component.literal(computerName + ":" + files.directory() + ">" + command));
        try {
            ensureStorage();
            List<Component> result = switch (operation) {
                case "HELP" -> {
                    List<Component> help = new ArrayList<>();
                    for (String name : new String[]{"HELP", "INFO", "NAME", "ECHO", "CLS", "RUN", "PYTHON", "STOP",
                            "PWD", "CD", "DIR", "LS", "TREE", "MKDIR", "TOUCH", "WRITE", "APPEND", "CAT", "TYPE", "RM", "DEL", "RMDIR", "DF",
                            "PORTS", "READ", "SET", "UNLINK", "NANO", "EDIT", "LANGS", "EXAMPLES", "LUA", "C", "CPP", "CSHARP", "JAVA", "RUST", "DISK", "MEM"}) {
                        help.add(TerminalOutput.message("command." + name.toLowerCase(java.util.Locale.ROOT)));
                    }
                    yield help;
                }
                case "INFO" -> List.of(TerminalOutput.message("hardware", advanced(Slot.CPU) ? 4 : 1, advanced(Slot.RAM) ? 64 : 16),
                    TerminalOutput.message("power", cost(), capacity()), TerminalOutput.message("name", computerName));
                case "DF", "DISK", "MEM" -> {
                    var status = new ArrayList<Component>();
                    if (operation.equals("MEM")) status.add(TerminalOutput.message("ram", advanced(Slot.RAM) ? 64 : 16));
                    status.add(TerminalOutput.message("drive", parts[Slot.DRIVE.ordinal()].getHoverName()));
                    status.addAll(files.execute("DF", arguments, diskCapacity()));
                    yield status;
                }
                case "NANO", "EDIT" -> {
                    Component error = editor.open(arguments.strip());
                    yield error == null ? List.of() : List.of(error);
                }
                case "LANGS" -> {
                    var languages = new ArrayList<Component>();
                    languages.add(Component.literal("Python: " + ProgramExamples.message("bundled").getString()));
                    for (String language : List.of("Lua", "C", "C++", "C#", "Java", "Rust")) {
                        String missing = NativePrograms.availability(language);
                        languages.add(Component.literal(language + ": ").append(ProgramExamples.message(missing.isEmpty() ? "ready" : "missing", missing)));
                    }
                    yield languages;
                }
                case "EXAMPLES" -> ProgramExamples.install(this);
                case "RUN", "PYTHON", "LUA", "C", "CPP", "CSHARP", "JAVA", "RUST" -> {
                    String expected = switch (operation) { case "PYTHON" -> "Python"; case "LUA" -> "Lua"; case "CPP" -> "C++"; case "CSHARP" -> "C#"; case "JAVA" -> "Java"; case "RUST" -> "Rust"; default -> operation; };
                    String language = arguments.toLowerCase(java.util.Locale.ROOT).endsWith(".py") ? "Python" : NativePrograms.language(arguments);
                    if (!operation.equals("RUN") && !expected.equals(language)) yield List.of(PythonPrograms.text("extension"));
                    Component error = python.start(arguments.strip());
                    yield error == null ? List.of() : List.of(error);
                }
                case "PORTS", "READ", "SET", "UNLINK" -> network.execute(operation, arguments);
                case "ECHO" -> List.of(Component.literal(arguments));
                default -> files.execute(operation, arguments, diskCapacity());
            };
            appendOutputs(result);
        } catch (Exception error) {
            appendOutput(Component.translatable("retrocomputers.files.io_error", String.valueOf(error.getMessage())));
        }
    }

    void clearPendingCommands() { commands.clear(); keys.clear(); }
    void runEditorFile(String file) { runCommand("RUN " + file); }

    private void assignName(String input) {
        if (python.running()) { appendOutput(PythonPrograms.text("stop_before_name")); return; }
        try {
            String name = ComputerStorage.validName(input);
            java.nio.file.Path root;
            if (computerName.isEmpty()) root = ComputerStorage.claim(name, identity);
            else {
                ensureStorage();
                root = ComputerStorage.rename(files.root(), name, identity);
            }
            files.mount(root);
            computerName = name;
            setChanged();
            appendOutput(TerminalOutput.message("named", name));
            if (!files.fileExists("/hello.py")) files.writeText("/hello.py",
                "import computer\nprint('Hello from', computer.name)\n", diskCapacity());
            appendOutput(TerminalOutput.message("python_example"));
        } catch (Exception error) {
            appendOutput(TerminalOutput.message("name_error", String.valueOf(error.getMessage())));
        }
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean sneaking) {
        tooltip.add(Component.translatable("retrocomputers.goggles.title").withStyle(ChatFormatting.GOLD));
        if (getBlockState().is(RetroComputers.PC.get())) {
            tooltip.add(TerminalOutput.message("name", computerName.isEmpty() ? "?" : computerName));
            tooltip.add(Component.translatable("retrocomputers.goggles.energy", energy.getEnergyStored(), energy.getMaxEnergyStored()));
            tooltip.add(Component.translatable("retrocomputers.goggles.load", cost(), capacity()));
            tooltip.add(Component.translatable("retrocomputers." + (powered ? "online" : "offline"))
                .withStyle(powered ? ChatFormatting.GREEN : ChatFormatting.RED));
            for (Slot slot : Slot.values()) {
                ItemStack item = parts[slot.ordinal()];
                Component name = item.isEmpty()
                    ? Component.translatable("retrocomputers.missing." + slot.name().toLowerCase(java.util.Locale.ROOT))
                    : item.getHoverName();
                tooltip.add(Component.literal("  ").append(name));
            }
        } else {
            if (getBlockState().is(RetroComputers.INTERFACE.get())) {
                tooltip.add(DeviceNetwork.message("status", portName, activeSignal));
            }
            Device computer = computer();
            String status = computer == null ? "disconnected" : computer.powered ? "online" : "offline";
            tooltip.add(Component.translatable("retrocomputers." + status));
            if (computer != null) {
                BlockPos pos = computer.getBlockPos();
                tooltip.add(Component.translatable("retrocomputers.goggles.connected", pos.getX(), pos.getY(), pos.getZ()));
            }
            if (getBlockState().is(RetroComputers.PANEL.get()) && origin != null) {
                tooltip.add(Component.translatable("retrocomputers.screen", width, height));
            }
        }
        return true;
    }

    void refresh() {
        python.updateCapacity(diskCapacity());
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putUUID("Identity", identity);
        if (computerPos != null && computerId != null) {
            tag.putLong("ComputerPos", computerPos.asLong());
            tag.putUUID("ComputerId", computerId);
        }
        if (origin != null) tag.putLong("Origin", origin.asLong());
        tag.putLong("SavedPosition", worldPosition.asLong());
        tag.putInt("SavedFacing", facing().get2DDataValue());
        tag.put("DeviceNetwork", network.save());
        tag.putInt("RequestedSignal", requestedSignal);
        tag.putInt("ActiveSignal", activeSignal);
        tag.putString("PortName", portName);
        tag.putInt("Width", width);
        tag.putInt("Height", height);
        tag.putInt("Energy", energy.getEnergyStored());
        tag.putBoolean("Powered", powered);
        tag.putString("Input", input);
        tag.put("Editor", editor.save(registries));
        tag.putBoolean("ProgramRunning", programRunning);
        tag.putBoolean("ProgramWaiting", programWaiting);
        tag.putString("ProgramPrompt", programPrompt);
        tag.putString("ProgramPartial", programPartial);
        if (programRunning) {
            CompoundTag console = new CompoundTag();
            TerminalOutput.save(console, registries, consoleBeforeProgram);
            tag.put("ProgramConsole", console);
        }
        tag.putString("ComputerName", computerName);
        if (getBlockState().is(RetroComputers.PC.get())) tag.put("FileSystem", files.save());
        if (!output.isEmpty()) TerminalOutput.save(tag, registries, output);
        for (Slot slot : Slot.values()) tag.put(slot.name(), parts[slot.ordinal()].saveOptional(registries));
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.hasUUID("Identity")) identity = tag.getUUID("Identity");
        computerId = tag.hasUUID("ComputerId") ? tag.getUUID("ComputerId") : null;
        computerPos = computerId == null ? null : BlockPos.of(tag.getLong("ComputerPos"));
        origin = tag.contains("Origin") ? BlockPos.of(tag.getLong("Origin")) : null;
        java.util.function.UnaryOperator<BlockPos> move = position -> position;
        if (tag.contains("SavedPosition") && (tag.getLong("SavedPosition") != worldPosition.asLong()
                || tag.getInt("SavedFacing") != facing().get2DDataValue())) {
            BlockPos previous = BlockPos.of(tag.getLong("SavedPosition"));
            var oldFacing = net.minecraft.core.Direction.from2DDataValue(tag.getInt("SavedFacing"));
            var newFacing = facing();
            int turns = Math.floorMod(newFacing.get2DDataValue() - oldFacing.get2DDataValue(), 4);
            move = position -> relocate(position, previous, turns);
            if (computerPos != null) computerPos = relocate(computerPos, previous, turns);
            if (origin != null) origin = relocate(origin, previous, turns);
        }
        network.load(tag.getCompound("DeviceNetwork"), move);
        requestedSignal = Math.clamp(tag.getInt("RequestedSignal"), 0, 15);
        activeSignal = Math.clamp(tag.getInt("ActiveSignal"), 0, 15);
        portName = tag.getString("PortName");
        width = Math.clamp(tag.getInt("Width"), 1, 50);
        height = Math.clamp(tag.getInt("Height"), 1, 50);
        energy.deserializeNBT(registries, IntTag.valueOf(tag.getInt("Energy")));
        powered = tag.getBoolean("Powered");
        input = tag.getString("Input");
        editor.load(tag.getCompound("Editor"), registries);
        programRunning = tag.getBoolean("ProgramRunning");
        programWaiting = programRunning && tag.getBoolean("ProgramWaiting");
        programPrompt = programRunning ? tag.getString("ProgramPrompt") : "";
        programPartial = programRunning ? tag.getString("ProgramPartial") : "";
        consoleBeforeProgram = programRunning ? TerminalOutput.load(tag.getCompound("ProgramConsole"), registries) : List.of();
        computerName = tag.getString("ComputerName");
        if (tag.contains("FileSystem")) files.load(tag.getCompound("FileSystem"));
        syncedDirectory = tag.contains("WorkingDirectory") ? tag.getString("WorkingDirectory") : files.directory();
        output = TerminalOutput.load(tag, registries);
        for (Slot slot : Slot.values()) parts[slot.ordinal()] = ItemStack.parseOptional(registries, tag.getCompound(slot.name()));
    }

    private BlockPos relocate(BlockPos point, BlockPos previous, int turns) {
        BlockPos offset = point.subtract(previous);
        for (int i = 0; i < turns; i++) offset = new BlockPos(-offset.getZ(), offset.getY(), offset.getX());
        return worldPosition.offset(offset);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = saveWithoutMetadata(registries);
        tag.remove("FileSystem");
        tag.remove("ProgramConsole");
        tag.getCompound("Editor").remove("Saved");
        tag.getCompound("Editor").remove("Console");
        tag.putString("WorkingDirectory", files.directory());
        return tag;
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
