package net.zouhmi.silentstaffmembers;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

final class RankPolicy {
    static final Set<String> DEFAULT_STAFF_RANKS = Set.of(
            "Tester",
            "Senior Tester",
            "Tech Support",
            "Senior Tech Support",
            "Client Mod",
            "Senior Client Mod",
            "Tester Manager",
            "Client Mod Manager",
            "Admin",
            "Senior Admin",
            "Developer",
            "Owner"
    );

    private final Set<String> ranks;

    RankPolicy(Iterable<String> ranks) {
        this.ranks = StreamSupport.stream(ranks.spliterator(), false)
                .filter(rank -> rank != null && !rank.isBlank())
                .map(RankPolicy::normalize)
                .collect(Collectors.toUnmodifiableSet());
    }

    boolean allows(String rankName, String rankKey) {
        return matches(rankName) || matches(rankKey);
    }

    Set<String> configuredRanks() {
        return ranks;
    }

    static String displayName(String name, String key) {
        if (name != null && !name.isBlank()) return name.trim();
        if (key == null || key.isBlank()) return "Staff";
        return switch (normalize(key)) {
            case "tester" -> "Tester";
            case "tech support" -> "Tech Support";
            case "senior tech support" -> "Senior Tech Support";
            case "admin" -> "Admin";
            case "developer" -> "Developer";
            case "senior tester" -> "Senior Tester";
            case "senior admin" -> "Senior Admin";
            case "client mod" -> "Client Mod";
            default -> java.util.Arrays.stream(key.replace('_', ' ').replace('-', ' ').split("\\s+"))
                    .filter(part -> !part.isBlank())
                    .map(part -> {
                        String lower = part.toLowerCase(Locale.ROOT);
                        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
                    })
                    .collect(Collectors.joining(" "));
        };
    }

    private boolean matches(String rank) {
        return rank != null && ranks.contains(normalize(rank));
    }

    static String normalize(String rank) {
        return rank == null ? "" : rank.trim().toLowerCase(Locale.ROOT).replaceAll("[ _-]+", " ");
    }
}
