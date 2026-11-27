package io.github.retrocomputers;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.resources.model.ModelResourceLocation;

/** The needle uses the same low resolution gauge atlas as the housing. */
final class SensorRenderer implements BlockEntityRenderer<SensorBlockEntity> {
    @Override public void render(SensorBlockEntity sensor, float partialTick, PoseStack pose,
            MultiBufferSource buffers, int light, int overlay) {
        var minecraft = Minecraft.getInstance();
        var model = minecraft.getModelManager().getModel(ModelResourceLocation.standalone(RetroComputers.id("block/sensor_needle")));
        pose.pushPose();
        pose.translate(.5, .5, .5);
        pose.mulPose(Axis.YP.rotationDegrees(-sensor.getBlockState().getValue(SensorBlock.FACING).toYRot()));
        // Local face is +Z, pivot lies in the center of the gauge.
        pose.mulPose(Axis.ZP.rotationDegrees((float) (sensor.previousDial + (sensor.dial - sensor.previousDial) * partialTick)));
        pose.translate(-.5, -.5, -.5);
        minecraft.getBlockRenderer().getModelRenderer().renderModel(pose.last(), buffers.getBuffer(RenderType.solid()),
            sensor.getBlockState(), model, 1, 1, 1, light, overlay);
        pose.popPose();
    }
}
