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

package net.fabricmc.loom.test.unit.layeredmappings

import net.fabricmc.loom.configuration.providers.mappings.hashed.HashedMojmapMappingsSpec
import net.fabricmc.loom.configuration.providers.mappings.mojmap.MojangMappingsSpec
import net.fabricmc.loom.configuration.providers.mappings.parchment.ParchmentMappingsSpec

class ParchmentMappingLayerTest extends LayeredMappingsSpecification {
    def "Read parchment mappings" () {
        setup:
            mockMappingsProvider.hashedMojmapTinyFile() >> extractFileFromZip(downloadFile(HASHED_MOJMAP_1_17_1_URL, "hashed-mojmap.jar"), "hashed/mappings.tiny")
            mockMinecraftProvider.getVersionInfo() >> VERSION_META_1_17_1
        when:
            withMavenFile(PARCHMENT_NOTATION, downloadFile(PARCHMENT_URL, "parchment.zip"))
            def mappings = getLayeredMappings(
                    new HashedMojmapMappingsSpec(),
                    new MojangMappingsSpec(),
                    new ParchmentMappingsSpec(PARCHMENT_NOTATION, false)
            )
            def tiny = getTiny(mappings)
        then:
            mappings.srcNamespace == "named"
            mappings.dstNamespaces == ["hashed", "official"]
            mappings.classes.size() == 6113
            mappings.classes[2].srcName == "com/mojang/math/Matrix3f"
            mappings.classes[2].getDstName(0) == "net/minecraft/unmapped/C_ffukturc"
            mappings.classes[2].methods[1].args[0].srcName == "multiplier" // Apparently parameter names don't have a prefix anymore
    }

    def "Read parchment mappings remove prefix" () {
        setup:
            mockMappingsProvider.hashedMojmapTinyFile() >> extractFileFromZip(downloadFile(HASHED_MOJMAP_1_17_1_URL, "hashed-mojmap.jar"), "hashed/mappings.tiny")
            mockMinecraftProvider.getVersionInfo() >> VERSION_META_1_17_1
        when:
            withMavenFile(PARCHMENT_NOTATION, downloadFile(PARCHMENT_URL, "parchment.zip"))
            def mappings = getLayeredMappings(
                    new HashedMojmapMappingsSpec(),
                    new MojangMappingsSpec(),
                    new ParchmentMappingsSpec(PARCHMENT_NOTATION, true)
            )
            def tiny = getTiny(mappings)
        then:
            mappings.srcNamespace == "named"
            mappings.dstNamespaces == ["hashed", "official"]
            mappings.classes.size() == 6113
            mappings.classes[2].srcName == "com/mojang/math/Matrix3f"
            mappings.classes[2].getDstName(0) == "net/minecraft/unmapped/C_ffukturc"
            mappings.classes[2].methods[1].args[0].srcName == "multiplier"
    }
}
