package com.ionsignal.minecraft.ionnerrus.persona;

import com.ionsignal.minecraft.ionnerrus.persona.inventory.PersonaInventoryMenu;
import com.ionsignal.minecraft.ionnerrus.persona.movement.PersonaJumpControl;
import com.ionsignal.minecraft.ionnerrus.persona.movement.PersonaLookControl;
import com.ionsignal.minecraft.ionnerrus.persona.movement.PersonaMoveControl;
import com.ionsignal.minecraft.ionnerrus.persona.network.EmptyConnection;
import com.ionsignal.minecraft.ionnerrus.persona.network.EmptyPacketListener;
import com.ionsignal.minecraft.ionnerrus.persona.util.EmptyPlayerAdvancements;
import com.ionsignal.minecraft.ionnerrus.persona.util.EmptyServerStatsCounter;
import com.mojang.authlib.GameProfile;

import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.stats.ServerStatsCounter;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.ChatVisiblity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class PersonaEntity extends ServerPlayer implements PersonaHolder, MenuProvider {
    private final Persona persona;
    private final PlayerAdvancements advancements;
    private final ServerStatsCounter stats;
    private final Set<UUID> playersWithInfoPacket = Collections.newSetFromMap(new ConcurrentHashMap<>());

    // AI Controls, just like a standard Mob
    protected final PersonaMoveControl moveControl;
    protected final PersonaJumpControl jumpControl;
    protected final PersonaLookControl lookControl;

    public PersonaEntity(MinecraftServer server, ServerLevel level, GameProfile gameProfile, Persona persona) {
        super(server, level, gameProfile,
                new ClientInformation(
                        "en_us",
                        2,
                        ChatVisiblity.FULL,
                        true,
                        0,
                        HumanoidArm.RIGHT,
                        false,
                        false,
                        ParticleStatus.ALL));

        this.persona = persona;
        this.stats = new EmptyServerStatsCounter();
        this.advancements = new EmptyPlayerAdvancements(server.getFixerUpper(), server.getPlayerList(), this);
        this.connection = new EmptyPacketListener(
                server,
                new EmptyConnection(PacketFlow.SERVERBOUND),
                this,
                CommonListenerCookie.createInitial(gameProfile, false));

        // Initialize AI controls
        this.moveControl = new PersonaMoveControl(this);
        this.jumpControl = new PersonaJumpControl(this);
        this.lookControl = new PersonaLookControl(this);

        // Make this entity a passive puppet
        this.setInvulnerable(true);
    }

    @Override
    public boolean broadcastToPlayer(@SuppressWarnings("null") ServerPlayer player) {
        return playersWithInfoPacket.contains(player.getUUID());
    }

    public void grantVisibility(Player player) {
        playersWithInfoPacket.add(player.getUniqueId());
    }

    public void revokeVisibility(Player player) {
        playersWithInfoPacket.remove(player.getUniqueId());
    }

    public void clearVisibility() {
        playersWithInfoPacket.clear();
    }

    @Override
    public @NotNull Persona getPersona() {
        return persona;
    }

    @Override
    public @NotNull Component getDisplayName() {
        return Component.literal(this.persona.getName());
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, @SuppressWarnings("null") @NotNull Inventory playerInventory,
            @SuppressWarnings("null") @NotNull net.minecraft.world.entity.player.Player player) {
        this.persona.getPhysicalBody().onInventoryOpen(player.getBukkitEntity());
        return new PersonaInventoryMenu(containerId, playerInventory, this.getInventory(), this);
    }

    @Override
    public void doCloseContainer() {
        this.persona.getPhysicalBody().onInventoryClose();
        super.doCloseContainer();
    }

    @Override
    public void tick() {
        this.doTick();
    }

    @Override
    public void aiStep() {
        // Tick physics controls.
        // Note: lookControl is NOT ticked here.
        // It is driven exclusively by BukkitPhysicalBody
        this.moveControl.tick();
        this.jumpControl.tick();

        super.aiStep();
    }

    public PersonaMoveControl getMoveControl() {
        return this.moveControl;
    }

    public PersonaJumpControl getJumpControl() {
        return this.jumpControl;
    }

    public PersonaLookControl getLookControl() {
        return this.lookControl;
    }

    @Override
    public void die(@SuppressWarnings("null") @NotNull DamageSource damageSource) {
        if (isRemoved())
            return;
        this.remove(RemovalReason.KILLED);
    }

    @Override
    public void readAdditionalSaveData(@SuppressWarnings("null") @NotNull ValueInput input) {
    }

    @Override
    public void addAdditionalSaveData(@SuppressWarnings("null") @NotNull ValueOutput output) {
    }

    @Override
    public boolean isTransmittingWaypoint() {
        return false;
    }

    @Override
    public boolean isSpectator() {
        return false;
    }

    @Override
    public boolean isCreative() {
        return false;
    }

    @Override
    public @NotNull PlayerAdvancements getAdvancements() {
        return this.advancements;
    }

    @Override
    public @NotNull ServerStatsCounter getStats() {
        return this.stats;
    }

    @Override
    public void checkMovementStatistics(double dx, double dy, double dz) {
    }
}