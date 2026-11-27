package io.github.retrocomputers;

import com.mojang.serialization.Codec;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;

final class TerminalOutput {
    private static final String TAG = "TerminalOutput";
    private static final Codec<List<Component>> CODEC = ComponentSerialization.FLAT_CODEC.listOf();
    private static final Pattern LEGACY_HARDWARE = Pattern.compile("CPU (\\d+) MHZ  RAM (\\d+) KB");
    private static final Pattern LEGACY_POWER = Pattern.compile("POWER (\\d+)/(\\d+) FE/T");

    static List<Component> greeting() {
        return List.of(message("title"), message("ready"));
    }

    static Component message(String name, Object... arguments) {
        return Component.translatable("retrocomputers.terminal." + name, arguments);
    }

    static void save(CompoundTag tag, HolderLookup.Provider registries, List<Component> history) {
        CODEC.encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), history)
            .result().ifPresent(encoded -> tag.put(TAG, encoded));
    }

    static List<Component> load(CompoundTag tag, HolderLookup.Provider registries) {
        if (tag.contains(TAG)) {
            return CODEC.parse(registries.createSerializationContext(NbtOps.INSTANCE), tag.get(TAG))
                .result().orElse(List.of());
        }
        return migrate(tag.getString("Output"));
    }

    private static List<Component> migrate(String output) {
        if (output.isEmpty()) return List.of();
        String[] source = output.split("\n", -1);
        List<Component> translated = new ArrayList<>(source.length);
        for (int i = 0; i < source.length; i++) {
            String line = source[i];
            String previous = i > 0 ? source[i - 1] : "";
            if (i == 0 && line.equals("RETRO TERMINAL") && source.length > 1 && source[1].equals("READY. TYPE HELP")) {
                translated.addAll(greeting());
                i++;
            } else if (previous.equals(">INFO")) {
                var info = LEGACY_HARDWARE.matcher(line);
                translated.add(info.matches() ? message("hardware", info.group(1), info.group(2)) : Component.literal(line));
            } else if (i >= 2 && source[i - 2].equals(">INFO") && LEGACY_HARDWARE.matcher(previous).matches()) {
                var power = LEGACY_POWER.matcher(line);
                translated.add(power.matches() ? message("power", power.group(1), power.group(2)) : Component.literal(line));
            } else if (line.equals("? UNKNOWN COMMAND") && previous.startsWith(">") && !previous.startsWith(">ECHO ")) {
                translated.add(message("unknown"));
            } else {
                translated.add(Component.literal(line));
            }
        }
        return List.copyOf(translated);
    }
}
