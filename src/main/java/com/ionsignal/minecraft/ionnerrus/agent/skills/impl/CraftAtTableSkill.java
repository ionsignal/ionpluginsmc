package com.ionsignal.minecraft.ionnerrus.agent.skills.impl;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.skills.Skill;
import com.ionsignal.minecraft.ionnerrus.persona.platform.v1_21_R7.PersonaEntity;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.ItemStack;

import org.bukkit.Location;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.inventory.CraftingRecipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * A skill that performs a 3x3 craft at a crafting table.
 * Assumes the agent is standing next to the table and has the required materials.
 */
public class CraftAtTableSkill implements Skill<Boolean> {
    private final CraftingRecipe recipe;
    private final Location craftingTableLocation;
    private final Logger logger;

    public CraftAtTableSkill(CraftingRecipe recipe, Location craftingTableLocation) {
        this.recipe = recipe;
        this.craftingTableLocation = craftingTableLocation;
        this.logger = IonNerrus.getInstance().getLogger();
    }

    @Override
    public CompletableFuture<Boolean> execute(NerrusAgent agent) {
        return CompletableFuture.supplyAsync(() -> {
            PersonaEntity personaEntity = agent.getPersona().getPersonaEntity();
            if (personaEntity == null)
                return false;
            logger.info("[CraftTable] Starting 3x3 craft for: " + recipe.getResult().getType() + " at " + craftingTableLocation.toVector());
            // Step 1: Open the crafting table menu for the Persona
            MenuProvider menuProvider = new SimpleMenuProvider(
                    (id, inventory, player) -> new CraftingMenu(id, inventory, ContainerLevelAccess.create(personaEntity.level(),
                            new net.minecraft.core.BlockPos(craftingTableLocation.getBlockX(), craftingTableLocation.getBlockY(),
                                    craftingTableLocation.getBlockZ()))),
                    net.minecraft.network.chat.Component.translatable("container.crafting"));
            personaEntity.openMenu(menuProvider);
            // Ensure the opened menu is a CraftingMenu
            if (!(personaEntity.containerMenu instanceof CraftingMenu menu)) {
                logger.warning("[CraftTable] Failed to open CraftingMenu. Current menu is: "
                        + personaEntity.containerMenu.getClass().getSimpleName());
                personaEntity.closeContainer();
                return false;
            }
            // Step 2: Place ingredients into the grid
            boolean ingredientsPlaced = placeIngredients(personaEntity, menu);
            if (!ingredientsPlaced) {
                logger.warning("[CraftTable] Failed to place all ingredients.");
                clearCraftingGrid(personaEntity, menu);
                personaEntity.closeContainer();
                return false;
            }
            // Step 3: Craft the item
            ItemStack resultStack = menu.getSlot(0).getItem();
            logger.info("[CraftTable] After placing, result slot contains: " + CraftItemStack.asBukkitCopy(resultStack));
            if (resultStack.isEmpty()) {
                logger.warning("[CraftTable] Crafting failed, result slot is empty. Clearing grid.");
                clearCraftingGrid(personaEntity, menu);
                personaEntity.closeContainer();
                return false;
            }
            // Simulate shift-click on the result slot (0) to craft and move to inventory
            menu.clicked(0, 0, ClickType.QUICK_MOVE, personaEntity);
            // Step 4: Clean up
            logger.info("[CraftTable] Craft successful.");
            personaEntity.closeContainer();
            return true;
        }, IonNerrus.getInstance().getMainThreadExecutor());
    }

    private boolean placeIngredients(ServerPlayer player, CraftingMenu menu) {
        if (recipe instanceof ShapedRecipe shaped) {
            Map<Character, RecipeChoice> choiceMap = shaped.getChoiceMap();
            String[] shape = shaped.getShape();
            for (int row = 0; row < shape.length; row++) {
                for (int col = 0; col < shape[row].length(); col++) {
                    char c = shape[row].charAt(col);
                    if (c == ' ')
                        continue;
                    RecipeChoice choice = choiceMap.get(c);
                    if (choice == null)
                        continue;
                    int gridSlotIndex = 1 + col + (row * 3); // 1-based index for grid
                    logger.info("[CraftTable] Shaped recipe: Placing ingredient for char '" + c + "' into grid slot " + gridSlotIndex);
                    if (!moveOneItem(player, menu, choice, gridSlotIndex)) {
                        return false; // Failed to find a required ingredient
                    }
                }
            }
        } else if (recipe instanceof ShapelessRecipe shapeless) {
            for (int i = 0; i < shapeless.getChoiceList().size(); i++) {
                RecipeChoice choice = shapeless.getChoiceList().get(i);
                int gridSlotIndex = 1 + i;
                logger.info("[CraftTable] Shapeless recipe: Placing ingredient " + getChoiceDescription(choice) + " into grid slot "
                        + gridSlotIndex);
                if (!moveOneItem(player, menu, choice, gridSlotIndex)) {
                    return false; // Failed to find a required ingredient
                }
            }
        }
        return true;
    }

    private boolean moveOneItem(ServerPlayer player, CraftingMenu menu, RecipeChoice choice, int gridSlotIndex) {
        Optional<Integer> itemSlotOpt = findNextAvailableStackInMenu(menu, choice);
        if (itemSlotOpt.isEmpty()) {
            logger.warning("[CraftTable] Could not find available item for ingredient: " + getChoiceDescription(choice));
            return false;
        }
        int itemSlotIndex = itemSlotOpt.get();
        logger.info("[CraftTable] Placing ingredient " + getChoiceDescription(choice) + " from inv slot " + itemSlotIndex
                + " into grid slot " + gridSlotIndex);
        logger.info("[CraftTable] Menu state BEFORE click sequence: " + getMenuStateForLogging(menu));
        // Simulate picking up one item from the stack
        menu.clicked(itemSlotIndex, 0, ClickType.PICKUP, player); // Pickup stack
        menu.clicked(gridSlotIndex, 1, ClickType.PICKUP, player); // Place one
        menu.clicked(itemSlotIndex, 0, ClickType.PICKUP, player); // Put rest back
        logger.info("[CraftTable] Menu state AFTER click sequence: " + getMenuStateForLogging(menu));
        return true;
    }

    /**
     * Finds the next available item stack in the player's inventory that satisfies the recipe choice,
     * querying the live state of the crafting menu.
     *
     * @param menu
     *            The agent's current crafting menu.
     * @param choice
     *            The recipe choice to satisfy.
     * @return An Optional containing the menu slot index of a valid item, or empty if none is found.
     */
    private Optional<Integer> findNextAvailableStackInMenu(CraftingMenu menu, RecipeChoice choice) {
        // In CraftingMenu, main inventory is 10-36, hotbar is 37-45.
        for (int i = 10; i <= 45; i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (!stack.isEmpty() && choice.test(CraftItemStack.asBukkitCopy(stack))) {
                return Optional.of(i);
            }
        }
        return Optional.empty();
    }

    private void clearCraftingGrid(ServerPlayer player, AbstractContainerMenu menu) {
        for (int i = 1; i <= 9; i++) { // Slots 1-9 are the crafting grid
            if (menu.getSlot(i).hasItem()) {
                menu.clicked(i, 0, ClickType.QUICK_MOVE, player);
            }
        }
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

    private static String getMenuStateForLogging(AbstractContainerMenu menu) {
        String carried = CraftItemStack.asBukkitCopy(menu.getCarried()).toString();
        StringBuilder grid = new StringBuilder();
        for (int i = 1; i <= 9; i++) {
            grid.append(String.format("{%d:%s}, ", i, CraftItemStack.asBukkitCopy(menu.getSlot(i).getItem())));
        }
        String result = CraftItemStack.asBukkitCopy(menu.getSlot(0).getItem()).toString();
        return String.format("Carried: {%s}, Result: {%s}, Grid: [%s]", carried, result, grid.toString());
    }
}