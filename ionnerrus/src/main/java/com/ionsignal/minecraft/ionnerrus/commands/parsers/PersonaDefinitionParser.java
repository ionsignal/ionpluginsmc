package com.ionsignal.minecraft.ionnerrus.commands.parsers;

import com.ionsignal.minecraft.ionnerrus.agent.lifecycle.AgentService;
import com.ionsignal.minecraft.ionnerrus.network.model.PersonaListItem;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.MessageComponentSerializer;
import io.papermc.paper.command.brigadier.argument.CustomArgumentType;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import org.bukkit.entity.Player;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Parses a string argument into an PersonaListItem (Definition) from the player's cache.
 */
public class PersonaDefinitionParser implements CustomArgumentType.Converted<PersonaListItem, String> {

    private static final SimpleCommandExceptionType ERROR_NOT_PLAYER = new SimpleCommandExceptionType(
            MessageComponentSerializer.message()
                    .serialize(Component.text("Only players can spawn agents via command.", NamedTextColor.RED)));
    private static final SimpleCommandExceptionType ERROR_NO_PERSONAS = new SimpleCommandExceptionType(
            MessageComponentSerializer.message().serialize(Component
                    .text("Your personas are still loading, or you have none. Please try again in a moment.", NamedTextColor.RED)));
    private static final DynamicCommandExceptionType ERROR_NOT_FOUND = new DynamicCommandExceptionType(
            input -> MessageComponentSerializer.message()
                    .serialize(Component.text("Persona definition not found: " + input, NamedTextColor.RED)));

    private final AgentService agentService;

    public PersonaDefinitionParser(AgentService agentService) {
        this.agentService = agentService;
    }

    @Override
    public PersonaListItem convert(String nativeType) throws CommandSyntaxException {
        throw ERROR_NOT_PLAYER.create();
    }

    @Override
    public <S> PersonaListItem convert(String nativeType, S source) throws CommandSyntaxException {
        CommandSourceStack stack = (CommandSourceStack) source;
        if (!(stack.getSender() instanceof Player player)) {
            throw ERROR_NOT_PLAYER.create();
        }
        List<PersonaListItem> cachedPersonas = agentService.getCachedPersonas(player.getUniqueId());
        if (cachedPersonas.isEmpty()) {
            throw ERROR_NO_PERSONAS.create();
        }
        Optional<PersonaListItem> match = cachedPersonas.stream()
                .filter(config -> config.name().equalsIgnoreCase(nativeType))
                .findFirst();
        if (match.isEmpty()) {
            throw ERROR_NOT_FOUND.create(nativeType);
        }
        return match.get();
    }

    @Override
    public @NotNull ArgumentType<String> getNativeType() {
        return StringArgumentType.word();
    }

    @Override
    public <S> CompletableFuture<Suggestions> listSuggestions(CommandContext<S> context, SuggestionsBuilder builder) {
        CommandSourceStack stack = (CommandSourceStack) context.getSource();
        if (!(stack.getSender() instanceof Player player)) {
            return builder.buildFuture();
        }
        agentService.getCachedPersonas(player.getUniqueId()).stream()
                .map(PersonaListItem::name)
                .filter(name -> name.toLowerCase().startsWith(builder.getRemainingLowerCase()))
                .forEach(builder::suggest);
        return builder.buildFuture();
    }
}