package io.github.retrocomputers;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import java.util.ArrayDeque;
import java.util.HashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.*;
import net.minecraft.world.level.BlockGetter;

final class DeviceBlock extends BaseEntityBlock implements IWrenchable {
    enum Kind {
        COMPUTER, MONITOR, KEYBOARD;
        MapCodec<DeviceBlock> codec() {
            return simpleCodec(properties -> new DeviceBlock(properties, this));
        }
    }

    private static final VoxelShape KEYBOARD_SHAPE = Block.box(0, 0, 0, 16, 4, 16);
    static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    static final BooleanProperty RIGHT = BooleanProperty.create("right");
    static final BooleanProperty PRESSED = BlockStateProperties.POWERED;
    private final Kind kind;

    DeviceBlock(Properties properties, Kind kind) {
        super(properties);
        this.kind = kind;
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(PRESSED, false).setValue(RIGHT, false));
    }

    @Override protected MapCodec<DeviceBlock> codec() { return kind.codec(); }
    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new Device(pos, state); }

    @Override
    protected BlockState rotate(BlockState state, net.minecraft.world.level.block.Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    public BlockState getRotatedBlockState(BlockState state, Direction face) {
        return state.setValue(FACING, state.getValue(FACING).getClockWise());
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return kind == Kind.KEYBOARD ? KEYBOARD_SHAPE : Shapes.block();
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, PRESSED, RIGHT);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction facing = context.getHorizontalDirection().getOpposite();
        if (kind == Kind.KEYBOARD) {
            BlockPos other = context.getClickedPos().relative(facing.getCounterClockWise());
            if (!context.getLevel().isInWorldBounds(other) || !context.getLevel().getBlockState(other).canBeReplaced(context)) return null;
        }
        return defaultBlockState().setValue(FACING, facing);
    }

    static BlockPos keyboardMain(BlockPos pos, BlockState state) {
        return state.getValue(RIGHT) ? pos.relative(state.getValue(FACING).getClockWise()) : pos;
    }

    static boolean wideKeyboard(Level level, BlockPos pos, BlockState state) {
        BlockState other = level.getBlockState(pos.relative(state.getValue(FACING).getCounterClockWise()));
        return other.is(state.getBlock()) && other.getValue(RIGHT) && other.getValue(FACING) == state.getValue(FACING);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, net.minecraft.world.entity.LivingEntity entity, ItemStack stack) {
        super.setPlacedBy(level, pos, state, entity, stack);
        if (kind == Kind.KEYBOARD && !state.getValue(RIGHT)) {
            level.setBlock(pos.relative(state.getValue(FACING).getCounterClockWise()), state.setValue(RIGHT, true), Block.UPDATE_ALL);
        }
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (kind == Kind.KEYBOARD && !level.isClientSide) {
            BlockPos main = keyboardMain(pos, state);
            if (state.getValue(RIGHT)) {
                BlockState first = level.getBlockState(main);
                if (first.is(this) && !first.getValue(RIGHT)) level.destroyBlock(main, !player.isCreative());
            } else if (wideKeyboard(level, pos, state)) {
                level.removeBlock(pos.relative(state.getValue(FACING).getCounterClockWise()), false);
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    public InteractionResult onWrenched(BlockState state, net.minecraft.world.item.context.UseOnContext context) {
        if (kind != Kind.KEYBOARD) return IWrenchable.super.onWrenched(state, context);
        Level level = context.getLevel();
        BlockPos pos = keyboardMain(context.getClickedPos(), state);
        BlockState main = level.getBlockState(pos);
        if (!main.is(this)) return InteractionResult.PASS;
        BlockState rotated = getRotatedBlockState(main, context.getClickedFace());
        BlockPos next = pos.relative(rotated.getValue(FACING).getCounterClockWise());
        if (!level.getBlockState(next).canBeReplaced()) return InteractionResult.FAIL;
        if (!level.isClientSide) {
            if (wideKeyboard(level, pos, main)) level.removeBlock(pos.relative(main.getValue(FACING).getCounterClockWise()), false);
            level.setBlock(pos, rotated, Block.UPDATE_ALL);
            level.setBlock(next, rotated.setValue(RIGHT, true), Block.UPDATE_ALL);
            IWrenchable.playRotateSound(level, pos);
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResult onSneakWrenched(BlockState state, net.minecraft.world.item.context.UseOnContext context) {
        if (kind != Kind.KEYBOARD) return IWrenchable.super.onSneakWrenched(state, context);
        Level level = context.getLevel();
        BlockPos main = keyboardMain(context.getClickedPos(), state);
        BlockState first = level.getBlockState(main);
        if (!first.is(this)) return InteractionResult.PASS;
        boolean wide = wideKeyboard(level, main, first);
        var hit = new BlockHitResult(main.getCenter(), context.getClickedFace(), main, false);
        var redirected = new net.minecraft.world.item.context.UseOnContext(level, context.getPlayer(), context.getHand(), context.getItemInHand(), hit);
        InteractionResult result = IWrenchable.super.onSneakWrenched(first, redirected);
        if (!level.isClientSide && level.isEmptyBlock(main) && wide) {
            level.removeBlock(main.relative(first.getValue(FACING).getCounterClockWise()), false);
        }
        return result;
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return kind == Kind.COMPUTER && !level.isClientSide
            ? createTickerHelper(type, RetroComputers.DEVICE.get(), Device::tick) : null;
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hit) {
        if (kind == Kind.KEYBOARD && state.getValue(RIGHT)) {
            BlockPos main = keyboardMain(pos, state);
            BlockState first = level.getBlockState(main);
            return first.is(this) ? useItemOn(stack, first, level, main, player, hand, hit) : ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (stack.is(RetroComputers.LINKER.get())) {
            if (!level.isClientSide && level.getBlockEntity(pos) instanceof Device device) link(stack, device, player);
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        if (kind == Kind.COMPUTER) {
            for (Device.Slot slot : Device.Slot.values()) {
                String id = slot.name().toLowerCase(java.util.Locale.ROOT);
                var upgraded = RetroComputers.PARTS.get(id + "_advanced");
                if (!stack.is(RetroComputers.PARTS.get(id).get()) && (upgraded == null || !stack.is(upgraded.get()))) continue;
                if (!level.isClientSide && level.getBlockEntity(pos) instanceof Device computer) {
                    ItemStack old = computer.parts[slot.ordinal()];
                    computer.parts[slot.ordinal()] = stack.copyWithCount(1);
                    if (!player.getAbilities().instabuild) stack.shrink(1);
                    player.getInventory().placeItemBackInInventory(old);
                    computer.refresh();
                    status(player, computer);
                }
                return ItemInteractionResult.sidedSuccess(level.isClientSide);
            }
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (kind == Kind.KEYBOARD && state.getValue(RIGHT)) {
            BlockPos main = keyboardMain(pos, state);
            BlockState first = level.getBlockState(main);
            return first.is(this) ? useWithoutItem(first, level, main, player, hit) : InteractionResult.PASS;
        }
        if (level.getBlockEntity(pos) instanceof Device device) {
            if (kind == Kind.KEYBOARD && level.isClientSide) ClientDevices.open(pos, device);
            if (kind == Kind.COMPUTER && !level.isClientSide) {
                if (player.isShiftKeyDown() && player.getMainHandItem().isEmpty()) {
                    for (int i = device.parts.length - 1; i >= 0; i--) {
                        if (device.parts[i].isEmpty()) continue;
                        player.getInventory().placeItemBackInInventory(device.parts[i]);
                        device.parts[i] = ItemStack.EMPTY;
                        device.refresh();
                        break;
                    }
                }
                status(player, device);
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    static void playKeySound(Level level, BlockPos pos, int key) {
        boolean wide = key == KeyboardLayout.SPACE || key == KeyboardLayout.RETURN;
        var sound = wide ? RetroComputers.KEY_BAR.get() : RetroComputers.KEY_TAP.get();
        float pitch = key == KeyboardLayout.RETURN ? 1.18F : 1F;
        level.playSound(null, pos, sound, SoundSource.BLOCKS, 0.45F, pitch);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moving) {
        if (kind == Kind.COMPUTER && !state.is(next.getBlock()) && !level.isClientSide
                && level.getBlockEntity(pos) instanceof Device computer) {
            for (ItemStack part : computer.parts) if (!part.isEmpty()) popResource(level, pos, part);
        }
        super.onRemove(state, level, pos, next, moving);
    }

    private void status(Player player, Device computer) {
        player.displayClientMessage(Component.translatable("retrocomputers.status",
            slotName(computer.parts[0], "cpu"), slotName(computer.parts[1], "ram"),
            Component.translatable(computer.hasModem() ? "retrocomputers.modem.installed" : "retrocomputers.modem.absent"),
            slotName(computer.parts[2], "drive"), slotName(computer.parts[3], "psu"),
            computer.energy.getEnergyStored(), computer.cost(), computer.capacity()), true);
    }

    private static Component slotName(ItemStack stack, String key) {
        return stack.isEmpty() ? Component.translatable("retrocomputers.missing." + key) : stack.getHoverName();
    }

    private void link(ItemStack linker, Device target, Player player) {
        Level level = player.level();
        if (kind == Kind.COMPUTER) {
            CompoundTag data = new CompoundTag();
            data.putLong("Pos", target.getBlockPos().asLong());
            data.putUUID("Id", target.identity);
            data.putString("Dimension", level.dimension().location().toString());
            CustomData.set(DataComponents.CUSTOM_DATA, linker, data);
            message(player, "selected");
            return;
        }
        CompoundTag data = linker.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        BlockPos pos = BlockPos.of(data.getLong("Pos"));
        if (!data.hasUUID("Id") || !data.getString("Dimension").equals(level.dimension().location().toString())
                || !level.hasChunkAt(pos) || !(level.getBlockEntity(pos) instanceof Device computer)
                || !computer.getBlockState().is(RetroComputers.PC.get()) || !computer.identity.equals(data.getUUID("Id"))) {
            message(player, "select_first");
            return;
        }
        if (!pos.closerThan(target.getBlockPos(), 65)) {
            message(player, "range");
            return;
        }
        if (kind == Kind.KEYBOARD) {
            target.bind(computer);
            target.refresh();
            ConnectionEffects.show(level, target.getBlockPos(), computer.getBlockPos());
            message(player, "linked");
        } else {
            linkScreen(level, target, computer, player);
        }
    }

    private void linkScreen(Level level, Device target, Device computer, Player player) {
        Direction facing = target.getBlockState().getValue(FACING);
        Direction right = facing.getCounterClockWise();
        BlockPos start = target.getBlockPos();
        var pending = new ArrayDeque<BlockPos>();
        var panels = new HashSet<BlockPos>();
        pending.add(start);
        panels.add(start);
        int minX = 0, maxX = 0, minY = 0, maxY = 0;
        while (!pending.isEmpty()) {
            BlockPos pos = pending.remove();
            int x = (pos.getX() - start.getX()) * right.getStepX() + (pos.getZ() - start.getZ()) * right.getStepZ();
            int y = pos.getY() - start.getY();
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
            if (maxX - minX >= 50 || maxY - minY >= 50) {
                message(player, "shape");
                return;
            }
            for (Direction direction : new Direction[]{right, right.getOpposite(), Direction.UP, Direction.DOWN}) {
                BlockPos next = pos.relative(direction);
                if (!level.hasChunkAt(next) || panels.contains(next)) continue;
                BlockState state = level.getBlockState(next);
                if (state.is(this) && state.getValue(FACING) == facing) {
                    panels.add(next);
                    pending.add(next);
                }
            }
        }
        int width = maxX - minX + 1;
        int height = maxY - minY + 1;
        if (panels.size() != width * height) {
            message(player, "shape");
            return;
        }
        BlockPos origin = start.relative(right, minX).above(maxY);
        if (!computer.getBlockPos().closerThan(origin, 65)) {
            message(player, "range");
            return;
        }
        for (BlockPos pos : panels) {
            if (!(level.getBlockEntity(pos) instanceof Device panel)) {
                message(player, "shape");
                return;
            }
        }
        for (BlockPos pos : panels) {
            Device panel = (Device) level.getBlockEntity(pos);
            panel.bind(computer);
            panel.origin = origin;
            panel.width = width;
            panel.height = height;
            panel.refresh();
        }
        ConnectionEffects.show(level, origin, computer.getBlockPos());
        player.displayClientMessage(Component.translatable("retrocomputers.screen", width, height), true);
    }

    private void message(Player player, String id) {
        player.displayClientMessage(Component.translatable("retrocomputers." + id), true);
    }
}
