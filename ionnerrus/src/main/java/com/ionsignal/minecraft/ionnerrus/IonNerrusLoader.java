package com.ionsignal.minecraft.ionnerrus;

import io.papermc.paper.plugin.loader.PluginClasspathBuilder;
import io.papermc.paper.plugin.loader.PluginLoader;
import io.papermc.paper.plugin.loader.library.impl.MavenLibraryResolver;

import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.RemoteRepository;
import org.jetbrains.annotations.NotNull;

/**
 * Plugin Loader for IonNerrus.
 */
public class IonNerrusLoader implements PluginLoader {
    @Override
    public void classloader(@NotNull PluginClasspathBuilder classpathBuilder) {
        MavenLibraryResolver resolver = new MavenLibraryResolver();
        resolver.addRepository(
                new RemoteRepository.Builder("central", "default", MavenLibraryResolver.MAVEN_CENTRAL_DEFAULT_MIRROR).build());
        // This isolates the openai-java SDK requirements to the module that actually uses them.
        addDependency(resolver, "com.fasterxml.jackson.module:jackson-module-kotlin:2.13.4");
        addDependency(resolver, "org.jetbrains.kotlin:kotlin-reflect:1.9.22");
        addDependency(resolver, "org.jetbrains.kotlin:kotlin-stdlib:1.9.22");
        classpathBuilder.addLibrary(resolver);
    }

    private void addDependency(MavenLibraryResolver resolver, String artifactCoords) {
        resolver.addDependency(new Dependency(new DefaultArtifact(artifactCoords), null));
    }
}