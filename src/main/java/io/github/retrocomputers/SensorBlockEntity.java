package io.github.retrocomputers;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

final class SensorBlockEntity extends BlockEntity implements IHaveGoggleInformation {
    private Map<String, Object> values = Map.of();
    double dial, previousDial;

    SensorBlockEntity(BlockPos pos, BlockState state) { super(RetroComputers.SENSOR.get(), pos, state); }

    Map<String, Object> read() {
        if (level == null) return Map.of();
        var motion = MovingDevices.motion(level, worldPosition, getBlockState().getValue(SensorBlock.FACING));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("on_ship", motion.onShip());
        data.put("world_x", motion.position().x);
        data.put("world_y", motion.position().y);
        data.put("world_z", motion.position().z);
        if (getBlockState().is(RetroComputers.TILT.get())) {
            data.put("sensor", "tilt");
            data.put("pitch_deg", Math.toDegrees(Math.asin(clamp(motion.forward().y))));
            data.put("roll_deg", Math.toDegrees(Math.atan2(-motion.right().y, motion.up().y)));
            data.put("tilt_deg", Math.toDegrees(Math.acos(clamp(motion.up().y))));
            data.put("yaw_deg", (Math.toDegrees(Math.atan2(-motion.forward().x, motion.forward().z)) + 360) % 360);
        } else if (getBlockState().is(RetroComputers.MOTION.get())) {
            data.put("sensor", "speed");
            data.put("speed_mps", motion.velocity().length());
            data.put("speed_kmh", motion.velocity().length() * 3.6);
            data.put("horizontal_mps", Math.hypot(motion.velocity().x, motion.velocity().z));
            data.put("velocity_x", motion.velocity().x);
            data.put("velocity_y", motion.velocity().y);
            data.put("velocity_z", motion.velocity().z);
        } else {
            data.put("sensor", "altitude");
            data.put("altitude_m", motion.position().y);
            data.put("block_y", (int) Math.floor(motion.position().y));
        }
        return data;
    }

    private static double clamp(double v) { return Math.max(-1, Math.min(1, v)); }
    double value(String key) { return values.get(key) instanceof Number number ? number.doubleValue() : 0; }

    static void tick(Level level, BlockPos pos, BlockState state, SensorBlockEntity sensor) {
        // The server samples authoritative physics; clients smooth the synchronized needle.
        if (!level.isClientSide) {
            if (level.getGameTime() % 4 != Math.floorMod(pos.asLong(), 4)) return;
            Map<String, Object> next = sensor.read();
            if (!next.equals(sensor.values)) {
                sensor.values = next;
                level.sendBlockUpdated(pos, state, state, 2);
            }
        } else {
            sensor.previousDial = sensor.dial;
            double target = state.is(RetroComputers.TILT.get()) ? sensor.value("roll_deg")
                : state.is(RetroComputers.MOTION.get()) ? -120 + Math.min(1, sensor.value("speed_mps") / 50) * 240
                : -120 + Math.max(0, Math.min(1, (sensor.value("altitude_m") + 64) / 384)) * 240;
            sensor.dial += (target - sensor.dial) * .25;
        }
    }

    List<Component> readings() {
        Map<String, Object> data = level != null && !level.isClientSide ? read() : values;
        var lines = new java.util.ArrayList<Component>();
        if (getBlockState().is(RetroComputers.TILT.get())) {
            lines.add(line(data, "pitch", "pitch_deg")); lines.add(line(data, "roll", "roll_deg")); lines.add(line(data, "tilt", "tilt_deg"));
        } else if (getBlockState().is(RetroComputers.MOTION.get())) {
            lines.add(line(data, "speed", "speed_mps")); lines.add(line(data, "kmh", "speed_kmh"));
        } else lines.add(line(data, "altitude", "altitude_m"));
        return lines;
    }
    private Component line(Map<String, Object> data, String label, String key) {
        double value = data.get(key) instanceof Number number ? number.doubleValue() : 0;
        return Component.translatable("retrocomputers.sensor." + label, String.format(Locale.ROOT, "%.2f", Math.abs(value) < .005 ? 0 : value))
            .withStyle(ChatFormatting.AQUA);
    }
    @Override public boolean addToGoggleTooltip(List<Component> tooltip, boolean sneaking) {
        tooltip.add(getBlockState().getBlock().getName().copy().withStyle(ChatFormatting.GOLD));
        tooltip.addAll(readings());
        tooltip.add(Component.translatable("retrocomputers.sensor." + (Boolean.TRUE.equals(values.get("on_ship")) ? "ship" : "world"))
            .withStyle(ChatFormatting.GRAY));
        if (sneaking) tooltip.add(Component.translatable("retrocomputers.sensor.module").withStyle(ChatFormatting.GRAY));
        return true;
    }
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag result = new CompoundTag();
        values.forEach((key, value) -> {
            if (value instanceof Number number) result.putDouble(key, number.doubleValue());
            else if (value instanceof Boolean flag) result.putBoolean(key, flag);
            else result.putString(key, value.toString());
        });
        return result;
    }
    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        Map<String, Object> data = new LinkedHashMap<>();
        tag.getAllKeys().forEach(key -> data.put(key, key.equals("on_ship") ? tag.getBoolean(key)
            : key.equals("sensor") ? tag.getString(key) : tag.getDouble(key)));
        values = data;
    }
    @Override public ClientboundBlockEntityDataPacket getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }
}
