package com.ionsignal.minecraft.ioncore.network;

import com.ionsignal.minecraft.ioncore.network.model.IonEvent;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.DatabindContext;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.jsontype.impl.TypeIdResolverBase;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Custom Jackson TypeIdResolver for IonEvent polymorphism.
 */
public class IonEventTypeResolver extends TypeIdResolverBase {
    private static final Map<String, Class<? extends IonEvent>> typeRegistry = new ConcurrentHashMap<>();

    /**
     * Registers a concrete event class with its type discriminator.
     * 
     * @param typeId
     *            The event type string (e.g., "event:persona:state")
     * @param eventClass
     *            The concrete class implementing IonEvent
     */
    public static void registerType(String typeId, Class<? extends IonEvent> eventClass) {
        Class<? extends IonEvent> existing = typeRegistry.putIfAbsent(typeId, eventClass);
        if (existing != null && !existing.equals(eventClass)) {
            throw new IllegalArgumentException(
                    "Type ID collision: '" + typeId + "' is already mapped to " +
                            existing.getName() + ", cannot remap to " + eventClass.getName());
        }
    }

    @Override
    public String idFromValue(Object value) {
        if (value instanceof IonEvent event) {
            return event.type();
        }
        return null;
    }

    @Override
    public String idFromValueAndType(Object value, Class<?> suggestedType) {
        return idFromValue(value);
    }

    @Override
    public JavaType typeFromId(DatabindContext context, String id) {
        Class<? extends IonEvent> eventClass = typeRegistry.get(id);
        if (eventClass == null) {
            return null;
        }
        return context.constructType(eventClass);
    }

    @Override
    public JsonTypeInfo.Id getMechanism() {
        return JsonTypeInfo.Id.CUSTOM;
    }
}