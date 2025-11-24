package com.ionsignal.minecraft.ionnerrus.compatibility.impl;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.compatibility.CraftEngineService;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.logging.Level;

/**
 * Concrete implementation of CraftEngineService using Pure Reflection.
 * 
 * Updated to target 'onlineUsers' map or use 'addFakePlayer' method to ensure
 * BukkitAdaptors.adapt(player) returns a valid instance.
 */
public class CraftEngineServiceImpl implements CraftEngineService {
    // Reflected Classes
    private Class<?> bukkitCraftEngineClass;

    // Instance References
    private Object craftEngineInstance;
    private Object networkManagerInstance;

    // Public Methods
    private Method addFakePlayerMethod;
    private Method removeFakePlayerMethod;

    private boolean isInitialized = false;

    public CraftEngineServiceImpl() {
        try {
            initializeReflection();
            isInitialized = true;
            IonNerrus.getInstance().getLogger().info("CraftEngine compatibility layer initialized.");
        } catch (Exception e) {
            IonNerrus.getInstance().getLogger().log(Level.WARNING,
                    "Failed to reflectively initialize CraftEngine. Custom block interactions may fail.", e);
            isInitialized = false;
        }
    }

    private void initializeReflection() throws ReflectiveOperationException {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("CraftEngine");
        if (plugin == null)
            throw new IllegalStateException("CraftEngine plugin not found");
        // Load Core Classes
        this.bukkitCraftEngineClass = Class.forName("net.momirealms.craftengine.bukkit.plugin.BukkitCraftEngine");
        // Get Singleton Instance
        Method instanceMethod = bukkitCraftEngineClass.getMethod("instance");
        this.craftEngineInstance = instanceMethod.invoke(null);
        // Get NetworkManager Instance
        Method networkManagerMethod = bukkitCraftEngineClass.getMethod("networkManager");
        this.networkManagerInstance = networkManagerMethod.invoke(craftEngineInstance);
        // Try to find addFakePlayer/removeFakePlayer methods
        try {
            this.addFakePlayerMethod = networkManagerInstance.getClass().getMethod("addFakePlayer", Player.class);
            this.removeFakePlayerMethod = networkManagerInstance.getClass().getMethod("removeFakePlayer", Player.class);
            IonNerrus.getInstance().getLogger().info("CraftEngine: Using addFakePlayer/removeFakePlayer API.");
            return; // Success, stop here
        } catch (NoSuchMethodException e) {
            IonNerrus.getInstance().getLogger().info("CraftEngine: Public API not found, falling back to field injection.");
        }
    }

    @Override
    public void registerPersona(Player personaEntity) {
        if (!isInitialized)
            return;
        try {
            if (addFakePlayerMethod != null) {
                addFakePlayerMethod.invoke(networkManagerInstance, personaEntity);
                return;
            }
        } catch (Exception e) {
            IonNerrus.getInstance().getLogger().log(Level.WARNING,
                    "Failed to register Persona with CraftEngine (" + personaEntity.getName() + ")", e);
        }
    }

    @Override
    public void unregisterPersona(Player personaEntity) {
        if (!isInitialized)
            return;
        try {
            if (removeFakePlayerMethod != null) {
                removeFakePlayerMethod.invoke(networkManagerInstance, personaEntity);
                return;
            }
        } catch (Exception e) {
            IonNerrus.getInstance().getLogger().log(Level.WARNING,
                    "Failed to unregister Persona from CraftEngine (" + personaEntity.getName() + ")", e);
        }
    }
}