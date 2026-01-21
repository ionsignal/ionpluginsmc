package com.ionsignal.minecraft.iongenesis.generation.oracle;

import com.dfsek.terra.api.properties.Context;
import com.dfsek.terra.api.properties.PropertyKey;
import com.dfsek.terra.api.world.biome.Biome;
import com.dfsek.terra.api.world.biome.generation.BiomeProvider;
import com.dfsek.terra.api.world.chunk.generation.ChunkGenerator;
import com.dfsek.terra.api.world.info.WorldProperties;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * An oracle that queries the Terra NoiseChunkGenerator3D for terrain height using
 * reflective access to bypass ClassLoader isolation.
 */
public class TerraGeneratorOracle implements TerrainOracle {
    private static final Logger LOGGER = Logger.getLogger(TerraGeneratorOracle.class.getName());

    // Core Dependencies
    private final ChunkGenerator generator;
    private final WorldProperties world;
    private final BiomeProvider biomeProvider;
    private final int searchCeiling;
    private final int searchFloor;

    // Reflective Handles
    private boolean valid = false;
    private Object samplerProvider; // Instance of SamplerProvider
    private MethodHandle getChunkSamplerHandle; // SamplerProvider.getChunk(...) -> Sampler3D
    private MethodHandle sampleHandle; // Sampler3D.sample(int, int, int) -> double
    private MethodHandle carvingSamplerHandle; // BiomeNoiseProperties.carving() -> Sampler
    private MethodHandle getSampleHandle; // Sampler.getSample(...) -> double
    private MethodHandle seaLevelHandle; // BiomePaletteInfo.seaLevel() -> int

    // Dynamic Property Keys
    private PropertyKey<?> noisePropertiesKey;
    private PropertyKey<?> paletteInfoPropertyKey;

    public TerraGeneratorOracle(ChunkGenerator generator, WorldProperties world, BiomeProvider biomeProvider) {
        this.generator = generator;
        this.world = world;
        this.biomeProvider = biomeProvider;
        this.searchCeiling = world.getMaxHeight() - 1;
        this.searchFloor = world.getMinHeight();
        initializeReflection();
    }

    @SuppressWarnings("unchecked")
    private void initializeReflection() {
        try {
            // Get the ClassLoader from the generator instance.
            // This loader can see the Addon classes.
            ClassLoader cl = generator.getClass().getClassLoader();
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            // Load Addon Classes
            Class<?> genClass = Class.forName("com.dfsek.terra.addons.chunkgenerator.generation.NoiseChunkGenerator3D", true, cl);
            Class<?> samplerProviderClass = Class.forName("com.dfsek.terra.addons.chunkgenerator.generation.math.samplers.SamplerProvider",
                    true, cl);
            Class<?> sampler3DClass = Class.forName("com.dfsek.terra.addons.chunkgenerator.generation.math.samplers.Sampler3D", true, cl);
            Class<?> noisePropsClass = Class.forName("com.dfsek.terra.addons.chunkgenerator.config.noise.BiomeNoiseProperties", true, cl);
            Class<?> paletteInfoClass = Class.forName("com.dfsek.terra.addons.chunkgenerator.palette.BiomePaletteInfo", true, cl);
            // Verify Generator Type
            if (!genClass.isInstance(generator)) {
                LOGGER.warning("TerraGeneratorOracle: Generator is not NoiseChunkGenerator3D. Actual: " + generator.getClass().getName());
                return;
            }
            // Create Property Keys
            // We use the raw Context.create with the loaded class
            this.noisePropertiesKey = Context.create((Class<? extends com.dfsek.terra.api.properties.Properties>) noisePropsClass);
            this.paletteInfoPropertyKey = Context.create((Class<? extends com.dfsek.terra.api.properties.Properties>) paletteInfoClass);
            // Get SamplerProvider Instance
            // Method: NoiseChunkGenerator3D.samplerProvider()
            MethodHandle providerGetter = lookup.findVirtual(genClass, "samplerProvider", MethodType.methodType(samplerProviderClass));
            this.samplerProvider = providerGetter.invoke(generator);
            // Bind Methods
            // SamplerProvider.getChunk(int cx, int cz, WorldProperties, BiomeProvider) -> Sampler3D
            this.getChunkSamplerHandle = lookup.findVirtual(samplerProviderClass, "getChunk",
                    MethodType.methodType(sampler3DClass, int.class, int.class, WorldProperties.class, BiomeProvider.class));
            // Sampler3D.sample(int x, int y, int z) -> double
            this.sampleHandle = lookup.findVirtual(sampler3DClass, "sample",
                    MethodType.methodType(double.class, int.class, int.class, int.class));
            // BiomeNoiseProperties.carving() -> Sampler
            // Sampler is in the API, so we can reference it directly, but the return type of the record
            // accessor matches.
            this.carvingSamplerHandle = lookup.findVirtual(noisePropsClass, "carving",
                    MethodType.methodType(com.dfsek.seismic.type.sampler.Sampler.class));
            // Sampler.getSample(long seed, double x, double y, double z) -> double
            this.getSampleHandle = lookup.findVirtual(com.dfsek.seismic.type.sampler.Sampler.class, "getSample",
                    MethodType.methodType(double.class, long.class, double.class, double.class, double.class));
            // BiomePaletteInfo.seaLevel() -> int
            this.seaLevelHandle = lookup.findVirtual(paletteInfoClass, "seaLevel",
                    MethodType.methodType(int.class));
            this.valid = true;
            LOGGER.info("TerraGeneratorOracle initialized successfully via reflection.");
        } catch (Throwable e) {
            LOGGER.log(Level.SEVERE, "Failed to initialize TerraGeneratorOracle reflection. Terrain adaptation will be disabled.", e);
            this.valid = false;
        }
    }

    @Override
    public Optional<Integer> getSurfaceHeight(int x, int z) {
        if (!valid)
            return Optional.empty();
        try {
            long seed = world.getSeed();
            int cx = x >> 4;
            int cz = z >> 4;
            int localX = Math.floorMod(x, 16);
            int localZ = Math.floorMod(z, 16);
            // Get the Sampler3D for this chunk
            // invoke: getChunk(cx, cz, world, biomeProvider)
            Object sampler3D = getChunkSamplerHandle.invoke(samplerProvider, cx, cz, world, biomeProvider);
            // Raycast down
            for (int y = searchCeiling; y >= searchFloor; y--) {
                if (isSolid(sampler3D, x, y, z, localX, localZ, seed)) {
                    return Optional.of(y);
                }
            }
        } catch (Throwable e) {
            // Circuit Breaker: Disable on first runtime failure to prevent lag
            valid = false;
            LOGGER.log(Level.SEVERE, "TerraGeneratorOracle reflection failed at runtime. Disabling terrain adaptation.", e);
            return Optional.empty();
        }
        return Optional.empty();
    }

    @Override
    public Optional<Integer> findSurface(int x, int startY, int z, int verticalSearchLimit) {
        if (!valid)
            return Optional.empty();
        try {
            long seed = world.getSeed();
            int cx = x >> 4;
            int cz = z >> 4;
            int localX = Math.floorMod(x, 16);
            int localZ = Math.floorMod(z, 16);
            // Get the Sampler3D for this chunk
            Object sampler3D = getChunkSamplerHandle.invoke(samplerProvider, cx, cz, world, biomeProvider);
            // Check initial state
            boolean startSolid = isSolid(sampler3D, x, startY, z, localX, localZ, seed);
            if (startSolid) {
                // We are inside ground (Buried). Search UP for air.
                for (int y = startY + 1; y <= startY + verticalSearchLimit; y++) {
                    if (y > searchCeiling)
                        break;
                    if (!isSolid(sampler3D, x, y, z, localX, localZ, seed)) {
                        // Found air at y. Surface is y-1.
                        return Optional.of(y - 1);
                    }
                }
            } else {
                // We are in air (Floating). Search DOWN for ground.
                for (int y = startY - 1; y >= startY - verticalSearchLimit; y--) {
                    if (y < searchFloor)
                        break;
                    if (isSolid(sampler3D, x, y, z, localX, localZ, seed)) {
                        // Found solid at y. Surface is y.
                        return Optional.of(y);
                    }
                }
            }
        } catch (Throwable e) {
            valid = false;
            LOGGER.log(Level.SEVERE, "TerraGeneratorOracle reflection failed at runtime (findSurface).", e);
            return Optional.empty();
        }
        return Optional.empty();
    }

    /**
     * Helper method to check if a specific coordinate is considered "Solid" (Ground or Water).
     * Encapsulates reflection logic for density, carving, and sea level.
     */
    private boolean isSolid(Object sampler3D, int x, int y, int z, int localX, int localZ, long seed) throws Throwable {
        // Sample Density
        // invoke: sampler3D.sample(localX, y, localZ)
        double density = (double) sampleHandle.invoke(sampler3D, localX, y, localZ);
        if (density > 0) {
            // Check Carving (Caves)
            // We need the biome at this specific block to get its carving settings
            Biome biome = biomeProvider.getBiome(x, y, z, seed);
            Object noiseProps = biome.getContext().get(noisePropertiesKey);
            // invoke: noiseProps.carving()
            Object carvingSampler = carvingSamplerHandle.invoke(noiseProps);
            // invoke: carvingSampler.getSample(seed, x, y, z)
            // Note: Carving sampler uses absolute coordinates
            double carverSample = (double) getSampleHandle.invoke(carvingSampler, seed, (double) x, (double) y, (double) z);
            if (carverSample <= 0) {
                // Solid ground found (Density > 0 AND Not Carved)
                return true;
            }
            // It is a cave (Air)
            return false;
        } else {
            // Check Sea Level
            // If density <= 0, it might be water if below sea level
            Biome biome = biomeProvider.getBiome(x, y, z, seed);
            Object paletteInfo = biome.getContext().get(paletteInfoPropertyKey);
            // invoke: paletteInfo.seaLevel()
            int seaLevel = (int) seaLevelHandle.invoke(paletteInfo);
            return y <= seaLevel;
        }
    }
}