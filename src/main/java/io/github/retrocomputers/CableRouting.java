package io.github.retrocomputers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

final class CableRouting {
    static final double CLEARANCE = .045;
    static final double PORT_CLEARANCE = .058;
    private static final double STEP = .25;
    private static final double HEIGHT_OFFSET = .0625;
    private static final double VERTICAL_COST = 2;
    private static final int SEARCH_BUDGET = 16000;
    private static final Direction[] STEPS = Direction.values();

    static List<Vec3> find(Level level, Vec3 first, Vec3 last, int floor) {
        double height = floor + HEIGHT_OFFSET;
        Vec3 down = new Vec3(first.x, height, first.z);
        Vec3 across = new Vec3(last.x, height, last.z);
        List<Vec3> xFirst = List.of(first, down, new Vec3(last.x, height, first.z), across, last);
        if (clear(level, xFirst)) return simplify(xFirst);
        List<Vec3> zFirst = List.of(first, down, new Vec3(first.x, height, last.z), across, last);
        if (clear(level, zFirst)) return simplify(zFirst);
        if (clearSegment(level, first, down, CLEARANCE) && clearSegment(level, across, last, CLEARANCE)) {
            List<Vec3> floorDetour = search(level, grid(down), grid(across), floor, true);
            if (!floorDetour.isEmpty()) {
                List<Vec3> route = new ArrayList<>(floorDetour.size() + 2);
                route.add(first);
                route.addAll(floorDetour);
                route.add(last);
                return simplify(route);
            }
        }
        List<Vec3> straight = List.of(first, new Vec3(first.x, last.y, first.z),
            new Vec3(last.x, last.y, first.z), last);
        if (clear(level, straight)) return simplify(straight);
        return search(level, grid(first), grid(last), floor, false);
    }

    static boolean clear(Level level, List<Vec3> route) {
        for (int i = 1; i < route.size(); i++) {
            if (!clearSegment(level, route.get(i - 1), route.get(i), CLEARANCE)) return false;
        }
        return true;
    }

    static boolean clearSegment(Level level, Vec3 start, Vec3 end, double clearance) {
        AABB volume = new AABB(start, end).inflate(clearance);
        if (!level.hasChunkAt(BlockPos.containing(volume.minX, volume.minY, volume.minZ))
                || !level.hasChunkAt(BlockPos.containing(volume.minX, volume.minY, volume.maxZ))
                || !level.hasChunkAt(BlockPos.containing(volume.maxX, volume.maxY, volume.minZ))
                || !level.hasChunkAt(BlockPos.containing(volume.maxX, volume.maxY, volume.maxZ))) return false;
        return !level.getBlockCollisions(null, volume).iterator().hasNext();
    }

    private static List<Vec3> search(Level level, BlockPos start, BlockPos goal, int floor, boolean floorOnly) {
        int minX = Math.min(start.getX(), goal.getX()) - 20, maxX = Math.max(start.getX(), goal.getX()) + 20;
        int minZ = Math.min(start.getZ(), goal.getZ()) - 20, maxZ = Math.max(start.getZ(), goal.getZ()) + 20;
        int minY = floorOnly ? floor * 4 : Math.max(level.getMinBuildHeight() * 4, floor * 4 - 8);
        int maxY = floorOnly ? floor * 4
            : Math.min((level.getMaxBuildHeight() - 1) * 4 + 3, Math.max(start.getY(), goal.getY()) + 16);
        Map<BlockPos, Node> visited = new HashMap<>();
        var pending = new PriorityQueue<Node>(Comparator.comparingDouble(Node::score)
            .thenComparingDouble(Node::remaining));
        var first = new Node(start, null, 0, heuristic(start, goal));
        visited.put(start, first);
        pending.add(first);

        for (int examined = 0; examined < SEARCH_BUDGET && !pending.isEmpty(); examined++) {
            Node current = pending.remove();
            if (visited.get(current.position()) != current) continue;
            if (current.position().equals(goal)) {
                List<Vec3> route = new ArrayList<>();
                for (Node node = current; node != null; node = node.previous()) route.add(world(node.position()));
                Collections.reverse(route);
                return simplify(route);
            }
            Vec3 from = world(current.position());
            for (Direction step : STEPS) {
                BlockPos next = current.position().relative(step);
                int x = next.getX(), y = next.getY(), z = next.getZ();
                if (x < minX || x > maxX || y < minY || y > maxY || z < minZ || z > maxZ) continue;
                boolean vertical = step.getAxis().isVertical();
                double cost = current.cost() + (vertical ? VERTICAL_COST : 1) + .015 * Math.abs(y - floor * 4);
                Node known = visited.get(next);
                if (known != null && known.cost() <= cost) continue;
                if (!clearSegment(level, from, world(next), CLEARANCE)) continue;
                Node node = new Node(next, current, cost, heuristic(next, goal));
                visited.put(next, node);
                pending.add(node);
            }
        }
        return List.of();
    }

    private static List<Vec3> simplify(List<Vec3> route) {
        List<Vec3> simplified = new ArrayList<>();
        for (Vec3 point : route) {
            if (!simplified.isEmpty() && simplified.getLast().distanceToSqr(point) < 1e-8) continue;
            if (simplified.size() >= 2) {
                Vec3 last = simplified.getLast();
                Vec3 incoming = last.subtract(simplified.get(simplified.size() - 2));
                Vec3 outgoing = point.subtract(last);
                if (incoming.dot(outgoing) > 0 && incoming.cross(outgoing).lengthSqr() < 1e-8) simplified.removeLast();
            }
            simplified.add(point);
        }
        return simplified;
    }

    private static BlockPos grid(Vec3 point) {
        return new BlockPos((int) Math.round(point.x / STEP), (int) Math.round((point.y - HEIGHT_OFFSET) / STEP),
            (int) Math.round(point.z / STEP));
    }

    private static Vec3 world(BlockPos position) {
        return new Vec3(position.getX() * STEP, position.getY() * STEP + HEIGHT_OFFSET, position.getZ() * STEP);
    }

    private static double heuristic(BlockPos start, BlockPos goal) {
        return Math.abs(start.getX() - goal.getX()) + Math.abs(start.getZ() - goal.getZ())
            + VERTICAL_COST * Math.abs(start.getY() - goal.getY());
    }

    private record Node(BlockPos position, Node previous, double cost, double remaining) {
        double score() { return cost + remaining; }
    }
}
