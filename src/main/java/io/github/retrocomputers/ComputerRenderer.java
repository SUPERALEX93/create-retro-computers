package io.github.retrocomputers;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;

final class ComputerRenderer {
    private static final RenderType HARDWARE = texture("hardware_side");
    private static final RenderType CPU = texture("cpu_mounted");
    private static final RenderType CPU_ADVANCED = texture("cpu_advanced_mounted");
    private static final RenderType RAM = texture("ram_mounted");
    private static final RenderType RAM_ADVANCED = texture("ram_advanced_mounted");
    private static final RenderType DRIVE = texture("drive_mounted");
    private static final RenderType DRIVE_ADVANCED = texture("drive_advanced_mounted");
    private static final RenderType PSU = texture("psu_mounted");
    private static final RenderType PSU_ADVANCED = texture("psu_advanced_mounted");

    static void draw(Device computer, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        pose.pushPose();
        pose.translate(.5, 0, .5);
        pose.mulPose(Axis.YP.rotationDegrees(-computer.getBlockState().getValue(DeviceBlock.FACING).toYRot()));
        pose.translate(-.5, 0, -.5);
        pose.scale(1F / 16, 1F / 16, 1F / 16);
        var matrix = pose.last();
        if (installed(computer, Device.Slot.CPU)) {
            boolean advanced = computer.advanced(Device.Slot.CPU);
            box(buffers, HARDWARE, matrix, 5, 1.48F, 9, 11, 3.1F, 14, 0xffa07d4c, light, overlay);
            box(buffers, advanced ? CPU_ADVANCED : CPU, matrix, advanced ? 3.5F : 4.6F, 3.1F, 9.4F, advanced ? 12.5F : 11.4F, 7.6F, 13.6F,
                0xffd5c9b6, light, overlay);
        }
        if (installed(computer, Device.Slot.RAM)) {
            boolean advanced = computer.advanced(Device.Slot.RAM);
            var face = advanced ? RAM_ADVANCED : RAM;
            box(buffers, face, matrix, 4.1F, 8.45F, 8.4F, 11.9F, 10.55F, 14, 0xff97bc91, light, overlay);
            if (advanced) box(buffers, face, matrix, 4.1F, 11, 8.4F, 11.9F, 13.1F, 14, 0xff97bc91, light, overlay);
        }
        if (installed(computer, Device.Slot.DRIVE)) {
            boolean advanced = computer.advanced(Device.Slot.DRIVE);
            box(buffers, advanced ? DRIVE_ADVANCED : DRIVE, matrix, advanced ? 3.1F : 3.7F, 8.45F, 2, advanced ? 12.9F : 12.3F, 13.15F, 7.2F,
                0xffc6a985, light, overlay);
        }
        if (installed(computer, Device.Slot.PSU)) {
            boolean advanced = computer.advanced(Device.Slot.PSU);
            box(buffers, advanced ? PSU_ADVANCED : PSU, matrix, advanced ? 2.8F : 3.5F, 1.48F, 2, advanced ? 13.2F : 12.5F, 7.1F, 7.4F, 0xffb7b9ac, light, overlay);
        }
        if (computer.hasModem()) {
            box(buffers, RAM_ADVANCED, matrix, 4, 13.5F, 8.4F, 12, 14.25F, 14,
                0xff6b8bba, light, overlay);
        }
        if (computer.powered) {
            box(buffers, HARDWARE, matrix, 2.65F, 1.48F, 10.9F, 3.25F, 1.9F, 11.5F, 0xffa0ff81,
                LightTexture.FULL_BRIGHT, overlay);
            box(buffers, HARDWARE, matrix, 12.75F, 1.48F, 10.9F, 13.35F, 1.9F, 11.5F, 0xffa0ff81,
                LightTexture.FULL_BRIGHT, overlay);
        }
        pose.popPose();
    }

    private static boolean installed(Device computer, Device.Slot slot) {
        return !computer.parts[slot.ordinal()].isEmpty();
    }

    private static RenderType texture(String name) {
        return RenderType.entityCutout(RetroComputers.id("textures/block/computer/" + name + ".png"));
    }

    private static void box(MultiBufferSource buffers, RenderType texture, PoseStack.Pose pose, float x0, float y0, float z0,
            float x1, float y1, float z1, int sideColor, int light, int overlay) {
        var face = buffers.getBuffer(texture);
        int faceColor = texture == HARDWARE ? sideColor : -1;
        quad(face, pose, faceColor, light, overlay, 1, 0, 0, x1, y1, z1, x1, y0, z1, x1, y0, z0, x1, y1, z0);
        quad(face, pose, faceColor, light, overlay, -1, 0, 0, x0, y1, z0, x0, y0, z0, x0, y0, z1, x0, y1, z1);
        var side = texture == HARDWARE ? face : buffers.getBuffer(HARDWARE);
        quad(side, pose, sideColor, light, overlay, 0, 1, 0, x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0);
        quad(side, pose, sideColor, light, overlay, 0, -1, 0, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
        quad(side, pose, sideColor, light, overlay, 0, 0, 1, x0, y1, z1, x0, y0, z1, x1, y0, z1, x1, y1, z1);
        quad(side, pose, sideColor, light, overlay, 0, 0, -1, x1, y1, z0, x1, y0, z0, x0, y0, z0, x0, y1, z0);
    }

    private static void quad(VertexConsumer output, PoseStack.Pose pose, int color, int light, int overlay,
            float nx, float ny, float nz, float ax, float ay, float az, float bx, float by, float bz,
            float cx, float cy, float cz, float dx, float dy, float dz) {
        vertex(output, pose, color, light, overlay, nx, ny, nz, ax, ay, az, 0, 0);
        vertex(output, pose, color, light, overlay, nx, ny, nz, bx, by, bz, 0, 1);
        vertex(output, pose, color, light, overlay, nx, ny, nz, cx, cy, cz, 1, 1);
        vertex(output, pose, color, light, overlay, nx, ny, nz, dx, dy, dz, 1, 0);
    }

    private static void vertex(VertexConsumer output, PoseStack.Pose pose, int color, int light, int overlay,
            float nx, float ny, float nz, float x, float y, float z, float u, float v) {
        output.addVertex(pose, x, y, z).setColor(color).setUv(u, v).setOverlay(overlay)
            .setLight(light).setNormal(pose, nx, ny, nz);
    }
}
