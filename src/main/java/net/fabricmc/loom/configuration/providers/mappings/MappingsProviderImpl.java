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
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.google.common.base.Stopwatch;
import com.google.common.net.UrlEscapers;
import com.google.gson.JsonObject;
import org.apache.tools.ant.util.StringUtils;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ResolvedDependency;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.jetbrains.annotations.Nullable;

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
import net.fabricmc.stitch.commands.tinyv2.CommandMergeTinyV2;
import net.fabricmc.stitch.commands.tinyv2.CommandReorderTinyV2;

public class MappingsProviderImpl extends DependencyProvider implements MappingsProvider {
	public MinecraftMappedProvider mappedProvider;

	public String mappingsIdentifier;

	private Path mappingsWorkingDir;
	private Path hashedTiny;
	private boolean hasRefreshed = false;
	// The mappings that gradle gives us
	private Path baseTinyMappings;
	// The mappings we use in practice
	public Path tinyMappings;
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

		populateIntermediateMappings(minecraftProvider.getVersionInfo().id(), resolvedDependencies.iterator().next());
		// TODO: Use intermediate mappings from configuration instead

		getProject().getLogger().info(":setting up mappings (" + dependency.getDependency().getName() + " " + dependency.getResolvedVersion() + ")");

		String version = dependency.getResolvedVersion();
		File mappingsJar = dependency.resolveFile().orElseThrow(() -> new RuntimeException("Could not find mappings: " + dependency));

		String mappingsName = StringUtils.removeSuffix(dependency.getDependency().getGroup() + "." + dependency.getDependency().getName(), "-unmerged");
		boolean isV2 = isV2(dependency, mappingsJar);
		this.mappingsIdentifier = createMappingsIdentifier(mappingsName, version, getMappingsClassifier(dependency, isV2));

		initFiles();

		if (Files.notExists(tinyMappings) || isRefreshDeps()) {
			storeMappings(getProject(), minecraftProvider, mappingsJar.toPath());
		} else {
			try (FileSystem fileSystem = FileSystems.newFileSystem(mappingsJar.toPath(), (ClassLoader) null)) {
				extractExtras(fileSystem);
			}
		}

		mappingTree = readMappings();

		if (Files.notExists(tinyMappingsJar) || isRefreshDeps()) {
			Files.deleteIfExists(tinyMappingsJar);
			ZipUtils.add(tinyMappingsJar, Constants.Mappings.MAPPINGS_FILE_PATH, Files.readAllBytes(tinyMappings));
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
			getProject().getLogger().lifecycle("Using project based jar storage");
		} else {
			mappedProvider = new MinecraftMappedProvider(getProject());
		}

		mappedProvider.initFiles(minecraftProvider, this);
		mappedProvider.provide(dependency, postPopulationScheduler);
	}

	private void populateIntermediateMappings(String minecraftVersion, ResolvedDependency mappingsDependency) {
		String configName = Constants.Configurations.INTERMEDIATE_MAPPINGS;
		DependencyHandler dependencies = getProject().getDependencies();

		Set<ResolvedDependency> mappingsDependencyChildren = mappingsDependency.getChildren();

		if (mappingsDependencyChildren.size() > 1) {
			throw new IllegalArgumentException("The specified " + getTargetConfig() + " dependency has more than one dependency");
		} else if (mappingsDependencyChildren.size() == 1) {
			dependencies.add(configName, mappingsDependencyChildren.iterator().next().getName());
		} else {
			Configuration configuration = getProject().getConfigurations().getByName(configName);
			boolean configurationEmpty = configuration.getDependencies().isEmpty();
			dependencies.add(configName, "org.quiltmc:hashed:" + minecraftVersion + (Constants.Mappings.USE_SNAPSHOT_HASHES ? "-SNAPSHOT" : ""));

			if (configurationEmpty) {
				dependencies.add(configName, "net.fabricmc:intermediary:" + minecraftVersion + ":v2"); // Required for Fabric mods
			}
		}
	}

	private Set<File> resolveIntermediateMappings() {
		return getProject().getConfigurations().getByName(Constants.Configurations.INTERMEDIATE_MAPPINGS).resolve();
	}

	private String getMappingsClassifier(DependencyInfo dependency, boolean isV2) {
		String[] depStringSplit = dependency.getDepString().split(":");

		if (depStringSplit.length >= 4) {
			return "-" + depStringSplit[3] + (isV2 ? "-v2" : "");
		}

		return isV2 ? "-v2" : "";
	}

	private boolean isV2(DependencyInfo dependency, File mappingsJar) throws IOException {
		String minecraftVersion = getMinecraftProvider().minecraftVersion();

		// Only do this for official yarn, there isn't really a way we can get the mc version for all mappings
		if (dependency.getDependency().getGroup() != null && dependency.getDependency().getGroup().equals("net.fabricmc") && dependency.getDependency().getName().equals("yarn") && dependency.getDependency().getVersion() != null) {
			String yarnVersion = dependency.getDependency().getVersion();
			char separator = yarnVersion.contains("+build.") ? '+' : yarnVersion.contains("-") ? '-' : '.';
			String yarnMinecraftVersion = yarnVersion.substring(0, yarnVersion.lastIndexOf(separator));

			if (!yarnMinecraftVersion.equalsIgnoreCase(minecraftVersion)) {
				throw new RuntimeException(String.format("Minecraft Version (%s) does not match yarn's minecraft version (%s)", minecraftVersion, yarnMinecraftVersion));
			}

			// We can save reading the zip file + header by checking the file name
			return mappingsJar.getName().endsWith("-v2.jar");
		} else {
			return doesJarContainV2Mappings(mappingsJar.toPath());
		}
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
	}

	private void mergeAndSaveMappings(Project project, Path from, Path out) throws IOException {
		Stopwatch stopwatch = Stopwatch.createStarted();
		project.getLogger().info(":merging mappings");

		MemoryMappingTree tree = new MemoryMappingTree();
		MappingSourceNsSwitch sourceNsSwitch = new MappingSourceNsSwitch(tree, MappingsNamespace.OFFICIAL.toString());
		readHashedTree().accept(sourceNsSwitch);

		try (BufferedReader reader = Files.newBufferedReader(from, StandardCharsets.UTF_8)) {
			Tiny2Reader.read(reader, tree);
		}

		inheritMappedNamesOfEnclosingClasses(tree);

		try (Tiny2Writer writer = new Tiny2Writer(Files.newBufferedWriter(out, StandardCharsets.UTF_8), false)) {
			tree.accept(writer);
		}

		project.getLogger().info(":merged mappings in " + stopwatch.stop());
	}

	/**
	 * Searches the mapping tree for inner classes with no mapped name, whose enclosing classes have mapped names.
	 * Currently, Yarn does not export mappings for these inner classes.
	 */
	private void inheritMappedNamesOfEnclosingClasses(MemoryMappingTree tree) {
		int intermediaryIdx = tree.getNamespaceId(Constants.Mappings.INTERMEDIATE_NAMESPACE);
		int namedIdx = tree.getNamespaceId(Constants.Mappings.NAMED_NAMESPACE);

		// The tree does not have an index by intermediary names by default
		tree.setIndexByDstNames(true);

		for (MappingTree.ClassMapping classEntry : tree.getClasses()) {
			String intermediaryName = classEntry.getDstName(intermediaryIdx);
			String namedName = classEntry.getDstName(namedIdx);

			if (intermediaryName.equals(namedName) && intermediaryName.contains("$")) {
				String[] path = intermediaryName.split(Pattern.quote("$"));
				int parts = path.length;

				for (int i = parts - 2; i >= 0; i--) {
					String currentPath = String.join("$", Arrays.copyOfRange(path, 0, i + 1));
					String namedParentClass = tree.mapClassName(currentPath, intermediaryIdx, namedIdx);

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

	private MemoryMappingTree readHashedTree() throws IOException {
		MemoryMappingTree tree = new MemoryMappingTree();
		MappingNsCompleter nsCompleter = new MappingNsCompleter(tree, Collections.singletonMap(MappingsNamespace.NAMED.toString(), MappingsNamespace.HASHED.toString()), true);

		try (BufferedReader reader = Files.newBufferedReader(getHashedTiny(), StandardCharsets.UTF_8)) {
			Tiny2Reader.read(reader, nsCompleter);
		}

		return tree;
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
							MappingsNamespace.HASHED.toString(), MappingsNamespace.OFFICIAL.toString());
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
}
