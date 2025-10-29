package com.ionsignal.minecraft.ionnerrus.terra;

import com.ionsignal.minecraft.ionnerrus.terra.config.JigsawPoolTemplate;
import com.ionsignal.minecraft.ionnerrus.terra.config.JigsawPoolType;
import com.ionsignal.minecraft.ionnerrus.terra.config.JigsawStructureType;
import com.ionsignal.minecraft.ionnerrus.terra.generation.debug.DebugCommand;
import com.ionsignal.minecraft.ionnerrus.terra.generation.debug.DebugRenderTask;

import com.dfsek.terra.addons.manifest.api.AddonInitializer;
import com.dfsek.terra.api.Platform;
import com.dfsek.terra.api.addon.BaseAddon;
import com.dfsek.terra.api.event.events.config.pack.ConfigPackPreLoadEvent;
import com.dfsek.terra.api.event.functional.FunctionalEventHandler;
import com.dfsek.terra.api.inject.annotations.Inject;
import com.dfsek.terra.api.registry.key.RegistryKey;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Entry point for the IonNerrus Terra addon.
 */
public class NerrusTerraAddon implements AddonInitializer, Listener {
	private static final Logger LOGGER = LoggerFactory.getLogger(NerrusTerraAddon.class);
	private final DebugCommand debugCommand = new DebugCommand();
	private static Plugin terraPlugin;
	private Command registeredCommand;
	private DebugRenderTask renderTask;

	@Inject
	private Platform platform;

	@Inject
	private BaseAddon addon;

	@Override
	public void initialize() {
		LOGGER.info("Initializing IonNerrus Terra Addon v{}", addon.getVersion().getFormatted());
		Plugin plugin = Bukkit.getPluginManager().getPlugin("Terra");
		if (plugin != null) {
			NerrusTerraAddon.terraPlugin = plugin;
			Bukkit.getPluginManager().registerEvents(this, terraPlugin);
			LOGGER.info("Registered Bukkit event listeners for session lifecycle management.");
		} else {
			LOGGER.error("Could not find the Terra plugin instance! The IonNerrus addon will not function correctly.");
			return;
		}
		// Command registration
		this.registeredCommand = new Command("debug", "Debugs jigsaw structure generation.", "/ionnerrus:debug <subcommand>",
				List.of("jdb")) {
			@Override
			public boolean execute(@NotNull CommandSender sender, @NotNull String commandLabel, @NotNull String[] args) {
				return debugCommand.onCommand(sender, this, commandLabel, args);
			}
		};
		registeredCommand.setPermission("ionnerrus.debug");
		getCommandMap().register("ionnerrus", registeredCommand);
		LOGGER.info("Registered /ionnerrus:debug command.");
		// Start the debug render task
		renderTask = new DebugRenderTask(DebugCommand.getActiveDebugTasksReference());
		renderTask.runTaskTimer(terraPlugin, 0L, 1L);
		LOGGER.info("Started debug render task for visualization updates.");
		// Register our custom ConfigTypes with each pack as it loads
		FunctionalEventHandler eventHandler = platform.getEventManager().getHandler(FunctionalEventHandler.class);
		eventHandler.register(addon, ConfigPackPreLoadEvent.class)
				.priority(100)
				.then(event -> {
					LOGGER.info("Registering jigsaw ConfigTypes for pack.");
					try {
						event.getPack().registerConfigType(new JigsawPoolType(platform), RegistryKey.of("ionnerrus", "jigsaw_pool"), 100);
						event.getPack().registerConfigType(new JigsawStructureType(event.getPack()),
								RegistryKey.of("ionnerrus", "jigsaw_structure"), 100);
						event.getPack().applyLoader(JigsawPoolTemplate.PoolElement.class, JigsawPoolTemplate.PoolElement::new);
					} catch (Exception e) {
						LOGGER.error("Failed to register jigsaw ConfigTypes for pack.", e);
					}
				})
				.global();
		LOGGER.info("IonNerrus Terra Addon initialized successfully");
	}

	@EventHandler
	public void onPlayerQuit(PlayerQuitEvent event) {
		DebugCommand.clearActiveSession(event.getPlayer().getUniqueId());
	}

	@EventHandler
	public void onPluginDisable(PluginDisableEvent event) {
		if (event.getPlugin().equals(terraPlugin)) {
			LOGGER.info("Terra is disabling. Cleaning up all debug sessions and commands...");
			DebugCommand.clearAllSessions();
			if (renderTask != null) {
				renderTask.cancel();
			}
			unregisterCommand();
		}
	}

	/**
	 * Unregisters the command using the modern Bukkit API, removing all reflection.
	 */
	private void unregisterCommand() {
		if (registeredCommand != null) {
			if (registeredCommand.unregister(getCommandMap())) {
				LOGGER.info("Successfully unregistered /ionnerrus:debug command.");
			} else {
				LOGGER.error("Failed to unregister /ionnerrus:debug command.");
			}
		}
	}

	/**
	 * Provides safe access to the Terra plugin instance for Bukkit scheduling and events.
	 *
	 * @return The Terra plugin instance.
	 * @throws IllegalStateException
	 *             if the Terra plugin is not loaded.
	 */
	public static Plugin getTerraPlugin() {
		if (terraPlugin == null) {
			throw new IllegalStateException("The Terra plugin is not loaded, but the IonNerrus addon requires it.");
		}
		return terraPlugin;
	}

	private CommandMap getCommandMap() {
		return Bukkit.getServer().getCommandMap();
	}
}