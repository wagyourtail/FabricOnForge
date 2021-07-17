package net.fabricmc.api;

@FunctionalInterface
public interface ClientModInitializer {
    void onInitializeClient();
}
