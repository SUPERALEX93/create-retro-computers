package io.github.retrocomputers.mixin;

import io.github.retrocomputers.TerminalCamera;
import net.minecraft.client.Camera;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
abstract class CameraMixin implements TerminalCamera.WorldPose {
    @Shadow private Vec3 position;
    @Shadow @Final private BlockPos.MutableBlockPos blockPosition;
    @Shadow @Final private Quaternionf rotation;
    @Shadow @Final private Vector3f forwards;
    @Shadow @Final private Vector3f up;
    @Shadow @Final private Vector3f left;
    @Shadow private float xRot;
    @Shadow private float yRot;
    @Shadow private float roll;

    @Override
    public void retrocomputers$setWorldPose(Vec3 worldPosition, Quaternionf worldRotation) {
        position = worldPosition;
        blockPosition.set(worldPosition.x, worldPosition.y, worldPosition.z);
        rotation.set(worldRotation);
        rotation.transform(forwards.set(0, 0, -1));
        rotation.transform(up.set(0, 1, 0));
        rotation.transform(left.set(-1, 0, 0));
        Vector3f angles = rotation.getEulerAnglesYXZ(new Vector3f());
        xRot = (float) -Math.toDegrees(angles.x);
        yRot = 180 - (float) Math.toDegrees(angles.y);
        roll = (float) -Math.toDegrees(angles.z);
    }
    @Inject(method = "setup", at = @At("TAIL"))
    private void retrocomputers$focusMonitor(BlockGetter level, Entity entity, boolean detached,
            boolean mirrored, float partialTick, CallbackInfo callback) {
        TerminalCamera.update((Camera) (Object) this, partialTick);
    }
}
