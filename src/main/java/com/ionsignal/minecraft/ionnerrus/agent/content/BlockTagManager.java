package com.ionsignal.minecraft.ionnerrus.agent.content;

import org.bukkit.Material;
import org.bukkit.Tag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class BlockTagManager {

    private final Map<String, Set<Material>> materialGroups = new HashMap<>();

    public BlockTagManager() {
        loadMaterialGroups();
    }

    private void loadMaterialGroups() {
        // Tag-based groups
        materialGroups.put("wood", Tag.LOGS_THAT_BURN.getValues());
        materialGroups.put("stone", Tag.BASE_STONE_OVERWORLD.getValues());
        materialGroups.put("dirt", Tag.DIRT.getValues());
        materialGroups.put("sand", Tag.SAND.getValues());
        materialGroups.put("flowers", Tag.FLOWERS.getValues());

        // Manual definition
        materialGroups.put("mushroom", Set.of(Material.BROWN_MUSHROOM, Material.RED_MUSHROOM));

        // Composite example (more comprehensive stone)
        Set<Material> comprehensiveStone = Stream.concat(
                Tag.BASE_STONE_OVERWORLD.getValues().stream(),
                Stream.of(Material.COBBLESTONE, Material.MOSSY_COBBLESTONE)).collect(Collectors.toSet());
        materialGroups.put("all_stone", comprehensiveStone);
    }

    /**
     * Retrieves a set of materials for a given group name.
     *
     * @param groupName The name of the material group (case-insensitive).
     * @return An unmodifiable set of materials, or null if the group is not found.
     */
    @Nullable
    public Set<Material> getMaterialSet(@NotNull String groupName) {
        Set<Material> materials = materialGroups.get(groupName.toLowerCase());
        return materials != null ? Collections.unmodifiableSet(materials) : null;
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