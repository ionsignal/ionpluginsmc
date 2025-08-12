package com.ionsignal.minecraft.ionnerrus.agent.llm;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;

import io.github.sashirestela.cleverclient.client.OkHttpClientAdapter;
import io.github.sashirestela.openai.SimpleOpenAI;
import io.github.sashirestela.openai.domain.chat.Chat;
import io.github.sashirestela.openai.domain.chat.ChatRequest;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

public class LLMService {
    private static final Logger LOGGER = IonNerrus.getInstance().getLogger();
    private final SimpleOpenAI openAI;
    private final String modelName;

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
        } else {
            this.openAI = SimpleOpenAI.builder()
                    .apiKey(apiKey)
                    .baseUrl(baseUrl)
                    .clientAdapter(new OkHttpClientAdapter())
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

    public void shutdown() {
        if (openAI != null) {
            openAI.shutDown();
            LOGGER.info("LLMService shut down.");
        }
    }
}