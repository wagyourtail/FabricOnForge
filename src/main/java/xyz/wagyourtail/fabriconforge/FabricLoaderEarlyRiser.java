package xyz.wagyourtail.fabriconforge;

import cpw.mods.modlauncher.TransformingClassLoader;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.fabricmc.loader.impl.metadata.BuiltinModMetadata;
import net.fabricmc.loader.impl.metadata.LoaderModMetadata;
import net.fabricmc.loader.impl.util.UrlUtil;
import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;
import net.fabricmc.loader.impl.util.mappings.MixinIntermediaryDevRemapper;
import net.fabricmc.mapping.tree.TinyMappingFactory;
import net.fabricmc.mapping.tree.TinyTree;
import net.minecraftforge.fml.loading.FMLLoader;
import org.apache.commons.io.IOUtils;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.connect.IMixinConnector;
import xyz.wagyourtail.fabriconforge.loader.FabricLoaderImpl;

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

public class FabricLoaderEarlyRiser implements IMixinConnector {

    public static final Method addURL;
    public static final URLClassLoader classLoader;
    static {
        URLClassLoader classLoader1;
        Method addURL1;
        try {
            Field fd = TransformingClassLoader.class.getDeclaredField("delegatedClassLoader");
            fd.setAccessible(true);
            classLoader1 = (URLClassLoader) fd.get(FabricLoaderEarlyRiser.class.getClassLoader());
            addURL1 = URLClassLoader.class.getDeclaredMethod("addURL", URL.class);
            addURL1.setAccessible(true);
        } catch (IllegalAccessException | NoSuchFieldException | NoSuchMethodException e) {
            e.printStackTrace();
            classLoader1 = null;
            addURL1 = null;
        }
        classLoader = classLoader1;
        addURL = addURL1;
    }

    public static TinyTree getMappings() {
        try {
            Path cacheDr = FabricLoaderImpl.INSTANCE.getGameDir().resolve(FabricLoaderImpl.CACHE_DIR_NAME);
            Field mcVersionGetter = FMLLoader.class.getDeclaredField("mcVersion");
            mcVersionGetter.setAccessible(true);
            String mcVersion = (String) mcVersionGetter.get(null);
            Path mappingFile = cacheDr.resolve(String.format(CreateVolderYarn.VOLDERYARN, mcVersion, "snapshot", "20201028-1.16.3"));
            if (!mappingFile.toFile().exists()) {
                CreateVolderYarn.genMappings(cacheDr, mcVersion, "snapshot", "20201028-1.16.3");
            }

            return TinyMappingFactory.load(IOUtils.toBufferedReader(new FileReader(mappingFile.toFile())));
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    public static void addToClassPath(Path path) {
        try {
            addURL.invoke(classLoader, UrlUtil.asUrl(path));
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void connect() {
        try {
            FabricLoaderImpl.INSTANCE.load();
//            TODO: figure out why this one didn't work
            TinyTree mappings = getMappings();
            if (mappings != null) {
                List<String> namespaces = mappings.getMetadata().getNamespaces();


                if (namespaces.contains("intermediary") && namespaces.contains("named")) {
                    System.setProperty("mixin.env.remapRefMap", "true");

                    try {
                        MixinIntermediaryDevRemapper remapper = new MixinIntermediaryDevRemapper(mappings, "intermediary", "named");
                        MixinEnvironment.getDefaultEnvironment().getRemappers().add(remapper);
                        Log.info(LogCategory.MIXIN, "Loaded Fabric development mappings for mixin remapper!");
                    } catch (Exception e) {
                        Log.error(LogCategory.MIXIN, "Fabric development environment setup error - the game will probably crash soon!");
                        e.printStackTrace();
                    }
                }
            }
            FabricLoaderImpl.INSTANCE.freeze();
            getMixinConfigs(FabricLoaderImpl.INSTANCE, FabricLoaderImpl.INSTANCE.getEnvironmentType()).forEach(Mixins::addConfiguration);
        } catch (Throwable e) {
            e.printStackTrace();
            System.exit(0);
        }
    }

    static Set<String> getMixinConfigs(FabricLoaderImpl loader, EnvType type) {
        return loader.getAllMods().stream()
            .map(ModContainer::getMetadata)
            .filter((m) -> m instanceof LoaderModMetadata)
            .flatMap((m) -> ((LoaderModMetadata) m).getMixinConfigs(type).stream())
            .filter(s -> s != null && !s.isEmpty())
            .collect(Collectors.toSet());
    }

    public static Collection<BuiltinMod> getBuiltinMods() {
        try {
            Field mcVersionGetter =  FMLLoader.class.getDeclaredField("mcVersion");
            mcVersionGetter.setAccessible(true);
            String mcVersion = (String) mcVersionGetter.get(null);
            BuiltinModMetadata.Builder metadata = new BuiltinModMetadata.Builder("minecraft", mcVersion)
                .setName("Minecraft");

            return Collections.singletonList(new BuiltinMod(FMLLoader.getMCPaths()[0], metadata.build()));
        } catch (IllegalAccessException | NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
    }


    public static class BuiltinMod {
        public BuiltinMod(Path path, ModMetadata metadata) {
            Objects.requireNonNull(path, "null path");
            Objects.requireNonNull(metadata, "null metadata");

            this.path = path;
            this.metadata = metadata;
        }

        public final Path path;
        public final ModMetadata metadata;
    }
}
