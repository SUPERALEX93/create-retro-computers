package io.github.retrocomputers;

final class TerminalZoom {
    private static final int[] COLUMNS = {240, 192, 144, 120, 96, 84, 72, 60, 48, 36, 30, 24, 20, 16, 12};
    static final int DEFAULT = 11;

    static int columns(int step) { return COLUMNS[Math.clamp(step, 0, COLUMNS.length - 1)]; }
    static int canvas(int step) { return columns(step) * 7; }
    static int rows(int step) { return canvas(step) / 12; }
    static int percent(int step) { return Math.round(2400F / columns(step)); }

    static void change(Device computer, int amount) {
        if (computer == null) return;
        computer.zoomStep = Math.clamp(computer.zoomStep + amount, 0, COLUMNS.length - 1);
        computer.scrollRows = 0;
    }
}
