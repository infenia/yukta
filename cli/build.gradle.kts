/*
 * Copyright 2026 Infenia Private Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension

plugins {
    `java-library`
    id("com.infenia.yukta.java-conventions")
    id("com.infenia.yukta.quality-conventions")
    id("com.infenia.yukta.jacoco-conventions")
    id("io.spring.dependency-management") version "1.1.7"
}

configure<DependencyManagementExtension> {
    imports {
        // Spring Boot version is managed in libs.versions.toml
        mavenBom("org.springframework.boot:spring-boot-dependencies:4.0.3")
    }
}

dependencies {
    api(project(":core"))
    api(project(":web"))

    implementation(libs.picocli)
    implementation(libs.spring.boot.starter)
    implementation(libs.spring.boot.starter.webclient)
    implementation(libs.jackson.databind)

    annotationProcessor(libs.picocli.codegen)
    annotationProcessor(libs.spring.boot.configuration.processor)

    testImplementation(libs.spring.boot.starter.test)
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Aproject=${project.group}/${project.name}")
}

coverageConfig {
}
