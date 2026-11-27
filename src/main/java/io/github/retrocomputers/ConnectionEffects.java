package io.github.retrocomputers;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

final class ConnectionEffects {
    static void show(Level level, BlockPos device, BlockPos computer) {
        if (!(level instanceof ServerLevel server) || computer == null) return;
        var start = computer.getCenter();
        var end = device.getCenter();
        int steps = Math.clamp((int) (start.distanceTo(end) * 6), 8, 160);
        for (int i = 0; i <= steps; i++) {
            var point = start.lerp(end, (double) i / steps);
            server.sendParticles(ParticleTypes.ELECTRIC_SPARK, point.x, point.y, point.z, 1, .015, .015, .015, 0);
        }
    }
}
