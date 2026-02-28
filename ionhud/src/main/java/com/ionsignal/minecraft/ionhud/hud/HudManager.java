package com.ionsignal.minecraft.ionnerrus.hud;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;

import net.momirealms.craftengine.bukkit.api.event.AsyncResourcePackCacheEvent;
import net.momirealms.craftengine.bukkit.api.event.AsyncResourcePackGenerateEvent;
import net.momirealms.craftengine.core.pack.allocator.IdAllocator;
import net.momirealms.craftengine.core.util.CharacterUtils;
import net.momirealms.craftengine.core.util.Key;

import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.stream.Collectors;

/**
 * Manages HUD element registration and Component creation.
 * 
 * Architecture:
 * - Phase 1: Validates images and stores configurations
 * - Phase 2: Integrates with CraftEngine's IdAllocator and event system
 * - Provides convenience Component builders (PENDING)
 * 
 * Thread Safety: register() must be called during plugin init (main thread).
 * Component creation methods are thread-safe after registration completes.
 */
public class HudManager implements Listener {
    private static final Key HUD_FONT = Key.of("ionnerrus", "hud");
    private static final int CODEPOINT_START = 0xF000;
    private static final int CODEPOINT_END = 0xF8FF;
    private static final int TILE_HEIGHT = 10;

    private final IonNerrus plugin;

    // Thread safe tracking collections
    private final ConcurrentHashMap<Key, PendingRegistration> pendingAllocations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Key, HudShaderConfig> registeredElements = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, PlayerHudState> playerStates = new ConcurrentHashMap<>();

    // Add codepoint allocator for stable character assignments
    // Shader template strings (loaded from resources)
    private final IdAllocator shaderIdAllocator;
    private final IdAllocator codepointAllocator;
    private final String vertexShaderTemplate;
    private final String fragmentShaderTemplate;
    private final String renderTypeTemplate;
    private final int negativeSpaceCodepoint;

    /**
     * Stores pending allocations before batch processing.
     */
    private record PendingRegistration(
            HudElement element,
            CompletableFuture<Integer> codepointFuture,
            CompletableFuture<Integer> shaderIdFuture) {
    }

    public HudManager(IonNerrus plugin) {
        this.plugin = plugin;
        // Initialize codepoint allocator using Unicode Private Use Area (U+E000-U+F8FF)
        Path cachePath = plugin.getDataFolder().toPath()
                .resolve("cache").resolve("hud_codepoints.json");
        this.codepointAllocator = new IdAllocator(cachePath);
        this.codepointAllocator.reset(CODEPOINT_START, CODEPOINT_END);
        // Load existing allocations from disk cache
        try {
            this.codepointAllocator.loadFromCache();
            plugin.getLogger().info("Loaded HUD codepoint cache from disk.");
        } catch (IOException e) {
            plugin.getLogger().warning("Could not load HUD codepoint cache (will start fresh): " + e.getMessage());
        }
        // Allocate negative space (infrastructure requirement)
        try {
            CompletableFuture<Integer> future = this.codepointAllocator.requestAutoId("ionnerrus:negative_space");
            this.codepointAllocator.processPendingAllocations();
            this.negativeSpaceCodepoint = future.get(1, TimeUnit.SECONDS);
            plugin.getLogger().info("Reserved negative space codepoint: U+" +
                    Integer.toHexString(negativeSpaceCodepoint).toUpperCase());
        } catch (Exception e) {
            // Negative space is required for overlay system
            throw new IllegalStateException("Failed to allocate negative space codepoint - HUD system cannot initialize", e);
        }
        // Load shader templates from bundled resources
        try {
            this.vertexShaderTemplate = loadResourceAsString("shaders/text.vsh");
            this.fragmentShaderTemplate = loadResourceAsString("shaders/text.fsh");
            this.renderTypeTemplate = loadResourceAsString("shaders/rendertype_text.json");
            plugin.getLogger().info("Loaded HUD shader templates.");
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load HUD shader templates - required resource files missing", e);
        }
        // Add shader ID allocator
        Path shaderIdCache = plugin.getDataFolder().toPath()
                .resolve("cache").resolve("hud_shader_ids.json");
        this.shaderIdAllocator = new IdAllocator(shaderIdCache);
        this.shaderIdAllocator.reset(1, Integer.MAX_VALUE);
        try {
            this.shaderIdAllocator.loadFromCache();
        } catch (IOException e) {
            plugin.getLogger().warning("Could not load shader ID cache: " + e.getMessage());
        }
    }

    /**
     * Loads a bundled resource file as a String.
     * 
     * @param resourcePath
     *            Path relative to src/main/resources (e.g., "shaders/text.vsh")
     * @return File contents as String
     * @throws IOException
     *             if resource not found or read fails
     */
    private String loadResourceAsString(String resourcePath) throws IOException {
        try (InputStream is = plugin.getResource(resourcePath)) {
            if (is == null) {
                throw new IOException("Resource not found: " + resourcePath);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Registers a HUD element (non-blocking).
     * 
     * This method queues the element for registration but does NOT allocate codepoints yet.
     * You must call {@link #finishRegistration()} after all elements are registered
     * to complete the allocation process.
     * 
     * Design Pattern:
     * This follows CraftEngine's IdAllocator batch pattern:
     * 1. Queue allocations (this method)
     * 2. Process batch (finishRegistration)
     * 3. Await results
     * 
     * @param element
     *            The HUD element configuration to register
     * @throws IllegalArgumentException
     *             if validation fails or element already registered
     */
    public void register(HudElement element) {
        // Check for duplicate registration
        if (registeredElements.containsKey(element.id()) || pendingAllocations.containsKey(element.id())) {
            throw new IllegalArgumentException("HUD element already registered: " + element.id().asString());
        }
        // Validate texture file exists (without CraftEngine dependency)
        if (!validateTextureExists(element.atlasFile())) {
            throw new IllegalArgumentException("Texture file not found in resource packs: " + element.atlasFile() +
                    ". Ensure it exists in a loaded resource pack.");
        }
        // Queue allocations (NON-BLOCKING - futures won't complete until processPendingAllocations())
        CompletableFuture<Integer> codepointFuture = codepointAllocator.requestAutoId(element.id().asString());
        CompletableFuture<Integer> shaderIdFuture = shaderIdAllocator.requestAutoId(element.id().asString());
        // Store for batch processing
        pendingAllocations.put(element.id(), new PendingRegistration(element, codepointFuture, shaderIdFuture));
        plugin.getLogger().fine("Queued HUD element for registration: " + element.id().asString());
    }

    /**
     * Validates that a texture file exists in the resource pack structure.
     * This checks both IonNerrus and CraftEngine resource locations.
     */
    private boolean validateTextureExists(String atlasFile) {
        // Parse namespace:path format
        String[] parts = atlasFile.split(":", 2);
        if (parts.length != 2) {
            plugin.getLogger().warning("Invalid texture file format: " + atlasFile +
                    ". Expected 'namespace:path' format.");
            return false;
        }
        String namespace = parts[0];
        String path = parts[1];
        if (!path.endsWith(".png")) {
            plugin.getLogger().warning("Texture file must be a PNG: " + atlasFile);
            return false;
        }
        // Check multiple possible locations
        // 1. Check in CraftEngine's resources folder (PRIMARY LOCATION)
        Path craftEngineResourcePath = plugin.getDataFolder().toPath()
                .getParent() // Go up from plugins/IonNerrus
                .resolve("CraftEngine")
                .resolve("resources")
                .resolve(namespace)
                .resolve("resourcepack")
                .resolve("assets")
                .resolve(namespace)
                .resolve("textures")
                .resolve(path);
        if (Files.exists(craftEngineResourcePath)) {
            plugin.getLogger().fine("Found texture in CraftEngine resources: " + craftEngineResourcePath);
            return true;
        }
        // 2. Check in plugin's resources folder (for bundled textures)
        Path pluginResourcePath = plugin.getDataFolder().toPath()
                .resolve("resources")
                .resolve("assets")
                .resolve(namespace)
                .resolve("textures")
                .resolve(path);
        if (Files.exists(pluginResourcePath)) {
            plugin.getLogger().fine("Found texture in IonNerrus resources: " + pluginResourcePath);
            return true;
        }
        // 3. Check in temp pack directory (if it exists from a previous generation)
        Path tempDir = plugin.getDataFolder().toPath().resolve("temp_hud_pack");
        Path tempTexturePath = tempDir
                .resolve("assets")
                .resolve(namespace)
                .resolve("textures")
                .resolve(path);
        if (Files.exists(tempTexturePath)) {
            plugin.getLogger().fine("Found texture in temp pack: " + tempTexturePath);
            return true;
        }
        // 4. Check in server's resource pack directory (if configured)
        Path serverPackPath = plugin.getServer().getWorldContainer().toPath()
                .resolve("resourcepacks")
                .resolve("assets")
                .resolve(namespace)
                .resolve("textures")
                .resolve(path);

        if (Files.exists(serverPackPath)) {
            plugin.getLogger().fine("Found texture in server resourcepacks: " + serverPackPath);
            return true;
        }
        // Not found in any location - log detailed error
        plugin.getLogger().warning(String.format(
                "Texture file not found: %s. Checked locations:\n" +
                        "  1. CraftEngine: %s\n" +
                        "  2. IonNerrus:   %s\n" +
                        "  3. Temp pack:   %s\n" +
                        "  4. Server pack: %s",
                atlasFile,
                craftEngineResourcePath,
                pluginResourcePath,
                tempTexturePath,
                serverPackPath));
        return false;
    }

    /**
     * Listens for CraftEngine's resource pack cache event and contributes HUD resources.
     * 
     * This event fires during `/ce reload pack` and provides access to the virtual filesystem
     * where CraftEngine merges all resource sources. We generate our shaders and font files
     * into a temporary directory, then register that directory with CraftEngine.
     * 
     * CraftEngine will merge our files with other resources, handle conflicts, and compile
     * the final resource pack ZIP file.
     * 
     * @param event
     *            The CraftEngine cache event providing resource registration API
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onResourcePackCache(AsyncResourcePackCacheEvent event) {
        if (registeredElements.isEmpty()) {
            return; // Nothing to generate
        }
        // Create temporary directory for generated pack files
        Path tempDir = plugin.getDataFolder().toPath().resolve("temp_hud_pack");
        try {
            // Cleanup old directory
            if (Files.exists(tempDir)) {
                deleteDirectory(tempDir);
            }
            // Generate all necessary resource pack files
            Path fontDir = tempDir.resolve("assets/ionnerrus/font");
            Path shaderDir = tempDir.resolve("assets/minecraft/shaders/core");
            Files.createDirectories(fontDir);
            Files.createDirectories(shaderDir);
            // Generate shader files with element-specific transforms
            generateShaders(shaderDir);
            // Generate font provider JSON with proper char mappings
            generateFontProvider(fontDir);
            // Register temp directory with CraftEngine to be merged into final pack
            event.registerExternalResourcePack(tempDir);
            plugin.getLogger().info("Contributed " + registeredElements.size() + " HUD elements to CraftEngine resource pack build.");
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to generate and contribute HUD resources", e);
        }
    }

    @EventHandler
    public void onResourcePackGenerate(AsyncResourcePackGenerateEvent event) {
        // Pack generation complete - safe to delete temp files
        try {
            Path tempDir = plugin.getDataFolder().toPath().resolve("temp_hud_pack");
            if (Files.exists(tempDir)) {
                deleteDirectory(tempDir);
                plugin.getLogger().fine("Cleaned up temporary HUD pack directory.");
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to clean up temp HUD pack: " + e.getMessage());
        }
    }

    private void generateFontProvider(Path fontFile) throws IOException {
        JsonObject rootJson = new JsonObject();
        JsonArray providers = new JsonArray();
        // Create space provider
        JsonObject spaceProvider = new JsonObject();
        spaceProvider.addProperty("type", "space");
        JsonObject advances = new JsonObject();
        advances.addProperty(CharacterUtils.encodeCharsToUnicode(
                Character.toChars(negativeSpaceCodepoint)), -11);
        spaceProvider.add("advances", advances);
        providers.add(spaceProvider);
        // Group elements by atlas file
        Map<String, List<HudElement>> elementsByAtlas = registeredElements.values()
                .stream()
                .map(HudShaderConfig::element)
                .collect(Collectors.groupingBy(HudElement::atlasFile));
        // This prevents collisions between empty tiles and registered elements
        int maxAllocatedCodepoint = CODEPOINT_START - 1; // Start before first allocation
        for (HudShaderConfig config : registeredElements.values()) {
            maxAllocatedCodepoint = Math.max(maxAllocatedCodepoint, config.codepoint());
        }
        int nextEmptyTileCodepoint = maxAllocatedCodepoint + 1;
        // Create one provider per atlas
        for (Map.Entry<String, List<HudElement>> entry : elementsByAtlas.entrySet()) {
            JsonObject provider = new JsonObject();
            String atlasFile = entry.getKey();
            List<HudElement> elements = entry.getValue();
            provider.addProperty("type", "bitmap");
            provider.addProperty("file", atlasFile); // Full atlas path
            provider.addProperty("height", TILE_HEIGHT); // Single tile height
            provider.addProperty("ascent", 0); // Standard ascent (no encoding)
            int atlasRows = 25; // 256px / 16px tiles = 16 rows
            int atlasCols = 25; // 256px / 16px tiles = 16 columns
            Map<Integer, Map<Integer, HudShaderConfig>> grid = new HashMap<>();
            // Populate grid from registered elements
            for (HudElement element : elements) {
                HudShaderConfig config = registeredElements.get(element.id());
                grid.computeIfAbsent(element.tileY(), k -> new HashMap<>())
                        .put(element.tileX(), config);
            }
            // Generate complete character grid (one row per Y coordinate) because resource packs compute
            // individual glyph width based on the bitmap width and number contained per row
            JsonArray chars = new JsonArray();
            for (int y = 0; y < atlasRows; y++) {
                StringBuilder row = new StringBuilder();
                Map<Integer, HudShaderConfig> rowElements = grid.get(y);
                // Build complete row (including empty tiles)
                for (int x = 0; x < atlasCols; x++) {
                    HudShaderConfig config = rowElements != null ? rowElements.get(x) : null;
                    if (config != null) {
                        // Use allocated codepoint for this element
                        row.append(Character.toChars(config.codepoint()));
                    } else {
                        // Assign unique codepoint to empty tile
                        row.append(Character.toChars(nextEmptyTileCodepoint));
                        nextEmptyTileCodepoint++;
                    }
                }
                // Escape for JSON (CraftEngine pattern)
                String escapedRow = CharacterUtils.encodeCharsToUnicode(row.toString().toCharArray());
                chars.add(escapedRow);
            }
            provider.add("chars", chars);
            providers.add(provider);
        }
        rootJson.add("providers", providers);
        // Write with proper escaping (CraftEngine pattern)
        Gson gson = new Gson();
        String jsonString = gson.toJson(rootJson);
        jsonString = jsonString.replace("\\\\u", "\\u");
        Files.writeString(fontFile.resolve("hud.json"), jsonString, StandardCharsets.UTF_8);
    }

    /**
     * Generates the vertex and fragment shaders by populating the templates.
     * 
     * The vertex shader template contains a `#CreateLayout` marker that is replaced
     * with a GLSL switch statement. Each case applies the transforms for one HUD element:
     * - GUI-relative positioning (xGui, yGui)
     * - Z-index layering (layer)
     * - Opacity blending (opacity)
     * - Scale transforms (modifying pos.x and pos.y)
     * - Outline rendering flag (outline)
     * 
     * @param shaderDir
     *            The directory to write shader files into
     * @throws IOException
     *             if file writing fails
     */
    private void generateShaders(Path shaderDir) throws IOException {
        // Build the GLSL switch statement from registered elements
        StringBuilder layoutCases = new StringBuilder();
        // Sort by shader ID to ensure consistent ordering
        registeredElements.values().stream().sorted(Comparator.comparingInt(HudShaderConfig::shaderId)).forEach(config -> {
            // HudElement e = config.element();
            // Generate GLSL case statement with element-specific transforms
            // layoutCases.append(String.format(Locale.US, // accounting for whitespace
            // "case %d:\n" +
            // " xGui = ui.x * %d / 100.0;\n" +
            // " yGui = ui.y * %d / 100.0;\n" +
            // " layer = %d;\n" +
            // " opacity = %.4f;\n" +
            // " scale = %.4f;\n" +
            // " outline = %b;\n" +
            // " break;\n",
            // config.shaderId(),
            // e.gridY(),
            // e.gridX(),
            // e.layer(),
            // e.opacity(),
            // e.scale(),
            // e.outline()));
        });
        // Inject the switch cases into the vertex shader template
        String finalVertexShader = vertexShaderTemplate.replace("#CreateLayout", layoutCases.toString());
        // Write shader files to disk
        Files.writeString(shaderDir.resolve("rendertype_text.vsh"), finalVertexShader);
        Files.writeString(shaderDir.resolve("rendertype_text.fsh"), fragmentShaderTemplate);
        Files.writeString(shaderDir.resolve("rendertype_text.json"), renderTypeTemplate);
    }

    /**
     * Cleans up per-player state when players disconnect.
     * 
     * Critical: Must unregister boss bar to prevent memory leaks.
     * The boss bar instance holds references to the player, so failing
     * to cleanup would prevent garbage collection.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        PlayerHudState state = playerStates.remove(event.getPlayer().getUniqueId());
        if (state != null) {
            state.cleanup(event.getPlayer());
            plugin.getLogger().fine(
                    "Cleaned up HUD state for disconnected player: " + event.getPlayer().getName());
        }
    }

    /**
     * Updates a player's HUD overlay based on current visibility.
     * 
     * This method:
     * 1. Determines which elements should be visible
     * 2. Checks if visibility changed since last update
     * 3. Rebuilds Component only if necessary (update throttling)
     * 4. Sends updated Component to boss bar
     * 
     * Performance: Component building is cheap (cached codepoints), but
     * sending packets to clients is expensive. Only rebuild when needed.
     * 
     * @param player
     *            The player whose HUD should be updated
     * @param visibleElements
     *            Set of element IDs that should be shown
     * @param currentTick
     *            Server tick counter (for throttling)
     */
    public void updatePlayerHud(Player player, Set<Key> visibleElements, long currentTick) {
        // Get or create player state
        PlayerHudState state = playerStates.computeIfAbsent(
                player.getUniqueId(),
                uuid -> new PlayerHudState(player));
        // Build overlay Component from visible elements
        Component overlay = buildOverlay(visibleElements);
        // Update boss bar if visibility changed
        if (state.updateIfNeeded(visibleElements, overlay, currentTick)) {
            plugin.getLogger().fine(String.format(
                    "Updated HUD for %s: %d elements visible",
                    player.getName(),
                    visibleElements.size()));
        }
    }

    /**
     * Builds a composite Component from multiple HUD elements.
     * 
     * Assembly Strategy: Shader does positioning automatically via encoded
     * ascent values, so we just append Components. Elements are sorted by
     * layer (z-index) to ensure correct rendering order.
     * 
     * @param elementIds
     *            Set of element IDs to include in overlay
     * @return Combined Component ready for boss bar rendering
     */
    private Component buildOverlay(Set<Key> elementIds) {
        if (elementIds.isEmpty()) {
            return Component.empty();
        }
        // Sort by layer (z-index) - lower layers first, higher layers on top
        List<Key> sortedIds = elementIds.stream()
                .sorted(Comparator.comparingInt(id -> {
                    HudShaderConfig config = registeredElements.get(id);
                    if (config == null) {
                        plugin.getLogger().warning(
                                "Element " + id.asString() + " not registered during overlay build");
                        return Integer.MAX_VALUE;
                    }
                    return config.element().layer();
                }))
                .toList();
        // Append all elements (shader handles positioning)
        boolean first = true;
        Component result = Component.empty();
        for (Key id : sortedIds) {
            if (!first) {
                // Create negative space character with HUD font
                char[] negSpaceChar = Character.toChars(negativeSpaceCodepoint);
                Component negSpace = Component.text(new String(negSpaceChar))
                        .font(net.kyori.adventure.key.Key.key(HUD_FONT.asString()));
                result = result.append(negSpace);
            }
            // Append: [negative space][element] causes element to overlap previous
            result = result.append(createComponent(id));
            first = false;
        }
        return result;

    }

    /**
     * Checks if a HUD element is registered and ready to use.
     * 
     * Useful for conditional rendering without catching exceptions. Prefer this over try-catch when
     * checking
     * availability before rendering.
     * 
     * Thread Safety: Safe to call from any thread after registration completes.
     * 
     * @param elementId
     *            The element ID to check
     * @return true if the element is registered, false otherwise
     */
    public boolean isRegistered(Key elementId) {
        // Simple containsKey check
        return registeredElements.containsKey(elementId);
    }

    /**
     * Gets all registered element IDs for introspection/debugging.
     * 
     * Useful for debugging, admin commands, or dynamic UI generation. The returned set is unmodifiable
     * to
     * prevent external modification of the registration state.
     * 
     * Thread Safety: Safe to call from any thread after registration completes.
     * 
     * @return An unmodifiable set of registered element IDs
     */
    public java.util.Set<Key> getRegisteredElements() {
        // Return unmodifiable view of keySet
        return java.util.Collections.unmodifiableSet(registeredElements.keySet());
    }

    /**
     * Creates a Component for rendering a HUD element.
     * 
     * The returned Component contains a single Unicode character whose codepoint was allocated
     * during registration. When rendered with CraftEngine's shader system, the shader will
     * decode the encoded ascent value and apply the element's transforms.
     * 
     * Thread Safety: Safe to call from any thread after registration completes.
     * 
     * @param elementId
     *            The ID of the registered HUD element
     * @return A Component ready for rendering
     * @throws IllegalArgumentException
     *             if element not registered
     */
    public Component createComponent(Key elementId) {
        HudShaderConfig config = registeredElements.get(elementId);
        if (config == null) {
            throw new IllegalArgumentException("HUD element not registered: " + elementId.asString());
        }
        char[] chars = Character.toChars(config.codepoint());
        String character = new String(chars);
        HudElement element = config.element();
        int r = 64 + (element.gridX() * 5); // Range: 64-250
        int g = 64 + (element.gridY() * 5); // Range: 64-250
        int b = 255; // Magic marker
        return Component.text(character)
                .font(net.kyori.adventure.key.Key.key(config.font().asString()))
                .color(net.kyori.adventure.text.format.TextColor.color(r, g, b));
    }

    /**
     * Deletes temp directory.
     * 
     * @param dir
     *            Path directory to delete
     */
    private void deleteDirectory(Path dir) throws IOException {
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException e) {
                    // Log but don't fail cleanup
                }
            });
        }
    }

    /**
     * Completes all pending codepoint allocations and creates shader configs.
     * 
     * This method MUST be called after all {@link #register(HudElement)} calls
     * and BEFORE attempting to use any HUD elements (e.g., before pack generation).
     * 
     * Processing Steps:
     * 1. Calls processPendingAllocations() on both allocators (completes all futures)
     * 2. Awaits each future with a 1-second timeout (should be instant since already completed)
     * 3. Creates HudShaderConfig for each element
     * 4. Saves allocation caches to disk
     * 
     * Error Handling:
     * - Individual allocation failures are logged as warnings but don't stop processing
     * - Successfully allocated elements are still registered
     * - Cache save failures are non-fatal
     */
    public void finishRegistration() {
        if (pendingAllocations.isEmpty()) {
            plugin.getLogger().fine("No pending HUD registrations to process.");
            return;
        }
        plugin.getLogger().info("Processing " + pendingAllocations.size() + " pending HUD registrations...");
        // Process all allocations at once (completes ALL pending futures)
        codepointAllocator.processPendingAllocations();
        shaderIdAllocator.processPendingAllocations();
        // Await all futures and create shader configs
        int successCount = 0;
        int failureCount = 0;
        for (Map.Entry<Key, PendingRegistration> entry : pendingAllocations.entrySet()) {
            Key elementId = entry.getKey();
            PendingRegistration pending = entry.getValue();
            try {
                // These futures should complete instantly since processPendingAllocations() was called
                int codepoint = pending.codepointFuture().get(1, TimeUnit.SECONDS);
                int shaderId = pending.shaderIdFuture().get(1, TimeUnit.SECONDS);
                // Create and store shader config
                HudShaderConfig config = new HudShaderConfig(pending.element(), shaderId, codepoint, HUD_FONT);
                registeredElements.put(elementId, config);
                plugin.getLogger().info(String.format(
                        "✓ Registered HUD element: %s (shader ID: %d, codepoint: U+%04X)",
                        elementId.asString(), shaderId, codepoint));
                successCount++;
            } catch (Exception e) {
                plugin.getLogger().warning(String.format(
                        "✗ Failed to allocate IDs for HUD element %s: %s",
                        elementId.asString(), e.getMessage()));
                failureCount++;
            }
        }

        // Clear pending queue
        pendingAllocations.clear();

        // ═══════════════════════════════════════════════════════════════════════════
        // STEP 3: Save caches to disk
        // ═══════════════════════════════════════════════════════════════════════════
        try {
            codepointAllocator.saveToCache();
            shaderIdAllocator.saveToCache();
            plugin.getLogger().info("HUD allocation caches saved successfully.");
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save HUD caches (non-fatal): " + e.getMessage());
        }

        // Log summary
        plugin.getLogger().info(String.format(
                "HUD registration complete: %d succeeded, %d failed. Total elements: %d",
                successCount, failureCount, registeredElements.size()));
    }

    /**
     * Saves the codepoint allocation cache to disk.
     * 
     * This ensures stable codepoints across server restarts. Should be called:
     * - After all elements are registered (during plugin init)
     * - During plugin shutdown (to persist runtime-registered elements)
     * 
     * @throws IOException
     *             if cache write fails
     */
    public void saveCache() throws IOException {
        // Delegate to IdAllocator (replaces stub)
        codepointAllocator.saveToCache();
        plugin.getLogger().info("HUD codepoint cache saved successfully.");
    }

    public int getElementCount() {
        return registeredElements.size();
    }

    public int getShaderCount() {
        return registeredElements.size();
    }

    public void shutdown() {
        HandlerList.unregisterAll(this);
        // Save codepoint cache one final time before shutdown
        // This ensures any runtime-registered elements are persisted
        try {
            this.saveCache();
            plugin.getLogger().info("HUD codepoint cache saved successfully.");
        } catch (java.io.IOException e) {
            plugin.getLogger().warning(
                    "Failed to save HUD codepoint cache during shutdown: " + e.getMessage());
        }
    }

    /**
     * Tracks per-player HUD state for efficient boss bar updates.
     * 
     * Lifecycle:
     * - Created on first updatePlayerHud() call for a player
     * - Destroyed when player disconnects (via PlayerQuitEvent)
     * - Boss bar shown immediately upon creation
     * 
     * Thread Safety: Only accessed from main server thread (via NerrusTick).
     * ConcurrentHashMap used for safe read-only access from async contexts (e.g., debug commands).
     */
    private static class PlayerHudState {
        private final BossBar hudBossBar;
        private final Set<Key> visibleElements;
        private Component cachedOverlay;

        @SuppressWarnings("unused")
        private long lastUpdateTick;

        PlayerHudState(Player player) {
            // Create boss bar with empty initial state
            this.hudBossBar = BossBar.bossBar(
                    Component.empty(),
                    1.0f,
                    BossBar.Color.WHITE,
                    BossBar.Overlay.PROGRESS);
            this.visibleElements = new HashSet<>();
            this.cachedOverlay = Component.empty();
            this.lastUpdateTick = 0;

            // Immediately show boss bar to player (renders as invisible until elements added)
            player.showBossBar(hudBossBar);
        }

        /**
         * Updates the boss bar's content if visibility set has changed.
         * 
         * @return true if content was rebuilt, false if cached overlay was reused
         */
        boolean updateIfNeeded(Set<Key> newElements, Component newOverlay, long currentTick) {
            // Check if visibility changed (Set.equals() checks content, not reference)
            if (!visibleElements.equals(newElements)) {
                visibleElements.clear();
                visibleElements.addAll(newElements);
                cachedOverlay = newOverlay;
                lastUpdateTick = currentTick;
                hudBossBar.name(cachedOverlay);
                return true;
            }
            return false;
        }

        void cleanup(Player player) {
            // Hide boss bar on disconnect (prevents client-side ghost bars)
            player.hideBossBar(hudBossBar);
        }
    }
}