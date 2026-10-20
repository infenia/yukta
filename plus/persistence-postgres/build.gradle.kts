// SPDX-License-Identifier: AGPL-3.0-only
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
    implementation(libs.spring.boot.starter.actuator)
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
    // (GHSA-p9jg-fcr6-3mhf); 3.3 is the first patched release. scram-common moves with it. Declared
    // on implementation so the test configurations, which extend it, get the same versions.
    constraints {
        implementation("com.ongres.scram:scram-client:3.4")
        implementation("com.ongres.scram:scram-common:3.4")
    }
}

coverageConfig {
}
