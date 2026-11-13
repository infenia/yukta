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

// Spring Boot 4.1.2 already manages Jackson and Netty at the patched versions (CVE fixes). When a CVE
// needs a newer patch before Boot ships it, set the BOM property here (for example
// extra["netty.version"] = "..."), and delete it again once Boot catches up.

// Netty follows the catalog (boot/verifyNettyVersion checks it), ahead of the Boot BOM.
extra["netty.version"] = libs.findVersion("netty").get().requiredVersion

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:${libs.findVersion("springBoot").get().requiredVersion}")
        mavenBom("org.springframework.ai:spring-ai-bom:${libs.findVersion("springAi").get().requiredVersion}")
    }
}

dependencies {
    testImplementation(libs.findLibrary("spring-boot-starter-test").get())
    testImplementation(libs.findLibrary("reactor-test").get())
    testImplementation(libs.findLibrary("awaitility").get())
    // Fails or reports tests that hide an error (ErrorGuardExtension); every module but itself.
    if (path != ":test-support") {
        testImplementation(project(":test-support"))
    }
}
