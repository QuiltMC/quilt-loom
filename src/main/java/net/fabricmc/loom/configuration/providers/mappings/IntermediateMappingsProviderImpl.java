package net.fabricmc.loom.configuration.providers.mappings;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.Consumer;

import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.DependencySet;

import net.fabricmc.loom.util.Constants;

public class IntermediateMappingsProviderImpl extends AbstractMappingsProviderImpl {
	private boolean hasCheckedDependency = false;

	public IntermediateMappingsProviderImpl(Project project) {
		super(project);
	}

	private void updateDependencies() {
		if (!hasCheckedDependency) {
			hasCheckedDependency = true;
			Configuration configuration = getProject().getConfigurations().getByName(getTargetConfig());
			DependencySet dependencies = configuration.getDependencies();

			if (dependencies.isEmpty()) {
				// TODO: get from qm dependency, somehow
			}
		}
	}

	@Override
	public void provide(DependencyInfo dependency, Consumer<Runnable> postPopulationScheduler) throws Exception {
		this.updateDependencies();
		super.provide(dependency, postPopulationScheduler);
	}

	@Override
	protected void mergeAndSaveMappings(Project project, Path unorderedMappingsJar) throws IOException {
		Path unorderedMappings = Paths.get(mappingsStepsDir.toString(), Constants.Mappings.UNORDERED_INTERMEDIATE_MAPPINGS_FILE);
		project.getLogger().info(":extracting " + unorderedMappingsJar.getFileName());

		try (FileSystem unorderedMappingsJarFs = FileSystems.newFileSystem(unorderedMappingsJar, (ClassLoader) null)) {
			extractMappings(unorderedMappingsJarFs, unorderedMappings);
		}

		project.getLogger().info(":reordering");
		reorderMappings(unorderedMappings, tinyMappings.toPath(), Constants.Mappings.SOURCE_NAMESPACE, Constants.Mappings.INTERMEDIATE_NAMESPACE);
	}

	@Override
	public File getTinyMappings() {
		this.updateDependencies();
		return super.getTinyMappings();
	}

	@Override
	public String getTargetConfig() {
		return Constants.Configurations.INTERMEDIATE_MAPPINGS;
	}
}
