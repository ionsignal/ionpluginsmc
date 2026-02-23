package com.ionsignal.minecraft.ionnerrus.commands.parsers;

import com.ionsignal.minecraft.ionnerrus.agent.AgentService;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import org.checkerframework.checker.nullness.qual.NonNull;

import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.context.CommandInput;
import org.incendo.cloud.parser.ArgumentParseResult;
import org.incendo.cloud.parser.ArgumentParser;
import org.incendo.cloud.suggestion.Suggestion;
import org.incendo.cloud.suggestion.SuggestionProvider;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Parses a string argument into an active NerrusAgent instance.
 */
public class NerrusAgentParser implements ArgumentParser<CommandSourceStack, NerrusAgent>, SuggestionProvider<CommandSourceStack> {
    private final AgentService agentService;

    public NerrusAgentParser(AgentService agentService) {
        this.agentService = agentService;
    }

    @Override
    public @NonNull ArgumentParseResult<NerrusAgent> parse(@NonNull CommandContext<CommandSourceStack> commandContext,
            @NonNull CommandInput commandInput) {
        String input = commandInput.readString();
        CommandSender sender = commandContext.sender().getSender();
        boolean isAdminOrConsole = !(sender instanceof Player) || sender.hasPermission("ionnerrus.admin");
        // Filter agents based on ownership or admin status
        List<NerrusAgent> matches = agentService.getAgents().stream()
                .filter(agent -> agent.getName().equalsIgnoreCase(input) ||
                        String.valueOf(agent.getPersona().getSessionId()).equalsIgnoreCase(input))
                .filter(agent -> {
                    if (isAdminOrConsole)
                        return true;
                    Player player = (Player) sender;
                    return player.getUniqueId().equals(agent.getPersona().getOwnerId());
                })
                .toList();
        if (matches.isEmpty()) {
            return ArgumentParseResult.failure(new IllegalArgumentException("Active agent not found or you do not own it: " + input));
        }
        if (matches.size() > 1) {
            return ArgumentParseResult.failure(
                    new IllegalArgumentException("Multiple agents found named '" + input + "'. Please use the exact Session UUID."));
        }
        return ArgumentParseResult.success(matches.get(0));
    }

    @Override
    public @NonNull CompletableFuture<Iterable<Suggestion>> suggestionsFuture(@NonNull CommandContext<CommandSourceStack> context,
            @NonNull CommandInput input) {
        CommandSender sender = context.sender().getSender();
        boolean isAdminOrConsole = !(sender instanceof Player) || sender.hasPermission("ionnerrus.admin");
        return CompletableFuture.supplyAsync(() -> agentService.getAgents().stream()
                .filter(agent -> {
                    if (isAdminOrConsole)
                        return true;
                    Player player = (Player) sender;
                    return player.getUniqueId().equals(agent.getPersona().getOwnerId());
                })
                .map(agent -> Suggestion.suggestion(agent.getName()))
                .collect(Collectors.toList()));
    }
}