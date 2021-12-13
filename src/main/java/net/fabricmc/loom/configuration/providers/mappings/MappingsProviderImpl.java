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
import java.io.Reader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.google.common.base.Stopwatch;
import com.google.common.net.UrlEscapers;
import com.google.gson.JsonObject;
import org.apache.tools.ant.util.StringUtils;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ResolvedArtifact;
import org.gradle.api.artifacts.ResolvedConfiguration;
import org.gradle.api.artifacts.ResolvedDependency;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.LoomGradlePlugin;
import net.fabricmc.loom.api.mappings.layered.MappingsNamespace;
import net.fabricmc.loom.configuration.DependencyProvider;
import net.fabricmc.loom.configuration.accesswidener.AccessWidenerJarProcessor;
import net.fabricmc.loom.configuration.accesswidener.TransitiveAccessWidenerJarProcessor;
import net.fabricmc.loom.configuration.processors.JarProcessorManager;
import net.fabricmc.loom.configuration.processors.MinecraftProcessedProvider;
import net.fabricmc.loom.configuration.providers.MinecraftProviderImpl;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftMappedProvider;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.DeletingFileVisitor;
import net.fabricmc.loom.util.DownloadUtil;
import net.fabricmc.loom.util.ZipUtils;
import net.fabricmc.mappingio.MappingReader;
import net.fabricmc.mappingio.adapter.MappingNsCompleter;
import net.fabricmc.mappingio.adapter.MappingSourceNsSwitch;
import net.fabricmc.mappingio.format.MappingFormat;
import net.fabricmc.mappingio.format.Tiny2Reader;
import net.fabricmc.mappingio.format.Tiny2Writer;
import net.fabricmc.mappingio.tree.MappingTree;
import net.fabricmc.mappingio.tree.MemoryMappingTree;
import net.fabricmc.stitch.Command;
import net.fabricmc.stitch.commands.CommandProposeFieldNames;

public class MappingsProviderImpl extends DependencyProvider implements MappingsProvider {
	public IntermediateMappingsHandler intermediateMappingsHandler = new IntermediateMappingsHandler();
	public MinecraftMappedProvider mappedProvider;

	public String mappingsIdentifier;

	private Path mappingsWorkingDir;
	// Only used for the hashed mojmap mapping layer
	private Path hashedTiny;
	private boolean hasRefreshed = false;
	// The mappings that gradle gives us, which were inside a jar
	private Path baseTinyMappings;
	// The mappings we use in practice
	public Path tinyMappings;
	// A jar containing the tinyMappings file, used in the 'mappings final' configuration
	public Path tinyMappingsJar;
	private Path unpickDefinitions;
	private boolean hasUnpickDefinitions;
	private UnpickMetadata unpickMetadata;
	private MemoryMappingTree mappingTree;
	private Map<String, String> signatureFixes;

	public MappingsProviderImpl(Project project) {
		super(project);
	}

	public MemoryMappingTree getMappings() throws IOException {
		return Objects.requireNonNull(mappingTree, "Cannot get mappings before they have been read");
	}

	@Override
	public void provide(DependencyInfo dependency, Consumer<Runnable> postPopulationScheduler) throws Exception {
		MinecraftProviderImpl minecraftProvider = getDependencyManager().getProvider(MinecraftProviderImpl.class);

		Set<ResolvedDependency> resolvedDependencies = getProject().getConfigurations().getByName(getTargetConfig()).getResolvedConfiguration().getFirstLevelModuleDependencies();

		if (resolvedDependencies.size() != 1) {
			throw new IllegalStateException("There isn't only one resolved " + getTargetConfig() + " dependency");
		}

		ResolvedDependency resolvedDependency = resolvedDependencies.iterator().next();
		intermediateMappingsHandler.populateConfiguration(minecraftProvider.minecraftVersion(), resolvedDependency);

		getProject().getLogger().info(":setting up mappings (" + dependency.getDependency().getName() + " " + dependency.getResolvedVersion() + ")");

		String version = dependency.getResolvedVersion();
		// Resolve dependency artifacts
		Set<File> mappingsJars = resolvedDependency.getModuleArtifacts().stream().map(ResolvedArtifact::getFile).collect(Collectors.toSet());

		if (mappingsJars.size() != 1) {
			throw new RuntimeException("Could not find mappings: " + dependency);
		}

		File mappingsJar = mappingsJars.iterator().next();

		String mappingsName = StringUtils.removeSuffix(resolvedDependency.getModuleGroup() + "." + resolvedDependency.getModuleName(), "-unmerged");
		boolean isV2 = isV2(dependency, mappingsJar);
		this.mappingsIdentifier = createMappingsIdentifier(mappingsName, version, getMappingsClassifier(dependency, isV2));
		intermediateMappingsHandler.createDependencyIdentifiers();

		initFiles();
		intermediateMappingsHandler.initFiles();

		intermediateMappingsHandler.storeMappings();

		// Extract mappings to baseTinyMappings, save merged mappings to tinyMappings
		if (Files.notExists(tinyMappings) || isRefreshDeps()) {
			storeMappings(getProject(), minecraftProvider, mappingsJar.toPath());
		} else {
			// Extract extras
			try (FileSystem fileSystem = FileSystems.newFileSystem(mappingsJar.toPath(), (ClassLoader) null)) {
				extractExtras(fileSystem);
			}
		}

		// Read mappings from the tinyMappings file
		mappingTree = readMappings();

		if (Files.notExists(tinyMappingsJar) || isRefreshDeps()) {
			Files.deleteIfExists(tinyMappingsJar);
			ZipUtils.add(tinyMappingsJar, Constants.Mappings.DEFAULT_MAPPINGS_FILE_PATH, Files.readAllBytes(tinyMappings));
		}

		// Populate the 'mapping constants' and 'unpick classpath' configurations
		if (hasUnpickDefinitions()) {
			String notation = String.format("%s:%s:%s:constants",
					dependency.getDependency().getGroup(),
					dependency.getDependency().getName(),
					dependency.getDependency().getVersion()
			);

			getProject().getDependencies().add(Constants.Configurations.MAPPING_CONSTANTS, notation);
			populateUnpickClasspath();
		}

		// Populate the 'mappings final' configuration
		addDependency(tinyMappingsJar.toFile(), Constants.Configurations.MAPPINGS_FINAL);

		LoomGradleExtension extension = getExtension();

		if (extension.getAccessWidenerPath().isPresent()) {
			extension.getGameJarProcessors().add(new AccessWidenerJarProcessor(getProject()));
		}

		if (extension.getEnableTransitiveAccessWideners().get()) {
			TransitiveAccessWidenerJarProcessor transitiveAccessWidenerJarProcessor = new TransitiveAccessWidenerJarProcessor(getProject());

			if (!transitiveAccessWidenerJarProcessor.isEmpty()) {
				extension.getGameJarProcessors().add(transitiveAccessWidenerJarProcessor);
			}
		}

		extension.getAccessWidenerPath().finalizeValue();
		extension.getGameJarProcessors().finalizeValue();
		JarProcessorManager processorManager = new JarProcessorManager(extension.getGameJarProcessors().get());
		extension.setJarProcessorManager(processorManager);
		processorManager.setupProcessors();

		if (processorManager.active()) {
			mappedProvider = new MinecraftProcessedProvider(getProject(), processorManager);
			getProject().getLogger().info("Using project based jar storage");
		} else {
			mappedProvider = new MinecraftMappedProvider(getProject());
		}

		mappedProvider.initFiles(minecraftProvider, this);
		mappedProvider.provide(dependency, postPopulationScheduler);
	}

	private String getMappingsClassifier(DependencyInfo dependency, boolean isV2) {
		String[] depStringSplit = dependency.getDepString().split(":");

		if (depStringSplit.length >= 4) {
			return "-" + depStringSplit[3] + (isV2 ? "-v2" : "");
		}

		return isV2 ? "-v2" : "";
	}

	private boolean isV2(DependencyInfo dependency, File mappingsJar) throws IOException {
		// TODO: Check if the mappings version is the same as the minecraft version
		// TODO: Skip reading the mappings if the dependency is Quilt Mappings or Yarn

		return doesJarContainV2Mappings(mappingsJar.toPath());
	}

	private void storeMappings(Project project, MinecraftProviderImpl minecraftProvider, Path mappingsJar) throws IOException {
		project.getLogger().info(":extracting " + mappingsJar.getFileName());

		try (FileSystem fileSystem = FileSystems.newFileSystem(mappingsJar, (ClassLoader) null)) {
			boolean extracted = extractMappings(fileSystem, baseTinyMappings);

			if (!extracted) {
				throw new RuntimeException("Could not find mappings file in " + mappingsJar.toAbsolutePath());
			}

			extractExtras(fileSystem);
		}

		if (areMappingsV2(baseTinyMappings)) {
			// These are unmerged v2 mappings
			mergeAndSaveMappings(project, baseTinyMappings, tinyMappings);
		} else {
			// These are merged v1 mappings
			Files.deleteIfExists(tinyMappings);
			project.getLogger().lifecycle(":populating field names");
			suggestFieldNames(minecraftProvider, baseTinyMappings, tinyMappings);
		}
	}

	private MemoryMappingTree readMappings() throws IOException {
		MemoryMappingTree mappingTree = new MemoryMappingTree();
		MappingReader.read(tinyMappings, mappingTree);
		return mappingTree;
	}

	private static boolean areMappingsV2(Path path) throws IOException {
		try (BufferedReader reader = Files.newBufferedReader(path)) {
			return MappingReader.detectFormat(reader) == MappingFormat.TINY_2;
		}
	}

	private static boolean doesJarContainV2Mappings(Path path) throws IOException {
		try (FileSystem fs = FileSystems.newFileSystem(path, (ClassLoader) null)) {
			Path file = getMappingsFilePath(fs);

			if (file == null) {
				return false;
			}

			try (BufferedReader reader = Files.newBufferedReader(fs.getPath(file.toString()))) {
				return MappingReader.detectFormat(reader) == MappingFormat.TINY_2;
			}
		}
	}

	private static void extractMappings(Path jar, Path extractTo) throws IOException {
		try (FileSystem unmergedIntermediaryFs = FileSystems.newFileSystem(jar, (ClassLoader) null)) {
			extractMappings(unmergedIntermediaryFs, extractTo);
		}
	}

	public static boolean extractMappings(FileSystem jar, Path extractTo) throws IOException {
		Path file = getMappingsFilePath(jar);

		if (file != null) {
			Files.copy(jar.getPath(file.toString()), extractTo, StandardCopyOption.REPLACE_EXISTING);
			return true;
		} else {
			return false;
		}
	}

	public static Path getMappingsFilePath(FileSystem jar) {
		// Get the path of a file named "mappings.tiny"
		for (Path rootDir : jar.getRootDirectories()) {
			try (Stream<Path> stream = Files.find(rootDir, 2, (path, attrs) -> path.getFileName() != null && path.getFileName().toString().equals(Constants.Mappings.MAPPINGS_FILE))) {
				Optional<Path> optional = stream.findFirst();

				if (optional.isPresent()) {
					return optional.get();
				}
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}

		return null;
	}

	private void extractExtras(FileSystem jar) throws IOException {
		extractUnpickDefinitions(jar);
		extractSignatureFixes(jar);
	}

	private void extractUnpickDefinitions(FileSystem jar) throws IOException {
		Path unpickPath = jar.getPath("extras/definitions.unpick");
		Path unpickMetadataPath = jar.getPath("extras/unpick.json");

		if (!Files.exists(unpickPath) || !Files.exists(unpickMetadataPath)) {
			return;
		}

		Files.copy(unpickPath, unpickDefinitions, StandardCopyOption.REPLACE_EXISTING);

		unpickMetadata = parseUnpickMetadata(unpickMetadataPath);
		hasUnpickDefinitions = true;
	}

	private void extractSignatureFixes(FileSystem jar) throws IOException {
		Path recordSignaturesJsonPath = jar.getPath("extras/record_signatures.json");

		if (!Files.exists(recordSignaturesJsonPath)) {
			return;
		}

		try (Reader reader = Files.newBufferedReader(recordSignaturesJsonPath, StandardCharsets.UTF_8)) {
			//noinspection unchecked
			signatureFixes = LoomGradlePlugin.OBJECT_MAPPER.readValue(reader, Map.class);
		}
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

		// Unpick ships with a slightly older version of asm, ensure it runs with at least the same version as loom.
		String[] asmDeps = new String[] {
				"org.ow2.asm:asm:%s",
				"org.ow2.asm:asm-tree:%s",
				"org.ow2.asm:asm-commons:%s",
				"org.ow2.asm:asm-util:%s"
		};

		for (String asm : asmDeps) {
			getProject().getDependencies().add(Constants.Configurations.UNPICK_CLASSPATH,
					asm.formatted(Opcodes.class.getPackage().getImplementationVersion())
			);
		}
	}

	// Merge base mappings with intermediate mappings
	private void mergeAndSaveMappings(Project project, Path from, Path out) throws IOException {
		Stopwatch stopwatch = Stopwatch.createStarted();
		project.getLogger().info(":merging mappings");

		MemoryMappingTree mappingsTree = new MemoryMappingTree();

		try (BufferedReader reader = Files.newBufferedReader(from)) {
			Tiny2Reader.read(reader, mappingsTree);
		}

		String intermediateNamespace = mappingsTree.getSrcNamespace();
		MemoryMappingTree intermediateTree = new MemoryMappingTree();
		MappingSourceNsSwitch sourceNsSwitch = new MappingSourceNsSwitch(intermediateTree, intermediateNamespace);
		MappingTree tree;

		if (intermediateMappingsHandler.mappingsIntermediateDependency != null) {
			tree = intermediateMappingsHandler.getMappingTree(intermediateMappingsHandler.mappingsIntermediateDependency);

			if (!tree.getDstNamespaces().contains(intermediateNamespace)) {
				throw new IllegalArgumentException("The intermediate mappings do not contain the base mappings namespace");
			}
		} else {
			tree = intermediateMappingsHandler.getMappingTree(intermediateNamespace);

			if (tree == null) {
				throw new IllegalArgumentException("No intermediate mappings found with namespace " + intermediateNamespace);
			}
		}

		tree.accept(sourceNsSwitch);
		mappingsTree.accept(intermediateTree);

		MemoryMappingTree officialTree = new MemoryMappingTree();
		MappingNsCompleter namedNsCompleter = new MappingNsCompleter(officialTree, Map.of(MappingsNamespace.NAMED.toString(), intermediateNamespace));
		MappingNsCompleter nsCompleter = new MappingNsCompleter(namedNsCompleter, Map.of(MappingsNamespace.OFFICIAL.toString(), intermediateNamespace));
		MappingSourceNsSwitch nsSwitch = new MappingSourceNsSwitch(nsCompleter, MappingsNamespace.OFFICIAL.toString());
		intermediateTree.accept(nsSwitch);

		inheritMappedNamesOfEnclosingClasses(officialTree, intermediateNamespace);

		try (Tiny2Writer writer = new Tiny2Writer(Files.newBufferedWriter(out, StandardCharsets.UTF_8), false)) {
			officialTree.accept(writer);
		}

		project.getLogger().info(":merged mappings in " + stopwatch.stop());
	}

	/**
	 * Searches the mapping tree for inner classes with no mapped name, whose enclosing classes have mapped names.
	 * Currently, Yarn does not export mappings for these inner classes.
	 */
	private void inheritMappedNamesOfEnclosingClasses(MemoryMappingTree tree, String intermediateNamespace) {
		int intermediateIdx = tree.getNamespaceId(intermediateNamespace);
		int namedIdx = tree.getNamespaceId(Constants.Mappings.NAMED_NAMESPACE);

		// The tree does not have an index by intermediary names by default
		tree.setIndexByDstNames(true);

		for (MappingTree.ClassMapping classEntry : tree.getClasses()) {
			String intermediateName = classEntry.getDstName(intermediateIdx);
			String namedName = classEntry.getDstName(namedIdx);

			if (intermediateName.equals(namedName) && intermediateName.contains("$")) {
				String[] path = intermediateName.split(Pattern.quote("$"));
				int parts = path.length;

				for (int i = parts - 2; i >= 0; i--) {
					String currentPath = String.join("$", Arrays.copyOfRange(path, 0, i + 1));
					String namedParentClass = tree.mapClassName(currentPath, intermediateIdx, namedIdx);

					if (!namedParentClass.equals(currentPath)) {
						classEntry.setDstName(namedParentClass
										+ "$" + String.join("$", Arrays.copyOfRange(path, i + 1, path.length)),
								namedIdx);
						break;
					}
				}
			}
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
		mappingsWorkingDir = getMinecraftProvider().dir(mappingsIdentifier).toPath();
		baseTinyMappings = mappingsWorkingDir.resolve(Constants.Mappings.BASE_MAPPINGS_FILE);
		tinyMappings = mappingsWorkingDir.resolve(Constants.Mappings.MAPPINGS_FILE);
		tinyMappingsJar = mappingsWorkingDir.resolve(Constants.Mappings.MAPPINGS_JAR);
		unpickDefinitions = mappingsWorkingDir.resolve(Constants.Mappings.UNPICK_FILE);

		if (isRefreshDeps()) {
			cleanFiles();
		}
	}

	public void cleanFiles() {
		try {
			if (Files.exists(mappingsWorkingDir)) {
				Files.walkFileTree(mappingsWorkingDir, new DeletingFileVisitor());
			}

			Files.createDirectories(mappingsWorkingDir);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	@Override
	public String getTargetConfig() {
		return Constants.Configurations.MAPPINGS;
	}

	public Path getHashedTiny() throws IOException {
		if (hashedTiny == null) {
			hashedTiny = getMinecraftProvider().file(Constants.Mappings.HASHED_TINY_FILE).toPath();

			if (!Files.exists(hashedTiny) || (isRefreshDeps() && !hasRefreshed)) {
				hasRefreshed = true;

				// Download and extract hashed mojmap
				String encodedMinecraftVersion = UrlEscapers.urlFragmentEscaper().escape(getMinecraftProvider().minecraftVersion());
				String hashedMojmapArtifactUrl = getExtension().getHashedMojmapUrl(encodedMinecraftVersion);
				File hashedJar = getMinecraftProvider().file(Constants.Mappings.HASHED_JAR);

				DownloadUtil.downloadIfChanged(new URL(hashedMojmapArtifactUrl), hashedJar, getProject().getLogger());
				extractMappings(hashedJar.toPath(), hashedTiny);
			}
		}

		return hashedTiny;
	}

	@Override
	public Path mappingsWorkingDir() {
		return mappingsWorkingDir;
	}

	private String createMappingsIdentifier(String mappingsName, String version, String classifier) {
		//          mappingsName      . mcVersion . version        classifier
		// Example: net.fabricmc.yarn . 1_16_5    . 1.16.5+build.5 -v2
		return mappingsName + "." + getMinecraftProvider().minecraftVersion().replace(' ', '_').replace('.', '_').replace('-', '_') + "." + version + classifier;
	}

	public String mappingsIdentifier() {
		return mappingsIdentifier;
	}

	public File getUnpickDefinitionsFile() {
		return unpickDefinitions.toFile();
	}

	public boolean hasUnpickDefinitions() {
		return hasUnpickDefinitions;
	}

	@Nullable
	public Map<String, String> getSignatureFixes() {
		return signatureFixes;
	}

	@Override
	public File hashedMojmapTinyFile() {
		try {
			return getHashedTiny().toFile();
		} catch (IOException e) {
			throw new RuntimeException("Failed to get Hashed Mojmap", e);
		}
	}

	public record UnpickMetadata(String unpickGroup, String unpickVersion) {
	}

	public class IntermediateMappingsHandler {
		private final String CONFIG_NAME = Constants.Configurations.INTERMEDIATE_MAPPINGS;
		private Path intermediateMappingsDir;
		private ResolvedDependency mappingsIntermediateDependency;
		private Map<ResolvedDependency, MappingTree> mappingTrees;
		private Map<String, MappingTree> mappingTreesByNamespace;
		private Map<ResolvedDependency, File> dependencyJars;
		private Map<ResolvedDependency, Path> dependencyMappings;
		// identifiers by dependency
		private Map<ResolvedDependency, String> dependencies;

		private void populateConfiguration(String minecraftVersion, ResolvedDependency mappingsDependency) {
			DependencyHandler dependencies = getProject().getDependencies();

			Configuration configuration = getProject().getConfigurations().getByName(CONFIG_NAME);
			boolean configurationEmpty = configuration.getDependencies().isEmpty();
			Set<ResolvedDependency> mappingsDependencyChildren = mappingsDependency.getChildren();

			if (mappingsDependencyChildren.size() > 1) {
				throw new IllegalArgumentException("The specified " + getTargetConfig() + " dependency has more than one dependency");
			}

			if (mappingsDependencyChildren.size() == 1) {
				ResolvedDependency dependency = mappingsDependencyChildren.iterator().next();
				dependencies.add(CONFIG_NAME, dependency.getName());
				mappingsIntermediateDependency = dependency;
			} else {
				dependencies.add(CONFIG_NAME, "org.quiltmc:hashed:" + minecraftVersion);
			}

			if (configurationEmpty) {
				dependencies.add(CONFIG_NAME, "net.fabricmc:intermediary:" + minecraftVersion + ":v2"); // Required for Fabric mods
			}
		}

		private void createDependencyIdentifiers() {
			dependencies = new HashMap<>();
			dependencyJars = new HashMap<>();

			ResolvedConfiguration configuration = getProject().getConfigurations().getByName(CONFIG_NAME).getResolvedConfiguration();

			for (ResolvedDependency dependency : configuration.getFirstLevelModuleDependencies()) {
				Set<ResolvedArtifact> artifacts = dependency.getModuleArtifacts();

				if (artifacts.size() != 1) {
					throw new IllegalArgumentException("Intermediate mappings dependency '" + dependency.getName() + "' does not have one artifact");
				}

				ResolvedArtifact artifact = artifacts.iterator().next();
				String classifier = artifact.getClassifier() == null ? "" : "-" + artifact.getClassifier();
				dependencies.put(dependency, createDependencyIdentifier(dependency.getModuleGroup(), dependency.getModuleName(), dependency.getModuleVersion(), classifier));
				dependencyJars.put(dependency, artifact.getFile());
			}
		}

		private String createDependencyIdentifier(String group, String name, String version, String classifier) {
			return group + "." + name + "." + version + classifier;
		}

		private void initFiles() {
			intermediateMappingsDir = getMinecraftProvider().dir(Constants.Directories.INTERMEDIATE_MAPPINGS_DIR).toPath();
			dependencyMappings = new HashMap<>();

			for (ResolvedDependency dependency : dependencies.keySet()) {
				String identifier = dependencies.get(dependency);

				Path workingDir = intermediateMappingsDir.resolve(identifier);

				Path mappingsFile = workingDir.resolve(Constants.Mappings.MAPPINGS_FILE);
				dependencyMappings.put(dependency, mappingsFile);
			}
		}

		private void storeMappings() throws IOException {
			mappingTrees = new HashMap<>();
			mappingTreesByNamespace = new HashMap<>();

			for (ResolvedDependency dependency : dependencies.keySet()) {
				File jar = dependencyJars.get(dependency);
				Path mappingsFile = dependencyMappings.get(dependency);

				Files.createDirectories(mappingsFile.getParent());

				getProject().getLogger().info(":extracting " + jar.getName());
				extractMappings(jar.toPath(), mappingsFile);

				MappingTree mappingTree = readMappingsFile(mappingsFile);

				if (!mappingTree.getSrcNamespace().equals(MappingsNamespace.OFFICIAL.toString())) {
					throw new IllegalArgumentException("The dependency '" + dependency.getName() + "' does not have the correct source namespace ('official')");
				}

				String namespace = mappingTree.getDstNamespaces().get(0);

				if (mappingTreesByNamespace.containsKey(namespace)) {
					throw new IllegalArgumentException("A dependency with namespace '" + namespace + "' already exists");
				}

				mappingTrees.put(dependency, mappingTree);
				mappingTreesByNamespace.put(namespace, mappingTree);
			}
		}

		private MemoryMappingTree readMappingsFile(Path file) throws IOException {
			MemoryMappingTree mappingTree = new MemoryMappingTree();
			MappingReader.read(file, mappingTree);
			return mappingTree;
		}

		public MappingTree getMappingTree(ResolvedDependency dependency) {
			return mappingTrees.get(dependency);
		}

		public MappingTree getMappingTree(String namespace) {
			return mappingTreesByNamespace.get(namespace);
		}
	}
}
