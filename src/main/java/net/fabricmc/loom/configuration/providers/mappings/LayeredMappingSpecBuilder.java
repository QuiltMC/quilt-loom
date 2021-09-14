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

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

import org.gradle.api.Action;

import net.fabricmc.loom.configuration.providers.mappings.hashed.HashedMojmapMappingsSpec;
import net.fabricmc.loom.configuration.providers.mappings.mojmap.MojangMappingsSpec;
import net.fabricmc.loom.configuration.providers.mappings.parchment.ParchmentMappingsSpecBuilder;

public class LayeredMappingSpecBuilder {
	private final List<MappingsSpec<?>> layers = new LinkedList<>();

	public LayeredMappingSpecBuilder officialMojangMappings() {
		layers.add(new MojangMappingsSpec());
		return this;
	}

	public LayeredMappingSpecBuilder parchment(String mavenNotation) {
		parchment(mavenNotation, parchmentMappingsSpecBuilder -> parchmentMappingsSpecBuilder.setRemovePrefix(true));
		return this;
	}

	public LayeredMappingSpecBuilder parchment(String mavenNotation, Action<ParchmentMappingsSpecBuilder> action) {
		if (layers.stream().filter(mappingsSpec -> mappingsSpec instanceof MojangMappingsSpec).findAny().isEmpty()) {
			throw new UnsupportedOperationException("Parchment requires an official Mojang mappings layer to be defined first.");
		}

		var builder = ParchmentMappingsSpecBuilder.builder(mavenNotation);
		action.execute(builder);
		layers.add(builder.build());
		return this;
	}

	public LayeredMappingSpec build() {
		if (layers.isEmpty()) {
			throw new UnsupportedOperationException("Cannot build layered mappings without any layers!");
		}

		List<MappingsSpec<?>> builtLayers = new LinkedList<>();
		// Hashed mojmap is always the base layer
		builtLayers.add(new HashedMojmapMappingsSpec());
		builtLayers.addAll(layers);

		return new LayeredMappingSpec(Collections.unmodifiableList(builtLayers));
	}
}
