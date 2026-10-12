package net.zouhmi.silentstaffmembers;

import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;

import java.util.regex.Pattern;

record StaffRank(String name, String hexColor, int priority) {
    private static final Pattern HEX_COLOR = Pattern.compile("#[0-9a-fA-F]{6}");

    private static final Pattern FORMAT_CODES = Pattern.compile("[&\u00A7](#[0-9a-fA-F]{6}|[0-9a-fk-orA-FK-OR])");

    private static final Pattern UNSAFE_NAME = Pattern.compile("[&\u00A7\\p{Cntrl}]");
    private static final int MAX_NAME_LENGTH = 32;

    static StaffRank from(String name, String color) {
        return from(name, color, 0);
    }

    TextColor color() {
        TextColor color = TextColor.fromHexString(hexColor);
        return color == null ? NamedTextColor.WHITE : color;
    }

    static StaffRank from(String name, String color, int priority) {
        String safeColor = color != null && HEX_COLOR.matcher(color).matches() ? color : "#FFFFFF";
        return new StaffRank(safeName(name), safeColor, priority);
    }

    private static String safeName(String name) {
        if (name == null) return "Staff";
        String cleaned = UNSAFE_NAME.matcher(FORMAT_CODES.matcher(name).replaceAll("")).replaceAll("").trim();
        if (cleaned.isEmpty()) return "Staff";
        return cleaned.length() > MAX_NAME_LENGTH ? cleaned.substring(0, MAX_NAME_LENGTH) : cleaned;
    }
}
