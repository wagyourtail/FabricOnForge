package xyz.wagyourtail.fabriconforge.loader;

import com.google.common.collect.ImmutableList;
import cpw.mods.modlauncher.ArgumentHandler;
import cpw.mods.modlauncher.Launcher;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.FabricLoader;
import net.fabricmc.loader.api.LanguageAdapter;
import net.fabricmc.loader.api.MappingResolver;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.SemanticVersion;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import net.fabricmc.loader.impl.discovery.*;
import net.fabricmc.loader.impl.metadata.DependencyOverrides;
import net.fabricmc.loader.impl.metadata.EntrypointMetadata;
import net.fabricmc.loader.impl.metadata.LoaderModMetadata;
import net.fabricmc.loader.impl.util.DefaultLanguageAdapter;
import net.fabricmc.loader.impl.util.SystemProperties;
import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;
import net.minecraftforge.fml.loading.FMLLoader;
import xyz.wagyourtail.fabriconforge.loader.entrypoint.EntrypointStorage;
import xyz.wagyourtail.fabriconforge.FabricLoaderEarlyRiser;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

public class FabricLoaderImpl extends FabricLoader {
    public static final FabricLoaderImpl INSTANCE = new FabricLoaderImpl();
    public static final String CACHE_DIR_NAME = ".fabric";
    public static final String PROCESSED_MODS_DIR_NAME = "processedMods";
    public static final String REMAPPED_JARS_DIR_NAME = "remappedJars";
    public static final String TMP_DIR_NAME = "tmp";

    private final Map<String, LanguageAdapter> adapterMap = new HashMap<>();
    private final EntrypointStorage entrypointStorage = new EntrypointStorage();

    protected final Map<String, ModContainerImpl> modMap = new HashMap<>();
    protected List<ModContainerImpl> mods = new ArrayList<>();

    private boolean frozen = false;
    private Object gameInstance;
    private MappingResolver mappingResolver;

    public void freeze() {
        if (frozen) {
            throw new IllegalStateException("Already frozen!");
        }
        frozen = true;
        finishModLoading();
    }

    public void load() {
        if (frozen) throw new IllegalStateException("Frozen - cannot load additional mods!");
        try {
            setup();
        } catch (Throwable exception) {
            throw new RuntimeException(exception);
        }
    }

    private void setup() throws ModResolutionException {
        ModDiscoverer discoverer = new ModDiscoverer();
        discoverer.addCandidateFinder(new ClasspathModCandidateFinder());
        discoverer.addCandidateFinder(new DirectoryModCandidateFinder(getGameDir().resolve("fabricmods"), true));

        List<ModCandidate> mods = ModResolver.resolve(discoverer.discoverMods(this));

        String modListText = mods.stream()
            .map(candidate -> String.format("\t- %s %s", candidate.getId(), candidate.getVersion().getFriendlyString()))
            .collect(Collectors.joining("\n"));

        int count = mods.size();
        Log.info(LogCategory.GENERAL, "Loading %d mod%s:%n%s", count, count != 1 ? "s" : "", modListText);

        if (DependencyOverrides.INSTANCE.getDependencyOverrides().size() > 0) {
            Log.info(LogCategory.GENERAL, "Dependencies overridden for \"%s\"", String.join(", ", DependencyOverrides.INSTANCE.getDependencyOverrides()));
        }

        Path cacheDir = getGameDir().resolve(CACHE_DIR_NAME);
        Path outputdir = cacheDir.resolve(PROCESSED_MODS_DIR_NAME);

        // mod remapping
        RuntimeModRemapper.remap(mods, cacheDir.resolve(TMP_DIR_NAME), outputdir);

        String modsToLoadLate = System.getProperty(SystemProperties.DEBUG_LOAD_LATE);
        if (modsToLoadLate != null) {
            for (String modId : modsToLoadLate.split(",")) {
                for (Iterator<ModCandidate> it = mods.iterator(); it.hasNext(); ) {
                    ModCandidate mod = it.next();

                    if (mod.getId().equals(modId)) {
                        it.remove();
                        mods.add(mod);
                        break;
                    }
                }
            }
        }

        for (ModCandidate mod : mods) {
            if (!mod.hasPath() && !mod.isBuiltin()) {
                try {
                    mod.setPath(mod.copyToDir(outputdir, false));
                } catch (IOException e) {
                    throw new RuntimeException("Error extracting mod "+mod, e);
                }
            }

            addMod(mod);
        }
    }

    private void addMod(ModCandidate candidate) throws ModResolutionException {
        LoaderModMetadata info = candidate.getMetadata();

        ModContainerImpl container = new ModContainerImpl(info, candidate.getPath());
        mods.add(container);
        modMap.put(info.getId(), container);

        for (String provides : info.getProvides()) {
            modMap.put(provides, container);
        }
    }

    private void finishModLoading() {
        // add mods to classpath
        // TODO: This can probably be made safer, but that's a long-term goal
        for (ModContainerImpl mod : mods) {
            if (!mod.getInfo().getId().equals("fabricloader") && !mod.getInfo().getType().equals("builtin")) {
                FabricLoaderEarlyRiser.addToClassPath(mod.getOriginPath());
            }
        }

        if (isDevelopmentEnvironment()) {
            // Many development environments will provide classes and resources as separate directories to the classpath.
            // As such, we're adding them to the classpath here and now.
            // To avoid tripping loader-side checks, we also don't add URLs already in modsList.
            // TODO: Perhaps a better solution would be to add the Sources of all parsed entrypoints. But this will do, for now.

            Set<Path> knownModPaths = new HashSet<>();

            for (ModContainerImpl mod : mods) {
                knownModPaths.add(mod.getOriginPath().toAbsolutePath().normalize());
            }

            // suppress fabric loader explicitly in case its fabric.mod.json is in a different folder from the classes
            Path fabricLoaderPath = ClasspathModCandidateFinder.getFabricLoaderPath();
            if (fabricLoaderPath != null) knownModPaths.add(fabricLoaderPath);

            for (String pathName : System.getProperty("java.class.path", "").split(File.pathSeparator)) {
                if (pathName.isEmpty() || pathName.endsWith("*")) continue;

                Path path = Paths.get(pathName).toAbsolutePath().normalize();

                if (Files.isDirectory(path) && knownModPaths.add(path)) {
                    FabricLoaderEarlyRiser.addToClassPath(path);
                }
            }
        }

        postprocessModMetadata();
        setupLanguageAdapters();
        setupMods();
    }

    protected void postprocessModMetadata() {
        for (ModContainerImpl mod : mods) {
            if (!(mod.getInfo().getVersion() instanceof SemanticVersion)) {
                Log.warn(LogCategory.METADATA, "Mod `%s` (%s) does not respect SemVer - comparison support is limited.",
                    mod.getInfo().getId(), mod.getInfo().getVersion().getFriendlyString());
            } else if (((SemanticVersion) mod.getInfo().getVersion()).getVersionComponentCount() >= 4) {
                Log.warn(LogCategory.METADATA, "Mod `%s` (%s) uses more dot-separated version components than SemVer allows; support for this is currently not guaranteed.",
                    mod.getInfo().getId(), mod.getInfo().getVersion().getFriendlyString());
            }
        }
    }

    private void setupLanguageAdapters() {
        adapterMap.put("default", DefaultLanguageAdapter.INSTANCE);

        for (ModContainerImpl mod : mods) {
            // add language adapters
            for (Map.Entry<String, String> laEntry : mod.getInfo().getLanguageAdapterDefinitions().entrySet()) {
                if (adapterMap.containsKey(laEntry.getKey())) {
                    throw new RuntimeException("Duplicate language adapter key: " + laEntry.getKey() + "! (" + laEntry.getValue() + ", " + adapterMap.get(laEntry.getKey()).getClass().getName() + ")");
                }

                try {
                    adapterMap.put(laEntry.getKey(), (LanguageAdapter) Class.forName(laEntry.getValue(), true, FabricLoaderEarlyRiser.classLoader).getDeclaredConstructor().newInstance());
                } catch (Exception e) {
                    throw new RuntimeException("Failed to instantiate language adapter: " + laEntry.getKey(), e);
                }
            }
        }
    }

    private void setupMods() {
        for (ModContainerImpl mod : mods) {
            try {
                for (String in : mod.getInfo().getOldInitializers()) {
                    String adapter = mod.getInfo().getOldStyleLanguageAdapter();
                    entrypointStorage.addDeprecated(mod, adapter, in);
                }

                for (String key : mod.getInfo().getEntrypointKeys()) {
                    for (EntrypointMetadata in : mod.getInfo().getEntrypoints(key)) {
                        entrypointStorage.add(mod, key, in, adapterMap);
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException(String.format("Failed to setup mod %s (%s)", mod.getInfo().getName(), mod.getOriginPath()), e);
            }
        }
    }

    public void setGameInstance(Object instance) {
        this.gameInstance = instance;
    }

    @Override
    public <T> List<T> getEntrypoints(String key, Class<T> type) {
        return entrypointStorage.getEntrypoints(key, type);
    }

    @Override
    public <T> List<EntrypointContainer<T>> getEntrypointContainers(String key, Class<T> type) {
        return entrypointStorage.getEntrypointContainers(key, type);
    }

    @Override
    public MappingResolver getMappingResolver() {
        // TODO
        return null;
    }

    @Override
    public Optional<ModContainer> getModContainer(String id) {
        return Optional.ofNullable(modMap.get(id));
    }

    @Override
    public Collection<ModContainer> getAllMods() {
        return ImmutableList.copyOf(mods);
    }

    @Override
    public boolean isModLoaded(String id) {
        return modMap.containsKey(id);
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        return false;
    }

    @Override
    public EnvType getEnvironmentType() {
        return FMLLoader.getDist().isClient() ? EnvType.CLIENT : EnvType.SERVER;
    }

    @Override
    public Object getGameInstance() {
        return gameInstance;
    }

    @Override
    public Path getGameDir() {
        return FMLLoader.getGamePath();
    }

    @Override
    public File getGameDirectory() {
        return getGameDir().toFile();
    }

    @Override
    public Path getConfigDir() {
        return FMLLoader.getGamePath().resolve("config");
    }

    @Override
    public File getConfigDirectory() {
        return getConfigDir().toFile();
    }

    @Override
    public String[] getLaunchArguments(boolean sanitize) {
        try {
            Field args = Launcher.class.getDeclaredField("argumentHandler");
            args.setAccessible(true);
            ArgumentHandler handler = (ArgumentHandler)args.get(Launcher.INSTANCE);
            return handler.buildArgumentList();
        } catch (Throwable ex) {
            throw new RuntimeException(ex);
        }
    }

}
