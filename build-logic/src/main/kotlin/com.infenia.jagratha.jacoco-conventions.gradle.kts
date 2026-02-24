plugins {
    jacoco
}

jacoco {
    toolVersion = "0.8.12"
}

/*
 * Configure test tasks
 */
tasks.withType<Test>().configureEach {
    finalizedBy(tasks.withType<JacocoReport>())

    extensions.configure<JacocoTaskExtension> {
        excludes = listOf(
            "java.*",
            "javax.*",
            "sun.*",
            "jdk.*",
            "com.sun.*",
            "org.w3c.*",
            "org.xml.*"
        )
    }
}

/*
 * Safe JaCoCo report configuration
 */
tasks.withType<JacocoReport>().configureEach {

    dependsOn(tasks.withType<Test>())

    // Proper provider-based filtering (NO doFirst, NO afterEvaluate)
    classDirectories.setFrom(
        classDirectories.files.map { dir ->
            fileTree(dir) {
                exclude("gg/jte/generated/**")
            }
        }
    )

    onlyIf {
        executionData.files.any { it.exists() }
    }

    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

/*
 * Coverage verification
 */
tasks.withType<JacocoCoverageVerification>().configureEach {

    dependsOn(tasks.withType<JacocoReport>())

    classDirectories.setFrom(
        classDirectories.files.map { dir ->
            fileTree(dir) {
                exclude("gg/jte/generated/**")
            }
        }
    )

    onlyIf {
        executionData.files.any { it.exists() }
    }

    violationRules {
        rule {
            limit {
                minimum = if (project.hasProperty("jacocoMinimumCoverage")) {
                    project.property("jacocoMinimumCoverage").toString().toBigDecimal()
                } else {
                    0.80.toBigDecimal()
                }
            }
        }
    }
}

tasks.named("check") {
    dependsOn(tasks.withType<JacocoCoverageVerification>())
}
