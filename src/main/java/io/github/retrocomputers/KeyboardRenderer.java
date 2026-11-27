package io.github.retrocomputers;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.Util;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;

final class KeyboardRenderer {
    private static final RenderType CAPS = RenderType.entityCutoutNoCull(
        RetroComputers.id("textures/block/keycap.png"));
    private static final float BASE = 3F / 16;
    private static final float CAP_HEIGHT = 0.054F;
    private static final float TRAVEL = 0.039F;

    static void draw(Device keyboard, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        if (keyboard.getBlockState().getValue(DeviceBlock.RIGHT)) return;
        pose.pushPose();
        pose.translate(.5, 0, .5);
        pose.mulPose(Axis.YP.rotationDegrees(-keyboard.getBlockState().getValue(DeviceBlock.FACING).toYRot()));
        pose.translate(-.5, 0, -.5);
        if (DeviceBlock.wideKeyboard(keyboard.getLevel(), keyboard.getBlockPos(), keyboard.getBlockState())) pose.scale(2, 1, 1);

        var vertices = buffers.getBuffer(CAPS);
        var transform = pose.last();
        long time = Util.getMillis();
        for (KeyboardLayout.Key key : KeyboardLayout.KEYS) {
            float x0 = key.x() / (float) KeyboardLayout.WIDTH;
            float x1 = (key.x() + key.width()) / (float) KeyboardLayout.WIDTH;
            float z0 = .26F + (key.y() - 42) / 105F * .65F;
            float z1 = z0 + .095F;
            float depth = keyboard.keyDepth(key.code(), time);
            if (depth == 0 && keyboard.getBlockState().getValue(DeviceBlock.PRESSED) && key.code() == KeyboardLayout.SPACE) {
                depth = 1;
            }
            int color = key.label() != null && key.code() != KeyboardLayout.SPACE ? 0xffc99870 : 0xffefdab0;
            cap(vertices, transform, x0, x1, z0, z1, BASE + CAP_HEIGHT - TRAVEL * depth, color, light, overlay);
        }
        Device computer = keyboard.computer();
        lamp(vertices, transform, .79F, computer != null, 0xffffb454, light, overlay);
        lamp(vertices, transform, .86F, computer != null && computer.powered, 0xff8fe483, light, overlay);
        pose.popPose();
    }

    private static void lamp(VertexConsumer vertices, PoseStack.Pose pose, float x, boolean lit,
            int color, int light, int overlay) {
        cap(vertices, pose, x, x + .046F, .098F, .133F, BASE + .009F, lit ? color : 0xff655343,
            lit ? LightTexture.FULL_BRIGHT : light, overlay);
    }

    private static void cap(VertexConsumer out, PoseStack.Pose pose, float x0, float x1, float z0, float z1,
            float top, int color, int light, int overlay) {
        // Each top is slightly smaller than the base, like a molded keycap.
        float a = x0 + .004F, b = x1 - .004F, c = z0 + .006F, d = z1 - .006F;
        quad(out, pose, color, light, overlay, 0, 1, 0, a, top, c, a, top, d, b, top, d, b, top, c);
        quad(out, pose, color, light, overlay, 0, 0, -1, x0, BASE, z0, a, top, c, b, top, c, x1, BASE, z0);
        quad(out, pose, color, light, overlay, 0, 0, 1, x1, BASE, z1, b, top, d, a, top, d, x0, BASE, z1);
        quad(out, pose, color, light, overlay, -1, 0, 0, x0, BASE, z1, a, top, d, a, top, c, x0, BASE, z0);
        quad(out, pose, color, light, overlay, 1, 0, 0, x1, BASE, z0, b, top, c, b, top, d, x1, BASE, z1);
    }

    private static void quad(VertexConsumer out, PoseStack.Pose pose, int color, int light, int overlay,
            float nx, float ny, float nz, float ax, float ay, float az, float bx, float by, float bz,
            float cx, float cy, float cz, float dx, float dy, float dz) {
        vertex(out, pose, color, light, overlay, nx, ny, nz, ax, ay, az, 0, 0);
        vertex(out, pose, color, light, overlay, nx, ny, nz, bx, by, bz, 0, 1);
        vertex(out, pose, color, light, overlay, nx, ny, nz, cx, cy, cz, 1, 1);
        vertex(out, pose, color, light, overlay, nx, ny, nz, dx, dy, dz, 1, 0);
    }

    private static void vertex(VertexConsumer out, PoseStack.Pose pose, int color, int light, int overlay,
            float nx, float ny, float nz, float x, float y, float z, float u, float v) {
        out.addVertex(pose, x, y, z).setColor(color).setUv(u, v).setOverlay(overlay)
            .setLight(light).setNormal(pose, nx, ny, nz);
    }
}
