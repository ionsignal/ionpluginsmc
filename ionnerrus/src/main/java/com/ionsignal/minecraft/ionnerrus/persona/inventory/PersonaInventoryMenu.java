package com.ionsignal.minecraft.ionnerrus.persona.inventory;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.bukkit.craftbukkit.inventory.CraftInventory;
import org.bukkit.craftbukkit.inventory.CraftInventoryView;
import org.bukkit.inventory.InventoryView;
import org.jetbrains.annotations.NotNull;

import com.ionsignal.minecraft.ionnerrus.persona.PersonaEntity;

public class PersonaInventoryMenu extends AbstractContainerMenu {

    private final Container personaInventory;
    private final PersonaEntity persona;
    private final Player player;
    private InventoryView bukkitView;

    private static final int PERSONA_INV_SIZE = 36;
    private static final int PLAYER_INV_SIZE = 36;
    private static final int PERSONA_INV_END_INDEX = PERSONA_INV_SIZE;
    private static final int PLAYER_INV_START_INDEX = PERSONA_INV_SIZE;
    private static final int PLAYER_INV_END_INDEX = PERSONA_INV_SIZE + PLAYER_INV_SIZE;

    public PersonaInventoryMenu(int containerId, Inventory playerInventory, Container personaInventory, PersonaEntity persona) {
        super(MenuType.GENERIC_9x4, containerId);
        this.personaInventory = personaInventory;
        this.persona = persona;
        this.player = playerInventory.player;
        checkContainerSize(personaInventory, PERSONA_INV_SIZE);
        personaInventory.startOpen(this.player);

        // Persona Inventory (slots 0-35)
        for (int row = 0; row < 4; ++row) {
            for (int col = 0; col < 9; ++col) {
                int personaSlotIndex;
                if (row == 3) {
                    personaSlotIndex = col;
                } else {
                    personaSlotIndex = col + row * 9 + 9;
                }
                this.addSlot(new Slot(personaInventory, personaSlotIndex, 8 + col * 18, 18 + row * 18));
            }
        }

        // Player Inventory (slots 36-71)
        int playerInvY = 103;
        for (int row = 0; row < 3; ++row) {
            for (int col = 0; col < 9; ++col) {
                this.addSlot(new Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, playerInvY + row * 18));
            }
        }

        for (int i = 0; i < 9; ++i) {
            this.addSlot(new Slot(playerInventory, i, 8 + i * 18, playerInvY + 58));
        }
    }

    @Override
    public @NotNull ItemStack quickMoveStack(@SuppressWarnings("null") @NotNull Player player, int index) {
        ItemStack itemStack = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot != null && slot.hasItem()) {
            ItemStack originalStack = slot.getItem();
            itemStack = originalStack.copy();

            if (index < PERSONA_INV_END_INDEX) {
                if (!this.moveItemStackTo(originalStack, PLAYER_INV_START_INDEX, PLAYER_INV_END_INDEX, true)) {
                    return ItemStack.EMPTY;
                }
            } else if (!this.moveItemStackTo(originalStack, 0, PERSONA_INV_END_INDEX, false)) {
                return ItemStack.EMPTY;
            }

            if (originalStack.isEmpty()) {
                slot.set(ItemStack.EMPTY);
            } else {
                slot.setChanged();
            }
        }
        return itemStack;
    }

    @Override
    public boolean stillValid(@SuppressWarnings("null") @NotNull Player player) {
        return this.persona.isAlive() && player.distanceToSqr(this.persona) <= 64.0;
    }

    @Override
    public void removed(@SuppressWarnings("null") @NotNull Player player) {
        super.removed(player);
        this.personaInventory.stopOpen(player);
        this.persona.stopViewing();
    }

    @Override
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public @NotNull InventoryView getBukkitView() {
        if (this.bukkitView == null) {
            CraftInventory inventory = new CraftInventory(this.personaInventory);
            this.bukkitView = new CraftInventoryView(this.player.getBukkitEntity(), inventory, this);
        }
        return this.bukkitView;
    }
}