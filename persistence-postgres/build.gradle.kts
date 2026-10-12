// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
plugins {
    id("com.infenia.yukta.library-conventions")
}

// Stores for sessions, workflow definitions and the execution log in PostgreSQL (R2DBC at run time,
// Flyway over JDBC for the schema). Added to the application with -Pyukta.persistence=postgres.
dependencies {
    implementation(project(":core"))
    implementation(project(":plugin-api"))
    implementation(libs.spring.boot.starter.data.r2dbc)
    implementation(libs.spring.boot.starter.flyway)
    runtimeOnly(libs.flyway.postgresql)
    runtimeOnly(libs.postgresql.jdbc)
    runtimeOnly(libs.r2dbc.postgresql)

    testImplementation(libs.r2dbc.postgresql)
    testImplementation(libs.flyway.postgresql)
    testImplementation(libs.postgresql.jdbc)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit)

    // The R2DBC PostgreSQL driver pulls in scram-client 3.2, which has a high-severity advisory
    // (GHSA-p9jg-fcr6-3mhf); 3.3 is the first patched release. scram-common moves with it.
    constraints {
        runtimeOnly("com.ongres.scram:scram-client:3.3")
        runtimeOnly("com.ongres.scram:scram-common:3.3")
        testRuntimeOnly("com.ongres.scram:scram-client:3.3")
        testRuntimeOnly("com.ongres.scram:scram-common:3.3")
    }
}

coverageConfig {
}
