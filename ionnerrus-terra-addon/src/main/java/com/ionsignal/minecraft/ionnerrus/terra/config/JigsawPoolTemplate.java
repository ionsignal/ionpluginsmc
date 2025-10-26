package com.ionsignal.minecraft.ionnerrus.terra.config;

import com.dfsek.tectonic.api.config.template.annotations.Default;
import com.dfsek.tectonic.api.config.template.annotations.Value;
import com.dfsek.tectonic.api.config.template.object.ObjectTemplate;
import com.dfsek.terra.api.config.AbstractableTemplate;
import com.dfsek.terra.api.config.meta.Meta;

import java.util.List;

/**
 * Terra configuration template for jigsaw structure pools.
 * A pool contains weighted structure pieces that can be selected during generation.
 */
public class JigsawPoolTemplate implements AbstractableTemplate {

	@Value("id")
	private @Meta String id;

	@Value("elements")
	private @Meta List<PoolElement> elements;

	@Value("fallback")
	@Default
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

	/**
	 * Represents a weighted element in the structure pool.
	 */
	public static class PoolElement implements ObjectTemplate<PoolElement> {
		@Value("file")
		private String file;

		@Value("weight")
		@Default
		private int weight = 1;

		@Value("min-count")
		@Default
		private int minCount = 0;

		@Value("max-count")
		@Default
		private int maxCount = Integer.MAX_VALUE;

		public String getFile() {
			return file;
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