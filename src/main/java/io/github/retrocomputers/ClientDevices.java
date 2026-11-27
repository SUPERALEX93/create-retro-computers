package io.github.retrocomputers;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import java.util.stream.Collectors;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.createmod.ponder.foundation.PonderIndex;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

@EventBusSubscriber(modid = RetroComputers.ID, value = Dist.CLIENT)
public final class ClientDevices {
    private static final int GREEN = 0xff8dff91;
    private static final Style TERMINAL_STYLE = Style.EMPTY.withFont(RetroComputers.id("terminal"));

    @SubscribeEvent
    public static void renderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(RetroComputers.SENSOR.get(), context -> new SensorRenderer());
        event.registerBlockEntityRenderer(RetroComputers.DEVICE.get(), context -> new MonitorRenderer(context.getFont(), new MonitorContour()));
    }

    @SubscribeEvent
    public static void models(net.neoforged.neoforge.client.event.ModelEvent.RegisterAdditional event) {
        event.register(net.minecraft.client.resources.model.ModelResourceLocation.standalone(RetroComputers.id("block/sensor_needle")));
    }

    @SubscribeEvent
    public static void setup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> PonderIndex.addPlugin(new RetroPonder()));
    }

    static void open(BlockPos pos, Device device) {
        Minecraft.getInstance().setScreen(new KeyboardScreen(pos, device));
    }

    private static final class MonitorRenderer implements BlockEntityRenderer<Device> {
        private final Font font;
        private final MonitorContour contour;
        private final PeripheralCables cables = new PeripheralCables();
        private final Map<Device, TerminalText> text = new WeakHashMap<>();
        private final Map<Character, FormattedCharSequence> glyphs = new HashMap<>();

        MonitorRenderer(Font font, MonitorContour contour) {
            this.font = font;
            this.contour = contour;
        }

        @Override
        public AABB getRenderBoundingBox(Device device) {
            return PeripheralCables.bounds(device);
        }

        @Override
        public boolean shouldRender(Device device, Vec3 camera) {
            return PeripheralCables.bounds(device).distanceToSqr(camera) < 64 * 64;
        }

        @Override
        public void render(Device panel, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
            if (panel.getLevel() == null) return;
            cables.render(panel, pose, buffers, overlay);
            if (panel.getBlockState().is(RetroComputers.INTERFACE.get())) {
                ModuleRenderer.draw(panel, font, pose, buffers);
                return;
            }
            if (panel.getBlockState().is(RetroComputers.KEYBOARD.get())) {
                KeyboardRenderer.draw(panel, pose, buffers, light, overlay);
                return;
            }
            if (panel.getBlockState().is(RetroComputers.PC.get())) {
                ComputerRenderer.draw(panel, pose, buffers, light, overlay);
                return;
            }
            if (!panel.getBlockState().is(RetroComputers.PANEL.get())) return;
            Direction facing = panel.getBlockState().getValue(DeviceBlock.FACING);
            Direction right = facing.getCounterClockWise();
            int edges = contour.edges(panel.getLevel(), panel.getBlockPos(), facing, right);
            MonitorHousing.draw(panel, edges, pose, buffers, overlay);
            pose.pushPose();
            pose.translate(.5, .5, .5);
            pose.mulPose(Axis.YP.rotationDegrees(-facing.toYRot()));
            pose.translate(-.5, .5, .501);
            pose.scale(1, -1, 1);
            MonitorBezel.draw(edges, pose, buffers, light, overlay);
            if (panel.origin != null) drawText(panel, right, edges, pose, buffers);
            pose.popPose();
        }

        private void drawText(Device panel, Direction right, int edges, PoseStack pose, MultiBufferSource buffers) {
            Device computer = panel.computer();
            if (computer == null || !computer.powered) return;
            if (!(panel.getLevel().getBlockEntity(panel.origin) instanceof Device anchor)
                    || !anchor.getBlockState().is(RetroComputers.PANEL.get())
                    || !panel.computerId.equals(anchor.computerId) || !panel.origin.equals(anchor.origin)
                    || panel.width != anchor.width || panel.height != anchor.height) return;

            BlockPos offset = panel.getBlockPos().subtract(panel.origin);
            int x = offset.getX() * right.getStepX() + offset.getZ() * right.getStepZ();
            int y = -offset.getY();
            int columns = TerminalZoom.columns(computer.zoomStep);
            int rowsPerPanel = TerminalZoom.rows(computer.zoomStep);
            float canvas = TerminalZoom.canvas(computer.zoomStep);
            int lineWidth = columns * panel.width;
            List<String> lines = computer.editor.active()
                ? computer.editor.lines(lineWidth, rowsPerPanel * panel.height)
                : text.computeIfAbsent(computer, ignored -> new TerminalText()).lines(computer, lineWidth);
            int lastPage = Math.max(0, lines.size() - rowsPerPanel * panel.height);
            computer.scrollRows = Math.clamp(computer.scrollRows, 0, lastPage);
            int firstRow = lastPage - computer.scrollRows + rowsPerPanel * y;
            int firstColumn = columns * x;

            float left = (edges & MonitorContour.LEFT) != 0 ? MonitorBezel.WIDTH : 0;
            float top = (edges & MonitorContour.TOP) != 0 ? MonitorBezel.WIDTH : 0;
            float rightEdge = (edges & MonitorContour.RIGHT) != 0 ? MonitorBezel.WIDTH : 0;
            float bottom = (edges & MonitorContour.BOTTOM) != 0 ? MonitorBezel.WIDTH : 0;
            pose.translate(left, top, .001);
            pose.scale((1 - left - rightEdge) / canvas, (1 - top - bottom) / canvas, 1F / canvas);
            for (int row = 0; row < rowsPerPanel && firstRow + row < lines.size(); row++) {
                String line = lines.get(firstRow + row);
                for (int col = 0; col < columns && firstColumn + col < line.length(); col++) {
                    FormattedCharSequence glyph = glyphs.computeIfAbsent(line.charAt(firstColumn + col),
                        code -> FormattedCharSequence.codepoint(code, TERMINAL_STYLE));
                    font.drawInBatch(glyph, col * 7 + 1, row * 12 + 2, GREEN, false, pose.last().pose(),
                        buffers, Font.DisplayMode.POLYGON_OFFSET, 0, LightTexture.FULL_BRIGHT);
                }
            }
        }

        private static final class TerminalText {
            private final Map<Integer, List<String>> lines = new HashMap<>();
            private List<Component> output;
            private String input;
            private Language language;
            private String directory;
            private String computerName;
            private boolean programRunning;
            private boolean programWaiting;
            private String programPrompt = "";
            private String programPartial = "";

            List<String> lines(Device computer, int columns) {
                Language activeLanguage = Language.getInstance();
                if (!computer.output.equals(output) || !computer.input.equals(input) || language != activeLanguage || !computer.directory().equals(directory) || !computer.computerName.equals(computerName)
                        || programRunning != computer.programRunning || programWaiting != computer.programWaiting
                        || !programPrompt.equals(computer.programPrompt) || !programPartial.equals(computer.programPartial)) {
                    output = computer.output;
                    input = computer.input;
                    language = activeLanguage;
                    directory = computer.directory();
                    computerName = computer.computerName;
                    programRunning = computer.programRunning;
                    programWaiting = computer.programWaiting;
                    programPrompt = computer.programPrompt;
                    programPartial = computer.programPartial;
                    lines.clear();
                }
                return lines.computeIfAbsent(columns, width -> {
                    String history = output.stream().map(Component::getString).collect(Collectors.joining("\n"));
                    if (programRunning) {
                        String current = programWaiting ? programPrompt + programPartial + input + "_" : programPartial;
                        return wrap(history.isEmpty() ? current : history + "\n" + current, width);
                    }
                    String prompt = (computerName.isEmpty() ? "NAME>" : computerName + ":" + directory + ">") + input + "_";
                    return wrap(history.isEmpty() ? prompt : history + "\n" + prompt, width);
                });
            }
        }

        private static List<String> wrap(String text, int columns) {
            List<String> lines = new ArrayList<>();
            for (String line : text.split("\n", -1)) {
                if (line.isEmpty()) lines.add("");
                for (int start = 0; start < line.length(); start += columns) {
                    lines.add(line.substring(start, Math.min(start + columns, line.length())));
                }
            }
            return lines;
        }
    }

}
