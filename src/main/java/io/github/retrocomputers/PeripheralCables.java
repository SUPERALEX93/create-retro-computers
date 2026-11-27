package io.github.retrocomputers;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

final class PeripheralCables {
    private static final double EXIT = .25;
    private static final RenderType INSULATION = RenderType.entityCutoutNoCull(
        RetroComputers.id("textures/block/signal_cable.png"));
    private final Map<Device, Cable> cache = new WeakHashMap<>();

    static boolean endpoint(Device device) {
        return device.getBlockState().is(RetroComputers.INTERFACE.get())
            || device.getBlockState().is(RetroComputers.KEYBOARD.get()) && !device.getBlockState().getValue(DeviceBlock.RIGHT)
            || device.getBlockState().is(RetroComputers.PANEL.get()) && device.getBlockPos().equals(device.origin);
    }

    static AABB bounds(Device device) {
        AABB box = new AABB(device.getBlockPos());
        if (device.getBlockState().is(RetroComputers.KEYBOARD.get()) && !device.getBlockState().getValue(DeviceBlock.RIGHT)) {
            box = box.minmax(new AABB(device.getBlockPos().relative(device.getBlockState().getValue(DeviceBlock.FACING).getCounterClockWise())));
        }
        if (device.computerPos != null && endpoint(device)) {
            return box.minmax(new AABB(device.computerPos)).inflate(5.25, 4.25, 5.25);
        }
        return box;
    }

    void render(Device device, PoseStack pose, MultiBufferSource buffers, int overlay) {
        if (!endpoint(device)) return;
        Device computer = device.computer();
        Level level = device.getLevel();
        if (computer == null || level == null) {
            cache.remove(device);
            return;
        }
        boolean monitor = device.getBlockState().is(RetroComputers.PANEL.get());
        Direction pcFacing = computer.getBlockState().getValue(DeviceBlock.FACING);
        boolean module = device.getBlockState().is(RetroComputers.INTERFACE.get());
        Direction deviceFacing = module ? device.getBlockState().getValue(InterfaceBlock.FACE) : device.getBlockState().getValue(DeviceBlock.FACING);
        Port source = port(computer.getBlockPos(), pcFacing, module ? 0 : monitor ? -.25 : .25, module ? .8125 : monitor ? .5625 : .3125);
        Port target = module ? modulePort(device.getBlockPos(), deviceFacing)
            : port(device.getBlockPos(), deviceFacing, monitor ? .25 : 0, monitor ? .5625 : .0625);
        long tick = level.getGameTime();
        Cable cable = cache.get(device);
        if (cable == null || !cable.source().equals(source) || !cable.target().equals(target)) {
            cable = build(level, source, target, tick, computer, device);
        } else if (tick < cable.checkedAt() || tick - cable.checkedAt() >= (cable.path().isEmpty() ? 12 : 3)) {
            if (cable.path().isEmpty() || !source.exposed(level) || !target.exposed(level)
                    || !CableRouting.clear(level, cable.path())) {
                cable = build(level, source, target, tick, computer, device);
            } else {
                cable = new Cable(source, target, tick, cable.path(), cable.jacket(), cable.sourcePlug(), cable.targetPlug());
            }
        }
        cache.put(device, cable);

        int jacket = module ? 0xff739fbd : monitor ? 0xff688b75 : 0xffb7865f;
        int plug = module ? 0xff9fcfe8 : monitor ? 0xff8cba91 : 0xffd6aa75;
        var vertices = buffers.getBuffer(INSULATION);
        Vec3 offset = Vec3.atLowerCornerOf(device.getBlockPos());
        var matrix = pose.last();
        cable.jacket().draw(vertices, matrix, offset, jacket, overlay);
        cable.sourcePlug().draw(vertices, matrix, offset, plug, overlay);
        cable.targetPlug().draw(vertices, matrix, offset, plug, overlay);
    }

    private static Cable build(Level level, Port source, Port target, long tick, Device computer, Device device) {
        boolean sourceOpen = source.exposed(level);
        boolean targetOpen = target.exposed(level);
        int floor = Math.min(computer.getBlockPos().getY(), device.getBlockPos().getY());
        List<Vec3> path = sourceOpen && targetOpen ? CableRouting.find(level, source.exit(), target.exit(), floor) : List.of();
        PixelCableMesh jacket = PixelCableMesh.build(level, List.of(), 1);
        if (!path.isEmpty()) {
            List<Vec3> connected = new ArrayList<>(path.size() + 2);
            connected.add(source.point().relative(source.outward(), -.004));
            connected.addAll(path);
            connected.add(target.point().relative(target.outward(), -.004));
            jacket = PixelCableMesh.build(level, connected, 1);
        }
        return new Cable(source, target, tick, path, jacket,
            sourceOpen ? plug(level, source) : PixelCableMesh.build(level, List.of(), 2),
            targetOpen ? plug(level, target) : PixelCableMesh.build(level, List.of(), 2));
    }

    private static Port modulePort(BlockPos position, Direction outward) {
        Vec3 center = position.getCenter();
        org.joml.Vector3f offset = new org.joml.Vector3f(0, (float) (-.5 + 5.5 / 16), 1.5F / 16);
        outward.getRotation().transform(offset);
        return new Port(center.add(offset.x(), offset.y(), offset.z()), outward);
    }

    private static Port port(BlockPos pos, Direction facing, double horizontal, double height) {
        Vec3 start = Vec3.atBottomCenterOf(pos).add(0, height, 0)
            .relative(facing.getCounterClockWise(), horizontal)
            .relative(facing.getOpposite(), .5);
        return new Port(start, facing.getOpposite());
    }

    private static PixelCableMesh plug(Level level, Port port) {
        return PixelCableMesh.build(level, List.of(port.point().relative(port.outward(), -.003),
            port.point().relative(port.outward(), .123)), 2);
    }

    private record Port(Vec3 point, Direction outward) {
        Vec3 exit() {
            Vec3 end = point.relative(outward, EXIT + .1);
            return new Vec3(Math.round(end.x * 4) / 4D,
                Math.round((end.y - .0625) * 4) / 4D + .0625, Math.round(end.z * 4) / 4D);
        }

        boolean exposed(Level level) {
            return CableRouting.clearSegment(level, point.relative(outward, .061), exit(), CableRouting.PORT_CLEARANCE);
        }
    }
    private record Cable(Port source, Port target, long checkedAt, List<Vec3> path, PixelCableMesh jacket,
                         PixelCableMesh sourcePlug, PixelCableMesh targetPlug) {}
}
