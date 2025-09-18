package com.ionsignal.minecraft.ionnerrus.agent.skills.impl;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.skills.Skill;
import com.ionsignal.minecraft.ionnerrus.persona.platform.v1_21_R7.PersonaEntity;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;

import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.inventory.CraftingRecipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * A skill that performs a 2x2 craft in the agent's inventory.
 * Assumes the agent has the required materials.
 */
public class CraftInInventorySkill implements Skill<Boolean> {
    private final CraftingRecipe recipe;
    private final Logger logger;

    public CraftInInventorySkill(CraftingRecipe recipe) {
        this.recipe = recipe;
        this.logger = IonNerrus.getInstance().getLogger();
    }

    @Override
    public CompletableFuture<Boolean> execute(NerrusAgent agent) {
        // All inventory operations must happen on the main thread.
        return CompletableFuture.supplyAsync(() -> {
            PersonaEntity personaEntity = agent.getPersona().getPersonaEntity();
            if (personaEntity == null) {
                return false;
            }
            logger.info("[CraftInv] Starting 2x2 craft for: " + recipe.getResult().getType());
            AbstractContainerMenu menu = personaEntity.inventoryMenu;
            List<RecipeChoice> ingredients = getIngredients(recipe);
            String ingredientStr = ingredients.stream()
                    .map(c -> c == null ? "EMPTY" : getChoiceDescription(c))
                    .collect(Collectors.joining(", "));
            logger.info("[CraftInv] Parsed ingredients for 2x2 grid: [" + ingredientStr + "]");
            if (ingredients.isEmpty()) {
                logger.warning("[CraftInv] Recipe has no ingredients.");
                return false;
            }
            boolean success = placeIngredients(personaEntity, menu, ingredients);
            if (!success) {
                logger.warning("[CraftInv] Failed to place all ingredients. Clearing grid.");
                clearCraftingGrid(personaEntity, menu);
                return false;
            }
            // The result slot is index 0 in the player's inventory container.
            ItemStack resultStack = menu.getSlot(0).getItem();
            logger.info("[CraftInv] After placing, result slot contains: " + CraftItemStack.asBukkitCopy(resultStack));
            if (resultStack.isEmpty()) {
                // Crafting failed, clear grid and return failure
                logger.warning("[CraftInv] Crafting failed, result slot is empty. Clearing grid.");
                clearCraftingGrid(personaEntity, menu);
                return false;
            }
            // Simulate a shift-click on the result slot to craft and move to inventory.
            // This correctly handles consuming ingredients.
            personaEntity.containerMenu.clicked(0, 0, ClickType.QUICK_MOVE, personaEntity);
            logger.info("[CraftInv] Craft successful.");
            return true;
        }, IonNerrus.getInstance().getMainThreadExecutor());
    }

    private boolean placeIngredients(ServerPlayer player, AbstractContainerMenu menu, List<RecipeChoice> ingredients) {
        // Slots 1-4 are the 2x2 crafting grid.
        int[] gridSlots = { 1, 2, 3, 4 };
        for (int i = 0; i < ingredients.size(); i++) {
            RecipeChoice choice = ingredients.get(i);
            if (choice == null)
                continue;
            final int gridSlotIndex = gridSlots[i];
            // Find the next available item directly from the TRUE inventory state in the menu.
            Optional<Integer> sourceSlotOpt = findNextAvailableStackInMenu(menu, choice);
            if (sourceSlotOpt.isPresent()) {
                int slotIndex = sourceSlotOpt.get();
                logger.info("[CraftInv] Placing ingredient " + getChoiceDescription(choice) + " from inv slot " + slotIndex
                        + " into grid slot " + gridSlotIndex);
                logger.info("[CraftInv] Menu state BEFORE click sequence: " + getMenuStateForLogging(menu));
                menu.clicked(slotIndex, 0, ClickType.PICKUP, player); // Pick up stack
                menu.clicked(gridSlotIndex, 1, ClickType.PICKUP, player); // Place one
                menu.clicked(slotIndex, 0, ClickType.PICKUP, player); // Put rest back
                logger.info("[CraftInv] Menu state AFTER click sequence: " + getMenuStateForLogging(menu));
            } else {
                logger.warning("[CraftInv] Could not find available item for ingredient: " + getChoiceDescription(choice));
                return false; // A required ingredient was not found, placement fails.
            }
        }
        return true;
    }

    /**
     * Finds the next available item stack in the player's inventory that satisfies the recipe choice,
     * querying the live state of the container menu.
     *
     * @param menu
     *            The agent's current container menu.
     * @param choice
     *            The recipe choice to satisfy.
     * @return An Optional containing the menu slot index of a valid item, or empty if none is found.
     */
    private Optional<Integer> findNextAvailableStackInMenu(AbstractContainerMenu menu, RecipeChoice choice) {
        // Player's main inventory slots are from index 9 to the end of the container's slots.
        for (int i = 9; i < menu.slots.size(); i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (!stack.isEmpty() && choice.test(CraftItemStack.asBukkitCopy(stack))) {
                return Optional.of(i);
            }
        }
        return Optional.empty();
    }

    /**
     * Gets a representative description for a RecipeChoice for logging purposes without using the
     * deprecated getItemStack() method.
     */
    private String getChoiceDescription(RecipeChoice choice) {
        if (choice instanceof RecipeChoice.MaterialChoice mc) {
            if (!mc.getChoices().isEmpty()) {
                return mc.getChoices().get(0).toString();
            }
        } else if (choice instanceof RecipeChoice.ExactChoice ec) {
            if (!ec.getChoices().isEmpty()) {
                return ec.getChoices().get(0).getType().toString();
            }
        }
        return "unknown";
    }

    private void clearCraftingGrid(ServerPlayer player, AbstractContainerMenu menu) {
        for (int i = 1; i <= 4; i++) { // Slots 1-4 are the crafting grid
            if (menu.getSlot(i).hasItem()) {
                // QUICK_MOVE will shift-click the item back into the main inventory.
                menu.clicked(i, 0, ClickType.QUICK_MOVE, player);
            }
        }
    }

    private List<RecipeChoice> getIngredients(CraftingRecipe recipe) {
        if (recipe instanceof ShapedRecipe shaped) {
            List<RecipeChoice> ingredients = new ArrayList<>(4);
            String[] shape = shaped.getShape();
            java.util.Map<Character, RecipeChoice> choiceMap = shaped.getChoiceMap();
            for (int r = 0; r < 2; r++) {
                for (int c = 0; c < 2; c++) {
                    if (r < shape.length && c < shape[r].length()) {
                        ingredients.add(choiceMap.get(shape[r].charAt(c)));
                    } else {
                        ingredients.add(null);
                    }
                }
            }
            return ingredients;
        } else if (recipe instanceof ShapelessRecipe shapeless) {
            return shapeless.getChoiceList();
        }
        return List.of();
    }

    private static String getMenuStateForLogging(AbstractContainerMenu menu) {
        String carried = CraftItemStack.asBukkitCopy(menu.getCarried()).toString();
        String grid1 = CraftItemStack.asBukkitCopy(menu.getSlot(1).getItem()).toString();
        String grid2 = CraftItemStack.asBukkitCopy(menu.getSlot(2).getItem()).toString();
        String grid3 = CraftItemStack.asBukkitCopy(menu.getSlot(3).getItem()).toString();
        String grid4 = CraftItemStack.asBukkitCopy(menu.getSlot(4).getItem()).toString();
        String result = CraftItemStack.asBukkitCopy(menu.getSlot(0).getItem()).toString();
        return String.format("Carried: {%s}, Result: {%s}, Grid: [{1:%s}, {2:%s}, {3:%s}, {4:%s}]", carried, result, grid1, grid2, grid3,
                grid4);
    }
}