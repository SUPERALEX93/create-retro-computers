package io.github.retrocomputers;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ViewportEvent;
import org.joml.Quaternionf;

@EventBusSubscriber(modid = RetroComputers.ID, value = Dist.CLIENT)
public final class TerminalCamera {
    private static Device monitor;
    private static Device keyboard;
    private static Vec3 initialPosition;
    private static Quaternionf initialRotation;
    private static long started;
    private static double readingFov = 70;

    static boolean begin(Device keys) {
        end();
        Device computer = keys.computer();
        var client = Minecraft.getInstance();
        if (computer == null || keys.getLevel() == null) return false;
        var level = keys.getLevel();
        BlockPos pc = computer.getBlockPos();
        double nearest = Double.MAX_VALUE;
        for (int x = (pc.getX() - 64) >> 4; x <= (pc.getX() + 64) >> 4; x++) {
            for (int z = (pc.getZ() - 64) >> 4; z <= (pc.getZ() + 64) >> 4; z++) {
                if (!level.hasChunkAt(new BlockPos(x * 16, pc.getY(), z * 16))) continue;
                for (var entity : level.getChunk(x, z).getBlockEntities().values()) {
                    if (!(entity instanceof Device panel) || !panel.getBlockState().is(RetroComputers.PANEL.get())
                            || !panel.getBlockPos().equals(panel.origin) || !computer.identity.equals(panel.computerId)) continue;
                    double distance = panel.getBlockPos().distSqr(keys.getBlockPos());
                    if (distance < nearest) {
                        nearest = distance;
                        monitor = panel;
                    }
                }
            }
        }
        if (monitor == null) return false;
        keyboard = keys;
        var camera = client.gameRenderer.getMainCamera();
        initialPosition = camera.getPosition();
        initialRotation = new Quaternionf(camera.rotation());
        started = Util.getMillis();
        readingFov = 70;
        return true;
    }

    static void end() {
        monitor = null;
        keyboard = null;
    }

    private static boolean focused() {
        return monitor != null && Minecraft.getInstance().screen instanceof KeyboardScreen;
    }

    @SubscribeEvent
    public static void fov(ViewportEvent.ComputeFov event) {
        if (focused()) event.setFOV(readingFov);
    }

    @SubscribeEvent
    public static void hand(net.neoforged.neoforge.client.event.RenderHandEvent event) {
        if (focused()) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void terminalOverlays(net.neoforged.neoforge.client.event.RenderGuiLayerEvent.Pre event) {
        if (!(Minecraft.getInstance().screen instanceof KeyboardScreen)) return;
        var name = event.getName();
        if (name.equals(net.neoforged.neoforge.client.gui.VanillaGuiLayers.CROSSHAIR)
                || name.equals(net.neoforged.neoforge.client.gui.VanillaGuiLayers.OVERLAY_MESSAGE)
                || name.equals(net.neoforged.neoforge.client.gui.VanillaGuiLayers.SELECTED_ITEM_NAME)
                || name.equals(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("create", "goggle_info"))) {
            event.setCanceled(true);
        }
    }

    /** Apply an absolute world pose without re-entering Sable's player-camera rotation hooks. */
    public interface WorldPose {
        void retrocomputers$setWorldPose(Vec3 position, Quaternionf rotation);
    }

    public static void update(net.minecraft.client.Camera camera, float partialTick) {
        var client = Minecraft.getInstance();
        if (!(client.screen instanceof KeyboardScreen) || monitor == null || keyboard == null) return;
        var level = keyboard.getLevel();
        if (level == null || monitor.isRemoved() || !monitor.getBlockState().is(RetroComputers.PANEL.get())
                || !monitor.getBlockPos().equals(monitor.origin) || keyboard.computer() == null
                || !keyboard.computer().identity.equals(monitor.computerId)) {
            end();
            return;
        }
        Direction facing = monitor.getBlockState().getValue(DeviceBlock.FACING);
        Direction right = facing.getCounterClockWise();
        Vec3 center = monitor.getBlockPos().getCenter()
            .relative(right, (monitor.width - 1) / 2D).add(0, -(monitor.height - 1) / 2D, 0)
            .relative(facing, .501);
        var frame = MovingDevices.viewFrame(level, monitor.getBlockPos(), center, facing, partialTick);
        Vec3 normal = frame.forward().normalize();
        Vec3 up = frame.up().normalize();
        double guiHeight = client.getWindow().getGuiScaledHeight();
        double available = Math.max(40, guiHeight - KeyboardLayout.HEIGHT * .7 - 24);
        double fraction = Math.min(.8, available / guiHeight);
        double aspect = (double) client.getWindow().getWidth() / client.getWindow().getHeight();
        double tangent = Math.tan(Math.toRadians(70 / 2D));
        double distance = Math.max(monitor.height / (2 * tangent * fraction),
            monitor.width / (2 * tangent * aspect * .9)) * 1.12;
        double offset = (guiHeight / 2 - available / 2 - 10) / guiHeight * 2 * distance * tangent;
        Vec3 localNormal = Vec3.atLowerCornerOf(facing.getNormal());
        Vec3 localDestination = center.add(localNormal.scale(distance)).add(0, -offset, 0);
        double actualDistance = distance;
        // Check the monitor's own block coordinates, independent of the ship's frame interpolation.
        for (int attempt = 0; attempt < 24 && actualDistance > .45; attempt++) {
            var volume = new net.minecraft.world.phys.AABB(localDestination.subtract(.18, .18, .18), localDestination.add(.18, .18, .18));
            boolean occupied = occupied(level, volume);
            Vec3 start = center.add(localNormal.scale(.03));
            Vec3 end = localDestination;
            // Avoid Level.clip, whose Sable hooks project the ray into world space again.
            boolean blocked = net.minecraft.world.level.BlockGetter.traverseBlocks(start, end, level,
                (world, pos) -> world.getBlockState(pos).getVisualShape(world, pos,
                    net.minecraft.world.phys.shapes.CollisionContext.empty()).clip(start, end, pos) == null ? null : Boolean.TRUE,
                world -> Boolean.FALSE);
            if (!occupied && !blocked) break;
            actualDistance *= .85;
            localDestination = center.add(localNormal.scale(actualDistance)).add(0, -offset, 0);
        }
        Vec3 destination = frame.center().add(frame.forward().scale(actualDistance)).subtract(frame.up().scale(offset));
        readingFov = Math.min(150, Math.toDegrees(2 * Math.atan(tangent * distance / actualDistance)));
        Vec3 direction = normal.scale(-1);
        double progress = Mth.clamp((Util.getMillis() - started) / 350D, 0, 1);
        progress = progress * progress * (3 - 2 * progress);
        Quaternionf target = new Quaternionf().lookAlong((float) direction.x, (float) direction.y, (float) direction.z,
            (float) up.x, (float) up.y, (float) up.z).conjugate();
        Quaternionf rotation = new Quaternionf(initialRotation).slerp(target, (float) progress).normalize();
        ((WorldPose) camera).retrocomputers$setWorldPose(initialPosition.lerp(destination, progress), rotation);
    }

    private static boolean occupied(net.minecraft.world.level.Level level, net.minecraft.world.phys.AABB volume) {
        var cameraShape = net.minecraft.world.phys.shapes.Shapes.create(volume);
        for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(volume.minX, volume.minY, volume.minZ),
                BlockPos.containing(volume.maxX, volume.maxY, volume.maxZ))) {
            var shape = level.getBlockState(pos).getCollisionShape(level, pos).move(pos.getX(), pos.getY(), pos.getZ());
            if (net.minecraft.world.phys.shapes.Shapes.joinIsNotEmpty(shape, cameraShape,
                    net.minecraft.world.phys.shapes.BooleanOp.AND)) return true;
        }
        return false;
    }
}
