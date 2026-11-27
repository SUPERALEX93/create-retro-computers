package io.github.retrocomputers;

import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

final class KeyboardScreen extends Screen {
    private static final ResourceLocation FACEPLATE = RetroComputers.id("textures/gui/keyboard.png");
    private final BlockPos position;
    private final Device keyboard;
    private boolean shiftLatch;
    private int left;
    private int top;
    private float keyboardScale = 1;
    private boolean cameraInitialized;

    KeyboardScreen(BlockPos position, Device keyboard) {
        super(Component.translatable("block.retrocomputers.keyboard"));
        this.position = position;
        this.keyboard = keyboard;
    }

    @Override
    protected void init() {
        if (!cameraInitialized) {
            keyboardScale = TerminalCamera.begin(keyboard) ? .7F : 1F;
            cameraInitialized = true;
        }
        left = (int) ((width / keyboardScale - KeyboardLayout.WIDTH) / 2);
        top = Math.max(0, (int) (height / keyboardScale - KeyboardLayout.HEIGHT - 8));
    }

    @Override
    public void removed() {
        TerminalCamera.end();
        super.removed();
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public void tick() {
        if (minecraft.level == null || minecraft.player == null || minecraft.level.getBlockEntity(position) != keyboard
                || !MovingDevices.inReach(minecraft.player, position)) onClose();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        graphics.pose().pushPose();
        graphics.pose().scale(keyboardScale, keyboardScale, 1);
        mouseX = (int) (mouseX / keyboardScale);
        mouseY = (int) (mouseY / keyboardScale);
        graphics.blit(FACEPLATE, left, top, 0, 0, KeyboardLayout.WIDTH, KeyboardLayout.HEIGHT,
            KeyboardLayout.WIDTH, KeyboardLayout.HEIGHT);
        Device computer = keyboard.computer();
        String status = computer == null ? "disconnected" : computer.powered ? "online" : "offline";
        Component heading = computer == null ? title : title.copy().append(" · " + TerminalZoom.percent(computer.zoomStep) + "%");
        graphics.drawString(font, heading, left + 12, top + 10, 0xfff0dabb, false);
        lamp(graphics, left + 13, computer != null, 0xffedbd68);
        lamp(graphics, left + 24, computer != null && computer.powered, 0xffa6e596);
        graphics.drawString(font, Component.translatable("retrocomputers." + status),
            left + 37, top + 26, 0xffece6cf, false);
        boolean shift = shiftLatch || hasShiftDown();
        long now = Util.getMillis();
        for (KeyboardLayout.Key key : KeyboardLayout.KEYS) {
            drawKey(graphics, key, shift, key.hit(mouseX - left, mouseY - top), now);
        }
        graphics.drawCenteredString(font, Component.translatable("retrocomputers.keyboard_hint"),
            left + KeyboardLayout.WIDTH / 2, top + 144, 0xffe5d3b5);
        graphics.pose().popPose();
    }

    private void lamp(GuiGraphics graphics, int x, boolean on, int light) {
        int y = top + 26;
        graphics.fill(x, y, x + 7, y + 7, 0xff24231c);
        graphics.fill(x + 1, y + 1, x + 6, y + 6, on ? light : 0xff584d3d);
        if (on) graphics.fill(x + 2, y + 1, x + 5, y + 2, 0xfffff2cc);
    }

    private void drawKey(GuiGraphics graphics, KeyboardLayout.Key key, boolean shift, boolean hover, long now) {
        int x = left + key.x();
        int y = top + key.y();
        int w = key.width();
        int travel = Math.round(3 * keyboard.keyDepth(key.code(), now));
        boolean function = key.label() != null && key.code() != KeyboardLayout.SPACE;
        boolean latched = key.code() == KeyboardLayout.SHIFT && shift;
        int base = function ? 0xff705646 : 0xff9b805f;
        int face = latched ? 0xffe6a966 : hover ? 0xffffebc5 : function ? 0xffc7a688 : 0xffeddbb6;
        graphics.fill(x, y + 2, x + w, y + 16, 0xff302923);
        graphics.fill(x + 1, y + 3 + travel, x + w - 1, y + 15, base);
        graphics.fill(x + 1, y + travel, x + w - 1, y + 12 + travel, face);
        graphics.fill(x + 2, y + travel, x + w - 2, y + 1 + travel, 0xffffeed1);
        graphics.fill(x + 2, y + 11 + travel, x + w - 2, y + 12 + travel, 0xffac8c68);
        String label = key.label() == null ? Character.toString(key.value(shift))
            : net.minecraft.client.resources.language.I18n.get("retrocomputers.key." + key.label().toLowerCase(java.util.Locale.ROOT));
        graphics.drawString(font, label, x + (w - font.width(label)) / 2,
            y + 2 + travel, 0xff30271f, false);
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        x /= keyboardScale;
        y /= keyboardScale;
        if (button == 0) {
            for (KeyboardLayout.Key key : KeyboardLayout.KEYS) {
                if (!key.hit(x - left, y - top)) continue;
                if (key.code() == KeyboardLayout.SHIFT) {
                    shiftLatch = !shiftLatch;
                    keyboard.animateKey(KeyboardLayout.SHIFT);
                    return true;
                }
                if (key.code() == KeyboardLayout.ESCAPE) {
                    onClose();
                    return true;
                }
                int value = key.value(shiftLatch || hasShiftDown());
                shiftLatch = false;
                return send(value);
            }
        }
        return super.mouseClicked(x, y, button);
    }

    @Override
    public boolean charTyped(char code, int modifiers) {
        // Physical keys below always use US layout, independently of the OS layout.
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scanCode, int modifiers) {
        if ((modifiers & GLFW.GLFW_MOD_CONTROL) != 0 || hasControlDown()) {
            Device computer = keyboard.computer();
            switch (key) {
                case GLFW.GLFW_KEY_C -> {
                    if (computer != null && computer.editor.active()) { minecraft.keyboardHandler.setClipboard(computer.editor.text()); return true; }
                    return send(KeyboardLayout.INTERRUPT);
                }
                case GLFW.GLFW_KEY_S, GLFW.GLFW_KEY_O -> { return send(KeyboardLayout.SAVE); }
                case GLFW.GLFW_KEY_X -> { return send(KeyboardLayout.EXIT_EDITOR); }
                case GLFW.GLFW_KEY_V -> {
                    if (computer != null && computer.editor.active()) {
                        String paste = minecraft.keyboardHandler.getClipboard();
                        if (paste.length() <= 8192) PacketDistributor.sendToServer(new RetroComputers.EditorPaste(position, paste));
                        else computer.editor.clipboardTooLarge();
                    }
                    return true;
                }
                case GLFW.GLFW_KEY_EQUAL, GLFW.GLFW_KEY_KP_ADD -> { TerminalZoom.change(computer, 1); return true; }
                case GLFW.GLFW_KEY_MINUS, GLFW.GLFW_KEY_KP_SUBTRACT -> { TerminalZoom.change(computer, -1); return true; }
                case GLFW.GLFW_KEY_0, GLFW.GLFW_KEY_KP_0 -> {
                    if (computer != null) { computer.zoomStep = TerminalZoom.DEFAULT; computer.scrollRows = 0; }
                    return true;
                }
            }
        }
        if (hasControlDown() || (modifiers & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_ALT)) != 0) return true;
        int character = englishCharacter(key, modifiers);
        if (character >= 0) { shiftLatch = false; return send(character); }
        return switch (key) {
            case GLFW.GLFW_KEY_LEFT -> send(KeyboardLayout.LEFT);
            case GLFW.GLFW_KEY_RIGHT -> send(KeyboardLayout.RIGHT);
            case GLFW.GLFW_KEY_HOME -> send(KeyboardLayout.HOME);
            case GLFW.GLFW_KEY_END -> send(KeyboardLayout.END);
            case GLFW.GLFW_KEY_PAGE_UP -> send(KeyboardLayout.PAGE_UP);
            case GLFW.GLFW_KEY_PAGE_DOWN -> send(KeyboardLayout.PAGE_DOWN);
            case GLFW.GLFW_KEY_TAB -> send(KeyboardLayout.TAB);
            case GLFW.GLFW_KEY_F5 -> send(KeyboardLayout.RUN_EDITOR);
            case GLFW.GLFW_KEY_UP -> send(KeyboardLayout.PREVIOUS);
            case GLFW.GLFW_KEY_DOWN -> send(KeyboardLayout.NEXT);
            case GLFW.GLFW_KEY_BACKSPACE -> send(KeyboardLayout.DELETE);
            case GLFW.GLFW_KEY_DELETE -> send(keyboard.computer() != null && keyboard.computer().editor.active() ? KeyboardLayout.FORWARD_DELETE : KeyboardLayout.DELETE);
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> send(KeyboardLayout.RETURN);
            case GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_RIGHT_SHIFT -> {
                keyboard.animateKey(KeyboardLayout.SHIFT);
                yield true;
            }
            default -> super.keyPressed(key, scanCode, modifiers);
        };
    }

    private int englishCharacter(int key, int modifiers) {
        boolean shift = shiftLatch || hasShiftDown() || (modifiers & GLFW.GLFW_MOD_SHIFT) != 0;
        if (key >= GLFW.GLFW_KEY_A && key <= GLFW.GLFW_KEY_Z) {
            return shift != ((modifiers & GLFW.GLFW_MOD_CAPS_LOCK) != 0) ? key : key + 32;
        }
        if (key >= GLFW.GLFW_KEY_0 && key <= GLFW.GLFW_KEY_9)
            return shift ? ")!@#$%^&*(".charAt(key - GLFW.GLFW_KEY_0) : key;
        if (key >= GLFW.GLFW_KEY_KP_0 && key <= GLFW.GLFW_KEY_KP_9) return '0' + key - GLFW.GLFW_KEY_KP_0;
        return switch (key) {
            case GLFW.GLFW_KEY_SPACE -> ' ';
            case GLFW.GLFW_KEY_MINUS -> shift ? '_' : '-';
            case GLFW.GLFW_KEY_EQUAL -> shift ? '+' : '=';
            case GLFW.GLFW_KEY_LEFT_BRACKET -> shift ? '{' : '[';
            case GLFW.GLFW_KEY_RIGHT_BRACKET -> shift ? '}' : ']';
            case GLFW.GLFW_KEY_BACKSLASH -> shift ? '|' : '\\';
            case GLFW.GLFW_KEY_SEMICOLON -> shift ? ':' : ';';
            case GLFW.GLFW_KEY_APOSTROPHE -> shift ? '"' : '\'';
            case GLFW.GLFW_KEY_GRAVE_ACCENT -> shift ? '~' : '`';
            case GLFW.GLFW_KEY_COMMA -> shift ? '<' : ',';
            case GLFW.GLFW_KEY_PERIOD -> shift ? '>' : '.';
            case GLFW.GLFW_KEY_SLASH -> shift ? '?' : '/';
            case GLFW.GLFW_KEY_KP_ADD -> '+';
            case GLFW.GLFW_KEY_KP_SUBTRACT -> '-';
            case GLFW.GLFW_KEY_KP_MULTIPLY -> '*';
            case GLFW.GLFW_KEY_KP_DIVIDE -> '/';
            case GLFW.GLFW_KEY_KP_DECIMAL -> '.';
            case GLFW.GLFW_KEY_KP_EQUAL -> '=';
            default -> -1;
        };
    }

    @Override
    public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        Device computer = keyboard.computer();
        if (computer != null) {
            computer.scrollRows = Math.clamp(computer.scrollRows + (int) Math.signum(vertical) * 3, 0, 8192);
        }
        return true;
    }

    private boolean send(int key) {
        keyboard.animateKey(key);
        Device computer = keyboard.computer();
        if (computer != null) computer.scrollRows = 0;
        PacketDistributor.sendToServer(new RetroComputers.KeyInput(position, key, minecraft.player.getUUID()));
        return true;
    }
}
