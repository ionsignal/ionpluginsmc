package com.ionsignal.minecraft.ioncore;

import io.papermc.paper.plugin.loader.PluginClasspathBuilder;
import io.papermc.paper.plugin.loader.PluginLoader;
import io.papermc.paper.plugin.loader.library.impl.MavenLibraryResolver;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.RemoteRepository;
import org.jetbrains.annotations.NotNull;

/**
 * Plugin Loader for IonCore:
 *
 * Responsible for configuring the runtime classpath with shared libraries NOT bundled by Paper.
 *
 * Paper's shared URLClassLoader provides core Jackson (jackson-core, jackson-annotations,
 * jackson-databind) at runtime via its own bundled library directory and wins the first-found
 * ordering race unconditionally.
 */
public class IonCoreLoader implements PluginLoader {

    @Override
    public void classloader(@NotNull PluginClasspathBuilder classpathBuilder) {
        MavenLibraryResolver resolver = new MavenLibraryResolver();
        // Define Maven Central
        resolver.addRepository(
                new RemoteRepository.Builder("central", "default", MavenLibraryResolver.MAVEN_CENTRAL_DEFAULT_MIRROR).build());
        // Supplemental Jackson modules — not bundled by Paper; MavenLibraryResolver is their sole provider.
        addDependency(resolver, "com.fasterxml.jackson.datatype:jackson-datatype-jdk8:2.13.4");
        addDependency(resolver, "com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.13.4");
        addDependency(resolver, "com.fasterxml.jackson.module:jackson-module-parameter-names:2.13.4");
        // Register the resolver
        classpathBuilder.addLibrary(resolver);
    }

    private void addDependency(MavenLibraryResolver resolver, String artifactCoords) {
        resolver.addDependency(new Dependency(new DefaultArtifact(artifactCoords), null));
    }
}