package net.zouhmi.silentstaffmembers;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one place that turns a format string and a rank into the text a player sees.
 *
 * <p>A format may use {@code %rank%} (the bracketed rank in the colour Silent Client
 * sends or the admin configured), {@code %rank_plain%} (the rank name on its own),
 * {@code %name%} and, in chat, {@code %message%}.
 */
final class NameFormats {
    private static final Pattern PLACEHOLDER =
            Pattern.compile("%(rank_plain|rank|name|message)%", Pattern.CASE_INSENSITIVE);
    private static final Pattern NAME_PLACEHOLDER =
            Pattern.compile("%name%", Pattern.CASE_INSENSITIVE);
    private static final Pattern RANK_PLACEHOLDER =
            Pattern.compile("%rank_plain%|%rank%", Pattern.CASE_INSENSITIVE);
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
        String template = template(format);
        Component safeMessage = message == null ? Component.empty() : message;
        String safeName = playerName == null ? "" : playerName;
        Matcher matcher = PLACEHOLDER.matcher(template);
        Component result = Component.empty();
        int offset = 0;
        while (matcher.find()) {
            if (matcher.start() > offset) {
                result = result.append(Component.text(template.substring(offset, matcher.start())));
            }
            result = result.append(switch (matcher.group(1).toLowerCase(Locale.ROOT)) {
                case "rank" -> Component.text("[" + rank.name() + "]", rank.color());
                // The bare rank name, for formats that do not want the brackets.
                case "rank_plain" -> Component.text(rank.name(), rank.color());
                case "name" -> Component.text(safeName, NamedTextColor.WHITE);
                case "message" -> safeMessage;
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
        String template = template(format);
        Matcher name = NAME_PLACEHOLDER.matcher(template);
        // Neither part can contain %name%, so only these placeholders need resolving.
        if (!name.find()) return new TabSlots(tabText(template, rank, ""), "");
        return new TabSlots(
                tabText(template.substring(0, name.start()), rank, ""),
                tabText(template.substring(name.end()), rank, ""));
    }

    /** True when the format shows a rank anywhere, for TAB nametag handling. */
    static boolean containsRank(String format) {
        String template = template(format);
        return RANK_PLACEHOLDER.matcher(template).find();
    }

    /** The full single-line tab text, for when the format never names the player. */
    static String tabString(String format, StaffRank rank, String playerName) {
        return tabText(template(format), rank, playerName);
    }

    private static String tabText(String template, StaffRank rank, String playerName) {
        String safeName = playerName == null ? "" : playerName;
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder(template.length() + 16);
        int offset = 0;
        while (matcher.find()) {
            out.append(template, offset, matcher.start());
            switch (matcher.group(1).toLowerCase(Locale.ROOT)) {
                case "rank" -> out.append(tabRank(rank));
                case "rank_plain" -> out.append(rank.name());
                case "name" -> out.append(safeName);
                default -> { }
            }
            offset = matcher.end();
        }
        out.append(template, offset, template.length());
        return out.toString();
    }

    private static String template(String format) {
        return format == null || format.isBlank() ? DEFAULT_FORMAT : format;
    }

    record TabSlots(String prefix, String suffix) { }
}
