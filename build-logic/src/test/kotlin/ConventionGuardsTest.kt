// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
import java.io.File
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The rules every module is merged under. A rule that silently stops firing looks exactly like a green
 * build, so each one gets a project that breaks it and must fail with the rule's own message.
 */
class ConventionGuardsTest {
    @TempDir
    lateinit var dir: File

    private val catalog = File("../gradle/libs.versions.toml").canonicalPath

    private fun project(plugin: String, build: String, withClass: Boolean = false) {
        File(dir, "settings.gradle.kts").writeText(
            """
            dependencyResolutionManagement {
                versionCatalogs { create("libs") { from(files("$catalog")) } }
            }
            rootProject.name = "guard-test"
            """.trimIndent()
        )
        File(dir, "build.gradle.kts").writeText("plugins { java; id(\"$plugin\") }\nrepositories { mavenCentral() }\n$build")
        if (withClass) {
            File(dir, "src/main/java/p").mkdirs()
            File(dir, "src/main/java/p/A.java").writeText("package p; public class A {}")
        }
    }

    private fun fails(vararg args: String): String =
        GradleRunner.create().withProjectDir(dir).withPluginClasspath().withArguments(*args, "--stacktrace").buildAndFail().output

    @Test
    fun `a disabled quality task fails the build`() {
        project("com.infenia.yukta.quality-conventions", """tasks.named("pmdMain") { enabled = false }""")
        assertTrue("must not be disabled" in fails("check", "--dry-run"))
    }

    @Test
    fun `a quality task that ignores failures fails the build`() {
        project("com.infenia.yukta.quality-conventions", "pmd { isIgnoreFailures = true }")
        assertTrue("ignore failures" in fails("check", "--dry-run"))
    }

    @Test
    fun `a coverage exception below the floor needs a reason`() {
        project(
            "com.infenia.yukta.jacoco-conventions",
            """coverageConfig { exception("x.*", "", mapOf("LINE" to 0.5)) }"""
        )
        assertTrue("without a reason" in fails("jacocoTestCoverageVerification", "--dry-run"))
    }

    @Test
    fun `a coverage exception that matches no class fails the build`() {
        project("com.infenia.yukta.jacoco-conventions", """coverageConfig { exception("x.*", "why", mapOf("LINE" to 0.9)) }""")
        // An empty execution data file is enough for the verification to run; the stale check comes first.
        File(dir, "build/jacoco").mkdirs()
        File(dir, "build/jacoco/test.exec").writeBytes(ByteArray(0))
        val out = fails("jacocoTestCoverageVerification", "-x", "jacocoTestReport", "-x", "test")
        assertTrue("match no class" in out, out)
    }

    @Test
    fun `classes without test execution data fail verifyCoverageData unless the module says why`() {
        project("com.infenia.yukta.jacoco-conventions", "", withClass = true)
        assertTrue("no coverage data" in fails("compileJava", "verifyCoverageData"))

        project("com.infenia.yukta.jacoco-conventions", """coverageConfig { noTests("API-only module") }""", withClass = true)
        GradleRunner.create().withProjectDir(dir).withPluginClasspath().withArguments("compileJava", "verifyCoverageData").build()
    }

    @Test
    fun `--tests marks the error guard as a partial run`() {
        project(
            "com.infenia.yukta.java-conventions",
            """tasks.register("show") { doLast { println("STALE=" + tasks.test.get().systemProperties["yukta.errorGuard.stale"]) } }"""
        )
        fun stale(vararg args: String) =
            GradleRunner.create().withProjectDir(dir).withPluginClasspath().withArguments(*args).build().output
        assertTrue("STALE=true" in stale("show"))
        assertTrue("STALE=false" in stale("test", "--tests", "p.A.other", "show"))
    }
}
