package com.ionsignal.minecraft.ioncore;

import com.ionsignal.minecraft.ioncore.auth.IdentityService;
import com.ionsignal.minecraft.ioncore.config.TenantConfig;

// STUBBED DEBUG REGISTRIES FOR IONGENESIS COMPILATION
import com.ionsignal.minecraft.ioncore.debug.DebugRegistry;
import com.ionsignal.minecraft.ioncore.debug.DebugSession;
import com.ionsignal.minecraft.ioncore.debug.ExecutionController;
import com.ionsignal.minecraft.ioncore.debug.SessionStatus;
import com.ionsignal.minecraft.ioncore.debug.VisualizationProvider;
import com.ionsignal.minecraft.ioncore.debug.VisualizationRegistry;
// END STUBBED DEBUG REGISTRIES
import com.ionsignal.minecraft.ioncore.exceptions.ServiceInitializationException;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

/**
 * IonCore - Core framework for Ion Signal plugins.
 */
public class IonCore extends JavaPlugin {
    private static IonCore instance;
    private ServiceContainer serviceContainer;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        try {
            // Initialize Service Container
            this.serviceContainer = new ServiceContainer(this);
            this.serviceContainer.initialize();
            getLogger().info("IonCore v" + getPluginMeta().getVersion() + " initialized.");
        } catch (ServiceInitializationException e) {
            getLogger().severe("CRITICAL INITIALIZATION FAILURE: " + e.getMessage());
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (serviceContainer != null) {
            serviceContainer.shutdown();
            serviceContainer = null;
        }
        getLogger().info("IonCore disabled.");
    }

    public static IonCore getInstance() {
        return instance;
    }

    public ServiceContainer getServiceContainer() {
        return serviceContainer;
    }

    public IdentityService getIdentityService() {
        ensureInitialized();
        return serviceContainer.getIdentityService();
    }

    public ExecutorService getVirtualThreadExecutor() {
        ensureInitialized();
        return serviceContainer.getVirtualThreadExecutor();
    }

    public TenantConfig getTenantConfig() {
        ensureInitialized();
        return serviceContainer.getTenantConfig();
    }

    private static void ensureInitialized() {
        if (instance == null || instance.serviceContainer == null) {
            throw new IllegalStateException("IonCore is not initialized. Ensure the plugin is enabled.");
        }
    }

    // STUBBED DEBUG REGISTRIES FOR IONGENESIS COMPILATION
    private static final DebugRegistry DUMMY_DEBUG_REGISTRY = new DebugRegistry() {
        @Override
        public Optional<DebugSession<?>> getActiveSession(UUID id) {
            return Optional.empty();
        }

        @Override
        public boolean cancelSession(UUID id) {
            return false;
        }

        @Override
        public <T> DebugSession<T> createSession(UUID id, T initialState, ExecutionController controller) {
            return new DebugSession<T>() {
                @Override
                public SessionStatus getStatus() {
                    return SessionStatus.CANCELLED;
                }

                @Override
                public void setStatus(SessionStatus status) {
                }

                @Override
                public boolean isActive() {
                    return false;
                }

                @Override
                public Optional<ExecutionController> getController() {
                    return Optional.empty();
                }

                @Override
                public void transitionTo(SessionStatus status) {
                }

                @Override
                public void setState(T state) {
                }
            };
        }
    };

    private static final VisualizationRegistry DUMMY_VISUALIZATION_REGISTRY = new VisualizationRegistry() {
        @Override
        public <T> void register(Class<T> type, VisualizationProvider<T> provider) {
            // No-op
        }

        @Override
        public <T> void unregister(Class<T> type) {
            // No-op
        }
    };

    public static DebugRegistry getDebugRegistry() {
        return DUMMY_DEBUG_REGISTRY;
    }

    public static VisualizationRegistry getVisualizationRegistry() {
        return DUMMY_VISUALIZATION_REGISTRY;
    }
    // END STUBBED DEBUG REGISTRIES
}