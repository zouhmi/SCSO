package net.zouhmi.silentstaffmembers;

import me.neznamy.tab.api.TabAPI;
import me.neznamy.tab.api.TabPlayer;
import me.neznamy.tab.api.event.EventBus;
import me.neznamy.tab.api.event.EventHandler;
import me.neznamy.tab.api.event.plugin.TabLoadEvent;
import me.neznamy.tab.api.event.player.PlayerLoadEvent;
import me.neznamy.tab.api.nametag.NameTagManager;
import me.neznamy.tab.api.tablist.SortingManager;
import me.neznamy.tab.api.tablist.TabListFormatManager;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Logger;

final class TabHook {
    private static final Map<String, String> SORT_TEAMS = Map.ofEntries(
            Map.entry(RankPolicy.normalize("Owner"), "owner"),
            Map.entry(RankPolicy.normalize("Developer"), "admin"),
            Map.entry(RankPolicy.normalize("Admin"), "admin"),
            Map.entry(RankPolicy.normalize("Senior Admin"), "admin"),
            Map.entry(RankPolicy.normalize("Client Mod Manager"), "mod"),
            Map.entry(RankPolicy.normalize("Client Mod"), "mod"),
            Map.entry(RankPolicy.normalize("Senior Client Mod"), "mod"),
            Map.entry(RankPolicy.normalize("Tester Manager"), "helper"),
            Map.entry(RankPolicy.normalize("Senior Tech Support"), "helper"),
            Map.entry(RankPolicy.normalize("Tech Support"), "helper"),
            Map.entry(RankPolicy.normalize("Senior Tester"), "helper"),
            Map.entry(RankPolicy.normalize("Tester"), "helper"));

    static String sortTeamFor(String rankName) {
        if (rankName == null) return null;
        return SORT_TEAMS.get(RankPolicy.normalize(rankName));
    }

    private static final int MAX_RETRIES = 12;

    interface RetryScheduler {
        void schedule(UUID playerId, int attempt, Runnable action);
    }

    private final Logger logger;
    private final Supplier<String> tabFormat;

    private final Supplier<String> sortTeam;
    private final Function<UUID, StaffRank> ranks;
    private final boolean nametagPrefix;
    private final TabAPI api;
    private final EventBus bus;
    private final RetryScheduler retryScheduler;

    private final Map<UUID, Integer> retryAttempts = new ConcurrentHashMap<>();
    private final AtomicBoolean warnedAboutFailures = new AtomicBoolean();
    private EventHandler<PlayerLoadEvent> playerLoadHandler;
    private EventHandler<TabLoadEvent> tabLoadHandler;
    private volatile boolean closed;

    private TabHook(Logger logger, Supplier<String> tabFormat, Function<UUID, StaffRank> ranks,
                    boolean nametagPrefix, Supplier<String> sortTeam,
                    RetryScheduler retryScheduler, TabAPI api, EventBus bus) {
        this.logger = logger;
        this.tabFormat = tabFormat;
        this.ranks = ranks;
        this.nametagPrefix = nametagPrefix;
        this.sortTeam = sortTeam;
        this.retryScheduler = retryScheduler;
        this.api = api;
        this.bus = bus;
    }

    static TabHook register(Logger logger, Supplier<String> tabFormat, Function<UUID, StaffRank> ranks,
                            boolean nametagPrefix) {
        return register(logger, tabFormat, ranks, nametagPrefix, () -> "", (playerId, attempt, task) -> { });
    }

    static TabHook register(Logger logger, Supplier<String> tabFormat, Function<UUID, StaffRank> ranks,
                            boolean nametagPrefix, Supplier<String> sortTeam,
                            RetryScheduler retryScheduler) {
        TabAPI api;
        try {
            api = TabAPI.getInstance();
        } catch (IllegalStateException e) {
            logger.warning("TAB is installed but its API is not ready yet; keeping the vanilla player list name.");
            return null;
        }
        if (api == null) {
            logger.warning("TAB is installed but returned no API instance; keeping the vanilla player list name.");
            return null;
        }
        try {
            EventBus bus = api.getEventBus();
            if (bus == null) {
                logger.warning("TAB's event bus is unavailable; keeping the vanilla player list name.");
                return null;
            }
            String summary = describe(api, nametagPrefix);
            TabHook hook = new TabHook(logger, tabFormat, ranks, nametagPrefix, sortTeam,
                    retryScheduler, api, bus);

            hook.playerLoadHandler = event -> hook.apply(event.getPlayer());
            hook.tabLoadHandler = event -> hook.applyAllOnline();
            bus.register(PlayerLoadEvent.class, hook.playerLoadHandler);
            bus.register(TabLoadEvent.class, hook.tabLoadHandler);
            logger.info("Hooked into TAB (" + summary + "); staff ranks are shown through TAB's API.");
            return hook;
        } catch (RuntimeException e) {
            logger.warning("Could not register with TAB (" + e + "); keeping the vanilla player list name.");
            return null;
        }
    }

    private static String describe(TabAPI api, boolean nametagPrefix) {
        String tablist = api.getTabListFormatManager() == null
                ? "tab list formatting is disabled in TAB" : "tab list formatting hooked";
        String nametags = !nametagPrefix ? "nametag prefixes off in config"
                : api.getNameTagManager() == null
                        ? "nametags are disabled in TAB" : "nametag prefixes hooked";
        return tablist + ", " + nametags;
    }

    void apply(UUID id) {
        if (closed) return;
        TabPlayer player;
        try {
            player = api.getPlayer(id);
        } catch (RuntimeException e) {
            scheduleRetry(id);
            return;
        }
        if (player == null) {
            scheduleRetry(id);
            return;
        }
        apply(player);
    }

    void applyAllOnline() {
        if (closed) return;
        TabPlayer[] online;
        try {
            online = api.getOnlinePlayers();
        } catch (RuntimeException e) {
            logger.fine("TAB was gone while applying ranks: " + e);
            return;
        }
        if (online == null) return;
        for (TabPlayer player : online) {
            apply(player);
        }
    }

    private void apply(TabPlayer player) {
        if (closed) return;
        StaffRank rank = ranks.apply(player.getUniqueId());
        if (rank == null) return;
        if (!player.isLoaded()) {
            scheduleRetry(player.getUniqueId());
            return;
        }
        try {
            applyNow(player, rank);
            retryAttempts.remove(player.getUniqueId());
        } catch (IllegalStateException e) {
            logger.fine("TAB was not ready for " + player.getName() + " yet; retrying the rank update.");
            scheduleRetry(player.getUniqueId());
        } catch (RuntimeException e) {
            if (warnedAboutFailures.compareAndSet(false, true)) {
                logger.warning("TAB refused a rank update (" + e
                        + "); staff ranks stay correct in chat and in the vanilla list name.");
            }
        }
    }

    private void applyNow(TabPlayer player, StaffRank rank) {
        String format = tabFormat.get();
        NameFormats.TabSlots slots = NameFormats.tabSlots(format, rank);
        TabListFormatManager formats = api.getTabListFormatManager();
        if (formats != null) {
            formats.setPrefix(player, orNull(slots.prefix()));
            formats.setName(player, player.getName());
            formats.setSuffix(player, orNull(slots.suffix()));
        } else {
            logger.fine("TAB's tablist-name-formatting feature is disabled; the rank is only in the vanilla list name.");
        }
        NameTagManager nametags = nametagPrefix ? api.getNameTagManager() : null;
        if (nametags != null) {
            nametags.setPrefix(player, nametagText(format, slots, rank));
        }
        forceSortTeam(player, rank);
    }

    private void forceSortTeam(TabPlayer player, StaffRank rank) {
        String team = configuredSortTeam();
        if (team == null) team = sortTeamFor(rank.name());
        if (team == null) return;
        SortingManager sorting = api.getSortingManager();
        if (sorting == null) return;
        try {
            sorting.forceTeamName(player, team);
        } catch (RuntimeException e) {
            logger.fine("TAB refused the sorting team '" + team + "': " + e);
        }
    }

    private String configuredSortTeam() {
        String team = sortTeam == null ? null : sortTeam.get();
        return team == null || team.isBlank() ? null : team.trim();
    }

    private void scheduleRetry(UUID id) {
        if (closed) return;
        int attempt = retryAttempts.merge(id, 1, Integer::sum);
        if (attempt > MAX_RETRIES) {
            retryAttempts.remove(id);
            logger.fine("Gave up re-applying the TAB rank for " + id + " after " + MAX_RETRIES + " attempts.");
            return;
        }
        retryScheduler.schedule(id, attempt, () -> {
            if (closed) return;
            TabPlayer player;
            try {
                player = api.getPlayer(id);
            } catch (RuntimeException e) {
                scheduleRetry(id);
                return;
            }
            if (player != null) apply(player);
            else scheduleRetry(id);
        });
    }

    private static String nametagText(String format, NameFormats.TabSlots slots, StaffRank rank) {
        String prefix = slots.prefix();
        if (!prefix.isBlank()) return prefix;
        if (!NameFormats.containsRank(format)) return null;
        return NameFormats.tabRank(rank) + " ";
    }

    private static String orNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    void clearAll() {
        TabPlayer[] online;
        try {
            online = api.getOnlinePlayers();
        } catch (RuntimeException e) {
            return;
        }
        if (online == null) return;
        for (TabPlayer player : online) {
            if (ranks.apply(player.getUniqueId()) == null) continue;
            clear(player);
        }
    }

    void clear(UUID id) {
        if (closed) return;
        TabPlayer player;
        try {
            player = api.getPlayer(id);
        } catch (RuntimeException e) {
            return;
        }
        if (player == null) return;
        clear(player);
    }

    private void clear(TabPlayer player) {
        TabListFormatManager formats = api.getTabListFormatManager();
        NameTagManager nametags = api.getNameTagManager();
        SortingManager sorting = api.getSortingManager();
        try {
            if (formats != null) {
                formats.setPrefix(player, null);
                formats.setName(player, null);
                formats.setSuffix(player, null);
            }
            if (nametags != null) nametags.setPrefix(player, null);
            if (sorting != null && sorting.getForcedTeamName(player) != null) {
                sorting.forceTeamName(player, null);
            }
        } catch (RuntimeException e) {
            logger.fine("Could not clear the TAB formatting for " + player.getName() + ": " + e);
        }
    }

    void close() {
        closed = true;
        retryAttempts.clear();
        try {
            clearAll();
        } catch (RuntimeException e) {
            logger.fine("TAB was already gone while clearing rank formats: " + e);
        }
        try {
            if (playerLoadHandler != null) bus.unregister(playerLoadHandler);
        } catch (RuntimeException e) {
            logger.fine("TAB was already gone while unregistering: " + e);
        }
        try {
            if (tabLoadHandler != null) bus.unregister(tabLoadHandler);
        } catch (RuntimeException e) {
            logger.fine("TAB was already gone while unregistering: " + e);
        }
        playerLoadHandler = null;
        tabLoadHandler = null;
    }
}
