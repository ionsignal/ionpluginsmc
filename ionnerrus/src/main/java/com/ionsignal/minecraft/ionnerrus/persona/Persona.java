package com.ionsignal.minecraft.ionnerrus.persona;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.persona.action.ActionController;
import com.ionsignal.minecraft.ionnerrus.persona.animation.PlayerAnimation;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.Navigator;
import com.ionsignal.minecraft.ionnerrus.persona.skin.SkinData;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;

import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.level.GameType;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;

import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public class Persona {
    private final UUID uuid;
    private final EntityType entityType;
    private final NerrusManager manager;

    private final Navigator navigator;
    private final ActionController actionController;
    private final MetadataStorage metadata;

    private String name;
    private PersonaEntity personaEntity;
    private Location lastLocation;
    private SkinData skinData;

    public Persona(NerrusManager manager, UUID uuid, String name, EntityType entityType) {
        this.manager = manager;
        this.uuid = uuid;
        this.name = name;
        this.entityType = entityType;
        this.navigator = new Navigator(this);
        this.actionController = new ActionController(this);
        this.metadata = new MetadataStorage();
    }

    public void spawn(Location location) {
        if (isSpawned()) {
            despawn();
        }
        this.lastLocation = location.clone();
        ServerLevel world = ((CraftWorld) location.getWorld()).getHandle();
        GameProfile profile = createGameProfile(uuid, name, skinData);
        MinecraftServer server = world.getServer();
        this.personaEntity = new PersonaEntity(server, world, profile, this);
        personaEntity.setGameMode(GameType.SURVIVAL);
        personaEntity.setPos(location.getX(), location.getY(), location.getZ());
        personaEntity.setRot(location.getYaw(), location.getPitch());
        personaEntity.setYHeadRot(location.getYaw());
        ClientboundPlayerInfoUpdatePacket addPlayerPacket = ClientboundPlayerInfoUpdatePacket.createSinglePlayerInitializing(personaEntity,
                false);
        for (org.bukkit.entity.Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            ((CraftPlayer) onlinePlayer).getHandle().connection.send(addPlayerPacket);
            personaEntity.grantVisibility(onlinePlayer);
        }
        world.addFreshEntity(personaEntity);
        manager.getRegistry().register(this);
    }

    public void despawn() {
        if (!isSpawned()) {
            manager.getLogger().warning("Trying to despawn a `!isSpawned()` PersonaEntity.");
            return;
        }
        this.lastLocation = getLocation();
        int oldId = this.personaEntity.getId();
        ClientboundPlayerInfoRemovePacket removePacket = new ClientboundPlayerInfoRemovePacket(java.util.List.of(personaEntity.getUUID()));
        for (org.bukkit.entity.Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            ((CraftPlayer) onlinePlayer).getHandle().connection.send(removePacket);
        }
        personaEntity.remove(net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
        this.personaEntity = null;
        manager.getRegistry().updateEntityId(this, oldId);
    }

    public boolean isSpawned() {
        return this.personaEntity != null && this.personaEntity.getBukkitEntity().isValid();
    }

    public void tick() {
        if (!isSpawned()) {
            return;
        }
        if (isInventoryLocked()) {
            return;
        }
        navigator.tick();
        actionController.tick();
    }

    public void teleport(Location location, TeleportCause cause) {
        if (!isSpawned()) {
            this.lastLocation = location;
            return;
        }
        this.personaEntity.getBukkitEntity().teleport(location, cause);
    }

    public void playAnimation(PlayerAnimation animation) {
        if (!isSpawned())
            return;
        // Inlined from PersonaNMSBridge.playAnimation()
        int animationId = switch (animation) {
            case SWING_MAIN_ARM -> ClientboundAnimatePacket.SWING_MAIN_HAND;
            case TAKE_DAMAGE -> 1;
            case LEAVE_BED -> ClientboundAnimatePacket.WAKE_UP;
            case SWING_OFF_HAND -> ClientboundAnimatePacket.SWING_OFF_HAND;
            case CRITICAL_EFFECT -> ClientboundAnimatePacket.CRITICAL_HIT;
            case MAGIC_CRITICAL_EFFECT -> ClientboundAnimatePacket.MAGIC_CRITICAL_HIT;
        };
        broadcastPacketToTrackers(new ClientboundAnimatePacket(personaEntity, animationId));
    }

    public void speak(String message) {
        if (!isSpawned()) {
            return;
        }
        // Currently we only use chat bubbles
        IonNerrus plugin = IonNerrus.getInstance();
        if (plugin.getPluginConfig().isChatBubblesEnabled() && plugin.getChatBubbleService() != null) {
            plugin.getChatBubbleService().showBubble(this.getPersonaEntity().getBukkitEntity(), message);
        }
    }

    public UUID getUniqueId() {
        return this.uuid;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
        if (isSpawned()) {
            Location loc = getLocation();
            despawn();
            spawn(loc);
        }
    }

    @Nullable
    public SkinData getSkin() {
        return skinData;
    }

    public void setSkin(SkinData skinData) {
        this.skinData = skinData;
        if (isSpawned()) {
            refreshPlayerProfile();
        }
    }

    private GameProfile createGameProfile(UUID uuid, String name, SkinData skin) {
        GameProfile profile = new GameProfile(uuid, name);
        if (skin != null) {
            profile.getProperties().put("textures",
                    new Property("textures", skin.texture(), skin.signature()));
        }
        return profile;
    }

    private void broadcastPacketToTrackers(net.minecraft.network.protocol.Packet<?> packet) {
        ServerLevel level = personaEntity.level();
        var trackedEntity = level.getChunkSource().chunkMap.entityMap.get(personaEntity.getId());
        if (trackedEntity != null) {
            trackedEntity.broadcast(packet);
        }
    }

    private void refreshPlayerProfile() {
        GameProfile profile = personaEntity.getGameProfile();
        if (skinData != null) {
            profile.getProperties().removeAll("textures");
            profile.getProperties().put("textures",
                    new Property("textures", skinData.texture(), skinData.signature()));
        }

        var trackedEntity = personaEntity.level().getChunkSource().chunkMap.entityMap.get(personaEntity.getId());
        if (trackedEntity != null) {
            ClientboundPlayerInfoRemovePacket removePacket = new ClientboundPlayerInfoRemovePacket(
                    java.util.List.of(personaEntity.getUUID()));
            ClientboundPlayerInfoUpdatePacket addPacket = ClientboundPlayerInfoUpdatePacket
                    .createPlayerInitializing(java.util.List.of(personaEntity));

            for (var connection : trackedEntity.seenBy) {
                connection.send(removePacket);
                connection.send(addPacket);
            }
        }
    }

    @SuppressWarnings("null")
    public void showToPlayer(org.bukkit.entity.Player viewer) {
        if (!isSpawned())
            return;
        ServerPlayer viewerPlayer = ((CraftPlayer) viewer).getHandle();
        ClientboundPlayerInfoUpdatePacket addPacket = ClientboundPlayerInfoUpdatePacket.createSinglePlayerInitializing(personaEntity,
                false);
        viewerPlayer.connection.send(addPacket);
        personaEntity.grantVisibility(viewer);
    }

    /**
     * Calculates how many ticks it will take this persona to break a block.
     * Factors in tool efficiency, enchantments, and mining effects.
     * 
     * @param block
     *            The block to break
     * @return Number of ticks required, or Integer.MAX_VALUE if unbreakable
     */
    public int calculateBreakTicks(org.bukkit.block.Block block) {
        if (!isSpawned()) {
            return Integer.MAX_VALUE;
        }
        // Inlined from PersonaNMSBridge.calculateBreakTicks()
        ServerLevel level = personaEntity.level();
        net.minecraft.core.BlockPos blockPos = new net.minecraft.core.BlockPos(block.getX(), block.getY(), block.getZ());
        net.minecraft.world.level.block.state.BlockState nmsBlockState = level.getBlockState(blockPos);
        float hardness = nmsBlockState.getDestroySpeed(level, blockPos);
        if (hardness < 0) {
            return Integer.MAX_VALUE; // Unbreakable (e.g., bedrock)
        }
        if (hardness == 0) {
            return 1; // Instantly breakable (e.g., flower)
        }
        float destroySpeed = getPlayerDestroySpeed(nmsBlockState);
        boolean hasCorrectTool = personaEntity.hasCorrectToolForDrops(nmsBlockState);
        float divisor = hasCorrectTool ? 30.0f : 100.0f;
        float damagePerTick = destroySpeed / hardness / divisor;
        if (damagePerTick <= 0) {
            return Integer.MAX_VALUE;
        }
        return (int) Math.ceil(1.0 / damagePerTick);
    }

    /**
     * Sends a block break animation packet to nearby players.
     * 
     * @param block
     *            The block being broken
     * @param stage
     *            Break stage (0-9), or -1 to clear animation
     */
    public void sendBlockBreakAnimation(org.bukkit.block.Block block, int stage) {
        if (!isSpawned()) {
            return;
        }
        net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket packet = new net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket(
                personaEntity.getId(),
                new net.minecraft.core.BlockPos(block.getX(), block.getY(), block.getZ()),
                stage);
        broadcastPacketToTrackers(packet);
    }

    /**
     * Destroys a block using this persona's game mode logic.
     * 
     * @param block
     *            The block to destroy
     * @return true if successfully destroyed, false if cancelled by plugin/world rules
     */
    public boolean destroyBlock(org.bukkit.block.Block block) {
        if (!isSpawned()) {
            return false;
        }
        // Inlined from PersonaNMSBridge.destroyBlock()
        net.minecraft.server.level.ServerPlayerGameMode gameMode = personaEntity.gameMode;
        net.minecraft.core.BlockPos blockPos = new net.minecraft.core.BlockPos(block.getX(), block.getY(), block.getZ());
        return gameMode.destroyBlock(blockPos);
    }

    /**
     * Helper method to calculate destroy speed with enchantments and effects.
     * Extracted from PersonaNMSBridge for calculateBreakTicks().
     */
    private float getPlayerDestroySpeed(net.minecraft.world.level.block.state.BlockState nmsBlockState) {
        var enchantmentRegistry = personaEntity.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT);
        float speed = personaEntity.getInventory().getSelectedItem().getDestroySpeed(nmsBlockState);
        if (speed > 1.0F) {
            var efficiencyHolder = enchantmentRegistry.getOrThrow(net.minecraft.world.item.enchantment.Enchantments.EFFICIENCY);
            int efficiencyLevel = net.minecraft.world.item.enchantment.EnchantmentHelper.getItemEnchantmentLevel(
                    efficiencyHolder,
                    personaEntity.getMainHandItem());
            if (efficiencyLevel > 0) {
                speed += (float) (efficiencyLevel * efficiencyLevel + 1);
            }
        }
        if (personaEntity.hasEffect(net.minecraft.world.effect.MobEffects.HASTE)) {
            speed *= 1.0F + (float) (java.util.Objects.requireNonNull(
                    personaEntity.getEffect(net.minecraft.world.effect.MobEffects.HASTE)).getAmplifier() + 1) * 0.2F;
        }
        if (personaEntity.hasEffect(net.minecraft.world.effect.MobEffects.MINING_FATIGUE)) {
            float fatigueMultiplier = switch (java.util.Objects.requireNonNull(
                    personaEntity.getEffect(net.minecraft.world.effect.MobEffects.MINING_FATIGUE)).getAmplifier()) {
                case 0 -> 0.3F;
                case 1 -> 0.09F;
                case 2 -> 0.0027F;
                default -> 8.1E-4F;
            };
            speed *= fatigueMultiplier;
        }
        var aquaHolder = enchantmentRegistry.getOrThrow(net.minecraft.world.item.enchantment.Enchantments.AQUA_AFFINITY);
        int aquaLevel = net.minecraft.world.item.enchantment.EnchantmentHelper.getItemEnchantmentLevel(
                aquaHolder,
                personaEntity.getMainHandItem());
        if (personaEntity.isEyeInFluid(net.minecraft.tags.FluidTags.WATER) && aquaLevel == 0) {
            speed /= 5.0F;
        }
        if (!personaEntity.onGround()) {
            speed /= 5.0F;
        }

        return speed;
    }

    public Location getLocation() {
        return isSpawned() ? this.personaEntity.getBukkitEntity().getLocation() : lastLocation;
    }

    public Navigator getNavigator() {
        return navigator;
    }

    public NerrusManager getManager() {
        return manager;
    }

    @Nullable
    public PlayerInventory getInventory() {
        if (!isSpawned()) {
            return null;
        }
        return this.personaEntity.getBukkitEntity().getInventory();
    }

    @Nullable
    public Container getNmsInventory() {
        if (!isSpawned()) {
            return null;
        }
        return this.personaEntity.getInventory();
    }

    public boolean isInventoryLocked() {
        return isSpawned() && personaEntity.isInventoryLocked();
    }

    @Nullable
    public Entity getEntity() {
        return (this.personaEntity != null && this.personaEntity.isAlive()) ? this.personaEntity.getBukkitEntity() : null;
    }

    @Nullable
    public PersonaEntity getPersonaEntity() {
        return personaEntity;
    }

    public ActionController getActionController() {
        return actionController;
    }

    public MetadataStorage getMetadata() {
        return metadata;
    }

    public EntityType getEntityType() {
        return entityType;
    }
}