package com.ionsignal.minecraft.ionnerrus.agent.content;

import org.bukkit.Material;
import org.bukkit.Tag;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;
// import java.util.stream.Collectors;
// import java.util.stream.Stream;

public class BlockTagManager {
    private final Map<String, Set<Material>> materialGroups = new HashMap<>();
    private final Set<String> gatherableGroups = new HashSet<>();
    private final Set<Material> rawMaterials = new HashSet<>();

    public BlockTagManager() {
        loadMaterialGroups();
        loadRawMaterials();
    }

    private void loadMaterialGroups() {
        // Tag-based groups
        materialGroups.put("wood", Tag.LOGS_THAT_BURN.getValues());
        materialGroups.put("stone", Tag.BASE_STONE_OVERWORLD.getValues());
        materialGroups.put("dirt", Tag.DIRT.getValues());
        materialGroups.put("sand", Tag.SAND.getValues());
        materialGroups.put("flowers", Tag.FLOWERS.getValues());
        materialGroups.put("planks", Tag.PLANKS.getValues());
        // Manual definition
        materialGroups.put("mushroom", Set.of(Material.BROWN_MUSHROOM, Material.RED_MUSHROOM));
        // // Composite example (more comprehensive stone)
        // Set<Material> comprehensiveStone = Stream.concat(
        // Tag.BASE_STONE_OVERWORLD.getValues().stream(),
        // Stream.of(Material.COBBLESTONE, Material.MOSSY_COBBLESTONE)).collect(Collectors.toSet());
        // materialGroups.put("all_stone", comprehensiveStone);
        // Define which of the above groups are considered "gatherable" by default.
        // Ores like COAL are intentionally omitted to force the agent to request them.
        gatherableGroups.add("wood");
        gatherableGroups.add("stone");
        gatherableGroups.add("dirt");
        gatherableGroups.add("sand");
    }

    private void loadRawMaterials() {
        // This set defines materials that the RecipeService should NEVER try to craft,
        // even if a "de-crafting" recipe (like from a storage block) exists.
        // These are the terminal nodes of our crafting dependency graph.
        // Mineral Resources
        rawMaterials.add(Material.DIAMOND);
        rawMaterials.add(Material.EMERALD);
        rawMaterials.add(Material.LAPIS_LAZULI);
        rawMaterials.add(Material.COAL);
        rawMaterials.add(Material.CHARCOAL);
        rawMaterials.add(Material.REDSTONE);
        rawMaterials.add(Material.QUARTZ);
        rawMaterials.add(Material.FLINT);
        rawMaterials.add(Material.CLAY_BALL);
        rawMaterials.add(Material.GLOWSTONE_DUST);
        // Ingots (from smelting)
        rawMaterials.add(Material.IRON_INGOT);
        rawMaterials.add(Material.GOLD_INGOT);
        rawMaterials.add(Material.COPPER_INGOT);
        rawMaterials.add(Material.NETHERITE_INGOT);
        rawMaterials.add(Material.NETHER_BRICK);
        // Mob Drops
        rawMaterials.add(Material.STRING);
        rawMaterials.add(Material.BONE);
        rawMaterials.add(Material.GUNPOWDER);
        rawMaterials.add(Material.ROTTEN_FLESH);
        rawMaterials.add(Material.SPIDER_EYE);
        rawMaterials.add(Material.SLIME_BALL);
        rawMaterials.add(Material.ENDER_PEARL);
        rawMaterials.add(Material.BLAZE_ROD);
        rawMaterials.add(Material.GHAST_TEAR);
        rawMaterials.add(Material.LEATHER);
        rawMaterials.add(Material.FEATHER);
    }

    /**
     * Checks if a material is defined as a "raw" or "base" material that should be
     * acquired from the world, not crafted.
     *
     * @param material
     *            The material to check.
     * @return True if the material is on the raw material list.
     */
    public boolean isRawMaterial(@NotNull Material material) {
        return rawMaterials.contains(material);
    }

    /**
     * Finds the common group name for a given set of materials.
     * This is useful for logging and creating user-friendly representations.
     *
     * @param materials
     *            The set of materials to find the group name for.
     * @return An Optional containing the group name, or empty if no exact match is found.
     */
    public Optional<String> getGroupNameFor(Set<Material> materials) {
        for (Map.Entry<String, Set<Material>> entry : materialGroups.entrySet()) {
            if (entry.getValue().equals(materials)) {
                return Optional.of(entry.getKey());
            }
        }
        return Optional.empty();
    }

    /**
     * Retrieves a set of materials for a given group name.
     *
     * @param groupName
     *            The name of the material group (case-insensitive).
     * @return An unmodifiable set of materials, or null if the group is not found.
     */
    @Nullable
    public Set<Material> getMaterialSet(@NotNull String groupName) {
        Set<Material> materials = materialGroups.get(groupName.toLowerCase());
        return materials != null ? Collections.unmodifiableSet(materials) : null;
    }

    /**
     * Finds the material group that a specific material belongs to.
     *
     * @param material
     *            The material to find the group for.
     * @return The set of materials in the group, or null if not found.
     */
    @Nullable
    public Set<Material> getMaterialSetFor(@NotNull Material material) {
        for (Set<Material> group : materialGroups.values()) {
            if (group.contains(material)) {
                return Collections.unmodifiableSet(group);
            }
        }
        return null;
    }

    /**
     * Checks if a given material belongs to a group that is designated as "gatherable".
     *
     * @param material
     *            The material to check.
     * @return True if the material is in a gatherable group, false otherwise.
     */
    public boolean isGatherable(@NotNull Material material) {
        for (Map.Entry<String, Set<Material>> entry : materialGroups.entrySet()) {
            if (gatherableGroups.contains(entry.getKey()) && entry.getValue().contains(material)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Retrieves the names of all registered material groups.
     *
     * @return An unmodifiable set of group names.
     */
    @NotNull
    public Set<String> getRegisteredGroupNames() {
        return Collections.unmodifiableSet(materialGroups.keySet());
    }
}