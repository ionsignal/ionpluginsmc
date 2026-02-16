package com.ionsignal.minecraft.ioncore.auth;

import com.ionsignal.minecraft.ioncore.IonCore;
import com.ionsignal.minecraft.ioncore.api.auth.IonIdentity;
import com.ionsignal.minecraft.ioncore.api.events.IonUserLinkedEvent;
import com.ionsignal.minecraft.ioncore.database.DatabaseManager;
import com.ionsignal.minecraft.ioncore.json.JsonService;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import io.vertx.sqlclient.Tuple;

import com.fasterxml.jackson.databind.JsonNode;

import java.security.SecureRandom;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class IdentityService {
    private final IonCore plugin;
    private final DatabaseManager databaseManager;
    private final JsonService jsonService;
    private final Map<UUID, IonIdentity> identityCache = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final String webUrl;

    public IdentityService(IonCore plugin, DatabaseManager databaseManager, JsonService jsonService) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
        this.jsonService = jsonService;
        String configUrl = plugin.getConfig().getString("web.url", "https://localhost:3002");
        // Strip trailing slash if present
        this.webUrl = configUrl.endsWith("/") ? configUrl.substring(0, configUrl.length() - 1) : configUrl;
    }

    /**
     * Retrieves the cached identity for a player.
     * Returns empty if the player's identity has not been loaded yet.
     */
    public Optional<IonIdentity> getCachedIdentity(UUID uuid) {
        return Optional.ofNullable(identityCache.get(uuid));
    }

    public void invalidate(UUID uuid) {
        identityCache.remove(uuid);
    }

    /**
     * Asynchronously fetches the identity from the database and updates the cache.
     */
    public CompletableFuture<IonIdentity> fetchIdentity(Player player) {
        CompletableFuture<IonIdentity> future = new CompletableFuture<>();
        // Join ion_users with users to get the web username
        String query = "SELECT u.username FROM ion_users i JOIN users u ON i.user_id = u.id WHERE i.minecraft_uuid = $1";
        databaseManager.getPgPool()
                .preparedQuery(query)
                .execute(Tuple.of(player.getUniqueId()))
                .onSuccess(rows -> {
                    IonIdentity identity;
                    if (rows.size() > 0) {
                        String webUsername = rows.iterator().next().getString("username");
                        identity = IonIdentity.linked(player.getUniqueId(), player.getName(), webUsername);
                    } else {
                        identity = IonIdentity.unlinked(player.getUniqueId(), player.getName());
                    }
                    identityCache.put(player.getUniqueId(), identity);
                    future.complete(identity);
                })
                .onFailure(err -> {
                    plugin.getLogger().severe("Failed to fetch identity for " + player.getName() + ": " + err.getMessage());
                    future.completeExceptionally(err);
                });
        return future;
    }

    /**
     * Generates a verification ticket and sends the link to the player.
     */
    public void initiateLinkingProcess(Player player) {
        generateTicket(player).thenAccept(token -> {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline())
                    return;
                String link = webUrl + "/link?ticket=" + token;
                Component message = Component.text()
                        .append(Component.text("════════════════════════════════════", NamedTextColor.DARK_GRAY))
                        .append(Component.newline())
                        .append(Component.text(" Link your Account", NamedTextColor.GOLD, TextDecoration.BOLD))
                        .append(Component.newline())
                        .append(Component.text(" Click below to link your Minecraft account to Runemind.", NamedTextColor.GRAY))
                        .append(Component.newline())
                        .append(Component.newline())
                        .append(Component.text(" [CLICK TO LINK]", NamedTextColor.GREEN, TextDecoration.BOLD)
                                .clickEvent(ClickEvent.openUrl(link))
                                .hoverEvent(HoverEvent.showText(Component.text("Click to open " + link, NamedTextColor.GRAY))))
                        .append(Component.newline())
                        .append(Component.text("════════════════════════════════════", NamedTextColor.DARK_GRAY))
                        .build();
                player.sendMessage(message);
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 2f);
            });
        });
    }

    private CompletableFuture<String> generateTicket(Player player) {
        CompletableFuture<String> future = new CompletableFuture<>();
        // Generate 32-char hex token
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        String token = sb.toString();
        // Upsert ticket (1 hour expiry)
        String sql = "INSERT INTO verification_tickets (token, minecraft_uuid, minecraft_username, expires_at) " +
                "VALUES ($1, $2, $3, NOW() + INTERVAL '1 hour') " +
                "ON CONFLICT (minecraft_uuid) " +
                "DO UPDATE SET token = EXCLUDED.token, expires_at = EXCLUDED.expires_at, minecraft_username = EXCLUDED.minecraft_username";
        databaseManager.getPgPool()
                .preparedQuery(sql)
                .execute(Tuple.of(token, player.getUniqueId(), player.getName()))
                .onSuccess(rows -> future.complete(token))
                .onFailure(err -> {
                    plugin.getLogger().severe("Failed to generate ticket for " + player.getName() + ": " + err.getMessage());
                    future.completeExceptionally(err);
                });
        return future;
    }

    /**
     * Callback for when the EventBus receives a PLAYER_LINKED message.
     *
     * @param payloadJson
     *            The raw JSON payload from the database event.
     */
    public void handleExternalLinkEvent(String payloadJson) {
        // Updated to use Jackson (JsonService) instead of Regex
        try {
            JsonNode root = jsonService.readTree(payloadJson);
            if (root.has("minecraftUuid")) {
                UUID uuid = UUID.fromString(root.get("minecraftUuid").asText());
                Player player = Bukkit.getPlayer(uuid);
                if (player != null && player.isOnline()) {
                    // Refresh Identity
                    fetchIdentity(player).thenAccept(identity -> {
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            // Fire Event
                            Bukkit.getPluginManager().callEvent(new IonUserLinkedEvent(player, identity));
                            // User Feedback
                            player.sendMessage(Component.text("Account successfully linked! Welcome, ", NamedTextColor.GREEN)
                                    .append(Component.text(identity.webUsername().orElse("User"), NamedTextColor.YELLOW)));
                            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
                        });
                    });
                } else {
                    // Player offline, just invalidate cache so next join fetches fresh
                    invalidate(uuid);
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to handle PLAYER_LINKED event: " + e.getMessage());
        }
    }
}