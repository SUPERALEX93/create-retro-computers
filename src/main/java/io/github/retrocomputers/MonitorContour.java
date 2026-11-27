package io.github.retrocomputers;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;

final class MonitorContour {
    static final int TOP = 1;
    static final int BOTTOM = 2;
    static final int LEFT = 4;
    static final int RIGHT = 8;

    private final Map<BlockPos, Integer> masks = new HashMap<>();
    private Level cachedLevel;
    private long tick;

    int edges(Level level, BlockPos start, Direction facing, Direction right) {
        if (cachedLevel != level || tick != level.getGameTime() || level != Minecraft.getInstance().level) {
            masks.clear();
            cachedLevel = level;
            tick = level.getGameTime();
        }
        Integer existing = masks.get(start);
        if (existing != null) return existing;
        calculate(level, start, facing, right);
        return masks.get(start);
    }

    private void calculate(Level level, BlockPos start, Direction facing, Direction right) {
        int minX = 0, maxX = 0, minY = 0, maxY = 0;
        var panels = new HashSet<BlockPos>();
        var pending = new ArrayDeque<BlockPos>();
        panels.add(start);
        pending.add(start);
        Direction[] offsets = {Direction.UP, Direction.DOWN, right.getOpposite(), right};
        while (!pending.isEmpty()) {
            BlockPos pos = pending.remove();
            int x = horizontal(start, pos, right);
            int y = pos.getY() - start.getY();
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
            for (Direction step : offsets) {
                BlockPos next = pos.relative(step);
                if (!level.hasChunkAt(next) || panels.contains(next)) continue;
                var state = level.getBlockState(next);
                if (state.is(RetroComputers.PANEL.get()) && state.getValue(DeviceBlock.FACING) == facing) {
                    panels.add(next);
                    pending.add(next);
                }
            }
        }

        // Padding provides one connected ring of outside space around the display.
        int width = maxX - minX + 3;
        int height = maxY - minY + 3;
        byte[] tiles = new byte[width * height];
        for (BlockPos pos : panels) {
            int index = horizontal(start, pos, right) - minX + 1
                + (pos.getY() - start.getY() - minY + 1) * width;
            tiles[index] = 1;
        }
        int[] queue = new int[tiles.length];
        int next = 0;
        int end = visit(tiles, queue, 0, 0);
        while (next < end) {
            int index = queue[next++];
            if (index % width > 0) end = visit(tiles, queue, index - 1, end);
            if (index % width < width - 1) end = visit(tiles, queue, index + 1, end);
            if (index >= width) end = visit(tiles, queue, index - width, end);
            if (index < tiles.length - width) end = visit(tiles, queue, index + width, end);
        }
        for (BlockPos pos : panels) {
            int index = horizontal(start, pos, right) - minX + 1
                + (pos.getY() - start.getY() - minY + 1) * width;
            int edges = 0;
            if (tiles[index + width] == 2) edges |= TOP;
            if (tiles[index - width] == 2) edges |= BOTTOM;
            if (tiles[index - 1] == 2) edges |= LEFT;
            if (tiles[index + 1] == 2) edges |= RIGHT;
            masks.put(pos, edges);
        }
    }

    private static int visit(byte[] tiles, int[] queue, int index, int end) {
        if (tiles[index] != 0) return end;
        tiles[index] = 2;
        queue[end] = index;
        return end + 1;
    }

    private static int horizontal(BlockPos start, BlockPos pos, Direction right) {
        return (pos.getX() - start.getX()) * right.getStepX() + (pos.getZ() - start.getZ()) * right.getStepZ();
    }
}
