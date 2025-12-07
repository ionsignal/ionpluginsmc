package com.ionsignal.minecraft.ionnerrus.network.messages;

import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.List;

public record InventoryUpdate(
        String personaId,
        List<ItemSlot> items) {

    public record ItemSlot(int slot, String material, int amount, String displayName) {
    }

    public static InventoryUpdate from(NerrusAgent agent) {
        List<ItemSlot> slots = new ArrayList<>();
        PlayerInventory inv = agent.getPersona().getInventory();

        if (inv != null) {
            for (int i = 0; i < inv.getSize(); i++) {
                ItemStack stack = inv.getItem(i);
                if (stack != null && !stack.getType().isAir()) {
                    String displayName = null;
                    if (stack.hasItemMeta()) {
                        var meta = stack.getItemMeta();
                        if (meta.hasDisplayName()) {
                            displayName = PlainTextComponentSerializer.plainText().serialize(meta.displayName());
                        }
                    }
                    slots.add(new ItemSlot(i, stack.getType().name(), stack.getAmount(), displayName));
                }
            }
        }
        return new InventoryUpdate(agent.getPersona().getUniqueId().toString(), slots);
    }
}