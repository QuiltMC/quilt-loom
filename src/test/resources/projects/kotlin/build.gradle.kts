plugins {
	kotlin("jvm") version "1.5.21"
	id("org.quiltmc.loom")
}

java {
	sourceCompatibility = JavaVersion.VERSION_1_8
	targetCompatibility = JavaVersion.VERSION_1_8
}

version = "0.0.1"

dependencies {
	minecraft(group = "com.mojang", name = "minecraft", version = "1.17.1")
	mappings(group = "org.quiltmc", name = "quilt-mappings", version = "1.17.1+build.9", classifier = "v2")
	modImplementation("org.quiltmc:quilt-loader:0.14.0-SNAPSHOT")
	modImplementation(group = "net.fabricmc", name = "fabric-language-kotlin", version = "1.6.3+kotlin.1.5.21")
}
