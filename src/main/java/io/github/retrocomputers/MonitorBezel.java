package io.github.retrocomputers;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;

final class MonitorBezel {
    static final float WIDTH = 3F / 32;
    private static final float RISE = 1F / 48;
    private static final RenderType FINISH = RenderType.entityCutoutNoCull(
        RetroComputers.id("textures/block/monitor_bezel.png"));

    static void draw(int edges, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        if (edges == 0) return;
        var out = buffers.getBuffer(FINISH);
        boolean top = (edges & MonitorContour.TOP) != 0;
        boolean right = (edges & MonitorContour.RIGHT) != 0;
        boolean bottom = (edges & MonitorContour.BOTTOM) != 0;
        boolean left = (edges & MonitorContour.LEFT) != 0;

        // Each side uses a local coordinates system with the outer edge at y = 0.
        side(out, pose, top, left, right, 0, light, overlay);
        side(out, pose, right, top, bottom, 1, light, overlay);
        side(out, pose, bottom, right, left, 2, light, overlay);
        side(out, pose, left, bottom, top, 3, light, overlay);
    }

    private static void side(VertexConsumer out, PoseStack pose, boolean show, boolean cornerStart,
            boolean cornerEnd, int turn, int light, int overlay) {
        if (!show) return;
        pose.pushPose();
        pose.translate(.5, .5, 0);
        pose.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(turn * 90));
        pose.translate(-.5, -.5, 0);
        var matrix = pose.last();
        float start = cornerStart ? WIDTH : 0;
        float end = cornerEnd ? 1 - WIDTH : 1;
        // Upper half of the texture is the side, lower half is its matching corner.
        vertex(out, matrix, start, 0, RISE, start, 0, light, overlay);
        vertex(out, matrix, end, 0, RISE, end, 0, light, overlay);
        vertex(out, matrix, end, WIDTH, 0, end, 0.492F, light, overlay);
        vertex(out, matrix, start, WIDTH, 0, start, 0.492F, light, overlay);

        if (cornerStart) {
            // Split the corner along the diagonal to match both slopes.
            corner(out, matrix, 0, 0, light, overlay);
            corner(out, matrix, WIDTH, 0, light, overlay);
            corner(out, matrix, WIDTH, WIDTH, light, overlay);
            corner(out, matrix, WIDTH, WIDTH, light, overlay);
            corner(out, matrix, 0, 0, light, overlay);
            corner(out, matrix, WIDTH, WIDTH, light, overlay);
            corner(out, matrix, 0, WIDTH, light, overlay);
            corner(out, matrix, 0, WIDTH, light, overlay);
        }
        pose.popPose();
    }

    private static void corner(VertexConsumer out, PoseStack.Pose pose, float x, float y, int light, int overlay) {
        vertex(out, pose, x, y, RISE * (1 - Math.min(x, y) / WIDTH), x / WIDTH,
            .5F + .492F * y / WIDTH, light, overlay);
    }

    private static void vertex(VertexConsumer out, PoseStack.Pose pose, float x, float y,
            float z, float u, float v, int light, int overlay) {
        out.addVertex(pose, x, y, z).setColor(-1).setUv(u, v).setOverlay(overlay)
            .setLight(light).setNormal(pose, 0, 0, 1);
    }
}
