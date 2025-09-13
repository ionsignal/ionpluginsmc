package com.ionsignal.minecraft.ionnerrus.agent.goals;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.AgentService;
import com.ionsignal.minecraft.ionnerrus.agent.content.BlockTagManager;

import org.reflections.Reflections;
import org.reflections.scanners.Scanners;
import org.reflections.util.ConfigurationBuilder;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Discovers and registers all GoalProvider implementations at startup.
 */
public class GoalRegistrar {
    private final Logger logger;
    private final GoalRegistry goalRegistry;
    private final BlockTagManager blockTagManager;

    public GoalRegistrar(GoalRegistry goalRegistry, BlockTagManager blockTagManager) {
        this.logger = IonNerrus.getInstance().getLogger();
        this.goalRegistry = goalRegistry;
        this.blockTagManager = blockTagManager;
    }

    /**
     * Scans the plugin's classpath for all classes that implement GoalProvider,
     * instantiates them, and registers their ToolDefinitions.
     */
    public void registerAll() {
        logger.info("Discovering GoalProviders...");
        try {
            // Use direct URL to JAR file rather than relying on ClasspathHelper within a shadowed plugin.
            ClassLoader pluginClassLoader = IonNerrus.getInstance().getClass().getClassLoader();
            File pluginFile = new File(IonNerrus.getInstance().getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
            ConfigurationBuilder config = new ConfigurationBuilder()
                    .setUrls(pluginFile.toURI().toURL())
                    .setScanners(Scanners.SubTypes.filterResultsBy(s -> true)) // Ensure all subtypes are found
                    .addClassLoaders(pluginClassLoader);
            Reflections reflections = new Reflections(config);
            Set<Class<? extends GoalProvider>> providerClasses = reflections.getSubTypesOf(GoalProvider.class);
            for (Class<? extends GoalProvider> providerClass : providerClasses) {
                if (providerClass.isMemberClass() && !java.lang.reflect.Modifier.isStatic(providerClass.getModifiers())) {
                    logger.warning(" -> Skipping non-static inner class: " + providerClass.getName());
                    continue;
                }
                try {
                    GoalProvider provider = providerClass.getDeclaredConstructor().newInstance();
                    var toolDefinition = provider.getToolDefinition(blockTagManager);
                    goalRegistry.register(toolDefinition);
                    logger.info(" -> Registered goal tool: " + toolDefinition.name());
                } catch (InstantiationException | IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
                    logger.log(Level.SEVERE, "Failed to instantiate GoalProvider: " + providerClass.getName(), e);
                }
            }
            logger.info("GoalProvider discovery complete. " + goalRegistry.getAll().size() + " tools registered.");
        } catch (URISyntaxException | MalformedURLException e) {
            logger.log(Level.SEVERE, "Could not get plugin JAR URL for classpath scanning.", e);
        }
    }
}