package com.ionsignal.minecraft.ionnerrus.persona.inventory;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.persona.PersonaEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.RecipeBookMenu;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;

import org.bukkit.Location;
import org.bukkit.craftbukkit.util.CraftNamespacedKey;
import org.bukkit.inventory.CraftingRecipe;

import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.logging.Logger;

/**
 * A utility class that encapsulates NMS logic for performing crafting actions.
 * This isolates version-specific code and provides a robust, high-level API for crafting skills.
 */
public class CraftingUtil {

    private static final Logger LOGGER = IonNerrus.getInstance().getLogger();

    /**
     * Performs a craft for a PersonaEntity using the server's internal recipe handling.
     * This method automatically places ingredients and executes the craft.
     *
     * @param personaEntity
     *            The NMS entity performing the craft.
     * @param recipe
     *            The Bukkit recipe to be crafted.
     * @param craftingTableLocation
     *            The location of the crafting table, or null for a 2x2 inventory craft.
     * @return true if the craft was successful, false otherwise.
     */
    public static boolean performCraft(PersonaEntity personaEntity, CraftingRecipe recipe, @Nullable Location craftingTableLocation) {
        // Step 1: Correctly resolve the Bukkit recipe to its NMS RecipeHolder counterpart.
        // First, get the ResourceLocation from the Bukkit key.
        ResourceLocation recipeLocation = CraftNamespacedKey.toMinecraft(recipe.getKey());
        // Then, create the proper ResourceKey by combining the location with its registry.
        ResourceKey<Recipe<?>> recipeKey = ResourceKey.create(Registries.RECIPE, recipeLocation);
        Optional<RecipeHolder<?>> nmsRecipeOpt = personaEntity.level().getServer().getRecipeManager().byKey(recipeKey);
        if (nmsRecipeOpt.isEmpty()) {
            LOGGER.warning("[CraftingUtil] Could not find NMS recipe for key: " + recipe.getKey());
            return false;
        }
        RecipeHolder<?> nmsRecipeHolder = nmsRecipeOpt.get();
        // Step 2: Determine and acquire the correct crafting menu.
        // The variable type is now AbstractCraftingMenu, which has all the methods we need.
        boolean is3x3 = craftingTableLocation != null;
        AbstractCraftingMenu menu;
        if (is3x3) {
            // For 3x3 crafts, we must open a new CraftingMenu at the table's location.
            MenuProvider menuProvider = new SimpleMenuProvider(
                    (id, inventory, player) -> new CraftingMenu(id, inventory,
                            ContainerLevelAccess.create(personaEntity.level(),
                                    new BlockPos(craftingTableLocation.getBlockX(), craftingTableLocation.getBlockY(),
                                            craftingTableLocation.getBlockZ()))),
                    net.minecraft.network.chat.Component.translatable("container.crafting"));
            personaEntity.openMenu(menuProvider);
            if (!(personaEntity.containerMenu instanceof CraftingMenu craftingMenu)) {
                LOGGER.severe("[CraftingUtil] Failed to open CraftingMenu. Current menu is: "
                        + personaEntity.containerMenu.getClass().getSimpleName());
                return false;
            }
            menu = craftingMenu;
        } else {
            // For 2x2 crafts, we use the agent's standard inventory menu.
            menu = personaEntity.inventoryMenu;
        }
        try {
            // Step 3: Use the server's recipe placement logic to move ingredients into the grid.
            RecipeBookMenu.PostPlaceAction placeAction = menu.handlePlacement(false, false, nmsRecipeHolder,
                    (ServerLevel) personaEntity.level(), personaEntity.getInventory());
            if (placeAction != RecipeBookMenu.PostPlaceAction.NOTHING) {
                LOGGER.warning("[CraftingUtil] NMS handlePlacement failed. Agent may be missing ingredients.");
                // The handlePlacement method clears the grid on failure, so no manual cleanup is needed here.
                return false;
            }
            // Step 4: Check if a result was produced. This now compiles correctly.
            if (menu.getResultSlot().getItem().isEmpty()) {
                LOGGER.warning("[CraftingUtil] Recipe placement succeeded, but result slot is emnerpty. Crafting failed.");
                return false;
            }
            // Step 5: Simulate a shift-click on the result slot to execute the craft.
            menu.clicked(0, 0, ClickType.QUICK_MOVE, personaEntity);
            LOGGER.info("[CraftingUtil] Successfully crafted " + recipe.getResult().getType());
            return true;
        } catch (Exception e) {
            LOGGER.log(java.util.logging.Level.SEVERE, "[CraftingUtil] An exception occurred during crafting.", e);
            return false;
        } finally {
            // Step 6: If we opened a temporary 3x3 menu, ensure it gets closed.
            if (is3x3) {
                personaEntity.closeContainer();
            }
        }
    }
}