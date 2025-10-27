package com.ionsignal.minecraft.ionnerrus.terra.generation.enforcement;

/**
 * Defines how min/max piece count constraints are enforced during generation.
 * 
 * STRICT: Fail generation if constraints cannot be met. Use for critical structures.
 * BEST_EFFORT: Place as many pieces as possible, log warnings if constraints unmet.
 * FLEXIBLE: Adjust constraints dynamically based on available space/connections.
 */
public enum EnforcementStrategy {
	/**
	 * Strict enforcement - generation fails if constraints cannot be met.
	 * Use when structure integrity depends on specific piece counts.
	 */
	STRICT("strict"),

	/**
	 * Best-effort - attempt to meet constraints but don't fail if impossible.
	 * Use for optional elements or decorative pieces.
	 * This is the recommended default for backward compatibility.
	 */
	BEST_EFFORT("best_effort"),

	/**
	 * Flexible - dynamically adjust constraints based on generation context.
	 * Use for adaptive structures that should fit available space.
	 */
	FLEXIBLE("flexible");

	private final String configValue;

	EnforcementStrategy(String configValue) {
		this.configValue = configValue;
	}

	public String getConfigValue() {
		return configValue;
	}

	/**
	 * Parses a strategy from configuration string.
	 * Defaults to BEST_EFFORT if unknown value.
	 */
	public static EnforcementStrategy fromConfig(String value) {
		if (value == null || value.isEmpty()) {
			return BEST_EFFORT; // Default for backward compatibility
		}
		for (EnforcementStrategy strategy : values()) {
			if (strategy.configValue.equalsIgnoreCase(value)) {
				return strategy;
			}
		}
		// Unknown value - log warning and use default
		java.util.logging.Logger.getLogger(EnforcementStrategy.class.getName())
				.warning("Unknown enforcement strategy: " + value + ", using best_effort");
		return BEST_EFFORT;
	}
}