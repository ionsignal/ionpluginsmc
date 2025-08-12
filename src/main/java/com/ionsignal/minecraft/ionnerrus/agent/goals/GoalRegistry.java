package com.ionsignal.minecraft.ionnerrus.agent.goals;

import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class GoalRegistry {

    private final Map<String, GoalDefinition> definitions = new HashMap<>();

    public void register(GoalDefinition definition) {
        definitions.put(definition.name().toUpperCase(), definition);
    }

    @Nullable
    public GoalDefinition get(String name) {
        return definitions.get(name.toUpperCase());
    }

    public Collection<GoalDefinition> getAll() {
        return Collections.unmodifiableCollection(definitions.values());
    }
}