package net.zouhmi.silentstaffmembers;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    static String tabRank(StaffRank rank) {
        return "&" + rank.hexColor() + "[" + rank.name() + "]";
    }

    static TabSlots tabSlots(String format, StaffRank rank) {
        String template = template(format);
        Matcher name = NAME_PLACEHOLDER.matcher(template);

        if (!name.find()) return new TabSlots(tabText(template, rank, ""), "");
        return new TabSlots(
                tabText(template.substring(0, name.start()), rank, ""),
                tabText(template.substring(name.end()), rank, ""));
    }

    static boolean containsRank(String format) {
        String template = template(format);
        return RANK_PLACEHOLDER.matcher(template).find();
    }

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
