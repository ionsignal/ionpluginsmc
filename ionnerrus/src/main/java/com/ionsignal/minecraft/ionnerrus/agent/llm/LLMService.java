package com.ionsignal.minecraft.ionnerrus.agent.llm;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;

import com.openai.client.OpenAIClientAsync;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;

import org.bukkit.configuration.file.FileConfiguration;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public class LLMService {
    private static final Logger LOGGER = IonNerrus.getInstance().getLogger();

    private final OpenAIClientAsync openAI;
    private final String modelName;
    private final ExecutorService vtExecutor;

    @SuppressWarnings("unused")
    private final IonNerrus plugin;

    public LLMService(IonNerrus plugin) {
        this.plugin = plugin;
        FileConfiguration config = plugin.getConfig();
        String apiKey = config.getString("llm.apiKey");
        String baseUrl = config.getString("llm.baseUrl");
        this.modelName = config.getString("llm.modelName", "qwen3-coder");
        // Initialize Virtual Thread Executor
        // This executor will be used for both Network I/O (Dispatcher) and Logic/Parsing (StreamHandler)
        this.vtExecutor = Executors.newVirtualThreadPerTaskExecutor();
        if (baseUrl == null || baseUrl.isBlank() || apiKey == null) {
            LOGGER.warning("LLM configuration is incomplete in config.yml.");
            this.openAI = null;
        } else {
            // Build OpenAIClient using OpenAIOkHttpClient builder
            // Removed redundant ClientOptions.Builder instantiation
            this.openAI = OpenAIOkHttpClient.builder()
                    .fromEnv() // Load defaults from env if any
                    // Inject VT executor for OkHttp Dispatcher (Blocking I/O)
                    .dispatcherExecutorService(vtExecutor)
                    // Inject VT executor for handling async stream callbacks
                    .streamHandlerExecutor(vtExecutor)
                    // Apply manual options (BaseURL, Key)
                    .baseUrl(baseUrl)
                    .apiKey(apiKey)
                    .timeout(Duration.ofSeconds(60))
                    .build()
                    .async(); // Return the async interface
            LOGGER.info("LLMService initialized with model: " + modelName + " and baseUrl: " + baseUrl + " using Virtual Threads.");
        }
    }

    public CompletableFuture<ChatCompletion> getNextToolCall(ChatCompletionCreateParams params) {
        if (openAI == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("LLMService is not initialized."));
        }
        try {
            // Delegate directly to the official library
            return openAI.chat().completions().create(params);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error calling LLM API:", e);
            return CompletableFuture.failedFuture(e);
        }
    }

    public String getModelName() {
        return modelName;
    }

    /**
     * Properly shut down LLM service.
     */
    public void shutdown() {
        if (openAI != null) {
            LOGGER.info("Shutting down LLMService...");
            // Close the client (this handles connection pool eviction)
            // Note: OpenAIClientAsync doesn't have close(), but the underlying implementation/client options
            // might. However, we manage the executor externally, so we focus on that. Shut down the VT executor
            if (vtExecutor != null && !vtExecutor.isShutdown()) {
                vtExecutor.shutdown();
                try {
                    // Wait briefly for tasks to complete
                    if (!vtExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                        LOGGER.warning("LLM VT executor did not terminate in time. Forcing shutdown...");
                        vtExecutor.shutdownNow();
                    } else {
                        LOGGER.info("LLM VT executor shut down cleanly.");
                    }
                } catch (InterruptedException e) {
                    LOGGER.warning("Interrupted while waiting for LLM executor shutdown.");
                    vtExecutor.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
            LOGGER.info("LLMService shut down complete.");
        }
    }
}
