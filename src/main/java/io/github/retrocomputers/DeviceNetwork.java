package io.github.retrocomputers;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.gauge.StressGaugeBlockEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.UnaryOperator;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.capabilities.Capabilities;

final class DeviceNetwork {
    private final Device owner;
    private final Map<String, Endpoint> endpoints = new TreeMap<>();
    private record Endpoint(BlockPos pos, UUID id) {}

    DeviceNetwork(Device owner) { this.owner = owner; }

    String connect(Device module) {
        endpoints.entrySet().removeIf(entry -> entry.getValue().id().equals(module.identity));
        for (int i = 1; i <= 32; i++) {
            String name = "m" + i;
            Endpoint existing = endpoints.get(name);
            if (existing != null && owner.getLevel().hasChunkAt(existing.pos())
                    && owner.getLevel().getBlockEntity(existing.pos()) instanceof Device device
                    && device.identity.equals(existing.id()) && owner.identity.equals(device.computerId)) continue;
            endpoints.put(name, new Endpoint(module.getBlockPos(), module.identity));
            return name;
        }
        return null;
    }

    List<Component> execute(String command, String arguments) {
        if (!owner.hasModem()) return List.of(message("no_modem"));
        String[] args = arguments.isBlank() ? new String[0] : arguments.strip().split("\s+");
        if (command.equals("PORTS")) {
            List<Component> result = new ArrayList<>();
            endpoints.forEach((name, endpoint) -> result.add(message("entry", name,
                Component.translatable("retrocomputers.network." + (module(endpoint) == null ? "offline" : "online")))));
            if (result.isEmpty()) result.add(message("empty"));
            return result;
        }
        if (args.length == 0) return List.of(message("usage"));
        Endpoint endpoint = endpoints.get(args[0].toLowerCase(Locale.ROOT));
        Device module = endpoint == null ? null : module(endpoint);
        if (module == null) return List.of(message("missing"));
        if (command.equals("UNLINK")) {
            if (args.length != 1) return List.of(message("usage"));
            endpoints.remove(args[0].toLowerCase(Locale.ROOT));
            module.computerId = null;
            module.computerPos = null;
            module.portName = "";
            module.requestedSignal = 0;
            module.activeSignal = 0;
            module.refresh();
            var level = owner.getLevel();
            level.updateNeighborsAt(module.getBlockPos(), module.getBlockState().getBlock());
            return List.of(message("unlinked"));
        }
        if (command.equals("SET")) {
            if (args.length != 2) return List.of(message("usage"));
            int strength;
            try { strength = Integer.parseInt(args[1]); }
            catch (NumberFormatException error) { return List.of(message("range")); }
            if (strength < 0 || strength > 15) return List.of(message("range"));
            module.requestedSignal = strength;
            module.activeSignal = strength;
            module.refresh();
            var level = owner.getLevel();
            level.updateNeighborsAt(module.getBlockPos(), module.getBlockState().getBlock());
            BlockPos target = InterfaceBlock.target(module.getBlockPos(), module.getBlockState());
            level.updateNeighborsAt(target, level.getBlockState(target).getBlock());
            return List.of(message("signal_set", strength));
        }
        if (!command.equals("READ") || args.length != 1) return List.of(message("usage"));
        var level = owner.getLevel();
        var face = module.getBlockState().getValue(InterfaceBlock.FACE);
        BlockPos target = InterfaceBlock.target(module.getBlockPos(), module.getBlockState());
        var state = level.getBlockState(target);
        List<Component> lines = new ArrayList<>();
        lines.add(message("target", state.getBlock().getName()));
        // Read the target's own output, which does not include the adapter's output.
        lines.add(message("redstone", level.getSignal(target, face)));
        if (level.getBlockEntity(target) instanceof KineticBlockEntity kinetic) {
            lines.add(message("speed", String.format(Locale.ROOT, "%.2f", kinetic.getSpeed())));
        }
        if (level.getBlockEntity(target) instanceof SensorBlockEntity sensor) lines.addAll(sensor.readings());
        if (level.getBlockEntity(target) instanceof StressGaugeBlockEntity stress) {
            lines.add(Component.translatable("retrocomputers.sensor.stress", number(stress.getNetworkStress()), number(stress.getNetworkCapacity())));
            lines.add(Component.translatable("retrocomputers.sensor.stress_percent", number(stressPercent(stress))));
        }
        var inventory = level.getCapability(Capabilities.ItemHandler.BLOCK, target, face);
        if (inventory != null) {
            long count = 0;
            for (int slot = 0; slot < inventory.getSlots(); slot++) count += inventory.getStackInSlot(slot).getCount();
            lines.add(message("items", count, inventory.getSlots()));
        }
        var fluids = level.getCapability(Capabilities.FluidHandler.BLOCK, target, face);
        if (fluids != null) {
            long amount = 0, capacity = 0;
            for (int tank = 0; tank < fluids.getTanks(); tank++) {
                amount += fluids.getFluidInTank(tank).getAmount();
                capacity += fluids.getTankCapacity(tank);
            }
            lines.add(message("fluid", amount, capacity));
        }
        return lines;
    }

    List<String> pythonPorts() {
        if (!owner.hasModem()) throw new IllegalStateException("Install a modem first");
        return endpoints.entrySet().stream().filter(entry -> module(entry.getValue()) != null).map(Map.Entry::getKey).toList();
    }

    Map<String, Object> pythonRead(String name) {
        Device module = requirePythonModule(name);
        var level = owner.getLevel();
        var face = module.getBlockState().getValue(InterfaceBlock.FACE);
        BlockPos target = InterfaceBlock.target(module.getBlockPos(), module.getBlockState());
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("block", net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(target).getBlock()).toString());
        data.put("redstone", level.getSignal(target, face));
        if (level.getBlockEntity(target) instanceof KineticBlockEntity kinetic) {
            data.put("rpm", kinetic.getSpeed());
            data.put("theoretical_rpm", kinetic.getTheoreticalSpeed());
            data.put("overstressed", kinetic.isOverStressed());
        }
        if (level.getBlockEntity(target) instanceof SensorBlockEntity sensor) data.putAll(sensor.read());
        if (level.getBlockEntity(target) instanceof StressGaugeBlockEntity stress) {
            data.put("stress_su", stress.getNetworkStress());
            data.put("capacity_su", stress.getNetworkCapacity());
            data.put("free_stress_su", Math.max(0, stress.getNetworkCapacity() - stress.getNetworkStress()));
            data.put("stress_percent", stressPercent(stress));
            data.put("stress_enabled", com.simibubi.create.content.kinetics.base.IRotate.StressImpact.isEnabled());
        }
        var inventory = level.getCapability(Capabilities.ItemHandler.BLOCK, target, face);
        if (inventory != null) {
            List<Map<String, Object>> items = new ArrayList<>();
            int count = 0;
            for (int slot = 0; slot < inventory.getSlots(); slot++) {
                var item = inventory.getStackInSlot(slot);
                count += item.getCount();
                if (!item.isEmpty()) items.add(Map.of("slot", slot, "item",
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item.getItem()).toString(), "count", item.getCount()));
            }
            data.put("items", items);
            data.put("item_count", count);
        }
        var fluid = level.getCapability(Capabilities.FluidHandler.BLOCK, target, face);
        if (fluid != null) {
            long amount = 0, capacity = 0;
            for (int tank = 0; tank < fluid.getTanks(); tank++) {
                amount += fluid.getFluidInTank(tank).getAmount();
                capacity += fluid.getTankCapacity(tank);
            }
            data.put("fluid_mb", amount);
            data.put("capacity_mb", capacity);
        }
        return data;
    }

    private static String number(double number) { return String.format(Locale.ROOT, "%.2f", number); }
    private static double stressPercent(StressGaugeBlockEntity stress) {
        return stress.getNetworkCapacity() > 0 ? 100.0 * stress.getNetworkStress() / stress.getNetworkCapacity() : 0;
    }

    void pythonSet(String name, int signal) {
        requirePythonModule(name);
        if (signal < 0 || signal > 15) throw new IllegalArgumentException("Signal must be 0..15");
        execute("SET", name + " " + signal);
    }

    private Device requirePythonModule(String name) {
        if (!owner.hasModem()) throw new IllegalStateException("Install a modem first");
        Endpoint endpoint = endpoints.get(name.toLowerCase(Locale.ROOT));
        Device module = endpoint == null ? null : module(endpoint);
        if (module == null) throw new IllegalArgumentException("Module is not connected: " + name);
        return module;
    }

    private Device module(Endpoint endpoint) {
        var level = owner.getLevel();
        if (level == null || !level.hasChunkAt(endpoint.pos())) return null;
        return level.getBlockEntity(endpoint.pos()) instanceof Device device
            && device.getBlockState().is(RetroComputers.INTERFACE.get()) && device.identity.equals(endpoint.id())
            && owner.identity.equals(device.computerId) ? device : null;
    }

    void reconcile() {
        var level = owner.getLevel();
        for (Endpoint endpoint : endpoints.values()) {
            if (!level.hasChunkAt(endpoint.pos())) continue;
            if (level.getBlockEntity(endpoint.pos()) instanceof Device module
                    && module.getBlockState().is(RetroComputers.INTERFACE.get())
                    && module.identity.equals(endpoint.id()) && owner.identity.equals(module.computerId)
                    && !owner.getBlockPos().equals(module.computerPos)) {
                module.computerPos = owner.getBlockPos();
                module.refresh();
            }
        }
    }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        endpoints.forEach((name, endpoint) -> {
            CompoundTag entry = new CompoundTag();
            entry.putString("Name", name);
            entry.putLong("Pos", endpoint.pos().asLong());
            entry.putUUID("Id", endpoint.id());
            list.add(entry);
        });
        tag.put("Ports", list);
        return tag;
    }

    void load(CompoundTag tag, UnaryOperator<BlockPos> relocate) {
        endpoints.clear();
        var list = tag.getList("Ports", Tag.TAG_COMPOUND);
        for (int i = 0; i < Math.min(list.size(), 32); i++) {
            var entry = list.getCompound(i);
            if (entry.hasUUID("Id")) endpoints.put(entry.getString("Name"),
                new Endpoint(relocate.apply(BlockPos.of(entry.getLong("Pos"))), entry.getUUID("Id")));
        }
    }

    static Component message(String key, Object... arguments) {
        return Component.translatable("retrocomputers.network." + key, arguments);
    }
}
