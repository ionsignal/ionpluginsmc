package com.ionsignal.minecraft.ionnerrus.agent.goals;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.content.BlockTagManager;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfoList;
import io.github.classgraph.ScanResult;

import java.lang.reflect.InvocationTargetException;
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
        logger.info("Discovering GoalProviders using ClassGraph...");
        String providerInterfaceName = GoalProvider.class.getName();
        // ClassGraph's try-with-resources ensures the scanner is closed properly.
        try (ScanResult scanResult = new ClassGraph()
                .enableClassInfo()
                .addClassLoader(IonNerrus.getInstance().getClass().getClassLoader())
                .scan()) {
            // Find all classes that implement the GoalProvider interface
            ClassInfoList providerClassesInfo = scanResult.getClassesImplementing(providerInterfaceName);
            // Load the classes, which gives us List<Class<GoalProvider>>
            for (Class<? extends GoalProvider> providerClass : providerClassesInfo.loadClasses(GoalProvider.class)) {
                // We can proceed directly to instantiation
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
        } catch (Exception e) {
            // Catching a broad exception here because various runtime exceptions on can be thrown
            logger.log(Level.SEVERE, "An unexpected error occurred during classpath scanning for GoalProviders.", e);
        }
    }
}