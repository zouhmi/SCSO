package net.zouhmi.silentstaffmembers;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class NameFormats {
    private static final Pattern PLACEHOLDER =
            Pattern.compile("%(icon|rank|name|message)%", Pattern.CASE_INSENSITIVE);
    /** A missing icon must not leave the whitespace that separated it from the rank. */
    private static final Pattern ICON_AND_SPACE = Pattern.compile("(?i)%icon%[ \\t]?");
    private static final String PLACEHOLDER_NAME = "%name%";
    static final String DEFAULT_FORMAT = "%rank% %name%";

    private NameFormats() { }

    static Component rankPrefix(StaffRank rank) {
        return Component.text("[" + rank.name() + "]", rank.color())
                .append(Component.text(" "));
    }

    static Component displayName(StaffRank rank, String playerName) {
        return rankPrefix(rank).append(Component.text(playerName, NamedTextColor.WHITE));
    }

    static Component chat(StaffRank rank, String playerName, Component message) {
        return apply(DEFAULT_FORMAT + " >> %message%", rank, playerName, message);
    }

    static Component apply(String format, StaffRank rank, String playerName, Component message) {
        return apply(format, rank, playerName, message, "");
    }

    /**
     * Renders a format for one viewer. {@code icon} is the pack glyph for the rank, or an
     * empty string when that viewer cannot render it - the format then simply loses the
     * icon and the space that followed it.
     */
    static Component apply(String format, StaffRank rank, String playerName, Component message, String icon) {
        String template = prepare(format, icon);
        Matcher matcher = PLACEHOLDER.matcher(template);
        Component result = Component.empty();
        int offset = 0;
        while (matcher.find()) {
            if (matcher.start() > offset) {
                result = result.append(Component.text(template.substring(offset, matcher.start())));
            }
            result = result.append(switch (matcher.group(1).toLowerCase(Locale.ROOT)) {
                case "icon" -> Component.text(icon);
                case "rank" -> Component.text("[" + rank.name() + "]", rank.color());
                case "name" -> Component.text(playerName, NamedTextColor.WHITE);
                case "message" -> message;
                default -> Component.empty();
            });
            offset = matcher.end();
        }
        if (offset < template.length()) result = result.append(Component.text(template.substring(offset)));
        return result;
    }

    /**
     * The colored {@code [Rank]} text in TAB's own string syntax. TAB parses
     * {@code &#RRGGBB} itself, and both the color and the rank name are validated
     * before they reach it, so a rank can never inject formatting of its own.
     */
    static String tabRank(StaffRank rank) {
        return "&" + rank.hexColor() + "[" + rank.name() + "]";
    }

    /**
     * Splits a format into the slots TAB's API takes. TAB keeps ownership of the
     * player's name, so {@code %name%} is the split point: the text before it becomes
     * the tab-list prefix, the text after it the suffix, and the name in the middle is
     * left to TAB so its sorting and name handling keep working.
     */
    static TabSlots tabSlots(String format, StaffRank rank) {
        return tabSlots(format, rank, "");
    }

    static TabSlots tabSlots(String format, StaffRank rank, String icon) {
        String template = prepare(format, icon);
        int nameAt = template.toLowerCase(Locale.ROOT).indexOf(PLACEHOLDER_NAME);
        // Neither part can contain %name%, so only these placeholders need resolving.
        if (nameAt < 0) return new TabSlots(tabText(template, rank, "", icon), "");
        return new TabSlots(
                tabText(template.substring(0, nameAt), rank, "", icon),
                tabText(template.substring(nameAt + PLACEHOLDER_NAME.length()), rank, "", icon));
    }

    /** The full single-line tab text, for when the format never names the player. */
    static String tabString(String format, StaffRank rank, String playerName) {
        return tabString(format, rank, playerName, "");
    }

    static String tabString(String format, StaffRank rank, String playerName, String icon) {
        return tabText(prepare(format, icon), rank, playerName, icon);
    }

    private static String tabText(String template, StaffRank rank, String playerName, String icon) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder(template.length() + 16);
        int offset = 0;
        while (matcher.find()) {
            out.append(template, offset, matcher.start());
            switch (matcher.group(1).toLowerCase(Locale.ROOT)) {
                case "icon" -> out.append(icon);
                case "rank" -> out.append(tabRank(rank));
                case "name" -> out.append(playerName);
                default -> { }
            }
            offset = matcher.end();
        }
        out.append(template, offset, template.length());
        return out.toString();
    }

    private static String prepare(String format, String icon) {
        String template = template(format);
        return icon == null || icon.isEmpty() ? ICON_AND_SPACE.matcher(template).replaceAll("") : template;
    }

    private static String template(String format) {
        return format == null || format.isBlank() ? DEFAULT_FORMAT : format;
    }

    record TabSlots(String prefix, String suffix) { }
}
