package io.github.retrocomputers;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

final class InterfaceBlock extends BaseEntityBlock implements IWrenchable {
    static final DirectionProperty FACE = BlockStateProperties.FACING;
    private static final MapCodec<InterfaceBlock> CODEC = simpleCodec(InterfaceBlock::new);

    InterfaceBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACE, Direction.UP));
    }

    @Override protected MapCodec<InterfaceBlock> codec() { return CODEC; }
    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new Device(pos, state); }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACE);
    }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACE, context.getClickedFace());
    }
    @Override protected BlockState rotate(BlockState state, net.minecraft.world.level.block.Rotation rotation) {
        return state.setValue(FACE, rotation.rotate(state.getValue(FACE)));
    }
    @Override public BlockState getRotatedBlockState(BlockState state, Direction face) {
        return state;
    }
    static BlockPos target(BlockPos position, BlockState state) {
        return position.relative(state.getValue(FACE).getOpposite());
    }
    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return switch (state.getValue(FACE)) {
            case UP -> Block.box(3, 0, 3, 13, 5.5, 13);
            case DOWN -> Block.box(3, 10.5, 3, 13, 16, 13);
            case NORTH -> Block.box(3, 3, 10.5, 13, 13, 16);
            case SOUTH -> Block.box(3, 3, 0, 13, 13, 5.5);
            case EAST -> Block.box(0, 3, 3, 5.5, 13, 13);
            case WEST -> Block.box(10.5, 3, 3, 16, 13, 13);
        };
    }
    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide ? null : createTickerHelper(type, RetroComputers.DEVICE.get(), (world, pos, block, module) -> {
            if (world.getGameTime() % 5 != 0) return;
            Device computer = module.computer();
            int output = computer != null && computer.powered && computer.hasModem() ? module.requestedSignal : 0;
            if (module.activeSignal != output) {
                module.activeSignal = output;
                module.refresh();
                world.updateNeighborsAt(pos, block.getBlock());
                world.updateNeighborsAt(target(pos, block), world.getBlockState(target(pos, block)).getBlock());
            }
        });
    }
    @Override protected boolean isSignalSource(BlockState state) { return true; }
    @Override protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return level.getBlockEntity(pos) instanceof Device device ? device.activeSignal : 0;
    }
    @Override protected int getDirectSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return getSignal(state, level, pos, direction);
    }
    @Override protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hit) {
        if (!stack.is(RetroComputers.LINKER.get())) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof Device module) {
            var data = stack.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                net.minecraft.world.item.component.CustomData.EMPTY).copyTag();
            BlockPos computerPos = BlockPos.of(data.getLong("Pos"));
            if (!data.hasUUID("Id") || !data.getString("Dimension").equals(level.dimension().location().toString())
                    || !level.hasChunkAt(computerPos) || !(level.getBlockEntity(computerPos) instanceof Device computer)
                    || !computer.getBlockState().is(RetroComputers.PC.get()) || !computer.identity.equals(data.getUUID("Id"))) {
                player.displayClientMessage(DeviceNetwork.message("select"), true);
            } else if (!computer.hasModem()) {
                player.displayClientMessage(DeviceNetwork.message("no_modem"), true);
            } else if (!pos.closerThan(computerPos, 65)) {
                player.displayClientMessage(net.minecraft.network.chat.Component.translatable("retrocomputers.range"), true);
            } else {
                String name = computer.network.connect(module);
                if (name == null) player.displayClientMessage(DeviceNetwork.message("limit"), true);
                else {
                    module.bind(computer);
                    module.requestedSignal = 0;
                    module.portName = name;
                    module.refresh();
                    computer.refresh();
                    ConnectionEffects.show(level, pos, computerPos);
                    player.displayClientMessage(DeviceNetwork.message("linked", name), true);
                }
            }
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }
    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof Device module) {
            player.displayClientMessage(DeviceNetwork.message("status", module.portName, module.activeSignal), true);
            ConnectionEffects.show(level, pos, module.computerPos);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
    @Override protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moving) {
        super.onRemove(state, level, pos, next, moving);
        if (!state.is(next.getBlock())) level.updateNeighborsAt(pos, this);
    }
}
