package net.zouhmi.silentstaffmembers;

import me.neznamy.tab.api.TabAPI;
import me.neznamy.tab.api.TabPlayer;
import me.neznamy.tab.api.event.EventBus;
import me.neznamy.tab.api.event.EventHandler;
import me.neznamy.tab.api.event.plugin.TabLoadEvent;
import me.neznamy.tab.api.event.player.PlayerLoadEvent;
import me.neznamy.tab.api.nametag.NameTagManager;
import me.neznamy.tab.api.tablist.TabListFormatManager;

import java.util.UUID;
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
    private final Logger logger;
    private final Supplier<String> tabFormat;
    private final Function<UUID, StaffRank> ranks;
    /** The pack glyph for a rank, or an empty string when this server has no icon for it. */
    private final Function<StaffRank, String> icons;
    private final boolean nametagPrefix;
    private final TabAPI api;
    private final EventBus bus;
    private EventHandler<PlayerLoadEvent> playerLoadHandler;
    private EventHandler<TabLoadEvent> tabLoadHandler;
    private volatile boolean closed;

    private TabHook(Logger logger, Supplier<String> tabFormat, Function<UUID, StaffRank> ranks,
                    Function<StaffRank, String> icons, boolean nametagPrefix, TabAPI api, EventBus bus) {
        this.logger = logger;
        this.tabFormat = tabFormat;
        this.ranks = ranks;
        this.icons = icons;
        this.nametagPrefix = nametagPrefix;
        this.api = api;
        this.bus = bus;
    }

    /**
     * Connects to TAB when it is installed and its event bus is available.
     *
     * @return the hook, or {@code null} when TAB is absent or its API is unusable
     */
    static TabHook register(Logger logger, Supplier<String> tabFormat, Function<UUID, StaffRank> ranks,
                            Function<StaffRank, String> icons, boolean nametagPrefix) {
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
            TabHook hook = new TabHook(logger, tabFormat, ranks, icons, nametagPrefix, api, bus);
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
        if (player == null) return;
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
        NameFormats.TabSlots slots = NameFormats.tabSlots(tabFormat.get(), rank, icons.apply(rank));
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
            nametags.setPrefix(player, nametagText(slots, rank));
        }
    }

    /** TAB's nametag API only has a prefix slot, so the rank goes there. */
    private static String nametagText(NameFormats.TabSlots slots, StaffRank rank) {
        String prefix = slots.prefix();
        return prefix.isBlank() ? NameFormats.tabRank(rank) + " " : prefix;
    }

    /** TAB takes {@code null} to mean "reset to TAB's own value". */
    private static String orNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** Undoes every override this hook made, leaving TAB's own formatting in place. */
    void clearAll() {
        TabListFormatManager formats = api.getTabListFormatManager();
        NameTagManager nametags = api.getNameTagManager();
        for (TabPlayer player : api.getOnlinePlayers()) {
            if (ranks.apply(player.getUniqueId()) == null) continue;
            if (formats != null) {
                formats.setPrefix(player, null);
                formats.setName(player, null);
                formats.setSuffix(player, null);
            }
            if (nametags != null) nametags.setPrefix(player, null);
        }
    }

    /** Detaches from TAB: the overrides are reverted and no further events are handled. */
    void close() {
        closed = true;
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
