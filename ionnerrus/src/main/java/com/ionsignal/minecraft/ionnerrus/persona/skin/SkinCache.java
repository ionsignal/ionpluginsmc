package com.ionsignal.minecraft.ionnerrus.persona.skin;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;

import org.bukkit.Bukkit;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class SkinCache {
    private static final String UUID_API_URL = "https://api.mojang.com/users/profiles/minecraft/";
    private static final String SKIN_API_URL = "https://sessionserver.mojang.com/session/minecraft/profile/";

    private final ConcurrentHashMap<String, SkinData> skinCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, UUID> uuidCache = new ConcurrentHashMap<>();
    private final HttpClient httpClient;
    private final IonNerrus plugin;

    public SkinCache(IonNerrus plugin) {
        this.plugin = plugin;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(10))
                .executor(plugin.getOffloadThreadExecutor())
                .build();
    }

    public CompletableFuture<SkinData> fetchSkin(String name) {
        String lowerCaseName = name.toLowerCase();
        if (skinCache.containsKey(lowerCaseName)) {
            return CompletableFuture.completedFuture(skinCache.get(lowerCaseName));
        }
        return fetchUUID(name).thenCompose(uuid -> {
            if (uuid == null) {
                IonNerrus.getInstance().getLogger().warning("Could not fetch UUID for skin name: " + name);
                return CompletableFuture.completedFuture(null);
            }
            return fetchSkinFromAPI(uuid, name);
        });
    }

    private CompletableFuture<SkinData> fetchSkinFromAPI(UUID uuid, String name) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(SKIN_API_URL + uuid.toString().replace("-", "") + "?unsigned=false"))
                .GET()
                .build();
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() != 200) {
                        IonNerrus.getInstance().getLogger()
                                .warning("Failed to fetch skin for " + name + ". Response code: " + response.statusCode());
                        return null;
                    }
                    JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
                    JsonObject properties = root.getAsJsonArray("properties").get(0).getAsJsonObject();
                    String texture = properties.get("value").getAsString();
                    String signature = properties.get("signature").getAsString();
                    SkinData skinData = new SkinData(texture, signature);
                    skinCache.put(name.toLowerCase(), skinData);
                    return skinData;
                })
                .exceptionally(ex -> {
                    IonNerrus.getInstance().getLogger().log(Level.SEVERE, "Exception while fetching skin for " + name, ex);
                    return null;
                });
    }

    private CompletableFuture<UUID> fetchUUID(String name) {
        String lowerCaseName = name.toLowerCase();
        if (uuidCache.containsKey(lowerCaseName)) {
            return CompletableFuture.completedFuture(uuidCache.get(lowerCaseName));
        }
        var player = Bukkit.getPlayerExact(name);
        if (player != null) {
            UUID uuid = player.getUniqueId();
            uuidCache.put(lowerCaseName, uuid);
            return CompletableFuture.completedFuture(uuid);
        }
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(UUID_API_URL + name))
                .GET()
                .build();
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() != 200) {
                        return null;
                    }
                    JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
                    String uuidStr = root.get("id").getAsString();
                    UUID uuid = UUID.fromString(uuidStr.replaceFirst(
                            "(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{12})", "$1-$2-$3-$4-$5"));
                    uuidCache.put(lowerCaseName, uuid);
                    return uuid;
                })
                .exceptionally(ex -> {
                    IonNerrus.getInstance().getLogger().log(Level.WARNING, "Could not fetch UUID for name: " + name, ex.getMessage());
                    return null;
                });
    }

    /**
     * Shuts down the HTTP client to release its internal threads.
     */
    public void shutdown() {
        plugin.getLogger().info("Shutting down SkinCache HTTP client...");
        try {
            // This closes the internal selector threads that HttpClient creates
            httpClient.close();
            plugin.getLogger().info("SkinCache HTTP client closed successfully.");
        } catch (Exception e) {
            plugin.getLogger().warning("Error closing SkinCache HTTP client: " + e.getMessage());
        }
        // Clear caches to release memory
        skinCache.clear();
        uuidCache.clear();
    }
}