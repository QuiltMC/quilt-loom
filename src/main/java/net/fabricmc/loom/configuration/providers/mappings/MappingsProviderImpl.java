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

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.function.Consumer;

import com.google.gson.JsonObject;
import org.apache.tools.ant.util.StringUtils;
import org.gradle.api.Project;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.LoomGradlePlugin;
import net.fabricmc.loom.configuration.accesswidener.AccessWidenerJarProcessor;
import net.fabricmc.loom.configuration.processors.JarProcessorManager;
import net.fabricmc.loom.configuration.processors.MinecraftProcessedProvider;
import net.fabricmc.loom.configuration.providers.MinecraftProviderImpl;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftMappedProvider;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.stitch.Command;
import net.fabricmc.stitch.commands.CommandProposeFieldNames;

public class MappingsProviderImpl extends AbstractMappingsProviderImpl {
	public MinecraftMappedProvider mappedProvider;

	private File unpickDefinitionsFile;
	private boolean hasUnpickDefinitions;
	private UnpickMetadata unpickMetadata;

	public MappingsProviderImpl(Project project) {
		super(project);
	}

	@Override
	public void provide(DependencyInfo dependency, Consumer<Runnable> postPopulationScheduler) throws Exception {
		MinecraftProviderImpl minecraftProvider = getDependencyManager().getProvider(MinecraftProviderImpl.class);

		File mappingsJar = dependency.resolveFile().orElseThrow(() -> new RuntimeException("Could not find quilt mappings: " + dependency));

		unpickDefinitionsFile = mappingsDir.resolve(StringUtils.removeSuffix(mappingsJar.getName(), ".jar") + ".unpick").toFile();

		super.provide(dependency, postPopulationScheduler);

		if (tinyMappings.exists() && !isRefreshDeps()) {
			try (FileSystem fileSystem = FileSystems.newFileSystem(mappingsJar.toPath(), (ClassLoader) null)) {
				extractUnpickDefinitions(fileSystem, unpickDefinitionsFile.toPath());
			}
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

	@Override
	protected void storeMappings(Project project, MinecraftProviderImpl minecraftProvider, Path mappingsJar) throws IOException {
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

	@Override
	protected void mergeAndSaveMappings(Project project, Path unmergedMappingsJar) throws IOException {
		Path unmergedMappings = Paths.get(mappingsStepsDir.toString(), Constants.Mappings.UNMERGED_MAPPINGS_FILE);
		project.getLogger().info(":extracting " + unmergedMappingsJar.getFileName());

		try (FileSystem unmergedMappingsJarFs = FileSystems.newFileSystem(unmergedMappingsJar, (ClassLoader) null)) {
			extractMappings(unmergedMappingsJarFs, unmergedMappings);
		}

		Path invertedHashed = Paths.get(mappingsStepsDir.toString(), Constants.Mappings.INVERTED_HASHED_FILE);
		reorderMappings(getHashedTiny(), invertedHashed, Constants.Mappings.INTERMEDIATE_NAMESPACE, Constants.Mappings.SOURCE_NAMESPACE);
		Path unorderedMergedMappings = Paths.get(mappingsStepsDir.toString(), Constants.Mappings.UNORDERED_MERGED_MAPPINGS_FILE);
		project.getLogger().info(":merging");
		mergeMappings(invertedHashed, unmergedMappings, unorderedMergedMappings);
		reorderMappings(unorderedMergedMappings, tinyMappings.toPath(), Constants.Mappings.SOURCE_NAMESPACE, Constants.Mappings.INTERMEDIATE_NAMESPACE, Constants.Mappings.NAMED_NAMESPACE);
	}

	private void suggestFieldNames(MinecraftProviderImpl minecraftProvider, Path oldMappings, Path newMappings) {
		Command command = new CommandProposeFieldNames();
		runCommand(command, minecraftProvider.getMergedJar().getAbsolutePath(),
						oldMappings.toAbsolutePath().toString(),
						newMappings.toAbsolutePath().toString());
	}

	@Override
	public String getTargetConfig() {
		return Constants.Configurations.MAPPINGS;
	}

	public Path getHashedTiny() throws IOException {
		IntermediateMappingsProviderImpl intermediateMappingsProvider = getDependencyManager().getProvider(IntermediateMappingsProviderImpl.class);
		return intermediateMappingsProvider.getTinyMappings().toPath();
	}

	public String getMappingsKey() {
		return mappingsName + "." + minecraftVersion.replace(' ', '_').replace('.', '_').replace('-', '_') + "." + mappingsVersion;
	}

	public File getUnpickDefinitionsFile() {
		return unpickDefinitionsFile;
	}

	@Override
	public boolean hasUnpickDefinitions() {
		return hasUnpickDefinitions;
	}

	public record UnpickMetadata(String unpickGroup, String unpickVersion) {
	}
}
