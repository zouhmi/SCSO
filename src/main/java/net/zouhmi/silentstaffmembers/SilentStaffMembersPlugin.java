package net.zouhmi.silentstaffmembers;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

public final class SilentStaffMembersPlugin extends JavaPlugin implements Listener, TabCompleter {
    private static final int MAX_USERNAME_LENGTH = 16;
    private static final int MAX_LOOKUP_WAIT_MS = 12000;
    private static final int MAX_WHITELIST_NAMES_SHOWN = 40;
    private static final String RELOAD_PERMISSION = "scso.reload";
    private static final String SYNC_PERMISSION = "scso.sync";
    private static final String WHITELIST_PERMISSION = "scso.whitelist";
    private static final String RANK_PERMISSION = "scso.rank";
    private static final String INFO_PERMISSION = "scso.info";
    private static final int MAX_RANK_PINS_SHOWN = 40;
    private static final String DEFAULT_DISPLAY_NAME_FORMAT = "%rank% %name%";
    private static final String DEFAULT_TAB_FORMAT = "%rank% %name%";

    private static final Pattern RANK_LABEL = Pattern.compile("[A-Za-z0-9][A-Za-z0-9 _.-]{0,31}");

    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

    private static final String COMMAND_NAME = "scso";
    private static final String DEFAULT_CHAT_FORMAT = "%rank% %name% >> %message%";

    private static final Pattern ICON_PLACEHOLDER = Pattern.compile("(?i)%icon%[ \\t]?");

    private static final String LEGACY_DATA_FOLDER = "SilentStaffMembers";

    private final Map<UUID, StaffRank> verifiedRanks = new ConcurrentHashMap<>();

    private final Map<UUID, String> sessionNames = new ConcurrentHashMap<>();

    private final Set<UUID> allowedSessions = ConcurrentHashMap.newKeySet();
    private volatile SilentClientApi api;
    private volatile RankPolicy rankPolicy;
    private volatile boolean failClosed;
    private volatile String kickMessage;
    private volatile String lookupFailedMessage;
    private volatile String chatFormat;
    private volatile String tabFormat;
    private volatile int requestTimeoutMs;
    private volatile boolean tabIntegration;
    private volatile boolean tabNametagPrefix;

    private volatile StaffWhitelist whitelist;

    private volatile TabHook tabHook;

    private volatile RankOverrides rankOverrides;

    private volatile RankDirectory rankDirectory = RankDirectory.empty();

    private final Map<String, StaffRank> serviceRanks = new ConcurrentHashMap<>();

    private volatile boolean overrideGrantsAccess;

    private volatile String displayNameFormat;

    private volatile String tabSortTeam;

    @Override
    public void onEnable() {

        migrateLegacyDataFolder();
        saveDefaultConfig();
        whitelist = new StaffWhitelist(getDataFolder().toPath().resolve("whitelist.yml"), getLogger());
        rankOverrides = new RankOverrides(getDataFolder().toPath().resolve("ranks.yml"), getLogger());
        if (!loadSettings()) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getServer().getPluginManager().registerEvents(this, this);
        connectTab();
        getLogger().info("Enabled with " + rankPolicy.configuredRanks().size()
                + " staff rank names, " + whitelist.size() + " whitelisted non-staff name(s) and "
                + rankOverrides.size() + " rank pin(s)"
                + "; Silent Client rank verification is required on login.");
    }

    private boolean loadSettings() {
        try {
            String baseUrl = getConfig().getString("api-base-url", "https://api.silentclient.net");
            URI uri = URI.create(baseUrl);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException("api-base-url must be a trusted HTTPS base URL");
            }
            int connectTimeoutMs = boundedInt("connect-timeout-ms", 2500, 250, 10000);
            requestTimeoutMs = boundedInt("request-timeout-ms", 5000, 500, 15000);

            requestTimeoutMs = Math.min(requestTimeoutMs, (MAX_LOOKUP_WAIT_MS - 2000) / 2);
            long ttl = Math.max(0, Math.min(3600, getConfig().getLong("cache.ttl-seconds", 300)));
            int maxEntries = boundedInt("cache.max-entries", 10000, 100, 100000);

            List<String> configured = getConfig().getStringList("staff-ranks");
            if (configured.isEmpty()) configured = RankPolicy.DEFAULT_STAFF_RANKS_ORDERED;
            rankPolicy = new RankPolicy(configured);
            api = new SilentClientApi(baseUrl, Duration.ofMillis(connectTimeoutMs),
                    Duration.ofMillis(requestTimeoutMs), rankPolicy.configuredRanks(), ttl, maxEntries);
            failClosed = getConfig().getBoolean("fail-closed", true);
            kickMessage = getConfig().getString("kick-message", "&cStaff only.");
            lookupFailedMessage = getConfig().getString("lookup-failed-message", "&cCould not verify your staff rank. Try again.");
            chatFormat = withoutIconPlaceholder(
                    getConfig().getString("chat-format", DEFAULT_CHAT_FORMAT), "chat-format");
            tabFormat = withoutIconPlaceholder(
                    getConfig().getString("tab-format", DEFAULT_TAB_FORMAT), "tab-format");
            displayNameFormat = withoutIconPlaceholder(getConfig().getString("display-name-format",
                    DEFAULT_DISPLAY_NAME_FORMAT), "display-name-format");
            tabIntegration = getConfig().getBoolean("tab-integration", true);
            tabNametagPrefix = getConfig().getBoolean("tab-nametag-prefix", true);
            rankDirectory = RankDirectory.parse(getConfig().getConfigurationSection("custom-ranks"),
                    getLogger()::warning);
            overrideGrantsAccess = getConfig().getBoolean("rank-override-grants-access", true);
            tabSortTeam = getConfig().getString("tab-sort-team", "");

            whitelist.load();
            rankOverrides.load(RankOverrides.readConfigured(
                    getConfig().getConfigurationSection("rank-overrides")));
            return true;
        } catch (IllegalArgumentException e) {
            getLogger().severe("Invalid configuration: " + e.getMessage());
            return false;
        }
    }

    private String withoutIconPlaceholder(String format, String key) {
        if (format == null || !ICON_PLACEHOLDER.matcher(format).find()) return format;
        String cleaned = ICON_PLACEHOLDER.matcher(format).replaceAll("").trim();
        getLogger().info(key + " used %icon%, which no longer exists, so it is now '" + cleaned + "'.");
        return cleaned;
    }

    private void migrateLegacyDataFolder() {
        Path dataFolder = getDataFolder().toPath();
        Path parent = dataFolder.getParent();
        if (parent == null) return;
        Path legacy = parent.resolve(LEGACY_DATA_FOLDER);
        if (!Files.isDirectory(legacy)) return;
        for (String name : new String[]{"config.yml", "whitelist.yml", "ranks.yml"}) {
            Path source = legacy.resolve(name);
            Path target = dataFolder.resolve(name);
            if (!Files.exists(source) || Files.exists(target)) continue;
            try {
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                    try (var paths = Files.walk(source)) {
                        for (Path path : paths.filter(Files::isRegularFile).toList()) {
                            Files.copy(path, target.resolve(source.relativize(path)));
                        }
                    }
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(source, target);
                }
                getLogger().info("Migrated " + name + " from the old " + LEGACY_DATA_FOLDER + " folder.");
            } catch (IOException e) {
                getLogger().warning("Could not migrate " + name + " from " + legacy + " (" + e + "); defaults are used.");
            }
        }
    }

    private void connectTab() {
        disconnectTab();
        if (!tabIntegration) return;
        if (getServer().getPluginManager().getPlugin("TAB") == null) {
            getLogger().info("TAB is not installed; the rank is shown through the vanilla player list name.");
            return;
        }
        try {
            TabHook hook = TabHook.register(getLogger(), () -> tabFormat,
                    uuid -> rankFor(uuid, sessionNames.get(uuid)), tabNametagPrefix,
                    () -> tabSortTeam, this::scheduleTabRetry);

            if (hook != null) tabHook = hook;
        } catch (Throwable t) {
            getLogger().warning("Could not enable the TAB integration (" + t
                    + "); the vanilla player list name is used instead.");
        }
    }

    private void disconnectTab() {
        TabHook previous = tabHook;
        tabHook = null;
        if (previous != null) previous.close();
    }

    private void applyDisplays(Player player) {
        StaffRank rank = rankFor(player.getUniqueId(), player.getName());
        if (rank == null) return;
        player.displayName(NameFormats.apply(displayNameFormat, rank, player.getName(), Component.empty()));

        player.playerListName(NameFormats.apply(tabFormat, rank, player.getName(), Component.empty()));
        TabHook hook = tabHook;
        if (hook != null) hook.apply(player.getUniqueId());
    }

    private StaffRank rankFor(UUID id, String name) {
        String player = name != null ? name : (id == null ? null : sessionNames.get(id));
        if (player != null) {
            String pinned = rankOverrides.rankFor(player);
            if (pinned != null) return pinnedRank(pinned);
        }
        return id == null ? null : verifiedRanks.get(id);
    }

    private void learnServiceRanks(List<StaffRank> ranks) {
        if (ranks == null) return;
        for (StaffRank rank : ranks) {
            if (rank != null) serviceRanks.put(RankDirectory.normalize(rank.name()), rank);
        }
    }

    private StaffRank pinnedRank(String label) {
        return resolvePinnedRank(label, rankDirectory, serviceRanks);
    }

    static StaffRank resolvePinnedRank(String label, RankDirectory directory, Map<String, StaffRank> service) {
        if (label == null || label.isBlank()) return null;
        if (directory.defines(label)) return directory.byLabel(label);
        StaffRank known = service == null ? null : service.get(RankDirectory.normalize(label));

        if (known != null) return known;
        return directory.byLabel(label);
    }

    static String unquote(String value) {
        if (value == null) return "";
        String trimmed = value.trim();
        if (trimmed.length() >= 2) {
            char first = trimmed.charAt(0);
            char last = trimmed.charAt(trimmed.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return trimmed.substring(1, trimmed.length() - 1).trim();
            }
        }
        return trimmed;
    }

    private void applyToOnlinePlayer(String username) {
        Player player = findOnlinePlayer(username);
        if (player == null) return;
        player.getScheduler().run(this, task -> {
            if (player.isOnline()) applyDisplays(player);
        }, null);
    }

    private static Player findOnlinePlayer(String username) {
        if (username == null) return null;
        Player exact = Bukkit.getPlayerExact(username);
        if (exact != null) return exact;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getName().equalsIgnoreCase(username)) return player;
        }
        return null;
    }

    private void scheduleTabRetry(UUID id, int attempt, Runnable action) {
        Player player = Bukkit.getPlayer(id);
        if (player == null) return;
        long delay = Math.min(40L, 5L * Math.max(1, attempt));
        player.getScheduler().runDelayed(this, task -> action.run(), null, delay);
    }

    private int boundedInt(String path, int defaultValue, int min, int max) {
        int value = getConfig().getInt(path, defaultValue);
        if (value < min || value > max) {
            throw new IllegalArgumentException(path + " must be between " + min + " and " + max);
        }
        return value;
    }

    private boolean pinGrantsAccess(String username) {
        return overrideGrantsAccess && rankOverrides.rankFor(username) != null;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAsyncPlayerPreLogin(AsyncPlayerPreLoginEvent event) {
        String username = event.getName();
        if (!isValidUsername(username)) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    Component.text("Invalid player name."));
            return;
        }

        sessionNames.put(event.getUniqueId(), username);
        String pinned = rankOverrides.rankFor(username);
        if (pinned != null && overrideGrantsAccess) {

            allowedSessions.add(event.getUniqueId());
            getLogger().info("Allowed " + username + " with the rank pin " + pinned
                    + " without a staff rank check.");
            return;
        }
        if (whitelist.contains(username)) {

            allowedSessions.add(event.getUniqueId());
            getLogger().info("Allowed whitelisted player " + username + " without a staff rank check.");
            return;
        }

        SilentClientApi localApi = api;
        if (localApi == null) {
            denyLookupFailure(event);
            return;
        }

        long lookupWaitMs = Math.min(MAX_LOOKUP_WAIT_MS, (long) requestTimeoutMs * 2L + 2000L);
        try {
            SilentClientApi.LookupResult result = localApi.lookup(username)
                    .get(lookupWaitMs, TimeUnit.MILLISECONDS);
            if (!result.isStaff()) {
                sessionNames.remove(event.getUniqueId(), username);
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                        legacyText(kickMessage));
                getLogger().info("Denied non-staff login for " + username + ".");
                return;
            }
            allowedSessions.add(event.getUniqueId());
            learnServiceRanks(result.staffRanks());
            verifiedRanks.put(event.getUniqueId(), rankDirectory.display(result.displayRank()));
            getLogger().info("Verified Silent Client staff login: " + username
                    + " (" + result.displayRank().name() + ").");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            denyLookupFailure(event);
        } catch (ExecutionException | TimeoutException | CompletionException e) {
            Throwable cause = e instanceof ExecutionException || e instanceof CompletionException
                    ? e.getCause() : e;
            getLogger().warning("Rank verification failed for " + username + ": "
                    + (cause == null ? e.getMessage() : cause.getMessage()));
            denyLookupFailure(event);
        } catch (RuntimeException e) {
            getLogger().warning("Rank verification failed for " + username + ": " + e.getMessage());
            denyLookupFailure(event);
        }
    }

    private void denyLookupFailure(AsyncPlayerPreLoginEvent event) {
        if (failClosed) {

            sessionNames.remove(event.getUniqueId());
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, legacyText(lookupFailedMessage));
        } else {

            allowedSessions.add(event.getUniqueId());
            getLogger().warning("Allowing " + event.getName() + " because fail-closed is disabled.");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        sessionNames.putIfAbsent(player.getUniqueId(), player.getName());
        String username = player.getName();
        if (!allowedSessions.contains(player.getUniqueId()) && !pinGrantsAccess(username)) {

            player.kick(legacyText(kickMessage));
            return;
        }
        if (rankFor(player.getUniqueId(), username) != null) {
            applyDisplays(player);
        } else if (whitelist.contains(username)) {

            resolveRank(player, false);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {

        UUID id = event.getPlayer().getUniqueId();
        verifiedRanks.remove(id);
        sessionNames.remove(id);
        allowedSessions.remove(id);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAsyncChat(AsyncChatEvent event) {
        StaffRank rank = rankFor(event.getPlayer().getUniqueId(), event.getPlayer().getName());
        if (rank == null) return;
        event.renderer((source, sourceDisplayName, message, viewer) -> NameFormats.apply(
                chatFormat, rank, source.getName(), message));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase(COMMAND_NAME)) return false;
        if (args.length == 0) {
            sendUsage(sender, label);
            return true;
        }
        if (args[0].equalsIgnoreCase("reload")) return reload(sender);
        if (args[0].equalsIgnoreCase("sync") || args[0].equalsIgnoreCase("resync")) return sync(sender);
        if (args[0].equalsIgnoreCase("whitelist") || args[0].equalsIgnoreCase("wl")) {
            return whitelist(sender, label, args);
        }
        if (args[0].equalsIgnoreCase("rank") || args[0].equalsIgnoreCase("ranks")) {
            return rank(sender, label, args);
        }
        if (args[0].equalsIgnoreCase("info") || args[0].equalsIgnoreCase("whois")) {
            return info(sender, args);
        }
        if (args[0].equalsIgnoreCase("check")) {
            return check(sender, label, args);
        }
        sendUsage(sender, label);
        return true;
    }

    private boolean reload(CommandSender sender) {
        if (!sender.hasPermission(RELOAD_PERMISSION)) {
            sender.sendMessage(Component.text("You do not have permission.", NamedTextColor.RED));
            return true;
        }
        reloadConfig();
        SilentClientApi previous = api;
        if (!loadSettings()) {
            sender.sendMessage(Component.text("Configuration is invalid; previous settings remain active.", NamedTextColor.RED));
            return true;
        }
        if (previous != null) previous.close();
        connectTab();
        for (Player player : Bukkit.getOnlinePlayers()) {

            player.getScheduler().run(this, task -> {
                if (player.isOnline()) applyDisplays(player);
            }, null);
            resolveRank(player, true);
        }
        sender.sendMessage(Component.text("SCSO reloaded; online staff ranks are being re-verified.", NamedTextColor.GREEN));
        return true;
    }

    private boolean sync(CommandSender sender) {
        if (!sender.hasPermission(SYNC_PERMISSION) && !sender.hasPermission(RELOAD_PERMISSION)) {
            sender.sendMessage(Component.text("You do not have permission.", NamedTextColor.RED));
            return true;
        }
        SilentClientApi localApi = api;
        if (localApi == null) {
            sender.sendMessage(Component.text("The Silent Client lookup is not configured.", NamedTextColor.RED));
            return true;
        }
        List<Player> online = List.copyOf(Bukkit.getOnlinePlayers());
        if (online.isEmpty()) {
            sender.sendMessage(Component.text("No online players to sync.", NamedTextColor.YELLOW));
            return true;
        }
        sender.sendMessage(Component.text("Syncing " + online.size() + " online player(s) with Silent Client...",
                NamedTextColor.GRAY));
        SyncSummary summary = new SyncSummary(sender, online.size());
        for (Player player : online) syncPlayer(player, localApi, summary);
        return true;
    }

    private void syncPlayer(Player player, SilentClientApi localApi, SyncSummary summary) {
        String username = player.getName();
        UUID id = player.getUniqueId();
        localApi.lookupFresh(username).whenComplete((result, error) -> {
            if (error != null || result == null) {

                Throwable cause = error instanceof ExecutionException || error instanceof CompletionException
                        ? error.getCause() : error;
                getLogger().warning("Sync lookup failed for " + username + ": "
                        + (cause == null ? "unknown error" : cause.getMessage()));
                player.getScheduler().run(this, task -> {
                    if (player.isOnline() && rankFor(id, username) != null) applyDisplays(player);
                }, null);
                summary.failed();
                return;
            }
            learnServiceRanks(result.staffRanks());
            if (!result.isStaff()) {
                verifiedRanks.remove(id);
                if (!whitelist.contains(username) && !pinGrantsAccess(username)) {
                    getLogger().info("Sync kicking " + username + ": no longer staff.");
                    player.getScheduler().run(this, task -> player.kick(legacyText(kickMessage)), null);
                    summary.kicked();
                    return;
                }
                refreshDisplayAfterDemotion(player);
                summary.demoted();
                return;
            }
            if (!player.isOnline()) {
                summary.done();
                return;
            }
            verifiedRanks.put(id, rankDirectory.display(result.displayRank()));
            player.getScheduler().run(this, task -> {
                if (player.isOnline()) applyDisplays(player);
            }, null);
            summary.updated();
        });
    }

    private final class SyncSummary {
        private final CommandSender sender;
        private final AtomicInteger pending;
        private final AtomicInteger updated = new AtomicInteger();
        private final AtomicInteger demoted = new AtomicInteger();
        private final AtomicInteger kicked = new AtomicInteger();
        private final AtomicInteger failed = new AtomicInteger();

        SyncSummary(CommandSender sender, int players) {
            this.sender = sender;
            this.pending = new AtomicInteger(players);
        }

        void updated() { updated.incrementAndGet(); done(); }
        void demoted() { demoted.incrementAndGet(); done(); }
        void kicked() { kicked.incrementAndGet(); done(); }
        void failed() { failed.incrementAndGet(); done(); }
        void done() {
            if (pending.decrementAndGet() > 0) return;
            int failures = failed.get();
            tell(sender, () -> sender.sendMessage(Component.text(
                    "SCSO sync finished: " + updated.get() + " rank(s) refreshed, "
                            + demoted.get() + " demoted but allowed, " + kicked.get() + " kicked, "
                            + failures + " lookup(s) failed.",
                    failures > 0 ? NamedTextColor.YELLOW : NamedTextColor.GREEN)));
        }
    }

    private void resolveRank(Player player, boolean kickIfNotStaff) {
        SilentClientApi localApi = api;
        if (localApi == null) return;
        String username = player.getName();
        localApi.lookup(username).whenComplete((result, error) -> {
            boolean definitiveNotStaff = error == null && result != null && !result.isStaff();
            if (error != null || result == null || !result.isStaff()) {

                if (definitiveNotStaff) verifiedRanks.remove(player.getUniqueId());
                if (definitiveNotStaff && kickIfNotStaff && !whitelist.contains(username)
                        && !pinGrantsAccess(username)) {
                    player.getScheduler().run(this, task -> player.kick(legacyText(kickMessage)), null);
                    return;
                }

                if (definitiveNotStaff) refreshDisplayAfterDemotion(player);
                return;
            }
            if (!player.isOnline()) return;
            learnServiceRanks(result.staffRanks());
            verifiedRanks.put(player.getUniqueId(), rankDirectory.display(result.displayRank()));
            player.getScheduler().run(this, task -> {
                if (player.isOnline()) applyDisplays(player);
            }, null);
        });
    }

    private void refreshDisplayAfterDemotion(Player player) {
        player.getScheduler().run(this, task -> {
            if (!player.isOnline()) return;
            if (rankFor(player.getUniqueId(), player.getName()) != null) applyDisplays(player);
            else resetDisplays(player);
        }, null);
    }

    private void resetDisplays(Player player) {
        player.displayName(Component.text(player.getName(), NamedTextColor.WHITE));
        player.playerListName(Component.text(player.getName()));
        TabHook hook = tabHook;
        if (hook != null) hook.clear(player.getUniqueId());
    }

    private static Component line(String label, String value) {
        return Component.text(label + ": ", NamedTextColor.GOLD).append(Component.text(value, NamedTextColor.WHITE));
    }

    private boolean whitelist(CommandSender sender, String label, String[] args) {
        if (!sender.hasPermission(WHITELIST_PERMISSION)) {
            sender.sendMessage(Component.text("You do not have permission.", NamedTextColor.RED));
            return true;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("add")) return whitelistAdd(sender, label, args);
        if (args.length >= 2 && args[1].equalsIgnoreCase("remove")) return whitelistRemove(sender, label, args);
        if (args.length >= 2 && args[1].equalsIgnoreCase("list")) return whitelistList(sender);
        sendUsage(sender, label);
        return true;
    }

    private boolean whitelistAdd(CommandSender sender, String label, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(Component.text("Usage: /" + label + " whitelist add <player>", NamedTextColor.YELLOW));
            return true;
        }
        String username = args[2].trim();
        if (!isValidUsername(username)) {
            sender.sendMessage(Component.text("\"" + username + "\" is not a valid Minecraft name.", NamedTextColor.RED));
            return true;
        }
        if (!whitelist.add(username)) {
            sender.sendMessage(Component.text(username + " is already whitelisted.", NamedTextColor.YELLOW));
            return true;
        }
        whitelist.save();
        getLogger().info(sender.getName() + " whitelisted " + username
                + " (" + whitelist.size() + " entries).");
        sender.sendMessage(Component.text("Whitelisted " + username + " - they may now join without a staff rank ("
                + whitelist.size() + " entries).", NamedTextColor.GREEN));
        return true;
    }

    private boolean whitelistRemove(CommandSender sender, String label, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(Component.text("Usage: /" + label + " whitelist remove <player>", NamedTextColor.YELLOW));
            return true;
        }
        String username = args[2].trim();
        if (!whitelist.remove(username)) {
            sender.sendMessage(Component.text(username + " is not on the whitelist.", NamedTextColor.YELLOW));
            return true;
        }
        whitelist.save();
        getLogger().info(sender.getName() + " removed " + username + " from the whitelist ("
                + whitelist.size() + " entries).");
        sender.sendMessage(Component.text("Removed " + username + " from the whitelist ("
                + whitelist.size() + " entries).", NamedTextColor.GREEN));
        kickIfNoLongerAllowed(username);
        return true;
    }

    private boolean whitelistList(CommandSender sender) {
        List<String> names = whitelist.names();
        if (names.isEmpty()) {
            sender.sendMessage(Component.text("The whitelist is empty.", NamedTextColor.YELLOW));
            return true;
        }
        String shown = String.join(", ", names.subList(0, Math.min(names.size(), MAX_WHITELIST_NAMES_SHOWN)));
        if (names.size() > MAX_WHITELIST_NAMES_SHOWN) {
            shown += ", and " + (names.size() - MAX_WHITELIST_NAMES_SHOWN) + " more";
        }
        sender.sendMessage(Component.text("Whitelist (" + names.size() + "): ", NamedTextColor.GOLD)
                .append(Component.text(shown, NamedTextColor.WHITE)));
        return true;
    }

    private void kickIfNoLongerAllowed(String username) {
        Player player = findOnlinePlayer(username);
        if (player == null) return;

        if (verifiedRanks.containsKey(player.getUniqueId()) || pinGrantsAccess(player.getName())
                || whitelist.contains(player.getName())) return;
        getLogger().info("Kicking " + player.getName() + " because they are no longer allowed in.");
        player.getScheduler().run(this, task -> player.kick(legacyText(kickMessage)), null);
    }

    private boolean rank(CommandSender sender, String label, String[] args) {
        if (!sender.hasPermission(RANK_PERMISSION)) {
            sender.sendMessage(Component.text("You do not have permission.", NamedTextColor.RED));
            return true;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("list")) return rankList(sender);
        if (args.length >= 3 && (args[1].equalsIgnoreCase("remove") || args[1].equalsIgnoreCase("clear")
                || args[1].equalsIgnoreCase("unset"))) {
            return rankRemove(sender, label, args[2]);
        }
        if (args.length >= 4 && (args[1].equalsIgnoreCase("set") || args[1].equalsIgnoreCase("add"))) {

            return rankSet(sender, label, args[2], String.join(" ", Arrays.copyOfRange(args, 3, args.length)));
        }
        sender.sendMessage(Component.text("Usage: /" + label + " rank set <player> <rank>", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("       /" + label + " rank remove <player>", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("       /" + label + " rank list", NamedTextColor.YELLOW));
        if (!rankDirectory.isEmpty()) {
            sender.sendMessage(Component.text("Ranks from custom-ranks: ", NamedTextColor.GOLD)
                    .append(Component.text(String.join(", ", rankDirectory.labels()), NamedTextColor.WHITE)));
        }
        return true;
    }

    private boolean rankSet(CommandSender sender, String label, String username, String rankLabel) {
        String name = username.trim();
        if (!isValidUsername(name)) {
            sender.sendMessage(Component.text("\"" + name + "\" is not a valid Minecraft name.", NamedTextColor.RED));
            return true;
        }
        String wanted = unquote(rankLabel).replaceAll("\\s+", " ").trim();
        if (!RANK_LABEL.matcher(wanted).matches()) {
            sender.sendMessage(Component.text("\"" + wanted + "\" is not a usable rank name (letters, digits, "
                    + "spaces, _ - and . up to 32 characters).", NamedTextColor.RED));
            return true;
        }
        StaffRank pinned = pinnedRank(wanted);
        if (pinned == null) {
            sender.sendMessage(Component.text("\"" + wanted + "\" is not a usable rank name.", NamedTextColor.RED));
            return true;
        }
        if (!rankOverrides.set(name, pinned.name())) {
            sender.sendMessage(Component.text(name + " is already shown as " + pinned.name() + ".",
                    NamedTextColor.YELLOW));
            return true;
        }
        rankOverrides.save();
        getLogger().info(sender.getName() + " pinned the " + pinned.name() + " rank onto " + name
                + " (" + rankOverrides.size() + " rank pin(s)).");
        sender.sendMessage(Component.text("Pinned ", NamedTextColor.GREEN)
                .append(Component.text(pinned.name(), pinned.color()))
                .append(Component.text(" onto " + name + " (" + rankOverrides.size() + " rank pin(s)).",
                        NamedTextColor.GREEN)));
        if (!rankDirectory.defines(pinned.name())
                && !serviceRanks.containsKey(RankDirectory.normalize(pinned.name()))) {
            sender.sendMessage(Component.text("Tip: add " + pinned.name() + " to custom-ranks in config.yml "
                    + "to give it a color and a priority.", NamedTextColor.GRAY));
        }
        applyToOnlinePlayer(name);
        return true;
    }

    private boolean rankRemove(CommandSender sender, String label, String username) {
        String name = username.trim();
        if (!rankOverrides.remove(name)) {
            sender.sendMessage(Component.text(name + " has no rank pin. Use /" + label + " rank list, or check "
                    + "rank-overrides in config.yml.", NamedTextColor.YELLOW));
            return true;
        }
        rankOverrides.save();
        getLogger().info(sender.getName() + " removed the rank pin from " + name
                + " (" + rankOverrides.size() + " left).");
        sender.sendMessage(Component.text("Removed the rank pin from " + name + " ("
                + rankOverrides.size() + " left).", NamedTextColor.GREEN));
        Player player = findOnlinePlayer(name);
        if (player != null) {

            if (verifiedRanks.containsKey(player.getUniqueId())) applyToOnlinePlayer(name);
            else if (whitelist.contains(player.getName())) {
                player.getScheduler().run(this, task -> {
                    if (player.isOnline()) resetDisplays(player);
                }, null);
                resolveRank(player, false);
            } else kickIfNoLongerAllowed(name);
        }
        return true;
    }

    private boolean rankList(CommandSender sender) {
        List<String> names = rankOverrides.names();
        if (names.isEmpty()) {
            sender.sendMessage(Component.text("No rank pins yet. Use /scso rank set <player> <rank>.",
                    NamedTextColor.YELLOW));
            return true;
        }
        sender.sendMessage(Component.text("Rank pins (" + names.size() + "):", NamedTextColor.GOLD));
        for (int i = 0; i < names.size(); i++) {
            if (i >= MAX_RANK_PINS_SHOWN) {
                sender.sendMessage(Component.text("  ... and " + (names.size() - MAX_RANK_PINS_SHOWN)
                        + " more", NamedTextColor.GRAY));
                break;
            }
            String name = names.get(i);
            String stored = rankOverrides.rankFor(name);
            StaffRank pinned = stored == null ? null : pinnedRank(stored);
            if (pinned == null) continue;
            sender.sendMessage(Component.text("  " + name + " -> ", NamedTextColor.WHITE)
                    .append(Component.text(pinned.name(), pinned.color())));
        }
        return true;
    }

    private boolean info(CommandSender sender, String[] args) {
        if (!sender.hasPermission(INFO_PERMISSION)) {
            sender.sendMessage(Component.text("You do not have permission.", NamedTextColor.RED));
            return true;
        }
        String username = args.length >= 2 ? args[1].trim()
                : sender instanceof Player player ? player.getName() : null;
        if (username == null || !isValidUsername(username)) {
            sender.sendMessage(Component.text("Usage: /scso info <player>", NamedTextColor.YELLOW));
            return true;
        }
        Player online = findOnlinePlayer(username);
        UUID id = online == null ? null : online.getUniqueId();
        String pinnedLabel = rankOverrides.rankFor(username);
        boolean verified = id != null && verifiedRanks.containsKey(id);
        boolean whitelisted = whitelist.contains(username);
        boolean pinGrants = pinnedLabel != null && overrideGrantsAccess;
        StaffRank rank = pinnedLabel != null ? pinnedRank(pinnedLabel)
                : id == null ? null : verifiedRanks.get(id);
        sender.sendMessage(line("Player", username + (online == null ? " (offline)" : " (online)")));
        sender.sendMessage(line("Rank", rank == null ? "none" : rank.name()));
        sender.sendMessage(line("Rank source", pinnedLabel != null
                ? "rank pin" + (rankDirectory.defines(pinnedLabel) ? " from custom-ranks" : " (not in custom-ranks)")
                : verified ? "Silent Client rank check"
                : "no rank in this session"));
        sender.sendMessage(line("Whitelisted", whitelisted ? "yes" : "no"));
        sender.sendMessage(line("Access", pinGrants ? "allowed by the rank pin"
                : verified ? "verified Silent Client staff"
                : whitelisted ? "allowed by the join whitelist"
                : pinnedLabel != null ? "rank pin is display-only"
                : "not allowed on its own"));
        return true;
    }

    private boolean check(CommandSender sender, String label, String[] args) {
        if (!sender.hasPermission(INFO_PERMISSION)) {
            sender.sendMessage(Component.text("You do not have permission.", NamedTextColor.RED));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(Component.text("Usage: /" + label + " check <player>", NamedTextColor.YELLOW));
            return true;
        }
        String username = args[1].trim();
        if (!isValidUsername(username)) {
            sender.sendMessage(Component.text("\"" + username + "\" is not a valid Minecraft name.", NamedTextColor.RED));
            return true;
        }
        SilentClientApi localApi = api;
        if (localApi == null) {
            sender.sendMessage(Component.text("The Silent Client lookup is not configured.", NamedTextColor.RED));
            return true;
        }
        sender.sendMessage(Component.text("Asking Silent Client about " + username + "...", NamedTextColor.GRAY));
        localApi.lookupFresh(username).whenComplete((result, error) -> tell(sender, () -> {
            if (error != null || result == null) {
                Throwable cause = error instanceof ExecutionException || error instanceof CompletionException
                        ? error.getCause() : error;
                sender.sendMessage(Component.text("Lookup failed for " + username + ": "
                        + (cause == null ? "unknown error" : cause.getMessage()), NamedTextColor.RED));
                return;
            }
            if (!result.isStaff()) {
                sender.sendMessage(Component.text(username + " holds none of the configured staff ranks.",
                        NamedTextColor.YELLOW));
                return;
            }

            learnServiceRanks(result.staffRanks());
            StringBuilder ranks = new StringBuilder();
            for (StaffRank rank : result.staffRanks()) {
                if (!ranks.isEmpty()) ranks.append(", ");
                ranks.append(rank.name());
            }
            StaffRank shown = rankDirectory.display(result.displayRank());
            sender.sendMessage(Component.text(username + " is staff: ", NamedTextColor.GREEN)
                    .append(Component.text(ranks.toString(), NamedTextColor.WHITE)));
            sender.sendMessage(Component.text("Shown rank: ", NamedTextColor.GOLD)
                    .append(Component.text(shown.name(), shown.color())));
        }));
        return true;
    }

    private void tell(CommandSender sender, Runnable action) {
        Bukkit.getGlobalRegionScheduler().run(this, task -> action.run());
    }

    private void sendUsage(CommandSender sender, String label) {
        sender.sendMessage(Component.text("Usage: /" + label + " reload | sync", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("       /" + label + " whitelist add|remove <player> | list",
                NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("       /" + label + " rank set <player> <rank> | remove <player> | list",
                NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("       /" + label + " info [player] | check <player>",
                NamedTextColor.YELLOW));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase(COMMAND_NAME)) return List.of();
        if (args.length == 1) {
            List<String> options = new java.util.ArrayList<>();
            if (sender.hasPermission(RELOAD_PERMISSION)) options.add("reload");
            if (sender.hasPermission(SYNC_PERMISSION) || sender.hasPermission(RELOAD_PERMISSION)) {
                options.add("sync");
            }
            if (sender.hasPermission(WHITELIST_PERMISSION)) options.add("whitelist");
            if (sender.hasPermission(RANK_PERMISSION)) options.add("rank");
            if (sender.hasPermission(INFO_PERMISSION)) {
                options.add("info");
                options.add("check");
            }
            return StaffWhitelist.filter(options, args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("whitelist")) {
            if (!sender.hasPermission(WHITELIST_PERMISSION)) return List.of();
            return StaffWhitelist.filter(List.of("add", "remove", "list"), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("whitelist")
                && (args[1].equalsIgnoreCase("remove"))) {
            if (!sender.hasPermission(WHITELIST_PERMISSION)) return List.of();
            return StaffWhitelist.filter(whitelist.names(), args[2]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("whitelist")
                && (args[1].equalsIgnoreCase("add"))) {
            if (!sender.hasPermission(WHITELIST_PERMISSION)) return List.of();
            return StaffWhitelist.filter(onlineNames(), args[2]);
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("info") || args[0].equalsIgnoreCase("check"))) {
            if (!sender.hasPermission(INFO_PERMISSION)) return List.of();
            return StaffWhitelist.filter(onlineNames(), args[1]);
        }
        if (args[0].equalsIgnoreCase("rank") || args[0].equalsIgnoreCase("ranks")) {
            if (!sender.hasPermission(RANK_PERMISSION)) return List.of();
            if (args.length == 2) {
                return StaffWhitelist.filter(List.of("set", "remove", "list"), args[1]);
            }
            if (args.length == 3 && !args[1].equalsIgnoreCase("list")) {

                if (args[1].equalsIgnoreCase("set") || args[1].equalsIgnoreCase("add")) {
                    return StaffWhitelist.filter(onlineNames(), args[2]);
                }
                return StaffWhitelist.filter(rankOverrides.names(), args[2]);
            }
            if (args.length >= 4 && (args[1].equalsIgnoreCase("set") || args[1].equalsIgnoreCase("add"))) {

                List<String> labels = new java.util.ArrayList<>(rankDirectory.labels());
                for (String known : RankPolicy.DEFAULT_STAFF_RANKS_ORDERED) {
                    if (!labels.contains(known)) labels.add(known);
                }
                for (String used : rankOverrides.labels()) {
                    if (!labels.contains(used)) labels.add(used);
                }
                String prefix = String.join(" ", Arrays.copyOfRange(args, 3, args.length));
                return StaffWhitelist.filter(labels, prefix);
            }
        }
        return List.of();
    }

    private List<String> onlineNames() {
        List<String> names = new java.util.ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) names.add(player.getName());
        return names;
    }

    private static boolean isValidUsername(String username) {
        return username != null && username.length() <= MAX_USERNAME_LENGTH
                && USERNAME.matcher(username).matches();
    }

    private static Component legacyText(String input) {
        if (input == null || input.isBlank()) return Component.empty();
        return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand()
                .deserialize(input);
    }

    @Override
    public void onDisable() {
        disconnectTab();
        verifiedRanks.clear();
        sessionNames.clear();
        allowedSessions.clear();
        SilentClientApi current = api;
        if (current != null) current.close();
    }
}
