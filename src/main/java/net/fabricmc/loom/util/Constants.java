/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2016-2021 FabricMC
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package net.fabricmc.loom.util;

import java.util.List;

import com.google.common.collect.ImmutableList;
import org.gradle.api.plugins.JavaPlugin;
import org.objectweb.asm.Opcodes;

import net.fabricmc.loom.configuration.RemappedConfigurationEntry;

public class Constants {
	public static final String LIBRARIES_BASE = "https://libraries.minecraft.net/";
	public static final String RESOURCES_BASE = "https://resources.download.minecraft.net/";
	public static final String VERSION_MANIFESTS = "https://launchermeta.mojang.com/mc/game/version_manifest_v2.json";
	public static final String EXPERIMENTAL_VERSIONS = "https://maven.fabricmc.net/net/minecraft/experimental_versions.json";

	public static final String SYSTEM_ARCH = System.getProperty("os.arch").equals("64") ? "64" : "32";

	public static final int ASM_VERSION = Opcodes.ASM9;

	public static final List<RemappedConfigurationEntry> MOD_COMPILE_ENTRIES = ImmutableList.of(
			new RemappedConfigurationEntry("modApi", JavaPlugin.API_CONFIGURATION_NAME, true, "compile"),
			new RemappedConfigurationEntry("modImplementation", JavaPlugin.IMPLEMENTATION_CONFIGURATION_NAME, true, "runtime"),
			new RemappedConfigurationEntry("modRuntime", JavaPlugin.RUNTIME_ONLY_CONFIGURATION_NAME, false, ""),
			new RemappedConfigurationEntry("modCompileOnly", JavaPlugin.COMPILE_ONLY_CONFIGURATION_NAME, true, "")
	);

	private Constants() {
	}

	/**
	 * Constants related to configurations.
	 */
	public static final class Configurations {
		public static final String MOD_COMPILE_CLASSPATH = "modCompileClasspath";
		public static final String MOD_COMPILE_CLASSPATH_MAPPED = "modCompileClasspathMapped";
		public static final String INCLUDE = "include";
		public static final String MINECRAFT = "minecraft";
		public static final String MINECRAFT_DEPENDENCIES = "minecraftLibraries";
		public static final String MINECRAFT_REMAP_CLASSPATH = "minecraftRemapClasspath";
		public static final String MINECRAFT_NAMED = "minecraftNamed";
		public static final String MAPPINGS = "mappings";
		public static final String MAPPINGS_FINAL = "mappingsFinal";
		public static final String LOADER_DEPENDENCIES = "loaderLibraries";
		public static final String LOOM_DEVELOPMENT_DEPENDENCIES = "loomDevelopmentDependencies";
		public static final String MAPPING_CONSTANTS = "mappingsConstants";
		public static final String UNPICK_CLASSPATH = "unpick";

		private Configurations() {
		}
	}

	/**
	 * Constants related to dependencies.
	 */
	public static final class Dependencies {
		public static final String MIXIN_COMPILE_EXTENSIONS = "org.quiltmc:sponge-mixin-compile-extensions:";
		public static final String DEV_LAUNCH_INJECTOR = "org.quiltmc:dev-launch-injector:";
		public static final String TERMINAL_CONSOLE_APPENDER = "net.minecrell:terminalconsoleappender:";
		public static final String JETBRAINS_ANNOTATIONS = "org.jetbrains:annotations:";

		private Dependencies() {
		}

		/**
		 * Constants for versions of dependencies.
		 */
		public static final class Versions {
			public static final String MIXIN_COMPILE_EXTENSIONS = "1.0.0";
			public static final String DEV_LAUNCH_INJECTOR = "1.0.1";
			public static final String TERMINAL_CONSOLE_APPENDER = "1.2.0";
			public static final String JETBRAINS_ANNOTATIONS = "19.0.0";

			private Versions() {
			}
		}
	}

	public static final class MixinArguments {
		public static final String IN_MAP_FILE_NAMED_HASHED = "inMapFileNamedHashed";
		public static final String OUT_MAP_FILE_NAMED_HASHED = "outMapFileNamedHashed";
		public static final String OUT_REFMAP_FILE = "outRefMapFile";
		public static final String DEFAULT_OBFUSCATION_ENV = "defaultObfuscationEnv";

		private MixinArguments() {
		}
	}

	public static final class Knot {
		public static final String KNOT_CLIENT = "org.quiltmc.loader.impl.launch.knot.KnotClient";
		public static final String KNOT_SERVER = "org.quiltmc.loader.impl.launch.knot.KnotServer";

		private Knot() {
		}
	}

	public static final class TaskGroup {
		public static final String QUILT = "quilt";
		public static final String IDE = "ide";

		private TaskGroup() {
		}
	}

	/**
	 * Constants related to directories.
	 */
	public static final class Directories {
		public static final String USER_CACHE_DIR = "quilt-loom";
		public static final String CACHE_DIR = "loom-cache";
		public static final String REMAPPED_MOD_CACHE_DIR = "remapped_mods";
		public static final String NESTED_MOD_CACHE_DIR = "nested_mods";
		public static final String NATIVES_DIR = "natives";
		public static final String NATIVES_JAR_DIR = NATIVES_DIR + "/jars";

		private Directories() {
		}
	}

	/**
	 * Constants related to mappings.
	 */
	public static final class Mappings {
		// Files & dirs
		public static final String MAPPINGS_FILE = "mappings.tiny";
		public static final String MAPPINGS_FILE_DIR = "hashed";
		public static final String MAPPINGS_FILE_PATH = MAPPINGS_FILE_DIR + "/" + MAPPINGS_FILE;
		public static final String UNMERGED_MAPPINGS_FILE = "unmerged-mappings.tiny";
		public static final String MAPPINGS_CACHE_DIR = "mappings";
		public static final String MAPPINGS_STEPS_CACHE_DIR = "steps";
		public static final String INVERTED_HASHED_FILE = "inverted-hashed.tiny";
		public static final String UNORDERED_MERGED_MAPPINGS_FILE = "unordered-merged.tiny";
		public static final String HASHED_TINY_FORMAT = "hashed-%s.tiny";
		public static final String HASHED_JAR_FORMAT = "hashed-%s.jar";

		// Namespaces
		public static final String SOURCE_NAMESPACE = "official";
		public static final String INTERMEDIATE_NAMESPACE = "hashed";
		public static final String NAMED_NAMESPACE = "named";

		private Mappings() {
		}
	}
}
