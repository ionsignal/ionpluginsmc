package com.ionsignal.minecraft.ionnerrus.persona.platform.v1_21_R7;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.persona.Persona;
import com.ionsignal.minecraft.ionnerrus.persona.animation.PlayerAnimation;
import com.ionsignal.minecraft.ionnerrus.persona.skin.SkinData;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundDisguisedChatPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkMap.TrackedEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.server.network.ServerPlayerConnection;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity.RemovalReason;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.state.BlockState;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public class PersonaNMSBridge {
    @SuppressWarnings("unused")
    private final IonNerrus plugin;
    private final MinecraftServer minecraftServer;

    public PersonaNMSBridge() {
        this.minecraftServer = ((CraftServer) Bukkit.getServer()).getServer();
        this.plugin = IonNerrus.getInstance();
    }

    public Entity spawn(Persona persona, Location location) {
        ServerLevel world = ((CraftWorld) Objects.requireNonNull(location.getWorld())).getHandle();
        GameProfile profile = createGameProfile(persona.getUniqueId(), persona.getName(), persona.getSkin());
        PersonaEntity personaEntity = new PersonaEntity(minecraftServer, world, profile, persona);

        personaEntity.setGameMode(GameType.SURVIVAL);
        personaEntity.setPos(location.getX(), location.getY(), location.getZ());
        personaEntity.setRot(location.getYaw(), location.getPitch());
        personaEntity.setYHeadRot(location.getYaw());

        ClientboundPlayerInfoUpdatePacket addPlayerPacket = ClientboundPlayerInfoUpdatePacket.createSinglePlayerInitializing(personaEntity,
                false);
        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            ((CraftPlayer) onlinePlayer).getHandle().connection.send(addPlayerPacket);
            personaEntity.grantVisibility(onlinePlayer);
        }

        world.addFreshEntity(personaEntity);

        return personaEntity.getBukkitEntity();
    }

    public void despawn(Persona persona) {
        ServerPlayer serverPlayer = getVanillaPlayer(persona);
        if (serverPlayer != null) {
            ClientboundPlayerInfoRemovePacket removePlayerPacket = new ClientboundPlayerInfoRemovePacket(List.of(serverPlayer.getUUID()));
            for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                ((CraftPlayer) onlinePlayer).getHandle().connection.send(removePlayerPacket);
            }
            serverPlayer.remove(RemovalReason.DISCARDED);
        }
    }

    public void playAnimation(Persona persona, PlayerAnimation animation) {
        ServerPlayer serverPlayer = getVanillaPlayer(persona);
        if (serverPlayer == null)
            return;
        int animationId = switch (animation) {
            case SWING_MAIN_ARM -> ClientboundAnimatePacket.SWING_MAIN_HAND;
            case TAKE_DAMAGE -> 1;
            case LEAVE_BED -> ClientboundAnimatePacket.WAKE_UP;
            case SWING_OFF_HAND -> ClientboundAnimatePacket.SWING_OFF_HAND;
            case CRITICAL_EFFECT -> ClientboundAnimatePacket.CRITICAL_HIT;
            case MAGIC_CRITICAL_EFFECT -> ClientboundAnimatePacket.MAGIC_CRITICAL_HIT;
        };
        broadcastPacketToTrackers(serverPlayer, new ClientboundAnimatePacket(serverPlayer, animationId));
    }

    public void broadcastChat(Persona persona, String message) {
        ServerPlayer serverPlayer = getVanillaPlayer(persona);
        if (serverPlayer == null)
            return;
        TrackedEntity trackedEntity = serverPlayer.level().getChunkSource().chunkMap.entityMap.get(serverPlayer.getId());
        if (trackedEntity != null) {
            Component messageContent = Component.literal(message);
            Component senderName = Component.literal(persona.getName());
            ChatType.Bound chatType = ChatType.bind(ChatType.CHAT, minecraftServer.registryAccess(), senderName);
            ClientboundDisguisedChatPacket chatPacket = new ClientboundDisguisedChatPacket(messageContent, chatType);
            trackedEntity.broadcast(chatPacket);
        }
    }

    public void sendBlockBreakAnimation(Persona persona, Block block, int stage) {
        ServerPlayer serverPlayer = getVanillaPlayer(persona);
        if (serverPlayer == null)
            return;
        broadcastPacketToTrackers(serverPlayer, new ClientboundBlockDestructionPacket(serverPlayer.getId(),
                new net.minecraft.core.BlockPos(block.getX(), block.getY(), block.getZ()), stage));
    }

    public int calculateBreakTicks(Persona persona, Block block) {
        PersonaEntity personaEntity = persona.getPersonaEntity();
        if (personaEntity == null) {
            return Integer.MAX_VALUE;
        }
        ServerLevel level = personaEntity.level();
        BlockPos blockPos = new BlockPos(block.getX(), block.getY(), block.getZ());
        BlockState nmsBlockState = level.getBlockState(blockPos);
        // The old method, getDestroyProgress(), returns the final damage-per-tick, which is incorrect here.
        float hardness = nmsBlockState.getDestroySpeed(level, blockPos);
        if (hardness < 0) { // Unbreakable block (e.g., bedrock)
            return Integer.MAX_VALUE;
        }
        if (hardness == 0) { // Instantly breakable block (e.g., flower)
            return 1;
        }
        // Simplified and corrected logic.
        // This single formula now correctly handles all cases.
        float destroySpeed = getPlayerDestroySpeed(personaEntity, nmsBlockState);
        boolean hasCorrectTool = personaEntity.hasCorrectToolForDrops(nmsBlockState);
        float divisor = hasCorrectTool ? 30.0f : 100.0f;
        float damagePerTick = destroySpeed / hardness / divisor;
        if (damagePerTick <= 0) {
            return Integer.MAX_VALUE; // Cannot be broken
        }
        return (int) Math.ceil(1.0 / damagePerTick);
    }

    private float getPlayerDestroySpeed(PersonaEntity personaEntity, BlockState nmsBlockState) {
        var enchantmentRegistry = personaEntity.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        float speed = personaEntity.getInventory().getSelectedItem().getDestroySpeed(nmsBlockState);
        if (speed > 1.0F) {
            var efficiencyHolder = enchantmentRegistry.getOrThrow(Enchantments.EFFICIENCY);
            int efficiencyLevel = EnchantmentHelper.getItemEnchantmentLevel(efficiencyHolder, personaEntity.getMainHandItem());
            if (efficiencyLevel > 0) {
                speed += (float) (efficiencyLevel * efficiencyLevel + 1);
            }
        }
        if (personaEntity.hasEffect(MobEffects.HASTE)) {
            speed *= 1.0F + (float) (Objects.requireNonNull(personaEntity.getEffect(MobEffects.HASTE)).getAmplifier() + 1) * 0.2F;
        }
        if (personaEntity.hasEffect(MobEffects.MINING_FATIGUE)) {
            float fatigueMultiplier = switch (Objects.requireNonNull(personaEntity.getEffect(MobEffects.MINING_FATIGUE)).getAmplifier()) {
                case 0 -> 0.3F;
                case 1 -> 0.09F;
                case 2 -> 0.0027F;
                default -> 8.1E-4F;
            };
            speed *= fatigueMultiplier;
        }
        var aquaHolder = enchantmentRegistry.getOrThrow(Enchantments.AQUA_AFFINITY);
        int aquaLevel = EnchantmentHelper.getItemEnchantmentLevel(aquaHolder, personaEntity.getMainHandItem());
        if (personaEntity.isEyeInFluid(FluidTags.WATER) && aquaLevel == 0) {
            speed /= 5.0F;
        }
        if (!personaEntity.onGround()) {
            speed /= 5.0F;
        }
        return speed;
    }

    public boolean destroyBlock(Persona persona, Block block) {
        PersonaEntity personaEntity = persona.getPersonaEntity();
        if (personaEntity == null) {
            return false;
        }
        ServerPlayerGameMode gameMode = personaEntity.gameMode;
        BlockPos blockPos = new BlockPos(block.getX(), block.getY(), block.getZ());
        return gameMode.destroyBlock(blockPos);
    }

    public void showToPlayer(Persona persona, Player viewer) {
        PersonaEntity personaEntity = persona.getPersonaEntity();
        if (personaEntity == null || !personaEntity.isAlive())
            return;
        ServerPlayer viewerPlayer = ((CraftPlayer) viewer).getHandle();
        ClientboundPlayerInfoUpdatePacket addPlayerPacket = ClientboundPlayerInfoUpdatePacket.createSinglePlayerInitializing(personaEntity,
                false);
        viewerPlayer.connection.send(addPlayerPacket);
        personaEntity.grantVisibility(viewer);
    }

    public void respawn(Persona persona) {
        Entity visual = persona.getEntity();
        if (visual == null)
            return;
        Location loc = persona.getLocation();
        if (loc == null) {
            loc = visual.getLocation();
        }
        despawn(persona);
        persona.spawn(loc);
    }

    private GameProfile createGameProfile(UUID uuid, String name, SkinData skin) {
        GameProfile profile = new GameProfile(uuid, name);
        if (skin != null) {
            profile.getProperties().put("textures", new Property("textures", skin.texture(), skin.signature()));
        }
        return profile;
    }

    public void refreshPlayerProfile(Persona persona) {
        ServerPlayer serverPlayer = getVanillaPlayer(persona);
        if (serverPlayer == null)
            return;
        GameProfile profile = serverPlayer.getGameProfile();
        if (persona.getSkin() != null) {
            profile.getProperties().removeAll("textures");
            profile.getProperties().put("textures", new Property("textures", persona.getSkin().texture(), persona.getSkin().signature()));
        }
        TrackedEntity trackedEntity = serverPlayer.level().getChunkSource().chunkMap.entityMap.get(serverPlayer.getId());
        if (trackedEntity != null) {
            ClientboundPlayerInfoRemovePacket removePacket = new ClientboundPlayerInfoRemovePacket(List.of(serverPlayer.getUUID()));
            ClientboundPlayerInfoUpdatePacket addPacket = ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(serverPlayer));
            for (ServerPlayerConnection connection : trackedEntity.seenBy) {
                connection.send(removePacket);
                connection.send(addPacket);
            }
        }
    }

    @SuppressWarnings("unused")
    private void broadcastPacket(ServerPlayer from, net.minecraft.network.protocol.Packet<?> packet) {
        minecraftServer.getPlayerList().broadcastAll(packet, from.level().dimension());
    }

    private void broadcastPacketToTrackers(ServerPlayer from, net.minecraft.network.protocol.Packet<?> packet) {
        TrackedEntity trackedEntity = from.level().getChunkSource().chunkMap.entityMap.get(from.getId());
        if (trackedEntity != null) {
            trackedEntity.broadcast(packet);
        }
    }

    @Nullable
    private ServerPlayer getVanillaPlayer(Persona persona) {
        Entity entity = persona.getEntity();
        if (entity instanceof CraftPlayer craftPlayer) {
            return craftPlayer.getHandle();
        }
        return null;
    }
}