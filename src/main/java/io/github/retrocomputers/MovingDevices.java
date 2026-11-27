package io.github.retrocomputers;

import java.lang.reflect.Method;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;

final class MovingDevices {
    private static final SableBridge SABLE = ModList.get().isLoaded("sable") ? new SableBridge() : null;

    static boolean inReach(Player player, BlockPos position) {
        Vec3 point = position.getCenter();
        if (player.distanceToSqr(point) <= 64) return true;
        return SABLE != null && player.distanceToSqr(SABLE.worldPoint(player.level(), position, point)) <= 64;
    }

    static Vec3 worldPoint(Level level, BlockPos position, Vec3 point) {
        return SABLE == null ? point : SABLE.worldPoint(level, position, point);
    }

    record Motion(Vec3 position, Vec3 velocity, Vec3 forward, Vec3 right, Vec3 up, boolean onShip) {}

    record ViewFrame(Vec3 center, Vec3 forward, Vec3 up) {}

    static ViewFrame viewFrame(Level level, BlockPos position, Vec3 center, net.minecraft.core.Direction facing, float partialTick) {
        Vec3 forward = Vec3.atLowerCornerOf(facing.getNormal());
        if (SABLE == null) return new ViewFrame(center, forward, new Vec3(0, 1, 0));
        return SABLE.viewFrame(level, position, center, forward, partialTick);
    }

    static Motion motion(Level level, BlockPos position, net.minecraft.core.Direction facing) {
        Vec3 forward = Vec3.atLowerCornerOf(facing.getNormal());
        Vec3 right = Vec3.atLowerCornerOf(facing.getClockWise().getNormal());
        if (SABLE == null) return new Motion(position.getCenter(), Vec3.ZERO, forward, right, new Vec3(0, 1, 0), false);
        return SABLE.motion(level, position, forward, right);
    }

    // Keep Sable optional while using the installed version's coordinate conversion API.
    private static final class SableBridge {
        private final Method container;
        private final Method plot;
        private final Method sublevel;
        private final Method pose;
        private final Method transform;
        private final Method normal;
        private final Method velocity;
        private final Object helper;
        private Method renderPose;

        SableBridge() {
            try {
                Class<?> containers = Class.forName("dev.ryanhcode.sable.api.sublevel.SubLevelContainer");
                container = containers.getMethod("getContainer", Level.class);
                plot = containers.getMethod("getPlot", ChunkPos.class);
                sublevel = Class.forName("dev.ryanhcode.sable.sublevel.plot.LevelPlot").getMethod("getSubLevel");
                pose = Class.forName("dev.ryanhcode.sable.sublevel.SubLevel").getMethod("logicalPose");
                helper = Class.forName("dev.ryanhcode.sable.Sable").getField("HELPER").get(null);
                velocity = helper.getClass().getMethod("getVelocity", Level.class, Vec3.class);
                normal = Class.forName("dev.ryanhcode.sable.companion.math.Pose3dc").getMethod("transformNormal", Vec3.class);
                transform = Class.forName("dev.ryanhcode.sable.companion.math.Pose3dc").getMethod("transformPosition", Vec3.class);
            } catch (ReflectiveOperationException error) {
                throw new IllegalStateException("Cannot initialize Sable coordinate support", error);
            }
        }

        ViewFrame viewFrame(Level level, BlockPos position, Vec3 center, Vec3 forward, float partialTick) {
            try {
                Object storage = container.invoke(null, level);
                Object localPlot = storage == null ? null : plot.invoke(storage, new ChunkPos(position));
                Object ship = localPlot == null ? null : sublevel.invoke(localPlot);
                if (ship == null) return new ViewFrame(center, forward, new Vec3(0, 1, 0));
                // Resolve client-only Sable classes lazily, keeping dedicated servers compatible.
                if (renderPose == null) renderPose = ship.getClass().getMethod("renderPose", float.class);
                Object currentPose = renderPose.invoke(ship, partialTick);
                return new ViewFrame((Vec3) transform.invoke(currentPose, center),
                    (Vec3) normal.invoke(currentPose, forward),
                    (Vec3) normal.invoke(currentPose, new Vec3(0, 1, 0)));
            } catch (ReflectiveOperationException error) {
                throw new IllegalStateException("Cannot read Sable render pose", error);
            }
        }

        Motion motion(Level level, BlockPos position, Vec3 forward, Vec3 right) {
            try {
                Object storage = container.invoke(null, level);
                Object localPlot = storage == null ? null : plot.invoke(storage, new ChunkPos(position));
                Object ship = localPlot == null ? null : sublevel.invoke(localPlot);
                Vec3 center = position.getCenter();
                if (ship == null) return new Motion(center, Vec3.ZERO, forward, right, new Vec3(0, 1, 0), false);
                Object currentPose = pose.invoke(ship);
                return new Motion((Vec3) transform.invoke(currentPose, center),
                    (Vec3) velocity.invoke(helper, level, center),
                    ((Vec3) normal.invoke(currentPose, forward)).normalize(),
                    ((Vec3) normal.invoke(currentPose, right)).normalize(),
                    ((Vec3) normal.invoke(currentPose, new Vec3(0, 1, 0))).normalize(), true);
            } catch (ReflectiveOperationException error) {
                throw new IllegalStateException("Cannot read Sable motion sensor", error);
            }
        }

        Vec3 worldPoint(Level level, BlockPos position, Vec3 point) {
            try {
                Object storage = container.invoke(null, level);
                if (storage == null) return point;
                Object localPlot = plot.invoke(storage, new ChunkPos(position));
                if (localPlot == null) return point;
                Object ship = sublevel.invoke(localPlot);
                return ship == null ? point : (Vec3) transform.invoke(pose.invoke(ship), point);
            } catch (ReflectiveOperationException error) {
                throw new IllegalStateException("Cannot convert device coordinates from Sable", error);
            }
        }
    }
}
