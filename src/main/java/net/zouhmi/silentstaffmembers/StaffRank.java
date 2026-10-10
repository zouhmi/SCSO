package net.zouhmi.silentstaffmembers;

import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;

import java.util.regex.Pattern;

/**
 * One staff rank exactly as Silent Client reports it: display name, the service's
 * own hex color, and its priority. Priority decides which rank is shown when an
 * account holds several (for example Tech Support and Senior Tech Support).
 */
record StaffRank(String name, String hexColor, int priority) {
    private static final Pattern HEX_COLOR = Pattern.compile("#[0-9a-fA-F]{6}");
    /** Legacy/hex formatting codes, so a name is never re-interpreted as one. */
    private static final Pattern FORMAT_CODES = Pattern.compile("[&\u00A7](#[0-9a-fA-F]{6}|[0-9a-fk-orA-FK-OR])");
    /** Anything left over that could still start a code, plus control characters. */
    private static final Pattern UNSAFE_NAME = Pattern.compile("[&\u00A7\\p{Cntrl}]");
    private static final int MAX_NAME_LENGTH = 32;

    static StaffRank from(String name, String color) {
        return from(name, color, 0);
    }

    /** The rank's color for the component path; validated, so it always resolves. */
    TextColor color() {
        TextColor color = TextColor.fromHexString(hexColor);
        return color == null ? NamedTextColor.WHITE : color;
    }

    static StaffRank from(String name, String color, int priority) {
        String safeColor = color != null && HEX_COLOR.matcher(color).matches() ? color : "#FFFFFF";
        return new StaffRank(safeName(name), safeColor, priority);
    }

    /**
     * Rank names come from the API, so they are stripped of anything that could be
     * read as a formatting code before they are ever rendered - the component path is
     * safe by construction, but TAB's API takes legacy strings that it parses itself.
     */
    private static String safeName(String name) {
        if (name == null) return "Staff";
        String cleaned = UNSAFE_NAME.matcher(FORMAT_CODES.matcher(name).replaceAll("")).replaceAll("").trim();
        if (cleaned.isEmpty()) return "Staff";
        return cleaned.length() > MAX_NAME_LENGTH ? cleaned.substring(0, MAX_NAME_LENGTH) : cleaned;
    }
}
