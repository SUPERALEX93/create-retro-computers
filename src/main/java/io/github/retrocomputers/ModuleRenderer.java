package io.github.retrocomputers;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.Direction;

final class ModuleRenderer {
    static void draw(Device module, Font font, PoseStack pose, MultiBufferSource buffers) {
        if (module.portName.isEmpty()) return;
        Direction face = module.getBlockState().getValue(InterfaceBlock.FACE);
        Device computer = module.computer();
        boolean online = computer != null && computer.powered && computer.hasModem();
        pose.pushPose();
        pose.translate(.5, .5, .5);
        pose.mulPose(face.getRotation());
        pose.translate(-.171875, -.5 + 3.76 / 16, 1.25 / 16);
        pose.mulPose(Axis.XP.rotationDegrees(-90));
        pose.scale(.007F, -.007F, .007F);
        font.drawInBatch(module.portName, -font.width(module.portName) / 2F,
            -4, online ? 0xffa5e3be : 0xffe3b184, false, pose.last().pose(), buffers,
            Font.DisplayMode.POLYGON_OFFSET, 0, LightTexture.FULL_BRIGHT);
        pose.popPose();
    }
}
