package com.ionsignal.minecraft.ionnerrus.terra.config;

import com.dfsek.tectonic.api.config.template.annotations.Default;
import com.dfsek.tectonic.api.config.template.annotations.Value;
import com.dfsek.tectonic.api.config.template.object.ObjectTemplate;
import com.dfsek.terra.api.config.AbstractableTemplate;
import com.dfsek.terra.api.config.meta.Meta;

import java.util.List;

public class JigsawPoolTemplate implements AbstractableTemplate {

    @Value("id")
    private @Meta String id;

    @Value("elements")
    private @Meta List<PoolElement> elements;

    @Default
    @Value("fallback")
    private @Meta String fallback = "minecraft:empty";

    @Override
    public String getID() {
        return id;
    }

    public List<PoolElement> getElements() {
        return elements;
    }

    public String getFallback() {
        return fallback;
    }

    public static class PoolElement implements ObjectTemplate<PoolElement> {
        @Value("structure")
        private String structure;

        @Default
        @Value("weight")
        private int weight = 1;

        @Default
        @Value("min-count")
        private int minCount = 0;

        @Default
        @Value("max-count")
        private int maxCount = Integer.MAX_VALUE;

        public String getStructure() {
            return structure;
        }

        public int getWeight() {
            return weight;
        }

        public int getMinCount() {
            return minCount;
        }

        public int getMaxCount() {
            return maxCount;
        }

        @Override
        public PoolElement get() {
            return this;
        }
    }
}