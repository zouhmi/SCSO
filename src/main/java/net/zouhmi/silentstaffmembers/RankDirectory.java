package net.zouhmi.silentstaffmembers;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * The admin's own view of the ranks: colours, priorities and labels, keyed by the rank
 * name Silent Client reports (or by a name the admin invented for a rank pin).
 *
 * <p>Every entry is optional, so the section lets an admin recolour a real rank, change
 * which rank wins when an account holds several, or invent a rank the service does not
 * have at all. Anything not mentioned keeps the service's own colour and priority.
 *
 * <pre>
 * custom-ranks:
 *   Senior Tech Support: { color: '#00AAFF' }   # recolour a service rank
 *   Head of Staff: { color: '#FF0000', priority: 90 }
 *   Owner: '#FFAA00'
 * </pre>
 */
final class RankDirectory {
    private static final Pattern HEX = Pattern.compile("#[0-9a-fA-F]{6}");
    private static final int MAX_LABEL_LENGTH = 32;

    private final Map<String, Entry> entries;

    private RankDirectory(Map<String, Entry> entries) {
        // Order is kept: the config's order is the order ranks are listed and completed.
        this.entries = Collections.unmodifiableMap(new LinkedHashMap<>(entries));
    }

    static RankDirectory empty() {
        return new RankDirectory(Map.of());
    }

    /** @param warn notified about entries that had to be skipped or repaired */
    static RankDirectory parse(ConfigurationSection section, Consumer<String> warn) {
        if (section == null) return empty();
        Map<String, Entry> entries = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            if (key == null || key.isBlank()) continue;
            Entry entry = readEntry(key, section, warn);
            if (entry == null) continue;
            // Reachable both by the key an admin wrote and by the label it renders as,
            // so renaming a rank still recolours the name the service reports.
            entries.put(normalize(key), entry);
            entries.put(normalize(entry.label()), entry);
        }
        return new RankDirectory(entries);
    }

    private static Entry readEntry(String key, ConfigurationSection section, Consumer<String> warn) {
        String label = key.trim();
        String color = null;
        int priority = 0;
        ConfigurationSection nested = section.getConfigurationSection(key);
        if (nested != null) {
            String configuredLabel = nested.getString("label", nested.getString("name"));
            if (configuredLabel != null && !configuredLabel.isBlank()) label = configuredLabel.trim();
            color = readColor(nested.getString("color"), key, warn);
            priority = nested.getInt("priority", 0);
        } else {
            String value = section.getString(key);
            if (value == null || value.isBlank()) return null;
            color = readColor(value, key, warn);
            // A bare value that is not a colour leaves the service's own colour alone.
        }
        if (label.length() > MAX_LABEL_LENGTH) {
            label = label.substring(0, MAX_LABEL_LENGTH);
            warn.accept("custom-ranks: '" + key + "' is too long and was shortened to '" + label + "'.");
        }
        return new Entry(label, color, priority);
    }

    private static String readColor(String value, String key, Consumer<String> warn) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim();
        if (!HEX.matcher(trimmed).matches()) {
            warn.accept("custom-ranks: '" + key + "' has an invalid color '" + trimmed
                    + "' (expected #RRGGBB); the service color is kept.");
            return null;
        }
        return trimmed;
    }

    boolean isEmpty() {
        return entries.isEmpty();
    }

    /** The configured label spelling for a rank, for messages and completions. */
    String label(String label) {
        Entry entry = entries.get(normalize(label));
        return entry == null ? (label == null ? "" : label.trim()) : entry.label();
    }

    /** Every configured rank label, in config order and without repeats. */
    List<String> labels() {
        List<String> labels = new ArrayList<>(entries.size());
        for (Entry entry : entries.values()) {
            if (!labels.contains(entry.label())) labels.add(entry.label());
        }
        return List.copyOf(labels);
    }

    /** True when the admin defined this label themselves, so it may be used for a rank pin. */
    boolean defines(String label) {
        return entries.containsKey(normalize(label));
    }

    /**
     * The rank as it should be shown: the admin's colour and priority win over the
     * service's, its name is left alone unless renamed.
     */
    StaffRank display(StaffRank rank) {
        if (rank == null) return null;
        Entry entry = entries.get(normalize(rank.name()));
        if (entry == null) return rank;
        return StaffRank.from(entry.label(), entry.color() == null ? rank.hexColor() : entry.color(),
                entry.priority() == 0 ? rank.priority() : entry.priority());
    }

    /**
     * Builds the rank a pinned label stands for: the admin's colour and priority when the
     * label is defined, otherwise the label itself in the default colour.
     */
    StaffRank byLabel(String label) {
        if (label == null || label.isBlank()) return null;
        Entry entry = entries.get(normalize(label));
        if (entry == null) return StaffRank.from(label, null);
        return StaffRank.from(entry.label(), entry.color(), entry.priority());
    }

    static String normalize(String label) {
        return label == null ? "" : label.trim().toLowerCase(Locale.ROOT).replaceAll("[ _-]+", " ");
    }

    private record Entry(String label, String color, int priority) { }
}
