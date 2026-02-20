package com.ionsignal.minecraft.ioncore.network;

import com.ionsignal.minecraft.ioncore.network.model.IonCommand;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.DatabindContext;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.jsontype.impl.TypeIdResolverBase;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Custom Jackson TypeIdResolver for IonCommand polymorphism.
 */
public class IonCommandTypeResolver extends TypeIdResolverBase {
    private static final Map<String, Class<? extends IonCommand>> typeRegistry = new ConcurrentHashMap<>();

    /**
     * Registers a concrete command class with its type discriminator.
     * Called by plugin implementations (e.g., IonNerrus) during initialization.
     * 
     * @param typeId
     *            The command type string (e.g., "command:persona:spawn")
     * @param commandClass
     *            The concrete class implementing IonCommand
     * @throws IllegalArgumentException
     *             if typeId is already registered
     */
    public static void registerType(String typeId, Class<? extends IonCommand> commandClass) {
        Class<? extends IonCommand> existing = typeRegistry.putIfAbsent(typeId, commandClass);
        if (existing != null && !existing.equals(commandClass)) {
            throw new IllegalArgumentException(
                    "Type ID collision: '" + typeId + "' is already mapped to " +
                            existing.getName() + ", cannot remap to " + commandClass.getName());
        }
    }

    @Override
    public String idFromValue(Object value) {
        if (value instanceof IonCommand) {
            return ((IonCommand) value).type();
        }
        return null;
    }

    @Override
    public String idFromValueAndType(Object value, Class<?> suggestedType) {
        return idFromValue(value);
    }

    @Override
    public JavaType typeFromId(DatabindContext context, String id) {
        Class<? extends IonCommand> commandClass = typeRegistry.get(id);
        if (commandClass == null) {
            // Unknown command type - return null (Jackson will handle gracefully)
            return null;
        }
        return context.constructType(commandClass);
    }

    @Override
    public JsonTypeInfo.Id getMechanism() {
        return JsonTypeInfo.Id.CUSTOM;
    }
}