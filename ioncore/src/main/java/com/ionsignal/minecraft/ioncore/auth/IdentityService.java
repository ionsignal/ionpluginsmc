package com.ionsignal.minecraft.ioncore.auth;

import com.ionsignal.minecraft.ioncore.IonCore;
import com.ionsignal.minecraft.ioncore.config.TenantConfig;
import com.ionsignal.minecraft.ioncore.json.JsonService;
import com.ionsignal.minecraft.ioncore.network.IonEventBroker;
import com.ionsignal.minecraft.ioncore.network.SubjectTaxonomy;
import com.ionsignal.minecraft.ioncore.network.UniversalSubjectBuilder;
import com.ionsignal.minecraft.ioncore.network.model.IonUser;
import com.ionsignal.minecraft.ioncore.network.model.MinecraftIdentity;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.JsonNode;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class IdentityService {
    private final IonCore plugin;
    private final IonEventBroker eventBroker;
    private final TenantConfig tenantConfig;
    private final JsonService jsonService;
    private final Map<UUID, Optional<IonUser>> identityCache = new ConcurrentHashMap<>();
    private final Map<UUID, CompletableFuture<Optional<IonUser>>> pendingFetches = new ConcurrentHashMap<>();
    private final String webUrl;

    public IdentityService(IonCore plugin, IonEventBroker eventBroker, TenantConfig tenantConfig, JsonService jsonService) {
        this.plugin = plugin;
        this.eventBroker = eventBroker;
        this.tenantConfig = tenantConfig;
        this.jsonService = jsonService;
        String configUrl = plugin.getConfig().getString("web.url", "https://localhost:3002");
        this.webUrl = configUrl.endsWith("/") ? configUrl.substring(0, configUrl.length() - 1) : configUrl;
    }

    /**
     * Retrieves the cached identity for a player and returns empty if the player's identity has not
     * been loaded yet.
     */
    public Optional<Optional<IonUser>> getCachedIdentity(UUID uuid) {
        return Optional.ofNullable(identityCache.get(uuid));
    }

    public void invalidate(UUID uuid) {
        identityCache.remove(uuid);
    }

    /**
     * Asynchronously fetches the identity from the database and updates the cache.
     */
    public CompletableFuture<Optional<IonUser>> fetchIdentity(Player player) {
        return pendingFetches.computeIfAbsent(player.getUniqueId(), uuid -> {
            ObjectNode req = jsonService.getObjectMapper().createObjectNode();
            req.put("minecraftUuid", player.getUniqueId().toString());
            String subject = UniversalSubjectBuilder.build(
                    SubjectTaxonomy.SubjectPrefix.REQUEST,
                    tenantConfig.getTenantId(),
                    "identity",
                    "get");
            return eventBroker.requestAsync(subject, req)
                    .thenApply(resp -> {
                        if (resp != null && resp.has("userId") && !resp.get("userId").isNull()) {
                            UUID userId = UUID.fromString(resp.get("userId").asText());
                            MinecraftIdentity identity = new MinecraftIdentity(player.getUniqueId(), player.getName());
                            IonUser user = new IonUser(userId, identity);
                            identityCache.put(player.getUniqueId(), Optional.of(user));
                            return Optional.of(user);
                        }
                        identityCache.put(player.getUniqueId(), Optional.empty());
                        return Optional.<IonUser>empty();
                    })
                    .exceptionally(ex -> {
                        // Graceful degradation: If NATS is down, assume unlinked rather than erroring out
                        plugin.getLogger().warning("NATS timeout/error fetching identity for " + player.getName() + ": " + ex.getMessage());
                        return Optional.empty();
                    })
                    .whenComplete((res, ex) -> pendingFetches.remove(uuid));
        });
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
        }).exceptionally(ex -> {
            plugin.getLogger().warning("Failed to generate link ticket: " + ex.getMessage());
            return null;
        });
    }

    private CompletableFuture<String> generateTicket(Player player) {
        ObjectNode req = jsonService.getObjectMapper().createObjectNode();
        req.put("minecraftUuid", player.getUniqueId().toString());
        req.put("minecraftUsername", player.getName());
        String subject = UniversalSubjectBuilder.build(
                SubjectTaxonomy.SubjectPrefix.REQUEST,
                tenantConfig.getTenantId(),
                "identity",
                "link.generate");
        return eventBroker.requestAsync(subject, req)
                .thenApply(resp -> {
                    if (resp != null && resp.has("token")) {
                        return resp.get("token").asText();
                    }
                    throw new RuntimeException("Invalid or missing token in NATS response");
                });
    }

    public void handleExternalLinkEvent(String payloadJson) {
        try {
            JsonNode root = jsonService.readTree(payloadJson);
            if (root.has("minecraftUuid")) {
                UUID uuid = UUID.fromString(root.get("minecraftUuid").asText());
                Player player = Bukkit.getPlayer(uuid);
                if (player != null && player.isOnline()) {
                    fetchIdentity(player).thenAccept(userOpt -> {
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            player.sendMessage(Component.text("Account successfully linked! Welcome.", NamedTextColor.GREEN));
                            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
                        });
                    });
                } else {
                    invalidate(uuid);
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to handle PLAYER_LINKED event: " + e.getMessage());
        }
    }
}