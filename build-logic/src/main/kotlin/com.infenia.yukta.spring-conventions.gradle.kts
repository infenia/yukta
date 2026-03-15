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
plugins {
    id("com.infenia.yukta.java-conventions")
    id("com.infenia.yukta.quality-conventions")
    id("com.infenia.yukta.jacoco-conventions")
    id("io.spring.dependency-management")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:${libs.findVersion("springBoot").get().requiredVersion}")
        mavenBom("org.springframework.ai:spring-ai-bom:${libs.findVersion("springAi").get().requiredVersion}")
    }
}

dependencies {
    testImplementation(libs.findLibrary("spring-boot-starter-test").get())
    testImplementation(libs.findLibrary("reactor-test").get())
}
