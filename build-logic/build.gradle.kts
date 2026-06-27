plugins {
    `kotlin-dsl`
}

kotlin {
    jvmToolchain(25)
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

dependencies {
    implementation("com.diffplug.spotless:spotless-plugin-gradle:${libs.versions.spotless.get()}")
    implementation("com.github.spotbugs.snom:spotbugs-gradle-plugin:${libs.versions.spotbugs.get()}")
    implementation("io.freefair.gradle:lombok-plugin:${libs.versions.lombok.get()}")
    implementation("com.github.node-gradle:gradle-node-plugin:${libs.versions.nodeGradle.get()}")
    implementation("io.spring.dependency-management:io.spring.dependency-management.gradle.plugin:${libs.versions.springDependencyManagement.get()}")
    implementation("net.ltgt.gradle:gradle-errorprone-plugin:${libs.versions.errorpronePlugin.get()}")
}
