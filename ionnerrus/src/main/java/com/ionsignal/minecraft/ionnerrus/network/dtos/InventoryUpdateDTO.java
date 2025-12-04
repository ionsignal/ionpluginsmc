package com.ionsignal.minecraft.ionnerrus.network.dtos;

import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import java.util.ArrayList;
import java.util.List;

public record InventoryUpdateDTO(
    String personaId,
    List<ItemSlot> items
) {
    public record ItemSlot(int slot, String material, int amount, String displayName) {}

    public static InventoryUpdateDTO from(NerrusAgent agent) {
        List<ItemSlot> slots = new ArrayList<>();
        PlayerInventory inv = agent.getPersona().getInventory();
        
        if (inv != null) {
            for (int i = 0; i < inv.getSize(); i++) {
                ItemStack is = inv.getItem(i);
                if (is != null && !is.getType().isAir()) {
                    slots.add(new ItemSlot(
                        i,
                        is.getType().name(),
                        is.getAmount(),
                        is.hasItemMeta() && is.getItemMeta().hasDisplayName() 
                            ? is.getItemMeta().getDisplayName() 
                            : null
                    ));
                }
            }
        }
        return new InventoryUpdateDTO(agent.getPersona().getUniqueId().toString(), slots);
    }
}