package com.ionsignal.minecraft.ionnerrus.agent;

public final class BlackboardKeys {
    private BlackboardKeys() {
    }

    // General
    public static final String TARGET_LOCATION = "targetLocation";
    public static final String ISSUE = "issue";

    // Task-specific results
    public static final String GATHER_BLOCK_RESULT = "gatherBlocks.result";
    public static final String GATHER_CURRENT_COUNT = "gather.currentCount";
    public static final String FIND_AREA_RESULT = "findArea.result";
}