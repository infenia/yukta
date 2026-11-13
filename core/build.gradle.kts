// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
plugins {
    id("com.infenia.yukta.library-conventions")
    `java-test-fixtures`
}

dependencies {
    api(project(":plugin-api"))
    api(project(":messaging"))

    // Default plugins

    implementation(libs.spring.boot.starter.webclient)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.micrometer.core)
    implementation("io.micrometer:context-propagation")
    // API only: reads the trace id (the exporter is in boot); aopalliance (Public Domain, no
    // licence URL) is for its @NewSpan aspects, which core does not use
    implementation("io.micrometer:micrometer-tracing") {
        exclude(group = "aopalliance", module = "aopalliance")
    }
    implementation(libs.caffeine)

    // @Schema on the model records; the application brings the full OpenAPI stack (web, boot)
    compileOnly(libs.swagger.annotations)
    testCompileOnly(libs.swagger.annotations)

    annotationProcessor(libs.spring.boot.configuration.processor)

    testImplementation(libs.spring.boot.starter.webflux.test)
    testImplementation(libs.spring.boot.starter.webclient.test)
    testImplementation(libs.spring.boot.starter.validation.test)
    testImplementation(libs.spring.boot.starter.actuator.test)
    testImplementation(libs.spring.boot.starter.jackson.test)
    testImplementation(libs.awaitility)

    // Short forms of the model records for tests (testFixtures), shared by the modules above core
    testFixturesApi(project(":plugin-api"))
    testFixturesImplementation(libs.spring.boot.starter.validation)
    testFixturesImplementation(libs.spring.boot.starter.webflux)
    testFixturesImplementation(libs.spring.boot.starter.actuator)
    testFixturesImplementation(libs.caffeine)
}


// InputRequestFuzzTest: -PfuzzTries=<cases per target>, -PfuzzSeed=<number>|random (nightly), as for boot's fuzz test
tasks.withType<Test>().configureEach {
    providers.gradleProperty("fuzzTries").orNull?.let { systemProperty("yukta.fuzz.tries", it) }
    providers.gradleProperty("fuzzSeed").orNull?.let { systemProperty("yukta.fuzz.seed", it) }
}
