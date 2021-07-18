/*
 * Copyright 2016 FabricMC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.fabricmc.loader.impl.discovery;

import com.google.gson.*;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.impl.util.FileSystemUtil;
import net.fabricmc.loader.impl.util.SystemProperties;
import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;
import net.fabricmc.loader.impl.util.mappings.TinyRemapperMappingsHelper;
import net.fabricmc.mapping.tree.ClassDef;
import net.fabricmc.mapping.tree.FieldDef;
import net.fabricmc.mapping.tree.MethodDef;
import net.fabricmc.mapping.tree.TinyTree;
import net.fabricmc.tinyremapper.InputTag;
import net.fabricmc.tinyremapper.NonClassCopyMode;
import net.fabricmc.tinyremapper.OutputConsumerPath;
import net.fabricmc.tinyremapper.TinyRemapper;
import org.objectweb.asm.commons.Remapper;
import xyz.wagyourtail.fabriconforge.FabricLoaderEarlyRiser;

import java.io.*;
import java.net.URISyntaxException;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

public final class RuntimeModRemapper {
	public static void remap(Collection<ModCandidate> modCandidates, Path tmpDir, Path outputDir) {
		List<ModCandidate> modsToRemap = new ArrayList<>();
		TinyTree mappings = FabricLoaderEarlyRiser.getMappings();
		if (mappings == null) return;

		for (ModCandidate mod : modCandidates) {
			if (mod.getRequiresRemap()) {
				modsToRemap.add(mod);
			}
		}

		if (modsToRemap.isEmpty()) return;

		TinyRemapper remapper = TinyRemapper.newRemapper()
				.withMappings(TinyRemapperMappingsHelper.create(mappings, "intermediary", "named"))
				.renameInvalidLocals(false)
				.build();

		try {
			remapper.readClassPathAsync(getRemapClasspath().toArray(new Path[0]));
		} catch (IOException e) {
			throw new RuntimeException("Failed to populate remap classpath", e);
		}

		Map<ModCandidate, RemapInfo> infoMap = new HashMap<>();

		try {
			for (ModCandidate mod : modsToRemap) {
				RemapInfo info = new RemapInfo();
				infoMap.put(mod, info);

				InputTag tag = remapper.createInputTag();
				info.tag = tag;

				if (mod.hasPath()) {
					info.inputPath = mod.getPath();
				} else {
					info.inputPath = mod.copyToDir(tmpDir, true);
					info.inputIsTemp = true;
				}

				info.outputPath = outputDir.resolve(mod.getDefaultFileName());
				Files.deleteIfExists(info.outputPath);

				remapper.readInputsAsync(tag, info.inputPath);
			}

			//Done in a 2nd loop as we need to make sure all the inputs are present before remapping
			for (ModCandidate mod : modsToRemap) {
				RemapInfo info = infoMap.get(mod);
				OutputConsumerPath outputConsumer = new OutputConsumerPath.Builder(info.outputPath).build();

				FileSystemUtil.FileSystemDelegate delegate = FileSystemUtil.getJarFileSystem(info.inputPath, false);

				if (delegate.get() == null) {
					throw new RuntimeException("Could not open JAR file " + info.inputPath.getFileName() + " for NIO reading!");
				}

				Path inputJar = delegate.get().getRootDirectories().iterator().next();
				outputConsumer.addNonClassFiles(inputJar, NonClassCopyMode.FIX_META_INF, remapper);

				info.outputConsumerPath = outputConsumer;

				remapper.apply(outputConsumer, info.tag);
			}

			//Done in a 3rd loop as this can happen when the remapper is doing its thing.
			for (ModCandidate mod : modsToRemap) {
				RemapInfo info = infoMap.get(mod);

				String accessWidener = mod.getMetadata().getAccessWidener();

				if (accessWidener != null) {
					info.accessWidenerPath = accessWidener;

					try (FileSystemUtil.FileSystemDelegate jarFs = FileSystemUtil.getJarFileSystem(info.inputPath, false)) {
						FileSystem fs = jarFs.get();
						info.accessWidener = remapAccessWidener(Files.readAllBytes(fs.getPath(accessWidener)), remapper.getRemapper());
					}
				}
			}

			remapper.finish();

			for (ModCandidate mod : modsToRemap) {
				RemapInfo info = infoMap.get(mod);

				info.outputConsumerPath.close();

				Set<String> mixins = new HashSet<>(mod.getMetadata().getMixinConfigs(EnvType.CLIENT));
				mixins.addAll(mod.getMetadata().getMixinConfigs(EnvType.SERVER));

				if (!mixins.isEmpty()) {
					try (FileSystemUtil.FileSystemDelegate jarFs = FileSystemUtil.getJarFileSystem(info.inputPath, false)) {
						try (FileSystemUtil.FileSystemDelegate jarFs2 = FileSystemUtil.getJarFileSystem(info.outputPath, false)) {
							FileSystem fs = jarFs.get();
							FileSystem outfs = jarFs2.get();
							Set<String> refmaps = new HashSet<>();

							for (String mixin : mixins) {
								JsonObject mixinConfig = new JsonParser().parse(new StringReader(new String(Files.readAllBytes(fs.getPath("/" + mixin))))).getAsJsonObject();

								refmaps.add(mixinConfig.get("refmap").getAsString());
							}

							for (String refmap : refmaps) {
								System.out.println(refmap);
								JsonObject refmapObject = new JsonParser().parse(new StringReader(new String(Files.readAllBytes(fs.getPath("/" + refmap))))).getAsJsonObject();
								Files.delete(outfs.getPath(refmap));
								Files.write(outfs.getPath(refmap), remapRefmap(refmapObject, mappings));
							}
						}
					}
				}


				if (info.accessWidenerPath != null) {
					try (FileSystemUtil.FileSystemDelegate jarFs = FileSystemUtil.getJarFileSystem(info.outputPath, false)) {
						FileSystem fs = jarFs.get();

						Files.delete(fs.getPath(info.accessWidenerPath));
						Files.write(fs.getPath(info.accessWidenerPath), info.accessWidener);
					}
				}

				mod.setPath(info.outputPath);
			}

		} catch (Throwable t) {
			remapper.finish();

			for (RemapInfo info : infoMap.values()) {
				try {
					Files.deleteIfExists(info.outputPath);
				} catch (IOException | NullPointerException e) {
					Log.warn(LogCategory.MOD_REMAP, "Error deleting failed output jar %s", info.outputPath, e);
				}
			}

			throw new RuntimeException("Failed to remap mods", t);
		} finally {
			for (RemapInfo info : infoMap.values()) {
				try {
					if (info.inputIsTemp) Files.deleteIfExists(info.inputPath);
				} catch (IOException  | NullPointerException e) {
					Log.warn(LogCategory.MOD_REMAP, "Error deleting temporary input jar %s", info.inputIsTemp, e);
				}
			}
		}
	}

	private final static Gson gson = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

	// fallback if no class specified in refmap
	private static String[] findMapping(String methodOrField, TinyTree tree) {
		String methodOrFieldName = methodOrField.split("[(:]")[0];
		boolean isMethod = methodOrField.contains("(");
		for (ClassDef aClass : tree.getClasses()) {
			if (isMethod) {
				for (MethodDef method : aClass.getMethods()) {
					if (method.getName("intermediary").equals(methodOrFieldName))
						return new String[] {"L" + aClass.getName("intermediary"), method.getName("intermediary") + method.getDescriptor("intermediary")};
				}
			} else {
				for (FieldDef field : aClass.getFields()) {
					if (field.getName("intermediary").equals(methodOrFieldName))
						return new String[] {"L" + aClass.getName("intermediary"), field.getName("intermediary") + ":" + field.getDescriptor("intermediary")};
				}
			}
		}
		return null;
	}

	private static String remapClass(String intClassDesc, TinyTree tree) {
		String intClassName = intClassDesc.replaceAll("^L", "").replaceAll(";$", "");
		ClassDef cls = tree.getClasses().parallelStream().filter(e -> e.getName("intermediary").equals(intClassName)).findFirst().orElse(null);
		if (cls == null) return intClassDesc;
		return "L" + cls.getName("named") + ";";
	}

	private static String remapFieldOrMethod(String intClassDesc, String intFieldOrMethodDesc, TinyTree tree) {
		String intClassName = intClassDesc.replaceAll("^L", "").replaceAll(";$", "");
		String[] methodOrFieldParts = intFieldOrMethodDesc.split("[(:]");
		boolean isMethod = intFieldOrMethodDesc.contains("(");
		ClassDef cls = tree.getClasses().parallelStream().filter(e -> e.getName("intermediary").equals(intClassName)).findFirst().orElse(null);
		if (cls == null) return intFieldOrMethodDesc;
		if (isMethod) {
			MethodDef method = cls.getMethods().parallelStream().filter(e -> intFieldOrMethodDesc.equals(e.getName("intermediary") + e.getDescriptor("intermediary"))).findFirst().orElse(null);
			if (method == null) return intFieldOrMethodDesc;
			return method.getName("named") + method.getDescriptor("named");
		} else {
			FieldDef field = cls.getFields().parallelStream().filter(e -> intFieldOrMethodDesc.equals(e.getName("intermediary") + ":" + e.getDescriptor("intermediary"))).findFirst().orElse(null);
			if (field == null) return intFieldOrMethodDesc;
			return field.getName("named") + ":" + field.getDescriptor("named");
		}
	}

	private static byte[] remapRefmap(JsonObject input, TinyTree tree) {
		JsonObject mappings = input.getAsJsonObject("mappings");
		JsonObject remappedRefmap = new JsonObject();
		JsonObject remappedMappings;
		remappedRefmap.add("mappings", remappedMappings = new JsonObject());
		for (Map.Entry<String, JsonElement> classEntry : mappings.entrySet()) {
			JsonObject remappedClassEntry;
			remappedMappings.add(classEntry.getKey(), remappedClassEntry = new JsonObject());
			for (Map.Entry<String, JsonElement> entry : classEntry.getValue().getAsJsonObject().entrySet()) {
				String reference = entry.getValue().getAsString();
				String[] parts = reference.split(";", 2);
				if (parts[0].startsWith("field") || parts[0].startsWith("method")) {
					parts = findMapping(parts[0], tree);
				}
				if (!parts[0].startsWith("L")) {
					parts[0] = "L" + parts[0];
				}
				String classDesc = parts[0] + ";";
				String remappedReference = remapClass(classDesc, tree);
				if (parts.length > 1) {
					String fieldOrMethod = parts[1];
					remappedReference += remapFieldOrMethod(classDesc, fieldOrMethod, tree);
				}
				System.out.println(reference + " -> " + remappedReference);
				remappedClassEntry.add(entry.getKey(), new JsonPrimitive(remappedReference));
			}
		}
		return gson.toJson((JsonElement) remappedRefmap).getBytes(StandardCharsets.UTF_8);
	}

	private static byte[] remapAccessWidener(byte[] input, Remapper remapper) {
		//TODO::::
		try (BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(input), StandardCharsets.UTF_8))) {
//			AccessWidener accessWidener = new AccessWidener();
//			AccessWidenerReader accessWidenerReader = new AccessWidenerReader(accessWidener);
//			accessWidenerReader.read(bufferedReader, "intermediary");
//
//			AccessWidenerRemapper accessWidenerRemapper = new AccessWidenerRemapper(accessWidener, remapper, "named");
//			AccessWidener remapped = accessWidenerRemapper.remap();
//			AccessWidenerWriter accessWidenerWriter = new AccessWidenerWriter(remapped);

			try (StringWriter writer = new StringWriter()) {
//				accessWidenerWriter.write(writer);
				return writer.toString().getBytes(StandardCharsets.UTF_8);
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private static List<Path> getRemapClasspath() throws IOException {
		return Arrays.stream(((URLClassLoader) ClassLoader.getSystemClassLoader()).getURLs()).map(e -> {
			try {
				return Paths.get(e.toURI());
			} catch (URISyntaxException uriSyntaxException) {
				throw new RuntimeException(uriSyntaxException);
			}
		}).collect(Collectors.toList());

//		String remapClasspathFile = System.getProperty(SystemProperties.REMAP_CLASSPATH_FILE);
//
//		if (remapClasspathFile == null) {
//			throw new RuntimeException("No remapClasspathFile provided");
//		}
//
//		String content = new String(Files.readAllBytes(Paths.get(remapClasspathFile)), StandardCharsets.UTF_8);
//
//		return Arrays.stream(content.split(File.pathSeparator))
//				.map(Paths::get)
//				.collect(Collectors.toList());
	}

	private static class RemapInfo {
		InputTag tag;
		Path inputPath;
		Path outputPath;
		boolean inputIsTemp;
		OutputConsumerPath outputConsumerPath;
		String accessWidenerPath;
		byte[] accessWidener;
	}
}
