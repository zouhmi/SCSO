package net.zouhmi.silentstaffmembers;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonParseException;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

final class SilentClientApi implements AutoCloseable {
    private final HttpClient client;
    private final String baseUrl;
    private final Duration requestTimeout;
    private final Set<String> allowedRanks;
    private final Map<String, CachedResult> cache = new ConcurrentHashMap<>();
    private volatile long cacheTtlNanos;
    private volatile int maxCacheEntries;

    SilentClientApi(String baseUrl, Duration connectTimeout, Duration requestTimeout,
                    Set<String> allowedRanks, long cacheTtlSeconds, int maxCacheEntries) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.requestTimeout = requestTimeout;
        this.allowedRanks = allowedRanks.stream()
                .map(SilentClientApi::normalize)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        this.cacheTtlNanos = TimeUnit.SECONDS.toNanos(Math.max(0, cacheTtlSeconds));
        this.maxCacheEntries = Math.max(1, maxCacheEntries);
        this.client = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    CompletableFuture<LookupResult> lookup(String username) {
        String key = normalize(username);
        long now = System.nanoTime();
        CachedResult found = cache.get(key);
        if (found != null && now - found.cachedAtNanos() < cacheTtlNanos) {
            return CompletableFuture.completedFuture(found.result());
        }

        String encoded = URLEncoder.encode(username, StandardCharsets.UTF_8).replace("+", "%20");
        return getJson("/users/account/" + encoded)
                .thenCompose(accountResponse -> {
                    JsonObject account = object(accountResponse, "account");
                    String canonicalName = string(account, "username");
                    if (canonicalName == null || !canonicalName.equalsIgnoreCase(username)) {
                        throw new LookupException("Silent Client account not found");
                    }
                    long userId;
                    try {
                        userId = account.get("user_id").getAsLong();
                    } catch (RuntimeException e) {
                        throw new LookupException("Account response has no user id", e);
                    }
                    return getJson("/ranks/ranks/user/" + userId)
                            .thenApply(rankResponse -> parseRanks(canonicalName, rankResponse));
                })
                .thenApply(result -> {
                    if (cacheTtlNanos > 0) cachePut(key, result);
                    return result;
                });
    }

    CompletableFuture<LookupResult> lookupFresh(String username) {
        cache.remove(normalize(username));
        return lookup(username);
    }

    private CompletableFuture<JsonObject> getJson(String path) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                .header("User-Agent", "SilentStaffMembers/1.0 (+https://silentclient.net)")
                .GET()
                .build();
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(response -> {
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        throw new LookupException(
                                "Silent Client API returned HTTP " + response.statusCode());
                    }
                    try {
                        JsonElement parsed = JsonParser.parseString(response.body());
                        if (!parsed.isJsonObject()) throw new LookupException("Expected a JSON object");
                        JsonObject root = parsed.getAsJsonObject();
                        if (hasErrors(root.get("errors"))) {
                            throw new LookupException("Silent Client API reported a lookup error");
                        }
                        return root;
                    } catch (JsonParseException | IllegalStateException e) {
                        throw new LookupException("Invalid JSON from Silent Client API", e);
                    }
                });
    }

    private LookupResult parseRanks(String username, JsonObject response) {
        JsonElement ranksElement = response.get("ranks");
        if (ranksElement == null || !ranksElement.isJsonArray()) {
            throw new LookupException("Rank response is missing its ranks list");
        }
        JsonArray ranks = ranksElement.getAsJsonArray();
        List<StaffRank> accepted = new ArrayList<>();
        for (JsonElement element : ranks) {
            if (!element.isJsonObject()) continue;
            JsonObject rank = element.getAsJsonObject();
            String rankName = string(rank, "name");
            String rankKey = string(rank, "key");
            if ((rankName != null && allowedRanks.contains(normalize(rankName)))
                    || (rankKey != null && allowedRanks.contains(normalize(rankKey)))) {
                JsonObject meta = rank.has("meta") && rank.get("meta").isJsonObject()
                        ? rank.getAsJsonObject("meta") : new JsonObject();
                accepted.add(StaffRank.from(
                        RankPolicy.displayName(rankName, rankKey),
                        string(meta, "color"),
                        number(rank, "priority")));
            }
        }
        return new LookupResult(username, List.copyOf(accepted));
    }

    private void cachePut(String key, LookupResult result) {
        if (cache.size() >= maxCacheEntries) {
            long now = System.nanoTime();
            cache.entrySet().removeIf(entry -> now - entry.getValue().cachedAtNanos() >= cacheTtlNanos);
            if (cache.size() >= maxCacheEntries) {
                String oldest = null;
                long oldestAt = Long.MAX_VALUE;
                for (Map.Entry<String, CachedResult> entry : cache.entrySet()) {
                    if (entry.getValue().cachedAtNanos() < oldestAt) {
                        oldestAt = entry.getValue().cachedAtNanos();
                        oldest = entry.getKey();
                    }
                }
                if (oldest != null) cache.remove(oldest);
            }
        }
        cache.put(key, new CachedResult(result, System.nanoTime()));
    }

    void clearCache() {
        cache.clear();
    }

    static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("[ _-]+", " ");
    }

    private static JsonObject object(JsonObject root, String field) {
        JsonElement element = root.get(field);
        if (element == null || !element.isJsonObject()) {
            throw new LookupException("Account response is missing its account");
        }
        return element.getAsJsonObject();
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() || !value.isJsonPrimitive()
                ? null : value.getAsString();
    }

    private static boolean hasErrors(JsonElement errors) {
        if (errors == null || errors.isJsonNull()) return false;
        if (errors.isJsonArray()) return !errors.getAsJsonArray().isEmpty();
        if (errors.isJsonObject()) return !errors.getAsJsonObject().isEmpty();
        if (errors.isJsonPrimitive()) {
            var primitive = errors.getAsJsonPrimitive();
            if (primitive.isBoolean()) return primitive.getAsBoolean();
            if (primitive.isNumber()) return primitive.getAsDouble() != 0;
            if (primitive.isString()) {
                String text = primitive.getAsString();
                return text != null && !text.isBlank() && !text.equalsIgnoreCase("null");
            }
        }
        return false;
    }

    private static int number(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive()) return 0;
        try {
            return value.getAsInt();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    @Override
    public void close() {
        cache.clear();
        try {
            client.close();
        } catch (RuntimeException e) {
        }
    }

    record LookupResult(String username, List<StaffRank> staffRanks) {
        boolean isStaff() {
            return !staffRanks.isEmpty();
        }

        StaffRank displayRank() {
            StaffRank best = null;
            for (StaffRank rank : staffRanks) {
                if (best == null || rank.priority() > best.priority()) best = rank;
            }
            return best == null ? StaffRank.from("Staff", "#FFFFFF") : best;
        }
    }

    private record CachedResult(LookupResult result, long cachedAtNanos) { }

    static final class LookupException extends RuntimeException {
        LookupException(String message) { super(message); }
        LookupException(String message, Throwable cause) { super(message, cause); }
    }
}
