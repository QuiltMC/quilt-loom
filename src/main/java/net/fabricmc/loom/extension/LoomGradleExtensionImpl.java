/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2021 FabricMC
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

package net.fabricmc.loom.extension;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.cadixdev.lorenz.MappingSet;
import org.cadixdev.mercury.Mercury;
import org.gradle.api.NamedDomainObjectProvider;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.file.ConfigurableFileCollection;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.configuration.InstallerData;
import net.fabricmc.loom.configuration.LoomDependencyManager;
import net.fabricmc.loom.configuration.accesswidener.AccessWidenerFile;
import net.fabricmc.loom.configuration.processors.JarProcessorManager;

public class LoomGradleExtensionImpl extends LoomGradleExtensionApiImpl implements LoomGradleExtension {
	private final Project project;
	private final MixinExtension mixinApExtension;
	private final LoomFiles loomFiles;
	private final ConfigurableFileCollection unmappedMods;

	private final Set<File> mixinMappings = Collections.synchronizedSet(new HashSet<>());
	private final MappingSet[] srcMappingCache = new MappingSet[2];
	private final Mercury[] srcMercuryCache = new Mercury[2];
	private final Map<String, NamedDomainObjectProvider<Configuration>> lazyConfigurations = new HashMap<>();
	private final List<AccessWidenerFile> transitiveAccessWideners = new ArrayList<>();

	private LoomDependencyManager dependencyManager;
	private JarProcessorManager jarProcessorManager;
	private InstallerData installerData;

	public LoomGradleExtensionImpl(Project project, LoomFiles files) {
		super(project, files);
		this.project = project;
		// Initiate with newInstance to allow gradle to decorate our extension
		this.mixinApExtension = project.getObjects().newInstance(MixinExtensionImpl.class, project);
		this.loomFiles = files;
		this.unmappedMods = project.files();
	}

	@Override
	protected Project getProject() {
		return project;
	}

	@Override
	public LoomFiles getFiles() {
		return loomFiles;
	}

	@Override
	public synchronized File getNextMixinMappings() {
		File mixinMapping = new File(getFiles().getProjectBuildCache(), "mixin-map-" + getMappingsProvider().mappingsIdentifier() + "." + mixinMappings.size() + ".tiny");
		mixinMappings.add(mixinMapping);
		return mixinMapping;
	}

	@Override
	public Set<File> getAllMixinMappings() {
		return mixinMappings;
	}

	@Override
	public void setDependencyManager(LoomDependencyManager dependencyManager) {
		this.dependencyManager = dependencyManager;
	}

	@Override
	public LoomDependencyManager getDependencyManager() {
		return Objects.requireNonNull(dependencyManager, "Cannot get LoomDependencyManager before it has been setup");
	}

	@Override
	public void setJarProcessorManager(JarProcessorManager jarProcessorManager) {
		this.jarProcessorManager = jarProcessorManager;
	}

	@Override
	public JarProcessorManager getJarProcessorManager() {
		return Objects.requireNonNull(jarProcessorManager, "Cannot get JarProcessorManager before it has been setup");
	}

	@Override
	public MappingSet getOrCreateSrcMappingCache(int id, Supplier<MappingSet> factory) {
		return srcMappingCache[id] != null ? srcMappingCache[id] : (srcMappingCache[id] = factory.get());
	}

	@Override
	public Mercury getOrCreateSrcMercuryCache(int id, Supplier<Mercury> factory) {
		return srcMercuryCache[id] != null ? srcMercuryCache[id] : (srcMercuryCache[id] = factory.get());
	}

	@Override
	public ConfigurableFileCollection getUnmappedModCollection() {
		return unmappedMods;
	}

	public void setInstallerData(InstallerData object) {
		this.installerData = object;
	}

	@Override
	public InstallerData getInstallerData() {
		return installerData;
	}

	@Override
	public boolean isRootProject() {
		return project.getRootProject() == project;
	}

	@Override
	public NamedDomainObjectProvider<Configuration> createLazyConfiguration(String name) {
		NamedDomainObjectProvider<Configuration> provider = project.getConfigurations().register(name);

		if (lazyConfigurations.containsKey(name)) {
			throw new IllegalStateException("Duplicate configuration name" + name);
		}

		lazyConfigurations.put(name, provider);

		return provider;
	}

	@Override
	public NamedDomainObjectProvider<Configuration> getLazyConfigurationProvider(String name) {
		NamedDomainObjectProvider<Configuration> provider = lazyConfigurations.get(name);

		if (provider == null) {
			throw new NullPointerException("Could not find provider with name: " + name);
		}

		return provider;
	}

	@Override
	public MixinExtension getMixin() {
		return this.mixinApExtension;
	}

	@Override
	public List<AccessWidenerFile> getTransitiveAccessWideners() {
		return transitiveAccessWideners;
	}

	@Override
	public void addTransitiveAccessWideners(List<AccessWidenerFile> accessWidenerFiles) {
		transitiveAccessWideners.addAll(accessWidenerFiles);
	}

	// Get hashed mojmap from the snapshot repository. TODO: Move remove this after moving hashed to release repo
	@Override
	public String getHashedMojmapUrl(String minecraftVersion) {
		String prefix = "https://maven.quiltmc.org/repository/snapshot/org/quiltmc/hashed-mojmap/" + minecraftVersion + "-SNAPSHOT/";

		// Read maven-metadata.xml to get the latest version
		String uri = prefix + "maven-metadata.xml";

		try {
			DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
			DocumentBuilder documentBuilder = dbf.newDocumentBuilder();
			Document document = documentBuilder.parse(uri);
			document.getDocumentElement().normalize();

			Element versioningElement = (Element) document.getElementsByTagName("versioning").item(0);
			Element snapshotElement = (Element) versioningElement.getElementsByTagName("snapshot").item(0);
			String timestamp = snapshotElement.getElementsByTagName("timestamp").item(0).getTextContent();
			String buildNumber = snapshotElement.getElementsByTagName("buildNumber").item(0).getTextContent();

			String version = minecraftVersion + "-" + timestamp + "-" + buildNumber;
			return prefix + "hashed-mojmap-" + version + ".jar";
		} catch (IOException | ParserConfigurationException | SAXException e) {
			throw new RuntimeException("Failed to get the latest hashed mojmap version", e);
		}
	}
}
