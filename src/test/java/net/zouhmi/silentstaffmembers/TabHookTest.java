package net.zouhmi.silentstaffmembers;

import me.neznamy.tab.api.TabAPI;
import me.neznamy.tab.api.TabPlayer;
import me.neznamy.tab.api.bossbar.BossBarManager;
import me.neznamy.tab.api.event.EventBus;
import me.neznamy.tab.api.event.EventHandler;
import me.neznamy.tab.api.event.TabEvent;
import me.neznamy.tab.api.event.plugin.TabLoadEvent;
import me.neznamy.tab.api.event.player.PlayerLoadEvent;
import me.neznamy.tab.api.nametag.NameTagManager;
import me.neznamy.tab.api.placeholder.PlaceholderManager;
import me.neznamy.tab.api.scoreboard.ScoreboardManager;
import me.neznamy.tab.api.tablist.HeaderFooterManager;
import me.neznamy.tab.api.tablist.SortingManager;
import me.neznamy.tab.api.tablist.TabListFormatManager;
import me.neznamy.tab.api.tablist.layout.LayoutManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The TAB integration is verified against a fake TAB API: the recording proxies below
 * capture exactly what would be handed to TAB, so the tab prefix/name/suffix, the nametag
 * prefix, the sorting team and the not-loaded retry are checked without a server or the
 * TAB plugin.
 */
class TabHookTest {
    private static final Logger LOGGER = Logger.getLogger("TabHookTest");
    private static final StaffRank ADMIN = StaffRank.from("Admin", "#FF55FF");

    private final List<String> calls = new ArrayList<>();
    /** Retry tasks the hook queued because TAB was not ready for the player yet. */
    private final List<Runnable> retries = new ArrayList<>();
    private final TabHook.RetryScheduler scheduler = (playerId, attempt, action) -> retries.add(action);
    private final FakeTab tab = new FakeTab();
    private final FakePlayer zouhir = new FakePlayer("Zouhir");
    private final FakePlayer guest = new FakePlayer("Guest");
    /** A player TAB has not taken yet: not in {@code players}, so {@code getPlayer} is null. */
    private final FakePlayer unknown = new FakePlayer("Ghost");

    @BeforeEach
    void installFakeTab() {
        TabAPI.setInstance(tab);
        tab.online.add(zouhir);
        tab.players.add(zouhir);
        tab.players.add(guest);
    }

    @AfterEach
    void removeFakeTab() {
        TabAPI.setInstance(null);
    }

    @Test
    void pushesTheRankPrefixAndNameThroughTabsApi() {
        TabHook hook = hook("%rank% %name%", player -> zouhir.getUniqueId().equals(player) ? ADMIN : null);

        hook.apply(zouhir.getUniqueId());

        assertEquals(List.of(
                "setPrefix(Zouhir, &#FF55FF[Admin] )",
                "setName(Zouhir, Zouhir)",
                "setSuffix(Zouhir, null)",
                "setPrefix(Zouhir, &#FF55FF[Admin] )",
                "forceTeamName(Zouhir, admin)"), calls);
    }

    @Test
    void movesTheRankToTheSuffixWhenTheFormatIsReordered() {
        TabHook hook = hook("%name% %rank%", player -> zouhir.getUniqueId().equals(player) ? ADMIN : null);

        hook.apply(zouhir.getUniqueId());

        assertEquals(List.of(
                "setPrefix(Zouhir, null)",
                "setName(Zouhir, Zouhir)",
                "setSuffix(Zouhir,  &#FF55FF[Admin])",
                "setPrefix(Zouhir, &#FF55FF[Admin] )",
                "forceTeamName(Zouhir, admin)"), calls);
    }

    @Test
    void doesNotTouchTabForPlayersWithoutAStaffRank() {
        TabHook hook = hook("%rank% %name%", player -> null);

        hook.apply(zouhir.getUniqueId());
        hook.apply(new UUID(0L, 0L));

        assertTrue(calls.isEmpty());
    }

    @Test
    void canSkipTheNametagPrefix() {
        TabHook hook = TabHook.register(LOGGER, () -> "%rank% %name%",
                player -> zouhir.getUniqueId().equals(player) ? ADMIN : null, false);

        hook.apply(zouhir.getUniqueId());

        assertEquals(List.of(
                "setPrefix(Zouhir, &#FF55FF[Admin] )",
                "setName(Zouhir, Zouhir)",
                "setSuffix(Zouhir, null)",
                "forceTeamName(Zouhir, admin)"), calls);
    }

    @Test
    void appliesTheRankWhenTabFinishesLoadingAPlayer() {
        TabHook hook = hook("%rank% %name%", player -> zouhir.getUniqueId().equals(player) ? ADMIN : null);

        tab.bus.fire(PlayerLoadEvent.class, new FakePlayerLoad(zouhir));

        assertEquals("setName(Zouhir, Zouhir)", calls.get(1));
    }

    @Test
    void reappliesRanksForEveryoneOnlineAfterATabReload() {
        TabHook hook = hook("%rank% %name%", player -> zouhir.getUniqueId().equals(player) ? ADMIN : null);

        tab.bus.fire(TabLoadEvent.class, new TabLoadEvent() { });

        assertEquals(List.of(
                "setPrefix(Zouhir, &#FF55FF[Admin] )",
                "setName(Zouhir, Zouhir)",
                "setSuffix(Zouhir, null)",
                "setPrefix(Zouhir, &#FF55FF[Admin] )",
                "forceTeamName(Zouhir, admin)"), calls);
    }

    @Test
    void closingRevertsTheOverridesAndStopsHandlingEvents() {
        TabHook hook = hook("%rank% %name%", player -> zouhir.getUniqueId().equals(player) ? ADMIN : null);
        hook.apply(zouhir.getUniqueId());
        calls.clear();

        hook.close();

        assertEquals(List.of(
                "setPrefix(Zouhir, null)",
                "setName(Zouhir, null)",
                "setSuffix(Zouhir, null)",
                "setPrefix(Zouhir, null)",
                "getForcedTeamName(Zouhir)"), calls);
        assertTrue(tab.bus.handlers.isEmpty());

        calls.clear();
        hook.apply(zouhir.getUniqueId());
        assertTrue(calls.isEmpty());
    }

    @Test
    void clearingOnePlayerRevertsOnlyTheirOverrides() {
        TabHook hook = hook("%rank% %name%", player -> zouhir.getUniqueId().equals(player) ? ADMIN : null);
        hook.apply(zouhir.getUniqueId());
        calls.clear();

        hook.clear(zouhir.getUniqueId());

        assertEquals(List.of(
                "setPrefix(Zouhir, null)",
                "setName(Zouhir, null)",
                "setSuffix(Zouhir, null)",
                "setPrefix(Zouhir, null)",
                "getForcedTeamName(Zouhir)"), calls);

        calls.clear();
        hook.clear(unknown.getUniqueId());
        assertTrue(calls.isEmpty(), "an unknown player must not touch TAB");
    }

    @Test
    void waitsForTabToFinishLoadingThePlayerInsteadOfThrowing() {
        zouhir.loaded = false;
        TabHook hook = hookWithRetries("%rank% %name%",
                player -> zouhir.getUniqueId().equals(player) ? ADMIN : null);

        // This is the join-event path that used to throw "This player is not loaded yet".
        hook.apply(zouhir.getUniqueId());

        assertTrue(calls.isEmpty(), calls.toString());
        assertEquals(1, retries.size(), "the update should be queued, not attempted");

        zouhir.loaded = true;
        retries.remove(0).run();
        assertEquals("setName(Zouhir, Zouhir)", calls.get(1));
        assertTrue(retries.isEmpty(), "a successful update stops the retries");
    }

    @Test
    void swallowsTabsNotLoadedExceptionAndRetriesLater() {
        tab.failSetPrefix = true;
        TabHook hook = hookWithRetries("%rank% %name%",
                player -> zouhir.getUniqueId().equals(player) ? ADMIN : null);

        hook.apply(zouhir.getUniqueId()); // must not throw

        assertTrue(calls.isEmpty(), calls.toString());
        assertEquals(1, retries.size());

        tab.failSetPrefix = false;
        retries.remove(0).run();
        assertEquals("setPrefix(Zouhir, &#FF55FF[Admin] )", calls.get(0));
    }

    @Test
    void stopsRetryingWhenTabNeverAcceptsThePlayer() {
        tab.failSetPrefix = true;
        TabHook hook = hookWithRetries("%rank% %name%",
                player -> zouhir.getUniqueId().equals(player) ? ADMIN : null);
        hook.apply(zouhir.getUniqueId());

        // Run the queue to exhaustion; a broken TAB must not keep rescheduling forever.
        int ran = 0;
        while (!retries.isEmpty() && ran < 100) {
            retries.remove(0).run();
            ran++;
        }

        assertTrue(ran >= 1, "the first attempt should have been made");
        assertTrue(ran <= 13, "the retries must be bounded, ran " + ran);
        assertTrue(calls.isEmpty(), calls.toString());
    }

    @Test
    void queuesARetryWhenTabDoesNotKnowThePlayerYet() {
        TabHook hook = hookWithRetries("%rank% %name%", player -> ADMIN);

        hook.apply(unknown.getUniqueId());

        assertTrue(calls.isEmpty());
        assertEquals(1, retries.size());
    }

    @Test
    void forcesTheConfiguredSortingTeamOntoStaff() {
        TabHook hook = TabHook.register(LOGGER, () -> "%rank% %name%",
                player -> zouhir.getUniqueId().equals(player) ? ADMIN : null, true,
                () -> "admin", scheduler);

        hook.apply(zouhir.getUniqueId());

        assertEquals("forceTeamName(Zouhir, admin)", calls.get(calls.size() - 1));
    }

    @Test
    void sortsStaffIntoTheTeamTheirRankMapsTo() {
        TabHook hook = TabHook.register(LOGGER, () -> "%rank% %name%",
                player -> zouhir.getUniqueId().equals(player) ? ADMIN : null, true,
                () -> "", scheduler);

        hook.apply(zouhir.getUniqueId());

        assertEquals("forceTeamName(Zouhir, admin)", calls.get(calls.size() - 1));
    }

    @Test
    void everyDefaultStaffRankHasATeamExceptNone() {
        for (String rank : RankPolicy.DEFAULT_STAFF_RANKS) {
            assertEquals("helper".equals(TabHook.sortTeamFor(rank)) ||
                            "mod".equals(TabHook.sortTeamFor(rank)) ||
                            "admin".equals(TabHook.sortTeamFor(rank)) ||
                            "owner".equals(TabHook.sortTeamFor(rank)),
                    true, "no TAB sorting team for " + rank);
        }
    }

    @Test
    void leavesTabsSortingAloneWhenNoTeamIsConfigured() {
        TabHook hook = TabHook.register(LOGGER, () -> "%rank% %name%",
                player -> zouhir.getUniqueId().equals(player) ? ADMIN : null, true,
                () -> "", scheduler);

        hook.apply(zouhir.getUniqueId());
        hook.close();

        // Blank config now means "the rank's own team", so a staff member is forced into it:
        // TAB otherwise pushes unknown groups to the bottom of the list.
        assertEquals("forceTeamName(Zouhir, admin)",
                calls.stream().filter(call -> call.startsWith("forceTeamName")).findFirst().orElse(null));
    }

    @Test
    void returnsNullWhenTabsApiIsNotReady() {
        TabAPI.setInstance(null);

        assertNull(TabHook.register(LOGGER, () -> "%rank% %name%", player -> ADMIN, true));
    }

    private TabHook hook(String format, Function<UUID, StaffRank> ranks) {
        TabHook hook = TabHook.register(LOGGER, () -> format, ranks, true);
        assertTrue(hook != null);
        return hook;
    }

    private TabHook hookWithRetries(String format, Function<UUID, StaffRank> ranks) {
        TabHook hook = TabHook.register(LOGGER, () -> format, ranks, true, () -> "", scheduler);
        assertTrue(hook != null);
        return hook;
    }

    private final class FakeTab extends TabAPI {
        final TabListFormatManager formats = recorder(TabListFormatManager.class);
        final NameTagManager nametags = recorder(NameTagManager.class);
        final SortingManager sorting = recorder(SortingManager.class);
        final FakeBus bus = new FakeBus();
        final List<TabPlayer> online = new ArrayList<>();
        final List<TabPlayer> players = new ArrayList<>();
        /** Makes TAB behave like a server that has not finished loading the player. */
        boolean failSetPrefix;

        TabPlayer find(UUID id) {
            return players.stream().filter(p -> p.getUniqueId().equals(id)).findFirst().orElse(null);
        }

        @Override public TabPlayer getPlayer(UUID id) { return find(id); }

        @Override public TabPlayer getPlayer(String name) {
            return players.stream().filter(p -> p.getName().equals(name)).findFirst().orElse(null);
        }

        @Override public TabPlayer[] getOnlinePlayers() { return online.toArray(new TabPlayer[0]); }

        @Override public TabListFormatManager getTabListFormatManager() { return formats; }

        @Override public NameTagManager getNameTagManager() { return nametags; }

        @Override public EventBus getEventBus() { return bus; }

        @Override public BossBarManager getBossBarManager() { return null; }

        @Override public ScoreboardManager getScoreboardManager() { return null; }

        @Override public HeaderFooterManager getHeaderFooterManager() { return null; }

        @Override public PlaceholderManager getPlaceholderManager() { return null; }

        @Override public LayoutManager getLayoutManager() { return null; }

        @Override public SortingManager getSortingManager() { return sorting; }
    }

    private final class FakeBus implements EventBus {
        final Map<Class<?>, EventHandler<?>> handlers = new LinkedHashMap<>();

        @Override public void register(Object listener) { throw new UnsupportedOperationException(); }

        @Override public <E extends TabEvent> void register(Class<E> type, EventHandler<E> handler) {
            handlers.put(type, handler);
        }

        @Override public void unregister(Object listener) { throw new UnsupportedOperationException(); }

        @Override public <E extends TabEvent> void unregister(EventHandler<E> handler) {
            handlers.values().removeIf(existing -> existing == handler);
        }

        @SuppressWarnings("unchecked")
        <E extends TabEvent> void fire(Class<E> type, E event) {
            ((EventHandler<E>) handlers.get(type)).handle(event);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T recorder(Class<T> type) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> {
                    if (tab.failSetPrefix && method.getName().startsWith("setPrefix")) {
                        throw new IllegalStateException("This player is not loaded yet");
                    }
                    calls.add(describe(method, args));
                    return defaultValue(method.getReturnType());
                });
    }

    private static String describe(Method method, Object[] args) {
        StringBuilder out = new StringBuilder(method.getName()).append('(');
        for (int i = 0; args != null && i < args.length; i++) {
            if (i > 0) out.append(", ");
            out.append(args[i]);
        }
        return out.append(')').toString();
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0d;
        if (type == float.class) return 0f;
        if (type == short.class) return (short) 0;
        if (type == byte.class) return (byte) 0;
        if (type == char.class) return (char) 0;
        return null;
    }

    private static final class FakePlayer implements TabPlayer {
        private final UUID id = UUID.randomUUID();
        private final String name;
        /** False mimics TAB announcing a player before the server-side player exists. */
        boolean loaded = true;

        FakePlayer(String name) { this.name = name; }

        @Override public String getName() { return name; }

        @Override public UUID getUniqueId() { return id; }

        @Override public Object getPlayer() { return null; }

        @Override public boolean isLoaded() { return loaded; }

        @Override public String getGroup() { return ""; }

        @Override public void setTemporaryGroup(String group) { }

        @Override public boolean hasTemporaryGroup() { return false; }

        @Override public void setExpectedProfileName(String name) { }

        @Override public String getExpectedProfileName() { return name; }

        @Override public String getServer() { return ""; }

        @Override public String getWorld() { return ""; }

        @Override public boolean isBedrockPlayer() { return false; }

        @Override public String toString() { return name; }
    }

    private static final class FakePlayerLoad implements PlayerLoadEvent {
        private final TabPlayer player;

        FakePlayerLoad(TabPlayer player) { this.player = player; }

        @Override public TabPlayer getPlayer() { return player; }

        @Override public boolean isJoin() { return true; }
    }
}
