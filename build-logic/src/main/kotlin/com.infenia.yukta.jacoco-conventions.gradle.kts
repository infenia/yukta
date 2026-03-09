plugins {
    jacoco
}

jacoco {
    toolVersion = "0.8.14"
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
            element = "CLASS"
            excludes = listOf(
                "com.infenia.yukta.mapper.*Impl",
                "com.infenia.yukta.model.*",
                "com.infenia.yukta.service.aggregate.InMemoryAggregateStore*",
                "com.infenia.yukta.service.resequence.InMemoryResequencerStore*",
                "com.infenia.yukta.service.resequence.ResequencerStore*",
                "com.infenia.yukta.mcp.AppMcpTools",
                "com.infenia.yukta.service.TaskTrackerService*",
                "com.infenia.yukta.service.WorkflowService",
                "com.infenia.yukta.service.NoOpSecretProvider",
                "com.infenia.yukta.service.SessionService",
                "com.infenia.yukta.service.LogRetrievalService",
                "com.infenia.yukta.service.WorkflowRegistry",
                "com.infenia.yukta.service.WorkflowValidator",
                "com.infenia.yukta.service.ControlBusService",
                "com.infenia.yukta.service.DefaultControlBusGateway",
                "com.infenia.yukta.service.WorkflowOrchestrator*",
                "com.infenia.yukta.service.join.InMemoryJoinStore*",
                "com.infenia.yukta.service.join.JoinStore*",
                "com.infenia.yukta.config.AppConfigService",
                "com.infenia.yukta.config.AppConfiguration",
                "com.infenia.yukta.controller.SessionController",
                "com.infenia.yukta.controller.ConfigController"
            )
            limit {
                minimum = 1.00.toBigDecimal()
            }
        }
    }
}

tasks.named("check") {
    dependsOn(tasks.withType<JacocoCoverageVerification>())
}
