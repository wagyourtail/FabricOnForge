package xyz.wagyourtail.fabriconforge;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.mapping.tree.TinyTree;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.InterModComms;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.event.lifecycle.InterModEnqueueEvent;
import net.minecraftforge.fml.event.lifecycle.InterModProcessEvent;
import net.minecraftforge.fml.event.server.FMLServerStartingEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import xyz.wagyourtail.fabriconforge.loader.FabricLoaderImpl;

import java.util.stream.Collectors;

// The value here should match an entry in the META-INF/mods.toml file
@Mod("fabric-on-forge")
public class FabricOnForge {

    // Directly reference a log4j logger.
    private static final Logger LOGGER = LogManager.getLogger();

    public FabricOnForge() {
        // Register ourselves for server and other game events we are interested in
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::setup);
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::doClientStuff);
        MinecraftForge.EVENT_BUS.register(this);
    }

    public void setup(final FMLCommonSetupEvent event) {
        LOGGER.log(Level.INFO, "starting fabric mods");
        FabricLoaderImpl.INSTANCE.getEntrypoints("main", ModInitializer.class).forEach(ModInitializer::onInitialize);
    }

    public void doClientStuff(final FMLClientSetupEvent event) {
        LOGGER.log(Level.INFO, "starting fabric client mods");
        FabricLoaderImpl.INSTANCE.getEntrypoints("client", ClientModInitializer.class).forEach(ClientModInitializer::onInitializeClient);
    }

    // You can use SubscribeEvent and let the Event Bus discover methods to call
    @SubscribeEvent
    public void onServerStarting(FMLServerStartingEvent event) {
        LOGGER.log(Level.INFO, "starting fabric server mods");
        FabricLoaderImpl.INSTANCE.getEntrypoints("server", DedicatedServerModInitializer.class).forEach(DedicatedServerModInitializer::onInitializeServer);
    }
}
