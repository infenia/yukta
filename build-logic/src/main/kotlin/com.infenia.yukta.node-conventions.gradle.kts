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
import com.github.gradle.node.NodeExtension

plugins {
    id("com.github.node-gradle.node")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

configure<NodeExtension> {
    // Version of node to use.
    version.set(libs.findVersion("node").get().requiredVersion)

    // Version of pnpm to use.
    pnpmVersion.set(libs.findVersion("pnpm").get().requiredVersion)

    // If true, it will download node using above parameters.
    // If false, it will try to use globally installed node.
    download.set(true)
}
