package com.ionsignal.minecraft.ionnerrus.terra;

import com.dfsek.terra.addons.manifest.api.AddonInitializer;
import com.dfsek.terra.api.addon.BaseAddon;
import com.dfsek.terra.api.event.events.config.pack.ConfigPackPreLoadEvent;
import com.dfsek.terra.api.event.events.config.pack.ConfigPackPostLoadEvent; // ADD THIS IMPORT
import com.dfsek.terra.api.event.functional.FunctionalEventHandler;
import com.dfsek.terra.api.inject.annotations.Inject;
import com.dfsek.terra.api.Platform;
import com.dfsek.terra.api.registry.key.RegistryKey;
import com.ionsignal.minecraft.ionnerrus.terra.config.JigsawPoolTemplate;
import com.ionsignal.minecraft.ionnerrus.terra.config.JigsawPoolType;
import com.ionsignal.minecraft.ionnerrus.terra.config.JigsawStructureType;
import com.dfsek.terra.api.registry.Registry; // ADD THIS IMPORT
import com.dfsek.terra.api.structure.Structure; // ADD THIS IMPORT

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This is the entry point for the IonNerrus Terra addon.
 * 
 * This class is instantiated by Terra's manifest addon loader during Terra's initialization, which
 * happens very early in the server startup sequence (during STARTUP phase). This timing is crucial
 * - it allows us to register custom ConfigTypes BEFORE Terra loads any configuration packs.
 * 
 * The initialize() method is called after Terra has set up its event system but before
 * it starts loading config packs. This is the correct place to:
 * 1. Register custom ConfigTypes (jigsaw_structure, jigsaw_pool)
 * 2. Set up event listeners for pack loading
 * 3. Perform any other Terra-specific initialization
 * 
 */
public class NerrusTerraAddon implements AddonInitializer {
	private static final Logger LOGGER = LoggerFactory.getLogger(NerrusTerraAddon.class);

	@Inject
	private Platform platform;

	@Inject
	private BaseAddon addon;

	/**
	 * Called by Terra's addon loader during initialization.
	 * This is the correct place to register custom ConfigTypes.
	 */
	@Override
	public void initialize() {
		LOGGER.info("Initializing IonNerrus Terra Addon v{}", addon.getVersion().getFormatted());
		// Register our custom ConfigTypes with each pack as it loads
		FunctionalEventHandler eventHandler = platform.getEventManager()
				.getHandler(FunctionalEventHandler.class);
		eventHandler.register(addon, ConfigPackPreLoadEvent.class)
				.priority(100) // High priority to ensure we register before other addons
				.then(event -> {
					LOGGER.info("Registering jigsaw ConfigTypes");
					try {
						// Register the jigsaw_pool ConfigType
						event.getPack().registerConfigType(new JigsawPoolType(platform), RegistryKey.of("ionnerrus", "jigsaw_pool"), 100);
						// Register the jigsaw_structure ConfigType
						event.getPack().registerConfigType(new JigsawStructureType(event.getPack()),
								RegistryKey.of("ionnerrus", "jigsaw_structure"), 100);
						// Explicitly tell Tectonic how to load the nested PoolElement class.
						event.getPack().applyLoader(JigsawPoolTemplate.PoolElement.class, JigsawPoolTemplate.PoolElement::new);
						LOGGER.info("Successfully registered jigsaw ConfigTypes for pack");
					} catch (Exception e) {
						LOGGER.error("Failed to register jigsaw ConfigTypes for pack");
					}
				})
				.global(); // Apply to all packs
		// --- ADD THIS BLOCK FOR DEBUGGING ---
		eventHandler.register(addon, ConfigPackPostLoadEvent.class)
				.then(event -> {
					// *** PLACE YOUR BREAKPOINT ON THE LINE BELOW ***
					Registry<Structure> structureRegistry = event.getPack().getRegistry(Structure.class);
					LOGGER.info("--- INSPECTING STRUCTURE REGISTRY FOR PACK: {} ---", event.getPack().getID());
					structureRegistry.forEach(structure -> {
						LOGGER.info("Found Structure: Class = {}",
								structure.getClass().getName());
					});
					LOGGER.info("--- FINISHED INSPECTION ---");
				})
				.global();
		// --- END OF DEBUGGING BLOCK ---
		LOGGER.info("IonNerrus Terra Addon initialized successfully");
	}
}