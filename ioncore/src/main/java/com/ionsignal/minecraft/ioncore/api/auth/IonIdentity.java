package com.ionsignal.minecraft.ioncore.api.auth;

import java.util.Optional;
import java.util.UUID;

/**
 * Represents the cached identity state of a Minecraft player within the Ion ecosystem.
 *
 * @param minecraftUuid
 *            The in-game UUID.
 * @param minecraftUsername
 *            The in-game username.
 * @param webUsername
 *            The linked web account username (if linked).
 * @param isLinked
 *            Whether the account is fully linked.
 */
public record IonIdentity(
        UUID minecraftUuid,
        String minecraftUsername,
        Optional<String> webUsername,
        boolean isLinked) {
    /**
     * Creates an unlinked identity.
     */
    public static IonIdentity unlinked(UUID uuid, String username) {
        return new IonIdentity(uuid, username, Optional.empty(), false);
    }

    /**
     * Creates a linked identity.
     */
    public static IonIdentity linked(UUID uuid, String username, String webUsername) {
        return new IonIdentity(uuid, username, Optional.ofNullable(webUsername), true);
    }
}