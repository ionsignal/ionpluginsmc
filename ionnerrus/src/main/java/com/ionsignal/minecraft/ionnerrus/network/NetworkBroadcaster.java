package com.ionsignal.minecraft.ionnerrus.network;

import com.ionsignal.minecraft.ioncore.IonCore;
import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.network.schema.Outgoing;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.bukkit.inventory.ItemStack; 
import org.bukkit.inventory.PlayerInventory;

/**
 * Handles the broadcasting of DISCRETE EVENTS to the IonCore network layer.
 */
public class NetworkBroadcaster {
    @SuppressWarnings("unused")
    private final IonNerrus plugin;
    private static final UUID NIL_UUID = UUID.fromString("00000000-0000-0000-0000-000000000000");

    public NetworkBroadcaster(IonNerrus plugin) {
        this.plugin = plugin;
    }

    private UUID resolveId(NerrusAgent agent) {
        UUID id = agent.getPersona().getDefinitionId();
        return id != null ? id : NIL_UUID;
    }

    public void broadcastInventory(NerrusAgent agent) {
        if (!agent.getPersona().isSpawned()) return;
        
        List<String> simpleItems = new ArrayList<>();
        PlayerInventory inv = agent.getPersona().getInventory();
        if (inv != null) {
            for (ItemStack item : inv.getStorageContents()) {
                if (item != null && !item.getType().isAir()) {
                    simpleItems.add(item.getAmount() + "x " + item.getType().name());
                }
            }
        }

        Outgoing.InventoryUpdate update = new Outgoing.InventoryUpdate(
            resolveId(agent),
            simpleItems
        );
        
        IonCore.getInstance().getServiceContainer().getEventBus().broadcast("AGENT_INVENTORY", update);
    }

    public void broadcastGoalEvent(NerrusAgent agent, String eventType, String goalName, String message) {
        if (!agent.getPersona().isSpawned()) return;
        
        Outgoing.GoalEvent event = new Outgoing.GoalEvent(
            resolveId(agent),
            goalName,
            eventType // e.g. "COMPLETED", "FAILED"
        );
        
        IonCore.getInstance().getServiceContainer().getEventBus().broadcast("AGENT_GOAL_EVENT", event);
    }
}