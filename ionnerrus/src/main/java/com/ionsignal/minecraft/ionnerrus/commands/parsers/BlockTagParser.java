package com.ionsignal.minecraft.ionnerrus.commands.parsers;

import com.ionsignal.minecraft.ionnerrus.content.BlockTagManager;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import io.papermc.paper.command.brigadier.MessageComponentSerializer;
import io.papermc.paper.command.brigadier.argument.CustomArgumentType;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;

/**
 * Validates and suggests custom block group tags (e.g. "logs", "leaves") for gathering.
 * NEW CLASS: Replaces the inline Cloud suggestion provider.
 */
public class BlockTagParser implements CustomArgumentType.Converted<String, String> {

    private static final DynamicCommandExceptionType ERROR_INVALID_TAG = new DynamicCommandExceptionType(
            input -> MessageComponentSerializer.message().serialize(Component.text("Invalid block type: " + input, NamedTextColor.RED)));

    private final BlockTagManager blockTagManager;

    public BlockTagParser(BlockTagManager blockTagManager) {
        this.blockTagManager = blockTagManager;
    }

    @Override
    public String convert(String nativeType) throws CommandSyntaxException {
        String groupName = nativeType.toLowerCase();
        if (blockTagManager.getMaterialSet(groupName) == null) {
            throw ERROR_INVALID_TAG.create(nativeType);
        }
        return groupName;
    }

    @Override
    public @NotNull ArgumentType<String> getNativeType() {
        return StringArgumentType.word();
    }

    @Override
    public <S> CompletableFuture<Suggestions> listSuggestions(CommandContext<S> context, SuggestionsBuilder builder) {
        blockTagManager.getRegisteredGroupNames().stream()
                .filter(name -> name.toLowerCase().startsWith(builder.getRemainingLowerCase()))
                .forEach(builder::suggest);
        return builder.buildFuture();
    }
}