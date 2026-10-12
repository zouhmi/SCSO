package net.zouhmi.silentstaffmembers;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * The rank an admin has pinned onto a player, overriding whatever Silent Client reports.
 *
 * <p>Entries are stored by Minecraft name (matched case-insensitively) because that is
 * what an admin types; the label is any rank name, including one defined in the config's
 * {@code custom-ranks} section. Entries come from two places, so the file wins:
 * the config's {@code rank-overrides} section is applied first and {@code ranks.yml},
 * which the commands write, is applied on top.
 */
final class RankOverrides {
    private static final String ROOT_KEY = "players";
    private static final String RANK_KEY = "rank";

    private final Path file;
    private final Logger logger;
    /** lowercase name -> name as it was typed */
    private final Map<String, String> names = new LinkedHashMap<>();
    /** lowercase name -> rank label as it was typed */
    private final Map<String, String> labels = new LinkedHashMap<>();
    /** Last config entries, so saving never copies config defaults into the file. */
    private Map<String, String> configured = Map.of();

    RankOverrides(Path file, Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    /** The pinned rank label for a player, or {@code null} when there is no override. */
    synchronized String rankFor(String username) {
        if (username == null) return null;
        return labels.get(username.trim().toLowerCase(Locale.ROOT));
    }

    synchronized boolean contains(String username) {
        return rankFor(username) != null;
    }

    /**
     * Pins a rank onto a player.
     *
     * @return {@code false} when the player already had exactly this rank
     */
    synchronized boolean set(String username, String label) {
        if (username == null) return false;
        String name = username.trim();
        String rank = label == null ? "" : label.trim();
        if (name.isEmpty() || rank.isEmpty()) return false;
        String key = name.toLowerCase(Locale.ROOT);
        String previous = labels.put(key, rank);
        names.put(key, name);
        return previous == null || !previous.equalsIgnoreCase(rank);
    }

    /**
     * @return {@code false} when the player had no override
     */
    synchronized boolean remove(String username) {
        if (username == null) return false;
        String key = username.trim().toLowerCase(Locale.ROOT);
        names.remove(key);
        return labels.remove(key) != null;
    }

    /** The overridden players, in the order they were added, as they were typed. */
    synchronized List<String> names() {
        return List.copyOf(labels.keySet().stream().map(names::get).filter(java.util.Objects::nonNull).toList());
    }

    /** Every distinct rank label in use, for command completion. */
    synchronized List<String> labels() {
        return List.copyOf(new LinkedHashSet<>(labels.values()));
    }

    synchronized int size() {
        return labels.size();
    }

    /**
     * Re-reads the overrides: the config's entries first, then {@code ranks.yml} on top,
     * then the file created if it does not exist yet.
     *
     * @param configured entries from the config's {@code rank-overrides} section
     */
    synchronized void load(Map<String, String> configured) {
        // Remember the exact configured pairs so saving never copies config defaults
        // into the file: removing a name from rank-overrides then truly removes it.
        Map<String, String> remember = new LinkedHashMap<>();
        Map<String, String> spellings = new LinkedHashMap<>();
        if (configured != null) {
            for (Map.Entry<String, String> entry : configured.entrySet()) {
                String name = entry.getKey() == null ? "" : entry.getKey().trim();
                String rank = entry.getValue() == null ? "" : entry.getValue().trim();
                if (name.isEmpty() || rank.isEmpty()) continue;
                String key = name.toLowerCase(Locale.ROOT);
                remember.put(key, rank);
                spellings.putIfAbsent(key, name);
            }
        }
        this.configured = Map.copyOf(remember);
        labels.clear();
        names.clear();
        for (Map.Entry<String, String> entry : remember.entrySet()) {
            add(new Entry(spellings.getOrDefault(entry.getKey(), entry.getKey()), entry.getValue()));
        }
        if (!Files.isRegularFile(file)) {
            save();
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file.toFile());
        } catch (IOException | InvalidConfigurationException | RuntimeException e) {
            logger.warning("Could not read " + file.getFileName() + " (" + e
                    + "); only the config's rank-overrides are in use.");
            return;
        }
        for (Entry entry : readEntries(yaml)) add(entry);
    }

    private void add(Entry entry) {
        names.put(entry.name().toLowerCase(Locale.ROOT), entry.name());
        labels.put(entry.name().toLowerCase(Locale.ROOT), entry.rank());
    }

    /**
     * Accepts both shapes a hand-edited file tends to take:
     * {@code players: {Notch: Admin}} and {@code players: {Notch: {rank: Admin}}}.
     */
    private static List<Entry> readEntries(YamlConfiguration yaml) {
        List<Entry> entries = new ArrayList<>();
        ConfigurationSection section = yaml.getConfigurationSection(ROOT_KEY);
        if (section != null) {
            for (String name : section.getKeys(false)) {
                if (name == null || name.isBlank()) continue;
                ConfigurationSection nested = section.getConfigurationSection(name);
                String rank = nested != null ? nested.getString(RANK_KEY) : section.getString(name);
                if (rank != null && !rank.isBlank()) entries.add(new Entry(name, rank));
            }
        }
        return entries;
    }

    synchronized void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        Map<String, String> stored = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : names.entrySet()) {
            String rank = labels.get(entry.getKey());
            if (rank == null) continue;
            // Config defaults live in config.yml; the file only keeps command pins and
            // hand-edits that differ, so deleting a config entry really deletes it.
            String configuredRank = configured.get(entry.getKey());
            if (configuredRank != null && configuredRank.equalsIgnoreCase(rank)) continue;
            stored.put(entry.getValue(), rank);
        }
        yaml.set(ROOT_KEY, stored);
        try {
            Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
            yaml.save(file.toFile());
        } catch (IOException e) {
            logger.severe("Could not save " + file + ": " + e);
        }
    }

    private record Entry(String name, String rank) { }

    /** A rank override as the config writes it; the file and the commands both use this shape. */
    static Map<String, String> readConfigured(ConfigurationSection section) {
        if (section == null) return Map.of();
        Map<String, String> configured = new LinkedHashMap<>();
        for (String name : section.getKeys(false)) {
            if (name == null || name.isBlank()) continue;
            // Accept both `Notch: Admin` and `Notch: {rank: Admin}`, like ranks.yml does.
            ConfigurationSection nested = section.getConfigurationSection(name);
            String rank = nested != null ? nested.getString(RANK_KEY) : section.getString(name);
            if (rank == null || rank.isBlank()) continue;
            configured.put(name.trim(), rank.trim());
        }
        return configured;
    }
}
