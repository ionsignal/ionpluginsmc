package com.ionsignal.minecraft.ioncore.config;

import com.ionsignal.minecraft.ioncore.IonCore;

/**
 * Resolves the multi-tenant configuration for the stateless Java Engine.
 */
public class TenantConfig {
    private final String tenantId;
    private final String natsServerUrl;
    private final String natsToken;
    private final Long natsRpcTimeoutSeconds;

    public TenantConfig(IonCore plugin) {
        // Resolve Tenant ID (LXD Env Var > Config > Default)
        String envTenant = System.getenv("ION_TENANT_ID");
        if (envTenant != null && !envTenant.isBlank()) {
            this.tenantId = envTenant;
        } else {
            this.tenantId = plugin.getConfig().getString("tenant.id", "tenant-default");
        }
        // Resolve NATS Server URL
        String envNats = System.getenv("NATS_URL");
        if (envNats != null && !envNats.isBlank()) {
            this.natsServerUrl = envNats;
        } else {
            this.natsServerUrl = plugin.getConfig().getString("nats.url", "nats://localhost:4222");
        }
        // Resolve NATS Token
        String envNatsToken = System.getenv("NATS_TOKEN");
        if (envNatsToken != null && !envNatsToken.isBlank()) {
            this.natsToken = envNatsToken;
        } else {
            this.natsToken = plugin.getConfig().getString("nats.token", null);
        }
        // Resolve NATS RPC Timeout'
        this.natsRpcTimeoutSeconds = plugin.getConfig().getLong("nats.rpc-timeout-seconds", 3L);
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getNatsServerUrl() {
        return natsServerUrl;
    }

    public String getNatsToken() {
        return natsToken;
    }

    public Long getRpcTimeoutSeconds() {
        return natsRpcTimeoutSeconds;
    }
}