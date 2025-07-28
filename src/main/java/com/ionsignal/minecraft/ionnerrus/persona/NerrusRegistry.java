package com.ionsignal.minecraft.ionnerrus.persona;

import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class NerrusRegistry {

    private final NerrusManager manager;
    private final Map<UUID, Persona> personaMap = new ConcurrentHashMap<>();
    private final Map<Integer, Persona> entityIdMap = new ConcurrentHashMap<>();

    public NerrusRegistry(NerrusManager manager) {
        this.manager = manager;
    }

    public Persona createPersona(EntityType type, String name) {
        UUID uuid = UUID.randomUUID();
        Persona persona = new Persona(manager, uuid, name, type);
        personaMap.put(uuid, persona);
        return persona;
    }

    public void register(Persona persona) {
        personaMap.put(persona.getUniqueId(), persona);
        if (persona.isSpawned()) {
            entityIdMap.put(persona.getEntity().getEntityId(), persona);
        }
    }

    public void deregister(Persona persona) {
        if (persona == null)
            return;
        deregister(persona.getUniqueId());
    }

    public void deregister(UUID uuid) {
        Persona persona = personaMap.remove(uuid);
        if (persona != null) {
            if (persona.isSpawned()) {
                entityIdMap.remove(persona.getEntity().getEntityId());
                persona.despawn();
            }
        }
    }

    public void updateEntityId(Persona persona, int oldId) {
        if (oldId != -1) {
            entityIdMap.remove(oldId);
        }
        if (persona.isSpawned()) {
            entityIdMap.put(persona.getEntity().getEntityId(), persona);
        }
    }

    public Optional<Persona> getByUuid(UUID uuid) {
        return Optional.ofNullable(personaMap.get(uuid));
    }

    public Optional<Persona> getByEntity(Entity entity) {
        if (entity == null)
            return Optional.empty();
        return Optional.ofNullable(entityIdMap.get(entity.getEntityId()));
    }

    public Optional<Persona> findByName(String name) {
        return personaMap.values().stream()
                .filter(p -> p.getName().equalsIgnoreCase(name))
                .findFirst();
    }

    public Collection<Persona> getAll() {
        return Collections.unmodifiableCollection(personaMap.values());
    }

    public void clear() {
        getAll().forEach(this::deregister);
        personaMap.clear();
        entityIdMap.clear();
    }
}