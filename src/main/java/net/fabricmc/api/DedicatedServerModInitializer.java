package net.fabricmc.api;

@FunctionalInterface
public interface DedicatedServerModInitializer {
    void onInitializeServer();
}
