package com.ionsignal.minecraft.ionnerrus.agent.llm;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;

import io.github.sashirestela.cleverclient.client.OkHttpClientAdapter;
import io.github.sashirestela.openai.SimpleOpenAI;
import io.github.sashirestela.openai.domain.chat.Chat;
import io.github.sashirestela.openai.domain.chat.ChatRequest;

import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public class LLMService {
    private static final Logger LOGGER = IonNerrus.getInstance().getLogger();
    private final SimpleOpenAI openAI;
    private final String modelName;
    private final ExecutorService httpExecutor;

    @SuppressWarnings("unused")
    private final IonNerrus plugin;

    public LLMService(IonNerrus plugin) {
        this.plugin = plugin;
        FileConfiguration config = plugin.getConfig();
        String apiKey = config.getString("llm.apiKey");
        String baseUrl = config.getString("llm.baseUrl");
        this.modelName = config.getString("llm.modelName", "qwen3-coder");
        if (baseUrl == null || baseUrl.isBlank() || apiKey == null) {
            LOGGER.warning("LLM configuration is incomplete in config.yml.");
            this.openAI = null;
            this.httpExecutor = null;
        } else {
            // Create executor with daemon threads
            this.httpExecutor = Executors.newCachedThreadPool(new ThreadFactory() {
                private int threadNumber = 0;

                @Override
                public Thread newThread(Runnable r) {
                    Thread thread = new Thread(r, "LLM-HTTP-" + threadNumber++);
                    thread.setDaemon(true); // <-- Makes threads daemon (won't block JVM shutdown)
                    return thread;
                }
            });
            // Configure OkHttp to use our daemon thread pool
            OkHttpClient httpClient = new OkHttpClient.Builder()
                    .dispatcher(new Dispatcher(httpExecutor))
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .build();
            this.openAI = SimpleOpenAI.builder()
                    .apiKey(apiKey)
                    .baseUrl(baseUrl)
                    .clientAdapter(new OkHttpClientAdapter(httpClient))
                    .build();
            LOGGER.info("LLMService initialized with model: " + modelName + " and baseUrl: " + baseUrl);
        }
    }

    public CompletableFuture<Chat> getNextToolCall(ChatRequest request) {
        if (openAI == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("LLMService is not initialized."));
        }
        try {
            // The ReActDirector will be responsible for building the full request,
            // including setting the model from getModelName().
            return openAI.chatCompletions().create(request);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error calling LLM API:", e);
            return CompletableFuture.failedFuture(e);
        }
    }

    public String getModelName() {
        return modelName;
    }

    /**
     * Properly shut down LLM service and wait for threads to terminate by blocking for up to 5 seconds
     * to ensure clean shutdown sequence.
     */
    public void shutdown() {
        if (openAI != null) {
            LOGGER.info("Shutting down LLMService...");
            // Step 1: Tell the OpenAI client to stop accepting new requests
            openAI.shutDown();
            // Step 2: Shut down the executor (stops accepting new tasks)
            if (httpExecutor != null && !httpExecutor.isShutdown()) {
                httpExecutor.shutdown();
                try {
                    // Step 3: Wait up to 5 seconds for tasks to complete
                    if (!httpExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                        LOGGER.warning("LLM HTTP executor did not terminate in time. Forcing shutdown...");
                        httpExecutor.shutdownNow();

                        // Step 4: Wait another 2 seconds after forced shutdown
                        if (!httpExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                            LOGGER.severe("LLM HTTP executor failed to terminate even after forced shutdown.");
                        }
                    } else {
                        LOGGER.info("LLM HTTP executor shut down cleanly.");
                    }
                } catch (InterruptedException e) {
                    LOGGER.warning("Interrupted while waiting for LLM executor shutdown.");
                    httpExecutor.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
            LOGGER.info("LLMService shut down complete.");
        }
    }
}