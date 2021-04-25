/*
 * This file is part of Quilt Loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2016, 2017, 2018 FabricMC, 2021 QuiltMC
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

package org.quiltmc.loom;

import com.google.common.collect.ImmutableMap;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.gradle.api.Plugin;
import org.gradle.api.Project;

import org.quiltmc.loom.configuration.CompileConfiguration;
import org.quiltmc.loom.configuration.MavenPublication;
import org.quiltmc.loom.configuration.ide.IdeConfiguration;
import org.quiltmc.loom.configuration.providers.mappings.MappingsCache;
import org.quiltmc.loom.decompilers.DecompilerConfiguration;
import org.quiltmc.loom.task.LoomTasks;

public class LoomGradlePlugin implements Plugin<Project> {
	public static boolean refreshDeps;
	public static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	@Override
	public void apply(Project project) {
		project.getLogger().lifecycle("Loom: " + LoomGradlePlugin.class.getPackage().getImplementationVersion());

		refreshDeps = project.getGradle().getStartParameter().isRefreshDependencies();

		if (refreshDeps) {
			MappingsCache.INSTANCE.invalidate();
			project.getLogger().lifecycle("Refresh dependencies is in use, loom will be significantly slower.");
		}

		// Apply default plugins
		project.apply(ImmutableMap.of("plugin", "java"));
		project.apply(ImmutableMap.of("plugin", "eclipse"));
		project.apply(ImmutableMap.of("plugin", "idea"));

		// Setup extensions, minecraft shadows loom
		project.getExtensions().create("loom", LoomGradleExtension.class, project);
		project.getExtensions().add("minecraft", project.getExtensions().getByName("loom"));

		// Setup component metadata rule so quilt loader provides fabric loader.
		// TODO: Also have QSL provide fabric api?
		project.getLogger().warn("You may need to exclude fabric api and fabric loader from dependencies for the time being!");
		project.getDependencies().getComponents().withModule("net.quiltmc:quilt-loader", QuiltLoaderProvidesFabricLoaderRule.class);

		CompileConfiguration.setupConfigurations(project);
		IdeConfiguration.setup(project);
		CompileConfiguration.configureCompile(project);
		MavenPublication.configure(project);
		LoomTasks.registerTasks(project);
		DecompilerConfiguration.setup(project);
	}
}
