package com.ionsignal.minecraft.ionnerrus.commands.parsers;

import com.ionsignal.minecraft.ioncore.auth.IdentityService;

import com.ionsignal.minecraft.ionnerrus.agent.cognition.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.lifecycle.AgentService;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.MessageComponentSerializer;
import io.papermc.paper.command.brigadier.argument.CustomArgumentType;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Parses a string argument into an active NerrusAgent instance using Paper's native Brigadier API.
 */
public class NerrusAgentParser implements CustomArgumentType.Converted<NerrusAgent, String> {

    private static final DynamicCommandExceptionType ERROR_NOT_FOUND = new DynamicCommandExceptionType(
            input -> MessageComponentSerializer.message()
                    .serialize(Component.text("Active agent not found or you do not own it: " + input, NamedTextColor.RED)));
    private static final DynamicCommandExceptionType ERROR_MULTIPLE = new DynamicCommandExceptionType(
            input -> MessageComponentSerializer.message().serialize(
                    Component.text("Multiple agents found named '" + input + "'. Please use the exact Session UUID.", NamedTextColor.RED)));
    private static final DynamicCommandExceptionType ERROR_NOT_LINKED = new DynamicCommandExceptionType(
            input -> MessageComponentSerializer.message()
                    .serialize(Component.text("You must link your Runemind account to interact with agents.", NamedTextColor.RED)));

    private final AgentService agentService;
    private final IdentityService identityService;

    public NerrusAgentParser(AgentService agentService, IdentityService identityService) {
        this.agentService = agentService;
        this.identityService = identityService;
    }

    @Override
    public NerrusAgent convert(String nativeType) throws CommandSyntaxException {
        throw ERROR_NOT_FOUND.create(nativeType);
    }

    @Override
    public <S> NerrusAgent convert(String nativeType, S source) throws CommandSyntaxException {
        CommandSourceStack stack = (CommandSourceStack) source;
        CommandSender sender = stack.getSender();
        boolean isAdminOrConsole = !(sender instanceof Player) || sender.hasPermission("ionnerrus.admin");

        UUID resolvedWebId = null;
        if (!isAdminOrConsole && sender instanceof Player player) {
            var userOpt = identityService.getCachedIdentity(player.getUniqueId());
            if (userOpt.isPresent() && userOpt.get().isPresent()) {
                resolvedWebId = userOpt.get().get().id();
            } else {
                throw ERROR_NOT_LINKED.create(nativeType);
            }
        }

        final UUID finalWebId = resolvedWebId;
        List<NerrusAgent> matches = agentService.getAgents().stream()
                .filter(agent -> agent.getName().equalsIgnoreCase(nativeType) ||
                        String.valueOf(agent.getPersona().getSessionId()).equalsIgnoreCase(nativeType))
                .filter(agent -> isAdminOrConsole || (finalWebId != null && finalWebId.equals(agent.getPersona().getOwnerId())))
                .toList();

        if (matches.isEmpty()) {
            throw ERROR_NOT_FOUND.create(nativeType);
        }
        if (matches.size() > 1) {
            throw ERROR_MULTIPLE.create(nativeType);
        }

        return matches.get(0);
    }

    @Override
    public @NotNull ArgumentType<String> getNativeType() {
        return StringArgumentType.word();
    }

    @Override
    public <S> CompletableFuture<Suggestions> listSuggestions(CommandContext<S> context, SuggestionsBuilder builder) {
        CommandSourceStack stack = (CommandSourceStack) context.getSource();
        CommandSender sender = stack.getSender();
        boolean isAdminOrConsole = !(sender instanceof Player) || sender.hasPermission("ionnerrus.admin");

        UUID resolvedWebId = null;
        if (!isAdminOrConsole && sender instanceof Player player) {
            var userOpt = identityService.getCachedIdentity(player.getUniqueId());
            if (userOpt.isPresent() && userOpt.get().isPresent()) {
                resolvedWebId = userOpt.get().get().id();
            }
        }
        final UUID finalWebId = resolvedWebId;

        agentService.getAgents().stream()
                .filter(agent -> isAdminOrConsole || (finalWebId != null && finalWebId.equals(agent.getPersona().getOwnerId())))
                .map(NerrusAgent::getName)
                .filter(name -> name.toLowerCase().startsWith(builder.getRemainingLowerCase()))
                .forEach(builder::suggest);

        return builder.buildFuture();
    }
}