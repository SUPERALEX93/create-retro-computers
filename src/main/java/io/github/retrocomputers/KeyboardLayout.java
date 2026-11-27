package io.github.retrocomputers;

import java.util.ArrayList;
import java.util.List;

final class KeyboardLayout {
    static final int WIDTH = 296;
    static final int HEIGHT = 160;
    static final int ESCAPE = 27;
    static final int SHIFT = 16;
    static final int DELETE = 8;
    static final int RETURN = 13;
    static final int SPACE = 32;
    static final int PREVIOUS = 256;
    static final int NEXT = 257;
    static final int INTERRUPT = 258;
    static final int LEFT = 259, RIGHT = 260, HOME = 261, END = 262, TAB = 263;
    static final int FORWARD_DELETE = 264, SAVE = 265, EXIT_EDITOR = 266;
    static final int PAGE_UP = 267, PAGE_DOWN = 268, RUN_EDITOR = 269;
    static final List<Key> KEYS = layout();

    record Key(int code, int shifted, String label, int x, int y, int width) {
        static final int HEIGHT = 16;

        boolean hit(double mouseX, double mouseY) {
            return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + HEIGHT;
        }

        int value(boolean shift) {
            return shift ? shifted : code;
        }
    }

    private static List<Key> layout() {
        List<Key> keys = new ArrayList<>();
        addRow(keys, "1234567890-=", "!@#$%^&*()_+", 11, 42);
        keys.add(command(DELETE, "DEL", 251, 42, 34));
        addRow(keys, "QWERTYUIOP[]\\", "QWERTYUIOP{}|", 20, 61);
        addRow(keys, "ASDFGHJKL;'", "ASDFGHJKL:\"", 28, 80);
        keys.add(command(RETURN, "RETURN", 249, 80, 36));
        keys.add(command(SHIFT, "SHIFT", 11, 99, 39));
        addRow(keys, "ZXCVBNM,./", "ZXCVBNM<>?", 53, 99);
        keys.add(new Key('`', '~', null, 254, 99, 18));
        keys.add(command(ESCAPE, "ESC", 11, 118, 35));
        keys.add(command(SPACE, "SPACE", 70, 118, 153));
        return List.copyOf(keys);
    }

    private static Key command(int code, String label, int x, int y, int width) {
        return new Key(code, code, label, x, y, width);
    }

    private static void addRow(List<Key> keys, String normal, String shifted, int x, int y) {
        for (int i = 0; i < normal.length(); i++) {
            keys.add(new Key(normal.charAt(i), shifted.charAt(i), null, x + i * 20, y, 18));
        }
    }

    static boolean accepts(int code) {
        return code == DELETE || code == RETURN || code >= PREVIOUS && code <= RUN_EDITOR
            || code >= 32 && code <= 126;
    }

    static int physicalKey(int code) {
        int upper = Character.toUpperCase(code);
        for (Key key : KEYS) {
            if (key.code() == upper || key.shifted() == upper) return key.code();
        }
        return upper;
    }
}
