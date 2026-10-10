package net.zouhmi.silentstaffmembers;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * The list of players who may join without holding a Silent Client staff rank.
 *
 * <p>Entries are plain Minecraft names, matched case-insensitively. They are kept in
 * insertion order and stored in {@code whitelist.yml} next to the config so a server
 * owner can also edit them by hand. Access is synchronised because the whitelist is read
 * from player login threads and written from the command thread.
 */
final class StaffWhitelist {
    private static final String ROOT_KEY = "players";

    private final Path file;
    private final Logger logger;
    /** lowercase name -> name as it was added */
    private final Map<String, String> names = Collections.synchronizedMap(new LinkedHashMap<>());

    StaffWhitelist(Path file, Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    boolean contains(String username) {
        return username != null && names.containsKey(username.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * @return {@code false} when the name is already whitelisted
     */
    boolean add(String username) {
        String trimmed = username.trim();
        return names.putIfAbsent(trimmed.toLowerCase(Locale.ROOT), trimmed) == null;
    }

    /**
     * @return {@code false} when the name was not whitelisted
     */
    boolean remove(String username) {
        return username != null && names.remove(username.trim().toLowerCase(Locale.ROOT)) != null;
    }

    /** The whitelisted names, in the order they were added, as they were typed. */
    List<String> names() {
        synchronized (names) {
            return List.copyOf(names.values());
        }
    }

    int size() {
        return names.size();
    }

    /** Re-reads the file, creating an empty one on first run. A broken file leaves the list empty. */
    void load() {
        names.clear();
        if (!Files.isRegularFile(file)) {
            save();
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file.toFile());
        } catch (IOException | InvalidConfigurationException | RuntimeException e) {
            logger.warning("Could not read " + file.getFileName() + " (" + e
                    + "); the join whitelist is being treated as empty.");
            return;
        }
        Object raw = yaml.get(ROOT_KEY);
        if (raw instanceof List<?> list) {
            for (Object value : list) addIfName(value);
        } else if (raw instanceof String single) {
            // A single name typed by hand instead of a list still counts.
            addIfName(single);
        }
    }

    private void addIfName(Object value) {
        if (value instanceof String name && !name.isBlank()) add(name);
    }

    void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set(ROOT_KEY, names());
        try {
            Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
            yaml.save(file.toFile());
        } catch (IOException e) {
            logger.severe("Could not save " + file + ": " + e);
        }
    }

    /** For the command's usage messages. */
    static List<String> filter(List<String> options, String prefix) {
        String lower = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lower)) matches.add(option);
        }
        return matches;
    }
}
