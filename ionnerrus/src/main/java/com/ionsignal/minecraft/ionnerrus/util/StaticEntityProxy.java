package com.ionsignal.minecraft.ionnerrus.util;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.UUID;

/**
 * A utility to create a dynamic proxy of a Bukkit Entity that represents a static location.
 * This allows systems requiring an Entity target (like Navigator.engageOn) to work with static
 * points.
 */
public class StaticEntityProxy implements InvocationHandler {
    private final Location location;
    private final UUID uuid;

    private StaticEntityProxy(Location location) {
        this.location = location;
        this.uuid = UUID.randomUUID();
    }

    public static Entity create(Location location) {
        return (Entity) Proxy.newProxyInstance(
                Entity.class.getClassLoader(),
                new Class[] { Entity.class },
                new StaticEntityProxy(location));
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String name = method.getName();
        switch (name) {
            case "getLocation":
                // Return a clone to prevent mutation of our internal state
                return location.clone();
            case "getUniqueId":
                return uuid;
            case "isValid":
            case "isDead":
                // Always valid, never dead
                return name.equals("isValid");
            case "getHeight":
                return 0.0; // Point has no height
            case "getWidth":
                return 0.0;
            case "getType":
                return EntityType.UNKNOWN;
            case "getEntityId":
                return -1;
            case "toString":
                return "StaticEntityProxy{loc=" + location + "}";
            case "equals":
                return args[0] != null && args[0] == proxy;
            case "hashCode":
                return uuid.hashCode();
            default:
                // Return defaults for primitives to avoid NPEs, null for objects
                if (method.getReturnType() == boolean.class)
                    return false;
                if (method.getReturnType() == int.class)
                    return 0;
                if (method.getReturnType() == double.class)
                    return 0.0;
                if (method.getReturnType() == float.class)
                    return 0.0f;
                return null;
        }
    }
}