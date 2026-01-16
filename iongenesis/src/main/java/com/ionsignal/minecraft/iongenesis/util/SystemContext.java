package com.ionsignal.minecraft.iongenesis.util;

/**
 * A utility to temporarily switch the Thread Context ClassLoader (TCCL) to the System ClassLoader.
 */
public class SystemContext implements AutoCloseable {
    private final ClassLoader originalLoader;
    private final Thread currentThread;

    public SystemContext() {
        this.currentThread = Thread.currentThread();
        this.originalLoader = currentThread.getContextClassLoader();
        currentThread.setContextClassLoader(ClassLoader.getSystemClassLoader());
    }

    @Override
    public void close() {
        currentThread.setContextClassLoader(originalLoader);
    }
}