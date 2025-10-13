package com.ionsignal.minecraft.ionnerrus.agent;

public final class BlackboardKeys {
    private BlackboardKeys() {
    }

    // General
    public static final String ISSUE = "issue";
    public static final String TARGET_LOCATION = "targetLocation";

    // Crafting
    public static final String CRAFTING_TABLE_LOCATION = "crafting.tableLocation";
    public static final String CRAFTING_CONTEXT = "crafting.context";
    public static final String CRAFTING_BLUEPRINT = "crafting.blueprint";
    public static final String CRAFTING_EXECUTION_PLAN = "crafting.executionPlan";
    public static final String NEXT_PREREQUISITE = "crafting.nextPrerequisite";
    public static final String MATERIALS_ACQUIRED = "crafting.materialsAcquired";

    // Task-specific results
    public static final String GATHER_BLOCK_RESULT = "gatherBlocks.result";
    public static final String GATHER_CURRENT_COUNT = "gather.currentCount";
    public static final String FIND_AREA_RESULT = "findArea.result";
}