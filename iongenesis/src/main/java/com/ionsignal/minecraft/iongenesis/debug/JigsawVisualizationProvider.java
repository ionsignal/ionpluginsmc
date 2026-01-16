package com.ionsignal.minecraft.iongenesis.debug;

import com.ionsignal.minecraft.ioncore.debug.VisualizationProvider;
import com.ionsignal.minecraft.iongenesis.IonGenesis;
import com.ionsignal.minecraft.iongenesis.generation.StructureBlueprint;
import com.ionsignal.minecraft.iongenesis.generation.placements.PlacedJigsawPiece;
import com.ionsignal.minecraft.iongenesis.model.geometry.AABB;

import com.dfsek.seismic.type.vector.Vector3Int;

import net.kyori.adventure.text.Component;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.TextDisplay;
import org.bukkit.util.Transformation;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class JigsawVisualizationProvider implements VisualizationProvider<StructureBlueprint> {
    private final Map<UUID, Set<UUID>> sessionEntities = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> renderedPieceCounts = new ConcurrentHashMap<>();
    private final Map<UUID, ActiveHead> sessionHeads = new ConcurrentHashMap<>();

    // Record to track active head entities
    private record ActiveHead(UUID blockDisplayId, UUID textDisplayId) {
    }

    @Override
    public void render(StructureBlueprint state) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Visualization must run on main thread");
        }
        UUID sessionId = state.sessionId();
        if (sessionId == null)
            return; // Should not happen in debug mode
        World world = Bukkit.getWorlds().get(0); // Simplification: assuming overworld or derived from origin
        // In production, origin should contain world UID or we get it from player context
        Entity player = Bukkit.getEntity(state.sessionId()); // Session ID is Player UUID
        if (player != null) {
            world = player.getWorld();
        }
        if (world == null)
            return;
        Set<UUID> entities = sessionEntities.computeIfAbsent(sessionId, k -> ConcurrentHashMap.newKeySet());
        int previouslyRendered = renderedPieceCounts.getOrDefault(sessionId, 0);
        int currentCount = state.pieces().size();
        // Demote the previous "Head" if new pieces are being added
        if (currentCount > previouslyRendered) {
            demoteActiveHead(sessionId);
        }
        // Render new pieces
        for (int i = previouslyRendered; i < currentCount; i++) {
            PlacedJigsawPiece piece = state.pieces().get(i);
            boolean isNewest = (i == currentCount - 1);
            // Capture the returned ActiveHead (if any) and track it
            ActiveHead newHead = spawnPieceVisuals(world, piece, isNewest, entities);
            if (newHead != null) {
                sessionHeads.put(sessionId, newHead);
            }
        }
        // Update "Active" highlight logic would go here in a full implementation
        // For now, we rely on the incremental spawning to highlight the newest added piece.
        renderedPieceCounts.put(sessionId, currentCount);
    }

    // Helper to fade the previous active piece to gray and remove text
    private void demoteActiveHead(UUID sessionId) {
        ActiveHead head = sessionHeads.remove(sessionId);
        if (head == null)
            return;
        // Update Block Display (Fade to Gray, remove Glow)
        // Defensive check: Entity might be null if chunk unloaded or killed
        Entity blockEntity = Bukkit.getEntity(head.blockDisplayId());
        if (blockEntity instanceof BlockDisplay blockDisplay) {
            blockDisplay.setBlock(Material.GRAY_STAINED_GLASS.createBlockData());
            blockDisplay.setGlowing(false);
            blockDisplay.setGlowColorOverride(null);
        }
        // Remove Text Display (Reduce clutter)
        if (head.textDisplayId() != null) {
            Entity textEntity = Bukkit.getEntity(head.textDisplayId());
            if (textEntity != null && textEntity.isValid()) {
                textEntity.remove();
            }
        }
    }

    private ActiveHead spawnPieceVisuals(World world, PlacedJigsawPiece piece, boolean isNewest, Set<UUID> tracker) {
        AABB bounds = piece.getWorldBounds();
        Vector3Int min = bounds.min();
        Vector3Int size = bounds.getSize();
        Location loc = new Location(world, min.getX(), min.getY(), min.getZ());
        // Spawn Block Display (Bounding Box)
        BlockDisplay display = (BlockDisplay) world.spawnEntity(loc, EntityType.BLOCK_DISPLAY);
        // Logic moved here - if newest, Green, else Gray.
        display.setBlock(isNewest ? Material.LIME_STAINED_GLASS.createBlockData() : Material.GRAY_STAINED_GLASS.createBlockData());
        // Transform to match size
        Transformation transform = display.getTransformation();
        transform.getScale().set(size.getX(), size.getY(), size.getZ());
        display.setTransformation(transform);
        // Configuration
        display.setPersistent(false); // CRITICAL: Do not save
        display.addScoreboardTag("ionnerrus:debug_visualizer");
        display.setBrightness(new Display.Brightness(15, 15));
        if (isNewest) {
            display.setGlowColorOverride(Color.LIME);
            display.setGlowing(true);
        }
        tracker.add(display.getUniqueId());
        UUID textId = null;
        if (isNewest) {
            Location textLoc = loc.clone().add(size.getX() / 2.0, size.getY() + 1.5, size.getZ() / 2.0);
            TextDisplay text = (TextDisplay) world.spawnEntity(textLoc, EntityType.TEXT_DISPLAY);
            text.text(Component.text(piece.structureId() + "\n" + piece.sourcePoolId()));
            text.setBillboard(Display.Billboard.CENTER);
            text.setPersistent(false);
            text.addScoreboardTag("ionnerrus:debug_visualizer");
            tracker.add(text.getUniqueId());
            textId = text.getUniqueId();
        }
        if (isNewest) {
            return new ActiveHead(display.getUniqueId(), textId);
        }
        return null;
    }

    @Override
    public CompletableFuture<Void> cleanup() {
        // Grab all entities and clear the map immediately.
        // This prevents race conditions where new sessions might be cleared by old tasks.
        Set<UUID> allEntities = ConcurrentHashMap.newKeySet();
        for (Set<UUID> set : sessionEntities.values()) {
            allEntities.addAll(set);
        }
        sessionEntities.clear();
        renderedPieceCounts.clear();
        sessionHeads.clear(); // NEW: Clear head tracking
        return scheduleRemoval(allEntities);
    }

    @Override
    public CompletableFuture<Void> cleanup(UUID sessionId) {
        // Remove the specific session set immediately.
        Set<UUID> entities = sessionEntities.remove(sessionId);
        renderedPieceCounts.remove(sessionId);
        sessionHeads.remove(sessionId); // NEW: Clear head tracking
        if (entities == null) {
            return CompletableFuture.completedFuture(null);
        }
        return scheduleRemoval(entities);
    }

    private CompletableFuture<Void> scheduleRemoval(Set<UUID> entitiesToRemove) {
        if (entitiesToRemove.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        if (!Bukkit.isPrimaryThread()) {
            CompletableFuture<Void> future = new CompletableFuture<>();
            Bukkit.getScheduler().runTask(IonGenesis.getInstance(), () -> {
                removeEntities(entitiesToRemove);
                future.complete(null);
            });
            return future;
        } else {
            removeEntities(entitiesToRemove);
            return CompletableFuture.completedFuture(null);
        }
    }

    private void removeEntities(Set<UUID> uuids) {
        for (UUID uuid : uuids) {
            Entity e = Bukkit.getEntity(uuid);
            if (e != null && e.isValid()) {
                e.remove();
            }
        }
    }

    @Override
    public Class<StructureBlueprint> getStateType() {
        return StructureBlueprint.class;
    }
}