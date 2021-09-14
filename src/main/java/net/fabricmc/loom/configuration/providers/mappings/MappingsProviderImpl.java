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

package net.fabricmc.loom.configuration.providers.mappings;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.function.Consumer;

import com.google.common.base.Preconditions;
import com.google.common.net.UrlEscapers;
import com.google.gson.JsonObject;
import org.apache.commons.io.FileUtils;
import org.apache.tools.ant.util.StringUtils;
import org.gradle.api.Project;
import org.zeroturnaround.zip.FileSource;
import org.zeroturnaround.zip.ZipEntrySource;
import org.zeroturnaround.zip.ZipUtil;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.LoomGradlePlugin;
import net.fabricmc.loom.configuration.DependencyProvider;
import net.fabricmc.loom.configuration.accesswidener.AccessWidenerJarProcessor;
import net.fabricmc.loom.configuration.processors.JarProcessorManager;
import net.fabricmc.loom.configuration.processors.MinecraftProcessedProvider;
import net.fabricmc.loom.configuration.providers.MinecraftProviderImpl;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftMappedProvider;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.DeletingFileVisitor;
import net.fabricmc.loom.util.DownloadUtil;
import net.fabricmc.mapping.reader.v2.TinyV2Factory;
import net.fabricmc.mapping.tree.TinyTree;
import net.fabricmc.stitch.Command;
import net.fabricmc.stitch.commands.CommandProposeFieldNames;
import net.fabricmc.stitch.commands.tinyv2.CommandMergeTinyV2;
import net.fabricmc.stitch.commands.tinyv2.CommandReorderTinyV2;

public class MappingsProviderImpl extends DependencyProvider implements MappingsProvider {
	public MinecraftMappedProvider mappedProvider;

	public String mappingsName;
	public String minecraftVersion;
	public String mappingsVersion;

	private final Path mappingsDir;
	private final Path mappingsStepsDir;
	private Path hashedTiny;
	private boolean hasRefreshed = false;
	// The mappings that gradle gives us
	private Path baseTinyMappings;
	// The mappings we use in practice
	public File tinyMappings;
	public File tinyMappingsJar;
	private File unpickDefinitionsFile;
	private boolean hasUnpickDefinitions;
	private UnpickMetadata unpickMetadata;

	public MappingsProviderImpl(Project project) {
		super(project);
		mappingsDir = getDirectories().getUserCache().toPath().resolve(Constants.Mappings.MAPPINGS_CACHE_DIR);
		mappingsStepsDir = mappingsDir.resolve(Constants.Mappings.MAPPINGS_STEPS_CACHE_DIR);
	}

	public void clean() throws IOException {
		FileUtils.deleteDirectory(mappingsDir.toFile());
	}

	public TinyTree getMappings() throws IOException {
		return MappingsCache.INSTANCE.get(tinyMappings.toPath());
	}

	@Override
	public void provide(DependencyInfo dependency, Consumer<Runnable> postPopulationScheduler) throws Exception {
		MinecraftProviderImpl minecraftProvider = getDependencyManager().getProvider(MinecraftProviderImpl.class);

		getProject().getLogger().info(":setting up mappings (" + dependency.getDependency().getName() + " " + dependency.getResolvedVersion() + ")");

		String version = dependency.getResolvedVersion();
		File mappingsJar = dependency.resolveFile().orElseThrow(() -> new RuntimeException("Could not find quilt mappings: " + dependency));

		this.mappingsName = StringUtils.removeSuffix(dependency.getDependency().getGroup() + "." + dependency.getDependency().getName(), "-unmerged");
		this.minecraftVersion = minecraftProvider.minecraftVersion();

		boolean isV2;

		// Only do this for official yarn, there isn't really a way we can get the mc version for all mappings
		if (dependency.getDependency().getGroup() != null && dependency.getDependency().getGroup().equals("net.fabricmc") && dependency.getDependency().getName().equals("yarn") && dependency.getDependency().getVersion() != null) {
			String yarnVersion = dependency.getDependency().getVersion();
			char separator = yarnVersion.contains("+build.") ? '+' : yarnVersion.contains("-") ? '-' : '.';
			String yarnMinecraftVersion = yarnVersion.substring(0, yarnVersion.lastIndexOf(separator));

			if (!yarnMinecraftVersion.equalsIgnoreCase(minecraftVersion)) {
				throw new RuntimeException(String.format("Minecraft Version (%s) does not match yarn's minecraft version (%s)", minecraftVersion, yarnMinecraftVersion));
			}

			// We can save reading the zip file + header by checking the file name
			isV2 = mappingsJar.getName().endsWith("-v2.jar");
		} else {
			isV2 = doesJarContainV2Mappings(mappingsJar.toPath());
		}

		this.mappingsVersion = version + (isV2 ? "-v2" : "");

		initFiles();

		if (isRefreshDeps()) {
			cleanFiles();
		}

		Files.createDirectories(mappingsDir);
		Files.createDirectories(mappingsStepsDir);

		String[] depStringSplit = dependency.getDepString().split(":");
		String jarClassifier = "final";

		if (depStringSplit.length >= 4) {
			jarClassifier = jarClassifier + depStringSplit[3];
		}

		tinyMappings = mappingsDir.resolve(StringUtils.removeSuffix(mappingsJar.getName(), ".jar") + ".tiny").toFile();
		unpickDefinitionsFile = mappingsDir.resolve(StringUtils.removeSuffix(mappingsJar.getName(), ".jar") + ".unpick").toFile();
		tinyMappingsJar = new File(getDirectories().getUserCache(), mappingsJar.getName().replace(".jar", "-" + jarClassifier + ".jar"));

		if (!tinyMappings.exists() || isRefreshDeps()) {
			storeMappings(getProject(), minecraftProvider, mappingsJar.toPath());
		} else {
			try (FileSystem fileSystem = FileSystems.newFileSystem(mappingsJar.toPath(), (ClassLoader) null)) {
				extractUnpickDefinitions(fileSystem, unpickDefinitionsFile.toPath());
			}
		}

		if (!tinyMappingsJar.exists() || isRefreshDeps()) {
			ZipUtil.pack(new ZipEntrySource[] {new FileSource(Constants.Mappings.MAPPINGS_FILE_PATH, tinyMappings)}, tinyMappingsJar);
		}

		if (hasUnpickDefinitions()) {
			String notation = String.format("%s:%s:%s:constants",
					dependency.getDependency().getGroup(),
					dependency.getDependency().getName(),
					dependency.getDependency().getVersion()
			);

			getProject().getDependencies().add(Constants.Configurations.MAPPING_CONSTANTS, notation);
			populateUnpickClasspath();
		}

		addDependency(tinyMappingsJar, Constants.Configurations.MAPPINGS_FINAL);

		LoomGradleExtension extension = getExtension();

		if (extension.getAccessWidenerPath().isPresent()) {
			extension.getGameJarProcessors().add(new AccessWidenerJarProcessor(getProject()));
		}

		extension.getAccessWidenerPath().finalizeValue();
		extension.getGameJarProcessors().finalizeValue();
		JarProcessorManager processorManager = new JarProcessorManager(extension.getGameJarProcessors().get());
		extension.setJarProcessorManager(processorManager);
		processorManager.setupProcessors();

		if (processorManager.active()) {
			mappedProvider = new MinecraftProcessedProvider(getProject(), processorManager);
			getProject().getLogger().lifecycle("Using project based jar storage");
		} else {
			mappedProvider = new MinecraftMappedProvider(getProject());
		}

		mappedProvider.initFiles(minecraftProvider, this);
		mappedProvider.provide(dependency, postPopulationScheduler);
	}

	private void storeMappings(Project project, MinecraftProviderImpl minecraftProvider, Path mappingsJar) throws IOException {
		project.getLogger().info(":extracting " + mappingsJar.getFileName());

		try (FileSystem fileSystem = FileSystems.newFileSystem(mappingsJar, (ClassLoader) null)) {
			extractMappings(fileSystem, baseTinyMappings);
			extractUnpickDefinitions(fileSystem, unpickDefinitionsFile.toPath());
		}

		if (baseMappingsAreV2()) {
			// These are unmerged v2 mappings
			mergeAndSaveMappings(project, mappingsJar);
		} else {
			// These are merged v1 mappings
			if (tinyMappings.exists()) {
				tinyMappings.delete();
			}

			project.getLogger().lifecycle(":populating field names");
			suggestFieldNames(minecraftProvider, baseTinyMappings, tinyMappings.toPath());
		}
	}

	private boolean baseMappingsAreV2() throws IOException {
		try (BufferedReader reader = Files.newBufferedReader(baseTinyMappings)) {
			TinyV2Factory.readMetadata(reader);
			return true;
		} catch (IllegalArgumentException e) {
			// TODO: just check the mappings version when Parser supports V1 in readMetadata()
			return false;
		}
	}

	private boolean doesJarContainV2Mappings(Path path) throws IOException {
		try (FileSystem fs = FileSystems.newFileSystem(path, (ClassLoader) null)) {
			try (BufferedReader reader = Files.newBufferedReader(fs.getPath(Constants.Mappings.MAPPINGS_FILE_DIR, Constants.Mappings.MAPPINGS_FILE))) {
				TinyV2Factory.readMetadata(reader);
				return true;
			} catch (IllegalArgumentException e) {
				return false;
			}
		}
	}

	public static void extractMappings(FileSystem jar, Path extractTo) throws IOException {
		Files.copy(jar.getPath(Constants.Mappings.MAPPINGS_FILE_PATH), extractTo, StandardCopyOption.REPLACE_EXISTING);
	}

	private void extractUnpickDefinitions(FileSystem jar, Path extractTo) throws IOException {
		Path unpickPath = jar.getPath("extras/definitions.unpick");
		Path unpickMetadataPath = jar.getPath("extras/unpick.json");

		if (!Files.exists(unpickPath) || !Files.exists(unpickMetadataPath)) {
			return;
		}

		Files.copy(unpickPath, extractTo, StandardCopyOption.REPLACE_EXISTING);

		unpickMetadata = parseUnpickMetadata(unpickMetadataPath);
		hasUnpickDefinitions = true;
	}

	private UnpickMetadata parseUnpickMetadata(Path input) throws IOException {
		JsonObject jsonObject = LoomGradlePlugin.GSON.fromJson(Files.readString(input), JsonObject.class);

		if (!jsonObject.has("version") || jsonObject.get("version").getAsInt() != 1) {
			throw new UnsupportedOperationException("Unsupported unpick version");
		}

		return new UnpickMetadata(
				jsonObject.get("unpickGroup").getAsString(),
				jsonObject.get("unpickVersion").getAsString()
		);
	}

	private void populateUnpickClasspath() {
		String unpickCliName = "unpick-cli";
		getProject().getDependencies().add(Constants.Configurations.UNPICK_CLASSPATH,
				String.format("%s:%s:%s", unpickMetadata.unpickGroup, unpickCliName, unpickMetadata.unpickVersion)
		);
	}

	private void extractHashedMojmap(Path hashedMojmapJar, Path hashedMojmapTiny) throws IOException {
		getProject().getLogger().info(":extracting " + hashedMojmapJar.getFileName());

		try (FileSystem unmergedHashedFs = FileSystems.newFileSystem(hashedMojmapJar, (ClassLoader) null)) {
			extractMappings(unmergedHashedFs, hashedMojmapTiny);
		}
	}

	private void mergeAndSaveMappings(Project project, Path unmergedMappingsJar) throws IOException {
		Path unmergedYarn = Paths.get(mappingsStepsDir.toString(), Constants.Mappings.UNMERGED_MAPPINGS_FILE);
		project.getLogger().info(":extracting " + unmergedMappingsJar.getFileName());

		try (FileSystem unmergedMappingsJarFs = FileSystems.newFileSystem(unmergedMappingsJar, (ClassLoader) null)) {
			extractMappings(unmergedMappingsJarFs, unmergedYarn);
		}

		Path invertedHashed = Paths.get(mappingsStepsDir.toString(), Constants.Mappings.INVERTED_HASHED_FILE);
		reorderMappings(getHashedTiny(), invertedHashed, Constants.Mappings.INTERMEDIATE_NAMESPACE, Constants.Mappings.SOURCE_NAMESPACE);
		Path unorderedMergedMappings = Paths.get(mappingsStepsDir.toString(), Constants.Mappings.UNORDERED_MERGED_MAPPINGS_FILE);
		project.getLogger().info(":merging");
		mergeMappings(invertedHashed, unmergedYarn, unorderedMergedMappings);
		reorderMappings(unorderedMergedMappings, tinyMappings.toPath(), Constants.Mappings.SOURCE_NAMESPACE, Constants.Mappings.INTERMEDIATE_NAMESPACE, Constants.Mappings.NAMED_NAMESPACE);
	}

	private void reorderMappings(Path oldMappings, Path newMappings, String... newOrder) {
		Command command = new CommandReorderTinyV2();
		String[] args = new String[2 + newOrder.length];
		args[0] = oldMappings.toAbsolutePath().toString();
		args[1] = newMappings.toAbsolutePath().toString();
		System.arraycopy(newOrder, 0, args, 2, newOrder.length);
		runCommand(command, args);
	}

	private void mergeMappings(Path hashedMappings, Path mappings, Path newMergedMappings) {
		try {
			Command command = new CommandMergeTinyV2();
			runCommand(command, hashedMappings.toAbsolutePath().toString(),
							mappings.toAbsolutePath().toString(),
							newMergedMappings.toAbsolutePath().toString(),
							Constants.Mappings.INTERMEDIATE_NAMESPACE, Constants.Mappings.SOURCE_NAMESPACE);
		} catch (Exception e) {
			throw new RuntimeException("Could not merge mappings from " + hashedMappings.toString()
							+ " with mappings from " + mappings, e);
		}
	}

	private void suggestFieldNames(MinecraftProviderImpl minecraftProvider, Path oldMappings, Path newMappings) {
		Command command = new CommandProposeFieldNames();
		runCommand(command, minecraftProvider.getMergedJar().getAbsolutePath(),
						oldMappings.toAbsolutePath().toString(),
						newMappings.toAbsolutePath().toString());
	}

	private void runCommand(Command command, String... args) {
		try {
			command.run(args);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private void initFiles() {
		baseTinyMappings = mappingsDir.resolve(mappingsName + "-tiny-" + minecraftVersion + "-" + mappingsVersion + "-base");
	}

	public void cleanFiles() {
		try {
			if (Files.exists(mappingsStepsDir)) {
				Files.walkFileTree(mappingsStepsDir, new DeletingFileVisitor());
			}

			if (Files.exists(baseTinyMappings)) {
				Files.deleteIfExists(baseTinyMappings);
			}

			if (tinyMappings != null) {
				tinyMappings.delete();
			}

			if (tinyMappingsJar != null) {
				tinyMappingsJar.delete();
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	@Override
	public String getTargetConfig() {
		return Constants.Configurations.MAPPINGS;
	}

	@Override
	public Path getMappingsDir() {
		return mappingsDir;
	}

	public Path getHashedTiny() throws IOException {
		if (hashedTiny == null) {
			minecraftVersion = getExtension().getMinecraftProvider().minecraftVersion();
			Preconditions.checkNotNull(minecraftVersion, "Minecraft version cannot be null");

			hashedTiny = mappingsDir.resolve(String.format(Constants.Mappings.HASHED_TINY_FORMAT, minecraftVersion));

			if (!Files.exists(hashedTiny) || (isRefreshDeps() && !hasRefreshed)) {
				hasRefreshed = true;

				// Download and extract hashed mojmap
				String encodedMinecraftVersion = UrlEscapers.urlFragmentEscaper().escape(minecraftVersion);
				String hashedMojmapArtifactUrl = getExtension().getHashedMojmapUrl(encodedMinecraftVersion);
				Path hashedJar = mappingsDir.resolve(String.format(Constants.Mappings.HASHED_JAR_FORMAT, minecraftVersion));

				DownloadUtil.downloadIfChanged(new URL(hashedMojmapArtifactUrl), hashedJar.toFile(), getProject().getLogger());

				extractHashedMojmap(hashedJar, hashedTiny);
			}
		}

		return hashedTiny;
	}

	public String getMappingsKey() {
		return mappingsName + "." + minecraftVersion.replace(' ', '_').replace('.', '_').replace('-', '_') + "." + mappingsVersion;
	}

	public File getUnpickDefinitionsFile() {
		return unpickDefinitionsFile;
	}

	public boolean hasUnpickDefinitions() {
		return hasUnpickDefinitions;
	}

	@Override
	public File hashedMojmapTinyFile() {
		try {
			return getHashedTiny().toFile();
		} catch (IOException e) {
			throw new RuntimeException("Failed to get hashed mojmap", e);
		}
	}

	public record UnpickMetadata(String unpickGroup, String unpickVersion) {
	}
}
