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

import net.fabricmc.loader.impl.util.UrlUtil;
import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;
import net.minecraftforge.fml.loading.FMLLoader;
import xyz.wagyourtail.fabriconforge.FabricLoaderEarlyRiser;

import java.nio.file.Path;

public class ClasspathModCandidateFinder implements ModCandidateFinder {
	@Override
	public void findCandidates(ModCandidateConsumer out) {
		try {
			out.accept(getFabricLoaderPath(), false);
		} catch (Throwable t) {
			Log.debug(LogCategory.DISCOVERY, "Could not retrieve launcher code source!", t);
		}
	}

	public static Path getFabricLoaderPath() {
		try {
			System.out.println(FMLLoader.getLoadingModList().getModFileById("fabric-on-forge").getFile().getFilePath());
			return FMLLoader.getLoadingModList().getModFileById("fabric-on-forge").getFile().getFilePath();
		} catch (Throwable t) {
			Log.debug(LogCategory.DISCOVERY, "Could not retrieve launcher code source!", t);
			return null;
		}
	}
}
