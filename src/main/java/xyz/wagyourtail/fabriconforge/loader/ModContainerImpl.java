package xyz.wagyourtail.fabriconforge.loader;

import net.fabricmc.loader.ModContainer;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.fabricmc.loader.impl.util.FileSystemUtil;
import net.fabricmc.loader.impl.util.log.LogCategory;
import net.fabricmc.loader.impl.metadata.LoaderModMetadata;
import org.jline.utils.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class ModContainerImpl extends ModContainer {
    private final LoaderModMetadata info;
    private final Path originPath;
    private volatile Path root;

    public ModContainerImpl(LoaderModMetadata info, Path originPath) {
        this.info = info;
        this.originPath = originPath;
    }

    @Override
    public ModMetadata getMetadata() {
        return info;
    }

    @Override
    protected Path getOriginPath() {
        return originPath;
    }

    @Override
    public Path getRootPath() {
        Path ret = root;

        if (ret == null || !ret.getFileSystem().isOpen()) {
            if (ret != null && !warned) {
                if (!FabricLoaderImpl.INSTANCE.isDevelopmentEnvironment()) warned = true;
                Log.warn(LogCategory.GENERAL, "FileSystem for %s has been closed unexpectedly, existing root path references may break!", this);
            }

            root = ret = obtainRootPath(); // obtainRootPath is thread safe, but we need to avoid plain or repeated reads to root
        }

        return ret;
    }

    private boolean warned = false;

    private Path obtainRootPath() {
        try {
            if (Files.isDirectory(originPath)) {
                return originPath;
            } else /* JAR */ {
                FileSystemUtil.FileSystemDelegate delegate = FileSystemUtil.getJarFileSystem(originPath, false);

                if (delegate.get() == null) {
                    throw new RuntimeException("Could not open JAR file " + originPath + " for NIO reading!");
                }

                return delegate.get().getRootDirectories().iterator().next();

                // We never close here. It's fine. getJarFileSystem() will handle it gracefully, and so should mods
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to find root directory for mod '" + info.getId() + "'!", e);
        }
    }

    @Override
    public LoaderModMetadata getInfo() {
        return info;
    }

    @Override
    public String toString() {
        return String.format("%s %s", info.getId(), info.getVersion());
    }
}
