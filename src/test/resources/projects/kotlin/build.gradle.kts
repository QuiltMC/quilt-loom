import java.util.Properties

plugins {
	kotlin("jvm") version "1.4.31"
	id("org.quiltmc.loom")
}

repositories {
	maven("https://maven.fabricmc.net")
}

java {
	sourceCompatibility = JavaVersion.VERSION_1_8
	targetCompatibility = JavaVersion.VERSION_1_8
}

version = "0.0.1"

dependencies {
	minecraft(group = "com.mojang", name = "minecraft", version = "1.16.5")
	mappings("org.quiltmc:yarn:1.16.5+build.5:v2")
	modImplementation("org.quiltmc:quilt-loader:0.13.0")
	modImplementation(group = "net.fabricmc", name = "fabric-language-kotlin", version = "1.5.0+kotlin.1.4.31")
}