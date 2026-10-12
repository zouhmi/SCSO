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

/**
 * Optional integration with NEZNAMY's TAB plugin.
 *
 * <p>When TAB is installed it owns the tab list and blocks other plugins from setting
 * the vanilla player list name, so this class pushes the rank through TAB's API
 * instead - both the tab-list prefix/suffix and, optionally, the nametag prefix.
 *
 * <p>TAB's API classes are compiled against but never bundled, and every reference to
 * them lives in this class. The plugin only constructs it once it knows TAB is
 * installed, so on a server without TAB these classes are never resolved.
 */
final class TabHook {
    /**
     * Staff ranks mapped onto the sorting teams TAB's own sorting lists ask for. TAB
     * sorts players by team name and warns "group (none) is not in sorting list!" for
     * anyone whose permission group is unknown, pushing every staff member to the
     * bottom of the list. A pin grants a known team instead, so staff sort by rank.
     *
     * <p>The names are matched the way the rest of the plugin ranks them; a rank that
     * has no team here sorts like the player TAB already handles.
     */
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

    /** The team a rank sorts into, or {@code null} when TAB's own sorting is left alone. */
    static String sortTeamFor(String rankName) {
        if (rankName == null) return null;
        return SORT_TEAMS.get(RankPolicy.normalize(rankName));
    }

    /** How often a player is re-applied before giving up; each attempt is a few ticks apart. */
    private static final int MAX_RETRIES = 12;

    /** Defers an update until the player is usable, without blocking whoever asked for it. */
    interface RetryScheduler {
        void schedule(UUID playerId, int attempt, Runnable action);
    }

    private final Logger logger;
    private final Supplier<String> tabFormat;
    /** The TAB sorting team to force onto staff, or blank to leave TAB's own sorting alone. */
    private final Supplier<String> sortTeam;
    private final Function<UUID, StaffRank> ranks;
    private final boolean nametagPrefix;
    private final TabAPI api;
    private final EventBus bus;
    private final RetryScheduler retryScheduler;
    /** Players TAB was not ready for yet, so only one retry per player is ever pending. */
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

    /**
     * Connects to TAB when it is installed and its event bus is available.
     *
     * @return the hook, or {@code null} when TAB is absent or its API is unusable
     */
    static TabHook register(Logger logger, Supplier<String> tabFormat, Function<UUID, StaffRank> ranks,
                            boolean nametagPrefix) {
        return register(logger, tabFormat, ranks, nametagPrefix, () -> "", (playerId, attempt, task) -> { });
    }

    /**
     * @param sortTeam       the TAB sorting team to force onto staff, blank to leave TAB alone
     * @param retryScheduler how a player is re-applied once TAB is ready for them
     */
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
        EventBus bus = api.getEventBus();
        if (bus == null) {
            logger.warning("TAB's event bus is unavailable; keeping the vanilla player list name.");
            return null;
        }
        try {
            // Read TAB's feature state before registering anything: if this throws, the
            // hook is abandoned without half-registering listeners.
            String summary = describe(api, nametagPrefix);
            TabHook hook = new TabHook(logger, tabFormat, ranks, nametagPrefix, sortTeam,
                    retryScheduler, api, bus);
            // TAB loads players asynchronously, so a join is re-applied once TAB knows
            // the player, and a TAB reload re-applies every online player's rank.
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

    /**
     * Reports which TAB features actually answered, so a server log shows whether the rank
     * can reach the tab list and the nametag above the player's head.
     */
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
        TabPlayer player = api.getPlayer(id);
        if (player == null) {
            // TAB has not taken the player yet. Its own load event re-applies the rank, and
            // the retry covers the case where that event already fired before we were ready.
            scheduleRetry(id);
            return;
        }
        apply(player);
    }

    void applyAllOnline() {
        if (closed) return;
        for (TabPlayer player : api.getOnlinePlayers()) {
            apply(player);
        }
    }

    private void apply(TabPlayer player) {
        if (closed) return;
        StaffRank rank = ranks.apply(player.getUniqueId());
        if (rank == null) return;
        if (!player.isLoaded()) {
            // TAB announces a player before the server-side player exists; writing now
            // throws "This player is not loaded yet", which used to break the join event.
            scheduleRetry(player.getUniqueId());
            return;
        }
        try {
            applyNow(player, rank);
            retryAttempts.remove(player.getUniqueId());
        } catch (IllegalStateException e) {
            // TAB refused because the player is mid-load; a short retry settles it.
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

    /** TAB sorts players by team name, so staff land at the bottom unless a team is forced. */
    private void forceSortTeam(TabPlayer player, StaffRank rank) {
        // The admin's own setting wins; blank means "pick a team from the rank".
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

    /** The configured sorting team, or {@code null} when TAB's own sorting is left alone. */
    private String configuredSortTeam() {
        String team = sortTeam == null ? null : sortTeam.get();
        return team == null || team.isBlank() ? null : team.trim();
    }

    /**
     * Re-applies a player later, once per outstanding attempt. TAB's own load event
     * usually arrives first; this is the safety net for the join path.
     */
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

    /**
     * TAB's nametag API only has a prefix slot, so the rank goes there. When the
     * tab format contains no rank at all, the nametag is left empty to respect it.
     */
    private static String nametagText(String format, NameFormats.TabSlots slots, StaffRank rank) {
        String prefix = slots.prefix();
        if (!prefix.isBlank()) return prefix;
        if (!NameFormats.containsRank(format)) return null;
        return NameFormats.tabRank(rank) + " ";
    }

    /** TAB takes {@code null} to mean "reset to TAB's own value". */
    private static String orNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** Undoes every override this hook made, leaving TAB's own formatting in place. */
    void clearAll() {
        for (TabPlayer player : api.getOnlinePlayers()) {
            if (ranks.apply(player.getUniqueId()) == null) continue;
            clear(player);
        }
    }

    /**
     * Forgets one player's overrides, leaving TAB's own formatting in place. Used when a
     * player loses their rank but stays online, so no stale prefix follows them around.
     */
    void clear(UUID id) {
        if (closed) return;
        TabPlayer player = api.getPlayer(id);
        if (player == null) return;
        clear(player);
    }

    /** One player TAB cannot reach must not stop the others from being cleaned up. */
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

    /** Detaches from TAB: the overrides are reverted and no further events are handled. */
    void close() {
        closed = true;
        retryAttempts.clear();
        try {
            clearAll();
        } catch (RuntimeException e) {
            logger.fine("TAB was already gone while clearing rank formats: " + e);
        }
        if (playerLoadHandler != null) bus.unregister(playerLoadHandler);
        if (tabLoadHandler != null) bus.unregister(tabLoadHandler);
        playerLoadHandler = null;
        tabLoadHandler = null;
    }
}
