package net.zouhmi.silentstaffmembers;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

final class StaffWhitelist {
    private static final String ROOT_KEY = "players";

    private final Path file;
    private final Logger logger;

    private final Map<String, String> names = new LinkedHashMap<>();

    StaffWhitelist(Path file, Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    synchronized boolean contains(String username) {
        return username != null && names.containsKey(username.trim().toLowerCase(Locale.ROOT));
    }

    synchronized boolean add(String username) {
        if (username == null) return false;
        String trimmed = username.trim();
        if (trimmed.isEmpty()) return false;
        return names.putIfAbsent(trimmed.toLowerCase(Locale.ROOT), trimmed) == null;
    }

    synchronized boolean remove(String username) {
        return username != null && names.remove(username.trim().toLowerCase(Locale.ROOT)) != null;
    }

    synchronized List<String> names() {
        return List.copyOf(names.values());
    }

    synchronized int size() {
        return names.size();
    }

    synchronized void load() {
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

            addIfName(single);
        } else if (raw instanceof Map<?, ?> map) {

            for (Object key : map.keySet()) addIfName(key);
        }
    }

    private void addIfName(Object value) {
        if (value instanceof String name && !name.isBlank()) add(name);
    }

    synchronized void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set(ROOT_KEY, List.copyOf(names.values()));
        try {
            Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
            yaml.save(file.toFile());
        } catch (IOException e) {
            logger.severe("Could not save " + file + ": " + e);
        }
    }

    static List<String> filter(List<String> options, String prefix) {
        String lower = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lower)) matches.add(option);
        }
        return matches;
    }
}
