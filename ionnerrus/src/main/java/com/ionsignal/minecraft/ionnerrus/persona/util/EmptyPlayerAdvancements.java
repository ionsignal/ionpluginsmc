package com.ionsignal.minecraft.ionnerrus.persona.util;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;

import com.mojang.datafixers.DataFixer;
import com.mojang.serialization.DynamicOps;

import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.ServerAdvancementManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;

import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * A "do-nothing" implementation of PlayerAdvancements for Persona entities.
 * It overrides methods to prevent file I/O, progress tracking, and packet sending.
 * This avoids creating useless advancement data files for each Persona.
 */
public class EmptyPlayerAdvancements extends PlayerAdvancements {
    public EmptyPlayerAdvancements(DataFixer dataFixer, PlayerList playerList, ServerPlayer serverPlayer) {
        // The key is to pass a path to a DIRECTORY.
        // The private load() method in the superclass checks `Files.isRegularFile()`,
        // which will be false for a directory, thus skipping all file I/O.
        // This avoids the need for reflection or wideners.
        super(dataFixer, playerList, new EmptyServerAdvancementManager(),
                IonNerrus.getPlugin(IonNerrus.class).getDataFolder().toPath(), serverPlayer);
    }

    /**
     * Override the save method to do nothing. This prevents writing advancement files for the Persona.
     */
    @Override
    public void save() {
    }

    /**
     * Prevent listeners from being registered for the Persona.
     */
    @Override
    public void stopListening() {
    }

    /**
     * Prevent advancement data from being reloaded.
     */
    @Override
    public void reload(@SuppressWarnings("null") ServerAdvancementManager serverAdvancementManager) {
    }

    /**
     * Prevent packets from being sent to the (non-existent) client.
     * The signature must match the superclass exactly.
     */
    @Override
    public void flushDirty(@SuppressWarnings("null") ServerPlayer serverPlayer, boolean showAdvancements) {
    }

    /**
     * Prevent awarding advancements.
     */
    @Override
    public boolean award(@SuppressWarnings("null") AdvancementHolder advancement, @SuppressWarnings("null") String criterion) {
        return false;
    }

    /**
     * Prevent revoking advancements.
     */
    @Override
    public boolean revoke(@SuppressWarnings("null") AdvancementHolder advancement, @SuppressWarnings("null") String criterion) {
        return false;
    }

    /**
     * Prevent setting a tab, which would try to send a packet.
     */
    @Override
    public void setSelectedTab(@Nullable @SuppressWarnings("null") AdvancementHolder advancement) {
    }

    /**
     * Always return a new, empty progress object. This prevents progress
     * from being stored in the internal map.
     */
    @Override
    public AdvancementProgress getOrStartProgress(@SuppressWarnings("null") AdvancementHolder advancement) {
        return new AdvancementProgress();
    }

    /**
     * A dummy AdvancementManager that holds no advancements.
     */
    private static class EmptyServerAdvancementManager extends ServerAdvancementManager {
        public EmptyServerAdvancementManager() {
            super(new EmptyProvider());
        }

        @Override
        public AdvancementHolder get(@SuppressWarnings("null") Identifier resourceLocation) {
            return null;
        }

        @Override
        public Collection<AdvancementHolder> getAllAdvancements() {
            return Collections.emptyList();
        }
    }

    /**
     * A dummy HolderLookup.Provider that provides no registries, required by the
     * ServerAdvancementManager constructor.
     */
    private static class EmptyProvider implements HolderLookup.Provider {
        @Override
        public Stream<ResourceKey<? extends Registry<?>>> listRegistryKeys() {
            return Stream.empty();
        }

        @Override
        public <T> Optional<HolderLookup.RegistryLookup<T>> lookup(
                @SuppressWarnings("null") ResourceKey<? extends Registry<? extends T>> registryKey) {
            return Optional.empty();
        }

        @Override
        @SuppressWarnings("null")
        public <T> RegistryOps<T> createSerializationContext(DynamicOps<T> dynamicOps) {
            return RegistryOps.create(dynamicOps, this);
        }
    }
}