package com.ionsignal.minecraft.ionnerrus.commands.parsers;

import com.ionsignal.minecraft.ionnerrus.agent.AgentService;
import com.ionsignal.minecraft.ionnerrus.network.model.PersonaListItem;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import org.bukkit.entity.Player;
import org.checkerframework.checker.nullness.qual.NonNull;

import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.context.CommandInput;
import org.incendo.cloud.parser.ArgumentParseResult;
import org.incendo.cloud.parser.ArgumentParser;
import org.incendo.cloud.suggestion.Suggestion;
import org.incendo.cloud.suggestion.SuggestionProvider;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Parses a string argument into an PersonaListItem (Definition) from the player's cache.
 * Fails if the definition is not found or the cache is empty.
 */
public class PersonaDefinitionParser
        implements ArgumentParser<CommandSourceStack, PersonaListItem>, SuggestionProvider<CommandSourceStack> {
    private final AgentService agentService;

    public PersonaDefinitionParser(AgentService agentService) {
        this.agentService = agentService;
    }

    @Override
    public @NonNull ArgumentParseResult<PersonaListItem> parse(@NonNull CommandContext<CommandSourceStack> commandContext,
            @NonNull CommandInput commandInput) {
        String input = commandInput.readString();
        if (!(commandContext.sender().getSender() instanceof Player player)) {
            return ArgumentParseResult.failure(new IllegalArgumentException("Only players can spawn agents via command."));
        }
        List<PersonaListItem> cachedPersonas = agentService.getCachedPersonas(player.getUniqueId());
        if (cachedPersonas.isEmpty()) {
            return ArgumentParseResult.failure(
                    new IllegalArgumentException("Your personas are still loading, or you have none. Please try again in a moment."));
        }
        Optional<PersonaListItem> match = cachedPersonas.stream()
                .filter(config -> config.name().equalsIgnoreCase(input))
                .findFirst();
        if (match.isEmpty()) {
            return ArgumentParseResult.failure(new IllegalArgumentException("Persona definition not found: " + input));
        }
        return ArgumentParseResult.success(match.get());
    }

    @Override
    public @NonNull CompletableFuture<Iterable<Suggestion>> suggestionsFuture(@NonNull CommandContext<CommandSourceStack> context,
            @NonNull CommandInput input) {
        if (!(context.sender().getSender() instanceof Player player)) {
            return CompletableFuture.completedFuture(List.of());
        }
        return CompletableFuture.supplyAsync(() -> agentService.getCachedPersonas(player.getUniqueId()).stream()
                .map(config -> Suggestion.suggestion(config.name()))
                .collect(Collectors.toList()));
    }
}