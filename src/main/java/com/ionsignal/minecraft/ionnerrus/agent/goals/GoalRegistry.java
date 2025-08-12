package com.ionsignal.minecraft.ionnerrus.agent.goals;

import com.ionsignal.minecraft.ionnerrus.agent.llm.tool.ToolDefinition;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class GoalRegistry {
    private final Map<String, ToolDefinition> definitions = new HashMap<>();

    public void register(ToolDefinition definition) {
        definitions.put(definition.name().toUpperCase(), definition);
    }

    @Nullable
    public ToolDefinition get(String name) {
        return definitions.get(name.toUpperCase());
    }

    public Collection<ToolDefinition> getAll() {
        return Collections.unmodifiableCollection(definitions.values());
    }
}