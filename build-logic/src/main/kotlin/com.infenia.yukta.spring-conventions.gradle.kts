// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
plugins {
    id("com.infenia.yukta.java-conventions")
    id("com.infenia.yukta.quality-conventions")
    id("com.infenia.yukta.jacoco-conventions")
    id("io.spring.dependency-management")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

// Mutation testing is opt-in (-Pmutation): it re-runs the tests once per mutant.
if (providers.gradleProperty("mutation").isPresent) {
    apply(plugin = "com.infenia.yukta.mutation-conventions")
}

// Patch releases of the BOM-managed libraries that fix published CVEs (Dependabot). Boot's BOM reads
// these properties, so they keep the same minor line; drop each one once Spring Boot ships it.
extra["jackson-2-bom.version"] = "2.21.7"
extra["jackson-bom.version"] = "3.1.7"
extra["netty.version"] = libs.findVersion("netty").get().requiredVersion

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:${libs.findVersion("springBoot").get().requiredVersion}")
        mavenBom("org.springframework.ai:spring-ai-bom:${libs.findVersion("springAi").get().requiredVersion}")
        // Spring Boot pins Netty older than the CVE fixes; import the patched BOM after it so it wins
        mavenBom("io.netty:netty-bom:${libs.findVersion("netty").get().requiredVersion}")
    }
}

dependencies {
    testImplementation(libs.findLibrary("spring-boot-starter-test").get())
    testImplementation(libs.findLibrary("reactor-test").get())
    // Fails or reports tests that hide an error (ErrorGuardExtension); every module but itself.
    if (path != ":test-support") {
        testImplementation(project(":test-support"))
    }
}
