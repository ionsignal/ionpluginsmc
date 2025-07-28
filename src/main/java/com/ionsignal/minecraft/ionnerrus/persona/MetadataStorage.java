package com.ionsignal.minecraft.ionnerrus.persona;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class MetadataStorage {
    private final Map<String, Object> metadata = new ConcurrentHashMap<>();
    private final Map<String, Object> persistentMetadata = new ConcurrentHashMap<>();

    public void set(String key, Object value) {
        metadata.put(key, value);
    }

    public void setPersistent(String key, Object value) {
        persistentMetadata.put(key, value);
    }

    public boolean has(String key) {
        return metadata.containsKey(key) || persistentMetadata.containsKey(key);
    }

    public <T> Optional<T> get(String key, Class<T> type) {
        Object value = metadata.get(key);
        if (value == null) {
            value = persistentMetadata.get(key);
        }

        if (type.isInstance(value)) {
            return Optional.of(type.cast(value));
        }
        return Optional.empty();
    }

    public void remove(String key) {
        metadata.remove(key);
        persistentMetadata.remove(key);
    }
}