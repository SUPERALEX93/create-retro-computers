package io.github.retrocomputers;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

final class PixelCableMesh {
    static final double PIXEL = 1D / 16;
    private final List<Face> faces;

    private PixelCableMesh(List<Face> faces) { this.faces = faces; }

    static PixelCableMesh build(Level level, List<Vec3> path, int width) {
        Set<BlockPos> cells = cells(path, width);
        List<Face> faces = new ArrayList<>();
        for (BlockPos cell : cells) {
            Vec3 min = new Vec3(cell.getX() * PIXEL, cell.getY() * PIXEL, cell.getZ() * PIXEL);
            int light = LevelRenderer.getLightColor(level, BlockPos.containing(min.add(PIXEL / 2, PIXEL / 2, PIXEL / 2)));
            for (Direction side : Direction.values()) {
                if (cells.contains(cell.relative(side))) continue;
                faces.add(new Face(min, side, light));
            }
        }
        return new PixelCableMesh(faces);
    }

    static Set<BlockPos> cells(List<Vec3> path, int width) {
        Set<BlockPos> cells = new HashSet<>();
        for (int segment = 1; segment < path.size(); segment++) {
            Vec3 start = path.get(segment - 1), end = path.get(segment);
            Vec3 delta = end.subtract(start);
            int steps = Math.max(1, (int) Math.ceil(Math.max(Math.abs(delta.x), Math.max(Math.abs(delta.y), Math.abs(delta.z))) * 32));
            BlockPos previous = null;
            for (int step = 0; step <= steps; step++) {
                Vec3 at = start.lerp(end, (double) step / steps);
                int x = (int) Math.floor(at.x * 16 - width / 2D + 1e-6);
                int y = (int) Math.floor(at.y * 16 - width / 2D + 1e-6);
                int z = (int) Math.floor(at.z * 16 - width / 2D + 1e-6);
                BlockPos next = new BlockPos(x, y, z);
                if (previous == null) previous = next;
                // Join rounded port positions through faces, never only through cube corners.
                while (!previous.equals(next)) {
                    addCube(cells, previous, width);
                    if (previous.getX() != x) previous = previous.offset(Integer.compare(x, previous.getX()), 0, 0);
                    else if (previous.getY() != y) previous = previous.offset(0, Integer.compare(y, previous.getY()), 0);
                    else previous = previous.offset(0, 0, Integer.compare(z, previous.getZ()));
                }
                addCube(cells, next, width);
            }
        }
        return cells;
    }

    private static void addCube(Set<BlockPos> cells, BlockPos origin, int width) {
        for (int dx = 0; dx < width; dx++)
            for (int dy = 0; dy < width; dy++)
                for (int dz = 0; dz < width; dz++)
                    cells.add(origin.offset(dx, dy, dz));
    }

    void draw(VertexConsumer out, PoseStack.Pose pose, Vec3 origin, int color, int overlay) {
        for (Face face : faces) {
            Vec3 low = face.min().subtract(origin);
            Vec3 high = low.add(PIXEL, PIXEL, PIXEL);
            Direction normal = face.normal();
            int shaded = shade(color, normal);
            switch (normal) {
                case UP -> quad(out, pose, face, shaded, overlay, low.x, high.y, low.z, low.x, high.y, high.z, high.x, high.y, high.z, high.x, high.y, low.z);
                case DOWN -> quad(out, pose, face, shaded, overlay, low.x, low.y, high.z, low.x, low.y, low.z, high.x, low.y, low.z, high.x, low.y, high.z);
                case NORTH -> quad(out, pose, face, shaded, overlay, high.x, high.y, low.z, high.x, low.y, low.z, low.x, low.y, low.z, low.x, high.y, low.z);
                case SOUTH -> quad(out, pose, face, shaded, overlay, low.x, high.y, high.z, low.x, low.y, high.z, high.x, low.y, high.z, high.x, high.y, high.z);
                case EAST -> quad(out, pose, face, shaded, overlay, high.x, high.y, high.z, high.x, low.y, high.z, high.x, low.y, low.z, high.x, high.y, low.z);
                case WEST -> quad(out, pose, face, shaded, overlay, low.x, high.y, low.z, low.x, low.y, low.z, low.x, low.y, high.z, low.x, high.y, high.z);
            }
        }
    }

    private static int shade(int color, Direction normal) {
        float brightness = switch (normal) {
            case UP -> 1;
            case DOWN -> .6F;
            case NORTH, SOUTH -> .8F;
            default -> .9F;
        };
        return 0xff000000 | (Math.round((color >> 16 & 255) * brightness) << 16)
            | (Math.round((color >> 8 & 255) * brightness) << 8) | Math.round((color & 255) * brightness);
    }

    private static void quad(VertexConsumer out, PoseStack.Pose pose, Face face, int color, int overlay,
            double ax, double ay, double az, double bx, double by, double bz,
            double cx, double cy, double cz, double dx, double dy, double dz) {
        vertex(out, pose, face, color, overlay, ax, ay, az, 0, 0);
        vertex(out, pose, face, color, overlay, bx, by, bz, 0, 1);
        vertex(out, pose, face, color, overlay, cx, cy, cz, 1, 1);
        vertex(out, pose, face, color, overlay, dx, dy, dz, 1, 0);
    }

    private static void vertex(VertexConsumer out, PoseStack.Pose pose, Face face, int color, int overlay,
            double x, double y, double z, float u, float v) {
        Direction normal = face.normal();
        out.addVertex(pose, (float) x, (float) y, (float) z).setColor(color).setUv(u, v)
            .setOverlay(overlay).setLight(face.light()).setNormal(pose, normal.getStepX(), normal.getStepY(), normal.getStepZ());
    }

    private record Face(Vec3 min, Direction normal, int light) {}
}
