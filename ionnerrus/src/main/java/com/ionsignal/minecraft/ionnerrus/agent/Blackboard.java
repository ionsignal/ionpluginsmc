package com.ionsignal.minecraft.ionnerrus.agent;

import org.bukkit.Location;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class Blackboard {
    private final Map<String, Object> data = new HashMap<>();

    public void put(String key, Object value) {
        data.put(key, value);
    }

    public Object get(String key) {
        return data.get(key);
    }

    public <T> Optional<T> get(String key, Class<T> type) {
        Object value = data.get(key);
        if (type.isInstance(value)) {
            return Optional.of(type.cast(value));
        }
        return Optional.empty();
    }

    public Optional<Location> getLocation(String key) {
        return get(key, Location.class);
    }

    public int getInt(String key, int defaultValue) {
        return get(key, Integer.class).orElse(defaultValue);
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        return get(key, Boolean.class).orElse(defaultValue);
    }

    public <T extends Enum<T>> Optional<T> getEnum(String key, Class<T> enumType) {
        return get(key, enumType);
    }

    public boolean has(String key) {
        return data.containsKey(key);
    }

    public void clear() {
        data.clear();
    }

    public Object remove(String key) {
        return data.remove(key);
    }
}