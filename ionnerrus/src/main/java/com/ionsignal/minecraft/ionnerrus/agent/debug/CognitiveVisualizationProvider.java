package com.ionsignal.minecraft.ionnerrus.agent.debug;

import com.ionsignal.minecraft.ioncore.debug.VisualizationProvider;
import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;

/**
 * Renders cognitive debug state into the Minecraft world using the ChatBubble system.
 */
public class CognitiveVisualizationProvider implements VisualizationProvider<CognitiveDebugState> {

    private final IonNerrus plugin;

    public CognitiveVisualizationProvider(IonNerrus plugin) {
        this.plugin = plugin;
    }

    @Override
    public void render(CognitiveDebugState state) {
        // Find the specific agent mentioned in the snapshot
        NerrusAgent agent = plugin.getAgentService().findAgentBySessionId(state.agentId());
        if (agent == null || !agent.getPersona().isSpawned())
            return;

        String hologramText = String.format(
                "§b§l[BRAIN PAUSED]§r\n" +
                        "§7Step: §f%d\n" +
                        "§7Last Tool: §e%s\n" +
                        "§bWaiting for Web Approval...",
                state.stepCount(),
                state.lastTool() != null ? state.lastTool() : "None");

        if (plugin.getChatBubbleService() != null) {
            plugin.getChatBubbleService().showBubble(
                    agent.getPersona().getPersonaEntity().getBukkitEntity(),
                    hologramText);
        }
    }

    @Override
    public Class<CognitiveDebugState> getStateType() {
        return CognitiveDebugState.class;
    }
}