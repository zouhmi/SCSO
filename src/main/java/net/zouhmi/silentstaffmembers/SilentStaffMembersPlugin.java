package net.zouhmi.silentstaffmembers;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class SilentStaffMembersPlugin extends JavaPlugin implements Listener, TabCompleter {
    private static final int MAX_USERNAME_LENGTH = 16;
    private static final int MAX_LOOKUP_WAIT_MS = 6500;
    private static final int MAX_WHITELIST_NAMES_SHOWN = 40;
    private static final String RELOAD_PERMISSION = "scso.reload";
    private static final String WHITELIST_PERMISSION = "scso.whitelist";
    /** This plugin's own command name; /staff and /silentstaff are aliases of it. */
    private static final String COMMAND_NAME = "scso";
    private static final String DEFAULT_CHAT_FORMAT = "%icon% %rank% %name% >> %message%";
    /** The format used before icons existed; upgrading it saves everyone the manual edit. */
    private static final String LEGACY_DEFAULT_CHAT_FORMAT = "%rank% %name% >> %message%";
    /** Folder used before the plugin was renamed, migrated once on first start as SCSO. */
    private static final String LEGACY_DATA_FOLDER = "SilentStaffMembers";

    private final Map<UUID, SilentClientApi.LookupResult> verifiedStaff = new ConcurrentHashMap<>();
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
    private volatile boolean chatIcons;
    private volatile boolean assumePackLoaded;
    private volatile String resourcePackUrl;
    private volatile String resourcePackPrompt;
    private volatile boolean resourcePackRequired;
    private volatile int resourcePackFormat = IconPack.DEFAULT_PACK_FORMAT;
    /** rank (lowercase) -> icon name, from the config's icon-ranks section. */
    private volatile Map<String, String> iconRanks = Map.of();
    /** The generated icon pack; {@code null} or unavailable when it could not be built. */
    private volatile IconPack iconPack;
    /** Players who may join without a staff rank; loaded in onEnable before any login. */
    private volatile StaffWhitelist whitelist;
    /** Non-null only while TAB is installed and its API accepted us. */
    private volatile TabHook tabHook;
    /** Players whose client confirmed it loaded the pack carrying the icons. */
    private final Set<UUID> packReady = ConcurrentHashMap.newKeySet();
    /** The pack id we sent each player, so another plugin's pack status is not mistaken for ours. */
    private final Map<UUID, UUID> offeredPacks = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        // Runs before saveDefaultConfig, so a config from the old folder is not overwritten.
        migrateLegacyDataFolder();
        saveDefaultConfig();
        whitelist = new StaffWhitelist(getDataFolder().toPath().resolve("whitelist.yml"), getLogger());
        if (!loadSettings()) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getServer().getPluginManager().registerEvents(this, this);
        rebuildIconPack();
        connectTab();
        getLogger().info("Enabled with " + rankPolicy.configuredRanks().size()
                + " staff rank names, " + whitelist.size() + " whitelisted non-staff name(s)"
                + (iconPack != null && iconPack.isAvailable()
                        ? " and " + iconPack.iconCount() + " chat icons" : "")
                + "; Silent Client rank verification is required on login.");
        logIconStatus();
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
            requestTimeoutMs = Math.min(requestTimeoutMs, MAX_LOOKUP_WAIT_MS - 500);
            long ttl = Math.max(0, Math.min(3600, getConfig().getLong("cache.ttl-seconds", 300)));
            int maxEntries = boundedInt("cache.max-entries", 10000, 100, 100000);

            List<String> configured = getConfig().getStringList("staff-ranks");
            if (configured.isEmpty()) configured = List.copyOf(RankPolicy.DEFAULT_STAFF_RANKS);
            rankPolicy = new RankPolicy(configured);
            api = new SilentClientApi(baseUrl, Duration.ofMillis(connectTimeoutMs),
                    Duration.ofMillis(requestTimeoutMs), rankPolicy.configuredRanks(), ttl, maxEntries);
            failClosed = getConfig().getBoolean("fail-closed", true);
            kickMessage = getConfig().getString("kick-message", "&cStaff only.");
            lookupFailedMessage = getConfig().getString("lookup-failed-message", "&cCould not verify your staff rank. Try again.");
            String configuredChatFormat = getConfig().getString("chat-format", DEFAULT_CHAT_FORMAT);
            if (configuredChatFormat != null
                    && LEGACY_DEFAULT_CHAT_FORMAT.equalsIgnoreCase(configuredChatFormat.trim())) {
                // Untouched old default: upgrade it rather than silently losing the icons.
                configuredChatFormat = DEFAULT_CHAT_FORMAT;
                getLogger().info("chat-format was the old default, so it is upgraded to '"
                        + DEFAULT_CHAT_FORMAT + "'; edit config.yml to keep a different format.");
            }
            chatFormat = configuredChatFormat;
            tabFormat = getConfig().getString("tab-format", "%rank% %name%");
            tabIntegration = getConfig().getBoolean("tab-integration", true);
            tabNametagPrefix = getConfig().getBoolean("tab-nametag-prefix", true);
            chatIcons = getConfig().getBoolean("chat-icons", true);
            assumePackLoaded = getConfig().getBoolean("resource-pack.assume-loaded", false);
            resourcePackUrl = getConfig().getString("resource-pack.url", "");
            resourcePackPrompt = getConfig().getString("resource-pack.prompt", "");
            resourcePackRequired = getConfig().getBoolean("resource-pack.required", false);
            int format = getConfig().getInt("resource-pack.format", IconPack.DEFAULT_PACK_FORMAT);
            if (format < 1 || format > 999) {
                throw new IllegalArgumentException("resource-pack.format must be between 1 and 999");
            }
            resourcePackFormat = format;
            iconRanks = readIconOverrides();
            // Only touched after the config is known good, so a bad config cannot
            // drop the whitelist that is currently in force.
            whitelist.load();
            return true;
        } catch (IllegalArgumentException e) {
            getLogger().severe("Invalid configuration: " + e.getMessage());
            return false;
        }
    }

    /** rank -> icon overrides, lowercased so lookups never depend on the admin's casing. */
    private Map<String, String> readIconOverrides() {
        ConfigurationSection section = getConfig().getConfigurationSection("icon-ranks");
        if (section == null) return Map.of();
        Map<String, String> overrides = new ConcurrentHashMap<>();
        for (String rank : section.getKeys(false)) {
            String icon = section.getString(rank);
            if (rank == null || icon == null || icon.isBlank()) continue;
            overrides.put(rank.trim().toLowerCase(Locale.ROOT), icon.trim().toLowerCase(Locale.ROOT));
        }
        return Map.copyOf(overrides);
    }

    /** Rebuilds the pack from the bundled icons plus anything the admin added to icons/. */
    private void rebuildIconPack() {
        iconPack = IconPack.generate(getLogger(), getDataFolder().toPath(), resourcePackFormat);
    }

    /**
     * Says plainly whether clients can actually get the icons, so "the icon did not load"
     * is answered by the log instead of by guesswork.
     */
    private void logIconStatus() {
        IconPack pack = iconPack;
        if (!chatIcons || pack == null || !pack.isAvailable()) return;
        if (chatFormat == null || !chatFormat.toLowerCase(Locale.ROOT).contains("%icon%")) {
            getLogger().info("chat-format has no %icon%, so ranks are shown without their icon. "
                    + "Add %icon% to it (for example '" + DEFAULT_CHAT_FORMAT + "') to show icons.");
            return;
        }
        if (resourcePackUrl != null && !resourcePackUrl.isBlank()) {
            getLogger().info("Icon pack is offered to players from " + resourcePackUrl);
        } else if (serverServesOurPack()) {
            getLogger().info("server.properties already serves this icon pack; players load it on join.");
        } else if (foreignServerPack()) {
            getLogger().warning("server.properties serves a different resource pack, so it cannot "
                    + "carry these icons. Host " + pack.zip() + " yourself and set resource-pack.url "
                    + "to it (sha1 " + pack.sha1() + "), then run /" + COMMAND_NAME + " reload.");
        } else if (assumePackLoaded) {
            getLogger().info("Icons are drawn for everyone because resource-pack.assume-loaded is true."
                    + " Only do that while something else really delivers the pack.");
        } else {
            getLogger().warning("Chat icons cannot reach any client yet: host " + pack.zip()
                    + " somewhere static and set resource-pack.url (sha1 " + pack.sha1() + "), or point "
                    + "server.properties' resource-pack at that file, then run /" + COMMAND_NAME
                    + " reload. Ranks are shown without an icon until then.");
        }
    }

    /** True when server.properties already serves the pack this plugin generated. */
    private boolean serverServesOurPack() {
        IconPack pack = iconPack;
        if (pack == null || !pack.isAvailable()) return false;
        String url = Bukkit.getResourcePack();
        String hash = Bukkit.getResourcePackHash();
        return url != null && !url.isBlank() && hash != null && hash.equalsIgnoreCase(pack.sha1());
    }

    /** True when server.properties serves some other pack, which cannot hold our glyphs. */
    private boolean foreignServerPack() {
        String url = Bukkit.getResourcePack();
        return url != null && !url.isBlank() && !serverServesOurPack();
    }

    /**
     * Copies config.yml, whitelist.yml and any custom icons from the folder used before the
     * plugin was renamed, so the upgrade keeps the admin's settings.
     */
    private void migrateLegacyDataFolder() {
        Path dataFolder = getDataFolder().toPath();
        Path parent = dataFolder.getParent();
        if (parent == null) return;
        Path legacy = parent.resolve(LEGACY_DATA_FOLDER);
        if (!Files.isDirectory(legacy)) return;
        for (String name : new String[]{"config.yml", "whitelist.yml", "icons"}) {
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

    /**
     * The glyph for a rank, or an empty string when this server has no icon for it.
     */
    private String glyphFor(StaffRank rank) {
        IconPack pack = iconPack;
        if (pack == null || !chatIcons) return "";
        return pack.glyphFor(rank.name(), iconRanks).orElse("");
    }

    /**
     * The glyph to draw in one viewer's chat: only a client that confirmed the pack can
     * render it, so everyone else gets plain text instead of a missing-glyph box.
     */
    private String chatIcon(StaffRank rank, Audience viewer) {
        String glyph = glyphFor(rank);
        if (glyph.isEmpty() || assumePackLoaded) return glyph;
        return viewer instanceof Player player && packReady.contains(player.getUniqueId()) ? glyph : "";
    }

    /** Sends the icon pack on join when a URL to host it is configured. */
    private void offerResourcePack(Player player) {
        IconPack pack = iconPack;
        String url = resourcePackUrl;
        if (pack == null || !pack.isAvailable() || url == null || url.isBlank()) return;
        try {
            UUID id = UUID.randomUUID();
            offeredPacks.put(player.getUniqueId(), id);
            player.addResourcePack(id, url, hexToBytes(pack.sha1()), resourcePackPrompt, resourcePackRequired);
        } catch (RuntimeException e) {
            getLogger().warning("Could not offer the icon resource pack to " + player.getName() + ": " + e);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onResourcePackStatus(PlayerResourcePackStatusEvent event) {
        Player player = event.getPlayer();
        UUID offered = offeredPacks.get(player.getUniqueId());
        // A pack we never sent is only trusted when the server does not serve its own pack,
        // which could not contain our glyphs.
        if (offered != null) {
            if (!offered.equals(event.getID())) return;
        } else if (foreignServerPack()) {
            return;
        }
        switch (event.getStatus()) {
            case SUCCESSFULLY_LOADED -> packReady.add(player.getUniqueId());
            case DECLINED, FAILED_DOWNLOAD, FAILED_RELOAD, DISCARDED, INVALID_URL -> {
                packReady.remove(player.getUniqueId());
                getLogger().warning("The icon resource pack was not applied for " + player.getName()
                        + " (" + event.getStatus() + "); their rank is shown without an icon.");
            }
            default -> { }
        }
    }

    private static byte[] hexToBytes(String hex) {
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }

    /**
     * Attaches to TAB when it is installed. TAB refuses to let another plugin control the
     * tab list, so the rank is submitted through TAB's own API. Nothing here touches TAB's
     * classes until the plugin is known to be present.
     */
    private void connectTab() {
        disconnectTab();
        if (!tabIntegration) return;
        if (getServer().getPluginManager().getPlugin("TAB") == null) {
            getLogger().info("TAB is not installed; the rank is shown through the vanilla player list name.");
            return;
        }
        try {
            TabHook hook = TabHook.register(getLogger(), () -> tabFormat, uuid -> {
                SilentClientApi.LookupResult result = verifiedStaff.get(uuid);
                return result == null ? null : result.displayRank();
            }, this::glyphFor, tabNametagPrefix);
            // TabHook logs what TAB actually offered once it is connected.
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

    /**
     * Writes the rank everywhere it is shown. The vanilla calls are the fallback and are
     * what TAB blocks when its own tablist format is enabled; the TAB API call is what
     * wins in that case.
     */
    private void applyDisplays(Player player, StaffRank rank) {
        player.displayName(NameFormats.displayName(rank, player.getName()));
        // The list name is one component for every viewer, so the icon only goes in when
        // the format asks for it - every viewer then needs the pack for it to look right.
        player.playerListName(NameFormats.apply(tabFormat, rank, player.getName(), Component.empty(),
                glyphFor(rank)));
        TabHook hook = tabHook;
        if (hook != null) hook.apply(player.getUniqueId());
    }

    private int boundedInt(String path, int defaultValue, int min, int max) {
        int value = getConfig().getInt(path, defaultValue);
        if (value < min || value > max) {
            throw new IllegalArgumentException(path + " must be between " + min + " and " + max);
        }
        return value;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAsyncPlayerPreLogin(AsyncPlayerPreLoginEvent event) {
        String username = event.getName();
        if (!isValidUsername(username)) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    Component.text("Invalid player name."));
            return;
        }

        if (whitelist.contains(username)) {
            // An admin vouched for this name, so it joins immediately: no API call, no
            // rank, and no dependency on the service being reachable.
            getLogger().info("Allowed whitelisted player " + username + " without a staff rank check.");
            return;
        }

        SilentClientApi localApi = api;
        if (localApi == null) {
            denyLookupFailure(event);
            return;
        }
        try {
            SilentClientApi.LookupResult result = localApi.lookup(username)
                    .get(MAX_LOOKUP_WAIT_MS, TimeUnit.MILLISECONDS);
            if (!result.isStaff()) {
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                        legacyText(kickMessage));
                getLogger().info("Denied non-staff login for " + username + ".");
                return;
            }
            verifiedStaff.put(event.getUniqueId(), result);
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
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, legacyText(lookupFailedMessage));
        } else {
            getLogger().warning("Allowing " + event.getName() + " because fail-closed is disabled.");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        SilentClientApi.LookupResult result = verifiedStaff.get(player.getUniqueId());
        if (result == null) {
            if (whitelist.contains(player.getName())) {
                // Allowed during pre-login. Look the rank up in the background so a
                // whitelisted staff member still ends up with their rank shown.
                resolveRank(player, false);
                return;
            }
            // A plugin might have changed the pre-login result between callbacks.
            player.kick(legacyText(lookupFailedMessage));
            return;
        }
        applyDisplays(player, result.displayRank());
        offerResourcePack(player);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        // Forget the verified rank for this session. The next login also has to wait
        // out the API cache TTL, so a rank change is picked up at most ttl-seconds later.
        UUID id = event.getPlayer().getUniqueId();
        verifiedStaff.remove(id);
        packReady.remove(id);
        offeredPacks.remove(id);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAsyncChat(AsyncChatEvent event) {
        SilentClientApi.LookupResult result = verifiedStaff.get(event.getPlayer().getUniqueId());
        if (result == null) return;
        StaffRank rank = result.displayRank();
        // The renderer runs per viewer, so an icon is only ever drawn for clients that can
        // actually render the glyph.
        event.renderer((source, sourceDisplayName, message, viewer) -> NameFormats.apply(
                chatFormat, rank, source.getName(), message, chatIcon(rank, viewer)));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase(COMMAND_NAME)) return false;
        if (args.length == 0) {
            sendUsage(sender, label);
            return true;
        }
        if (args[0].equalsIgnoreCase("reload")) return reload(sender);
        if (args[0].equalsIgnoreCase("whitelist") || args[0].equalsIgnoreCase("wl")) {
            return whitelist(sender, label, args);
        }
        if (args[0].equalsIgnoreCase("pack") || args[0].equalsIgnoreCase("icons")) {
            return packStatus(sender);
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
        verifiedStaff.clear();
        rebuildIconPack();
        connectTab();
        for (Player player : Bukkit.getOnlinePlayers()) {
            // Re-check online players without touching their state off their own region thread.
            resolveRank(player, true);
        }
        logIconStatus();
        sender.sendMessage(Component.text("SCSO reloaded; online staff ranks are being re-verified.", NamedTextColor.GREEN));
        return true;
    }

    /**
     * Resolves one online player's rank in the background and then applies the displays on
     * that player's own thread. Whitelisted players are never kicked, and a lookup failure
     * only kicks when {@code kickIfNotStaff} is set and the name is not whitelisted.
     */
    private void resolveRank(Player player, boolean kickIfNotStaff) {
        SilentClientApi localApi = api;
        if (localApi == null) return;
        String username = player.getName();
        localApi.lookup(username).whenComplete((result, error) -> {
            if (error != null || result == null || !result.isStaff()) {
                if (kickIfNotStaff && !whitelist.contains(username)) {
                    player.getScheduler().run(this, task -> player.kick(legacyText(kickMessage)), null);
                }
                return;
            }
            if (!player.isOnline()) return;
            verifiedStaff.put(player.getUniqueId(), result);
            player.getScheduler().run(this, task -> {
                if (player.isOnline()) applyDisplays(player, result.displayRank());
            }, null);
        });
    }

    /** What the icon pack is, where it is, and how many clients have loaded it. */
    private boolean packStatus(CommandSender sender) {
        if (!sender.hasPermission(RELOAD_PERMISSION)) {
            sender.sendMessage(Component.text("You do not have permission.", NamedTextColor.RED));
            return true;
        }
        IconPack pack = iconPack;
        if (pack == null || !pack.isAvailable()) {
            sender.sendMessage(Component.text("No chat icon pack is available; check the server log.",
                    NamedTextColor.RED));
            return true;
        }
        sender.sendMessage(line("Chat icons", chatIcons ? pack.iconCount() + " icons, pack format "
                + pack.packFormat() : "disabled (chat-icons: false)"));
        sender.sendMessage(line("Icons folder", getDataFolder().toPath().resolve("icons").toString()));
        sender.sendMessage(line("Pack file", pack.zip() == null ? "?" : pack.zip().toString()));
        sender.sendMessage(line("SHA-1", pack.sha1()));
        sender.sendMessage(line("Pack URL", resourcePackUrl == null || resourcePackUrl.isBlank()
                ? serverServesOurPack()
                        ? "server.properties already serves this pack"
                        : "not configured (resource-pack.url); host the pack or use server.properties"
                : resourcePackUrl));
        int ready = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (packReady.contains(player.getUniqueId())) ready++;
        }
        sender.sendMessage(line("Clients that loaded it", ready + " of " + Bukkit.getOnlinePlayers().size()));
        sender.sendMessage(line("Glyphs", String.join(", ", pack.iconNames())));
        return true;
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

    /** Removing a whitelisted non-staff player who is online takes their access away now. */
    private void kickIfNoLongerAllowed(String username) {
        Player player = Bukkit.getPlayerExact(username);
        if (player == null) return;
        SilentClientApi.LookupResult result = verifiedStaff.get(player.getUniqueId());
        if (result != null && result.isStaff()) return;
        getLogger().info("Kicking " + username + " because they are no longer whitelisted.");
        player.getScheduler().run(this, task -> player.kick(legacyText(kickMessage)), null);
    }

    private void sendUsage(CommandSender sender, String label) {
        sender.sendMessage(Component.text("Usage: /" + label + " reload", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("       /" + label + " pack", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("       /" + label + " whitelist add|remove <player> | list",
                NamedTextColor.YELLOW));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase(COMMAND_NAME)) return List.of();
        if (args.length == 1) {
            List<String> options = new java.util.ArrayList<>();
            if (sender.hasPermission(RELOAD_PERMISSION)) {
                options.add("reload");
                options.add("pack");
            }
            if (sender.hasPermission(WHITELIST_PERMISSION)) options.add("whitelist");
            return StaffWhitelist.filter(options, args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("whitelist")) {
            if (!sender.hasPermission(WHITELIST_PERMISSION)) return List.of();
            return StaffWhitelist.filter(List.of("add", "remove", "list"), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("whitelist")
                && args[1].equalsIgnoreCase("remove")) {
            if (!sender.hasPermission(WHITELIST_PERMISSION)) return List.of();
            return StaffWhitelist.filter(whitelist.names(), args[2]);
        }
        return List.of();
    }

    private static boolean isValidUsername(String username) {
        return username != null && !username.isEmpty() && username.length() <= MAX_USERNAME_LENGTH
                && username.matches("[A-Za-z0-9_]{1,16}");
    }

    private static Component legacyText(String input) {
        if (input == null || input.isBlank()) return Component.empty();
        return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand()
                .deserialize(input);
    }

    @Override
    public void onDisable() {
        disconnectTab();
        verifiedStaff.clear();
        SilentClientApi current = api;
        if (current != null) current.close();
    }
}
