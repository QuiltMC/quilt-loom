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

import net.fabricmc.loom.configuration.providers.minecraft.MinecraftVersionMeta

interface LayeredMappingsTestConstants {
    public static final String HASHED_MOJMAP_1_17_1_URL = "https://maven.quiltmc.org/repository/snapshot/org/quiltmc/hashed-mojmap/1.17.1-SNAPSHOT/hashed-mojmap-1.17.1-20210825.203034-1.jar"

    public static final Map<String, MinecraftVersionMeta.Download> DOWNLOADS_1_17_1 = [
            client_mappings:new MinecraftVersionMeta.Download(null, "e4d540e0cba05a6097e885dffdf363e621f87d3f", 6437531, "https://launcher.mojang.com/v1/objects/e4d540e0cba05a6097e885dffdf363e621f87d3f/client.txt"),
            server_mappings:new MinecraftVersionMeta.Download(null, "f6cae1c5c1255f68ba4834b16a0da6a09621fe13", 4953452, "https://launcher.mojang.com/v1/objects/f6cae1c5c1255f68ba4834b16a0da6a09621fe13/server.txt")
    ]
    public static final MinecraftVersionMeta VERSION_META_1_17_1 = new MinecraftVersionMeta(null, null, null, 0, DOWNLOADS_1_17_1, null, null, null, null, 0, null, null, null)

    public static final String PARCHMENT_NOTATION = "org.parchmentmc.data:parchment-1.17.1:2021.09.05-nightly-SNAPSHOT@zip"
    public static final String PARCHMENT_URL = "https://maven.parchmentmc.net/org/parchmentmc/data/parchment-1.17.1/2021.09.05-nightly-SNAPSHOT/parchment-1.17.1-2021.09.05-nightly-20210905.120025-1.zip"
}
