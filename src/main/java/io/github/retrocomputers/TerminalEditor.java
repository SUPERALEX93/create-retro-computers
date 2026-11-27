package io.github.retrocomputers;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

/** A shared, persistent editor buffer; edits and saves are authoritative on the server. */
final class TerminalEditor {
    private final Device owner;
    private String file = "";
    private String text = "";
    private String saved = "";
    private int cursor;
    private int preferredColumn = -1;
    private boolean dirty;
    private boolean confirming;
    private String status = "";
    private List<Component> console = List.of();
    private List<String> cached;
    private int cachedColumns, cachedRows;

    TerminalEditor(Device owner) { this.owner = owner; }
    boolean active() { return !file.isEmpty(); }
    String text() { return text; }

    Component open(String path) {
        cached = null;
        if (path.isBlank() || path.contains(" ")) return message("usage");
        if (owner.programRunning) return PythonPrograms.text("busy");
        try {
            String initial = owner.files.fileExists(path) ? owner.files.readText(path) : "";
            if (initial.length() > 131072) return message("large");
            file = path;
            text = saved = initial.replace("\r\n", "\n").replace('\r', '\n');
            cursor = 0; preferredColumn = -1; dirty = confirming = false; status = "";
            console = owner.output;
            owner.input = "";
            owner.clearPendingCommands();
            owner.refresh();
            return null;
        } catch (Exception error) { return message("error", String.valueOf(error.getMessage())); }
    }

    void paste(String value) {
        cached = null;
        if (!active() || confirming) return;
        String inserted = value.replace("\r\n", "\n").replace('\r', '\n').replace("\t", "    ");
        if (inserted.codePoints().anyMatch(c -> Character.isISOControl(c) && c != '\n')) return;
        if (text.length() + inserted.length() > 131072) { status = "large"; owner.refresh(); return; }
        text = text.substring(0, cursor) + inserted + text.substring(cursor);
        cursor += inserted.length();
        preferredColumn = -1; dirty = !text.equals(saved); status = "";
        owner.refresh();
    }

    void key(int key) {
        cached = null;
        if (!active()) return;
        if (confirming) {
            switch (Character.toLowerCase(key)) {
                case 'y' -> { if (save()) close(); }
                case 'n' -> close();
                case 'c' -> confirming = false;
                default -> { if (key == KeyboardLayout.INTERRUPT) confirming = false; }
            }
            owner.refresh(); return;
        }
        switch (key) {
            case KeyboardLayout.SAVE -> save();
            case KeyboardLayout.EXIT_EDITOR -> { if (dirty) confirming = true; else close(); }
            case KeyboardLayout.RUN_EDITOR -> {
                if (save()) { String path = file; close(); owner.runEditorFile(path); }
            }
            case KeyboardLayout.PREVIOUS -> moveLine(-1);
            case KeyboardLayout.NEXT -> moveLine(1);
            case KeyboardLayout.PAGE_UP -> moveLine(-10);
            case KeyboardLayout.PAGE_DOWN -> moveLine(10);
            case KeyboardLayout.LEFT -> { cursor = Math.max(0, cursor - 1); preferredColumn = -1; }
            case KeyboardLayout.RIGHT -> { cursor = Math.min(text.length(), cursor + 1); preferredColumn = -1; }
            case KeyboardLayout.HOME -> { cursor = lineStart(); preferredColumn = -1; }
            case KeyboardLayout.END -> { cursor = lineEnd(); preferredColumn = -1; }
            case KeyboardLayout.DELETE -> {
                if (cursor > 0) { text = text.substring(0, cursor - 1) + text.substring(cursor); cursor--; }
                dirty = !text.equals(saved); preferredColumn = -1;
            }
            case KeyboardLayout.FORWARD_DELETE -> {
                if (cursor < text.length()) text = text.substring(0, cursor) + text.substring(cursor + 1);
                dirty = !text.equals(saved); preferredColumn = -1;
            }
            case KeyboardLayout.TAB -> paste("    ");
            case KeyboardLayout.RETURN -> {
                String line = text.substring(lineStart(), cursor);
                int spaces = 0; while (spaces < line.length() && line.charAt(spaces) == ' ') spaces++;
                paste("\n" + " ".repeat(spaces));
            }
            default -> { if (key >= 32 && key <= 126) paste(Character.toString(key)); }
        }
        owner.refresh();
    }

    private int lineStart() { return cursor == 0 ? 0 : text.lastIndexOf('\n', cursor - 1) + 1; }
    private int lineEnd() { int end = text.indexOf('\n', cursor); return end < 0 ? text.length() : end; }

    private void moveLine(int amount) {
        int start = lineStart();
        if (preferredColumn < 0) preferredColumn = cursor - start;
        for (int i = 0; i < Math.abs(amount); i++) {
            if (amount < 0) {
                if (start == 0) break;
                start = text.lastIndexOf('\n', start - 2) + 1;
            } else {
                int end = text.indexOf('\n', start);
                if (end < 0) break;
                start = end + 1;
            }
        }
        int end = text.indexOf('\n', start);
        cursor = Math.min(start + preferredColumn, end < 0 ? text.length() : end);
    }

    private boolean save() {
        try {
            owner.files.writeText(file, text, owner.diskCapacity());
            saved = text; dirty = false; status = "saved";
            return true;
        } catch (Exception error) {
            status = "! " + error.getMessage();
            return false;
        }
    }

    private void close() {
        cached = null;
        file = ""; confirming = dirty = false;
        owner.output = console;
        text = saved = ""; cursor = 0; status = ""; console = List.of();
        owner.refresh();
    }

    List<String> lines(int columns, int rows) {
        if (cached != null && cachedColumns == columns && cachedRows == rows) return cached;
        var result = new ArrayList<String>();
        String[] source = text.split("\n", -1);
        int lineNumber = 0;
        for (int i = 0; i < cursor; i++) if (text.charAt(i) == '\n') lineNumber++;
        int column = cursor - lineStart();
        int visible = Math.max(1, rows - 3);
        int top = Math.max(0, lineNumber - visible + 1);
        int gutter = Integer.toString(source.length).length() + 1;
        int horizontal = Math.max(0, column - Math.max(1, columns - gutter - 2));
        result.add(crop("Nano | " + file + (dirty ? " [+]" : ""), columns));
        for (int i = top; i < top + visible; i++) {
            String line = i < source.length ? source[i] : "";
            if (i == lineNumber) line = line.substring(0, column) + "|" + line.substring(column);
            String number = i < source.length ? String.format("%" + (gutter - 1) + "d ", i + 1) : " ".repeat(gutter);
            result.add(crop(number + line.substring(Math.min(horizontal, line.length())), columns));
        }
        String hint = confirming ? message("confirm").getString() : status.isEmpty()
            ? (lineNumber + 1) + ":" + (column + 1) : status.startsWith("!") ? status : message(status).getString();
        result.add(crop(hint, columns));
        result.add(crop(message("keys").getString(), columns));
        cachedColumns = columns; cachedRows = rows;
        return cached = List.copyOf(result);
    }

    void clipboardTooLarge() { status = "paste_large"; cached = null; }

    private static String crop(String line, int width) { return line.substring(0, Math.min(width, line.length())); }
    static Component message(String key, Object... args) { return Component.translatable("retrocomputers.editor." + key, args); }

    CompoundTag save(HolderLookup.Provider registries) {
        var tag = new CompoundTag();
        if (!active()) return tag;
        tag.putString("File", file); tag.putString("Text", text); tag.putString("Saved", saved);
        tag.putInt("Cursor", cursor); tag.putBoolean("Dirty", dirty); tag.putBoolean("Confirm", confirming); tag.putString("Status", status);
        var previous = new CompoundTag(); TerminalOutput.save(previous, registries, console); tag.put("Console", previous);
        return tag;
    }

    void load(CompoundTag tag, HolderLookup.Provider registries) {
        cached = null;
        file = tag.getString("File"); text = tag.getString("Text"); saved = tag.contains("Saved") ? tag.getString("Saved") : text;
        cursor = Math.clamp(tag.getInt("Cursor"), 0, text.length()); dirty = tag.getBoolean("Dirty");
        confirming = tag.getBoolean("Confirm"); status = tag.getString("Status");
        console = TerminalOutput.load(tag.getCompound("Console"), registries); preferredColumn = -1;
    }
}
