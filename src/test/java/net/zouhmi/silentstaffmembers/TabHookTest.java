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
 * capture exactly what would be handed to TAB, so the tab prefix/name/suffix and the
 * nametag prefix are checked without a server or the TAB plugin.
 */
class TabHookTest {
    private static final Logger LOGGER = Logger.getLogger("TabHookTest");
    private static final StaffRank ADMIN = StaffRank.from("Admin", "#FF55FF");
    /** No icon pack, which is the default on a server that has not hosted one. */
    private static final Function<StaffRank, String> NO_ICONS = rank -> "";

    private final List<String> calls = new ArrayList<>();
    private final FakeTab tab = new FakeTab();
    private final FakePlayer zouhir = new FakePlayer("Zouhir");
    private final FakePlayer guest = new FakePlayer("Guest");

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
                "setPrefix(Zouhir, &#FF55FF[Admin] )"), calls);
    }

    @Test
    void movesTheRankToTheSuffixWhenTheFormatIsReordered() {
        TabHook hook = hook("%name% %rank%", player -> zouhir.getUniqueId().equals(player) ? ADMIN : null);

        hook.apply(zouhir.getUniqueId());

        assertEquals(List.of(
                "setPrefix(Zouhir, null)",
                "setName(Zouhir, Zouhir)",
                "setSuffix(Zouhir,  &#FF55FF[Admin])",
                "setPrefix(Zouhir, &#FF55FF[Admin] )"), calls);
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
                player -> zouhir.getUniqueId().equals(player) ? ADMIN : null, NO_ICONS, false);

        hook.apply(zouhir.getUniqueId());

        assertEquals(List.of(
                "setPrefix(Zouhir, &#FF55FF[Admin] )",
                "setName(Zouhir, Zouhir)",
                "setSuffix(Zouhir, null)"), calls);
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
                "setPrefix(Zouhir, &#FF55FF[Admin] )"), calls);
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
                "setPrefix(Zouhir, null)"), calls);
        assertTrue(tab.bus.handlers.isEmpty());

        calls.clear();
        hook.apply(zouhir.getUniqueId());
        assertTrue(calls.isEmpty());
    }

    @Test
    void returnsNullWhenTabsApiIsNotReady() {
        TabAPI.setInstance(null);

        assertNull(TabHook.register(LOGGER, () -> "%rank% %name%", player -> ADMIN, NO_ICONS, true));
    }

    @Test
    void putsAPackGlyphInTheTabPrefixWhenTheFormatAsksForIt() {
        TabHook hook = TabHook.register(LOGGER, () -> "%icon% %rank% %name%",
                player -> zouhir.getUniqueId().equals(player) ? ADMIN : null,
                rank -> "\uE000", true);

        hook.apply(zouhir.getUniqueId());

        assertEquals("setPrefix(Zouhir, \uE000 &#FF55FF[Admin] )", calls.get(0));
    }

    private TabHook hook(String format, Function<UUID, StaffRank> ranks) {
        return hook(format, ranks, NO_ICONS);
    }

    private TabHook hook(String format, Function<UUID, StaffRank> ranks,
                         Function<StaffRank, String> icons) {
        TabHook hook = TabHook.register(LOGGER, () -> format, ranks, icons, true);
        assertTrue(hook != null);
        return hook;
    }

    private final class FakeTab extends TabAPI {
        final TabListFormatManager formats = recorder(TabListFormatManager.class);
        final NameTagManager nametags = recorder(NameTagManager.class);
        final FakeBus bus = new FakeBus();
        final List<TabPlayer> online = new ArrayList<>();
        final List<TabPlayer> players = new ArrayList<>();

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

        @Override public SortingManager getSortingManager() { return null; }
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

        FakePlayer(String name) { this.name = name; }

        @Override public String getName() { return name; }

        @Override public UUID getUniqueId() { return id; }

        @Override public Object getPlayer() { return null; }

        @Override public boolean isLoaded() { return true; }

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
