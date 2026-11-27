package io.github.retrocomputers;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;

/** One enclosure around an entire display, with continuous ventilation between tiles. */
final class MonitorHousing {
    private static final float PIXEL = 1F / 16;
    private static final int EDGE = 0xffd0bb99;
    private static final int SHADOW = 0xff8d795e;
    private static final int VENT = 0xff594c3b;
    private static final RenderType MATERIAL = RenderType.entitySolid(ResourceLocation.withDefaultNamespace("textures/misc/white.png"));

    private enum Face {
        BACK(0, 0, -1), LEFT(-1, 0, 0), RIGHT(1, 0, 0), TOP(0, 1, 0), BOTTOM(0, -1, 0);
        final int nx, ny, nz;
        Face(int x, int y, int z) { nx = x; ny = y; nz = z; }
    }

    static boolean joins(Device panel, Direction side) {
        var level = panel.getLevel();
        var position = panel.getBlockPos().relative(side);
        if (level == null || !level.hasChunkAt(position)) return false;
        var neighbor = level.getBlockState(position);
        return neighbor.is(RetroComputers.PANEL.get())
            && neighbor.getValue(DeviceBlock.FACING) == panel.getBlockState().getValue(DeviceBlock.FACING);
    }

    static void draw(Device panel, int edges, PoseStack pose, MultiBufferSource buffers, int overlay) {
        Direction facing = panel.getBlockState().getValue(DeviceBlock.FACING);
        Direction right = facing.getCounterClockWise();
        pose.pushPose();
        pose.translate(.5, .5, .5);
        pose.mulPose(Axis.YP.rotationDegrees(-facing.toYRot()));
        pose.translate(-.5, -.5, -.5);
        var out = buffers.getBuffer(MATERIAL);
        var matrix = pose.last();
        paint(out, matrix, Face.BACK, edges, light(panel, facing.getOpposite()), overlay);
        if (!joins(panel, right.getOpposite())) paint(out, matrix, Face.LEFT,
            MonitorContour.LEFT | MonitorContour.RIGHT | (edges & (MonitorContour.TOP | MonitorContour.BOTTOM)), light(panel, right.getOpposite()), overlay);
        if (!joins(panel, right)) paint(out, matrix, Face.RIGHT,
            MonitorContour.LEFT | MonitorContour.RIGHT | (edges & (MonitorContour.TOP | MonitorContour.BOTTOM)), light(panel, right), overlay);
        if (!joins(panel, Direction.UP)) paint(out, matrix, Face.TOP,
            MonitorContour.TOP | MonitorContour.BOTTOM | (edges & (MonitorContour.LEFT | MonitorContour.RIGHT)), light(panel, Direction.UP), overlay);
        if (!joins(panel, Direction.DOWN)) paint(out, matrix, Face.BOTTOM,
            MonitorContour.TOP | MonitorContour.BOTTOM | (edges & (MonitorContour.LEFT | MonitorContour.RIGHT)), light(panel, Direction.DOWN), overlay);
        pose.popPose();
    }

    private static int light(Device panel, Direction face) {
        return LevelRenderer.getLightColor(panel.getLevel(), panel.getBlockPos().relative(face));
    }

    private static void paint(VertexConsumer out, PoseStack.Pose pose, Face face, int edges, int light, int overlay) {
        boolean top = (edges & MonitorContour.TOP) != 0;
        boolean bottom = (edges & MonitorContour.BOTTOM) != 0;
        boolean left = (edges & MonitorContour.LEFT) != 0;
        boolean right = (edges & MonitorContour.RIGHT) != 0;
        if (left) rect(out, pose, face, 0, 0, PIXEL, 1, .008F, EDGE, light, overlay);
        if (right) rect(out, pose, face, 1 - PIXEL, 0, 1, 1, .008F, SHADOW, light, overlay);
        if (top) rect(out, pose, face, 0, 1 - PIXEL, 1, 1, .0081F, EDGE, light, overlay);
        if (bottom) rect(out, pose, face, 0, 0, 1, PIXEL, .0081F, SHADOW, light, overlay);

        float start = left ? 2 * PIXEL : 0;
        float end = right ? 1 - 2 * PIXEL : 1;
        float low = bottom ? 2 * PIXEL : 0;
        float high = top ? 1 - 2 * PIXEL : 1;
        if (face == Face.TOP || face == Face.BOTTOM) {
            // Slots cross horizontal tile joins without a cap or frame at each block.
            for (int row = 3; row < 14; row += 2)
                rect(out, pose, face, start, row * PIXEL, end, (row + 1) * PIXEL, .008F, VENT, light, overlay);
        } else {
            // A 2-pixel period divides the tile width exactly; no gap at the tile seam.
            for (int column = 1; column < 16; column += 2) {
                float a = Math.max(start, column * PIXEL);
                float b = Math.min(end, (column + 1) * PIXEL);
                if (b > a) rect(out, pose, face, a, low, b, high, .008F, VENT, light, overlay);
            }
        }
    }

    private static void rect(VertexConsumer out, PoseStack.Pose pose, Face face, float u0, float v0,
            float u1, float v1, float depth, int color, int light, int overlay) {
        vertex(out, pose, face, u0, v0, depth, color, light, overlay);
        vertex(out, pose, face, u0, v1, depth, color, light, overlay);
        vertex(out, pose, face, u1, v1, depth, color, light, overlay);
        vertex(out, pose, face, u1, v0, depth, color, light, overlay);
    }

    private static void vertex(VertexConsumer out, PoseStack.Pose pose, Face face, float u, float v,
            float depth, int color, int light, int overlay) {
        float x, y, z;
        switch (face) {
            case BACK -> { x = u; y = v; z = 0; }
            case LEFT -> { x = 0; y = v; z = 1 - u; }
            case RIGHT -> { x = 1; y = v; z = u; }
            case TOP -> { x = u; y = 1; z = v; }
            case BOTTOM -> { x = u; y = 0; z = 1 - v; }
            default -> throw new IllegalStateException();
        }
        out.addVertex(pose, x + face.nx * depth, y + face.ny * depth, z + face.nz * depth).setColor(color)
            .setUv(.5F, .5F).setOverlay(overlay).setLight(light).setNormal(pose, face.nx, face.ny, face.nz);
    }
}
