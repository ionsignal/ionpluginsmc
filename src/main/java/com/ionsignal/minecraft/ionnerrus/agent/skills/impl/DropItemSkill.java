package com.ionsignal.minecraft.ionnerrus.agent.skills.impl;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.skills.Skill;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Item;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * A skill to remove a specified quantity of an item from the agent's inventory
 * and drop it into the world in front of the agent.
 */
public class DropItemSkill implements Skill<Boolean> {
    private final Material material;
    private final int quantity;
    private final LivingEntity target;

    public DropItemSkill(Material material, int quantity, LivingEntity target) {
        this.material = material;
        this.quantity = quantity;
        this.target = target;
    }

    @Override
    public CompletableFuture<Boolean> execute(NerrusAgent agent) {
        return CompletableFuture.supplyAsync(() -> {
            PlayerInventory inventory = agent.getPersona().getInventory();
            if (inventory == null || !inventory.contains(material, quantity)) {
                return false;
            }
            // Remove the items from inventory
            Map<Integer, ? extends ItemStack> removed = inventory.removeItem(new ItemStack(material, quantity));
            if (removed.isEmpty()) {
                // Successfully removed the exact amount
                World world = agent.getPersona().getLocation().getWorld();
                if (world != null) {
                    // Define the drop location and the item to be dropped.
                    Location eyeLocation = agent.getPersona().getPersonaEntity().getBukkitEntity().getEyeLocation();
                    Location dropLocation = eyeLocation.clone().add(eyeLocation.getDirection().multiply(0.75));
                    ItemStack itemToDrop = new ItemStack(material, quantity);
                    Item droppedItem = world.dropItem(dropLocation, itemToDrop);
                    if (target != null && target.isValid()) {
                        Location targetFeetLocation = target.getLocation();
                        double distance = dropLocation.distance(targetFeetLocation);
                        // This calculation creates a gentle lob that scales with distance.
                        Vector velocity = targetFeetLocation.toVector().subtract(dropLocation.toVector());
                        velocity.normalize().multiply(Math.max(0.3, distance * 0.15)); // Scale horizontal speed by distance
                        velocity.setY(velocity.getY() + 0.25); // Add a consistent upward arc
                        droppedItem.setVelocity(velocity);
                    } else {
                        // Fallback to old behavior if target is invalid
                        Vector tossVelocity = eyeLocation.getDirection().multiply(0.1).add(new Vector(0, 0.2, 0));
                        droppedItem.setVelocity(tossVelocity);
                    }

                    droppedItem.setPickupDelay(10); // Give the player a moment to react
                    return true;
                }
            } else {
                // This case handles if removeItem couldn't remove the full amount, which shouldn't happen due to
                // our earlier check, but is good for safety so we add back the items it did manage to remove.
                for (ItemStack item : removed.values()) {
                    inventory.addItem(item);
                }
            }
            return false;
        }, IonNerrus.getInstance().getMainThreadExecutor());
    }
}