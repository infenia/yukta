// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugins;

import static com.infenia.yukta.plugins.PluginWorkflows.node;
import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.gateway.SubWorkflowGateway;
import com.infenia.yukta.service.plugin.PluginRegistry;
import com.infenia.yukta.service.workflow.store.WorkflowDefinitionStore;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Happy path of the plugins that talk to the outside world, against local stand-ins. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = "yukta.process.allow-any=true")
@SuppressWarnings({
  "PMD.CommentRequired",
  "PMD.ShortVariable",
  "PMD.UseConcurrentHashMap",
  "PMD.AvoidDuplicateLiterals",
  "PMD.SignatureDeclareThrowsException"
})
@NoArgsConstructor
class IoPluginsAcceptanceTest {

  private static final String H2 = "jdbc:h2:mem:plugin_it;DB_CLOSE_DELAY=-1";

  @Autowired private WorkflowDefinitionStore definitions;
  @Autowired private SubWorkflowGateway runner;
  @Autowired private PluginRegistry registry;

  private PluginWorkflows wf;

  @BeforeEach
  void setUp() {
    wf = new PluginWorkflows(definitions, runner, registry);
  }

  @Test
  void processExecutor_runsCommand() {
    wf.requirePlugin("PROCESS_EXECUTOR");
    final var key =
        wf.chain(node("p", "PROCESS_EXECUTOR", Map.of("command", List.of("echo", "hi-there"))));

    assertThat(wf.runOne(key, Map.of()).toString()).contains("hi-there");
  }

  @Test
  void httpRequest_callsTheServer_andReturnsStatusAndBody() throws IOException {
    wf.requirePlugin("HTTP_REQUEST");
    try (FakeHttp server = new FakeHttp().reply("{\"pong\":true}")) {
      final var key =
          wf.chain(
              node("h", "HTTP_REQUEST", Map.of("url", server.url() + "/ping", "method", "GET")));

      final Map<String, Object> out = wf.runOne(key, Map.of());

      assertThat(out).containsEntry("status", 200);
      assertThat(out.toString()).contains("pong");
      assertThat(server.requests())
          .singleElement()
          .satisfies(r -> assertThat(r.path()).isEqualTo("/ping"));
    }
  }

  @Test
  void fileWrite_thenFileRead_returnsWhatWasWritten(@TempDir final Path dir) {
    wf.requirePlugin("FILE_WRITE");
    final Map<String, Object> where = Map.of("baseDir", dir.toString(), "path", "out/a.txt");
    final var key = wf.chain(node("w", "FILE_WRITE", where), node("r", "FILE_READ", where));

    final Map<String, Object> out = wf.runOne(key, Map.of("content", "file-body"));

    assertThat(out).containsEntry("content", "file-body");
    assertThat(dir.resolve("out/a.txt")).hasContent("file-body");
  }

  @Test
  void patchGuard_rejectsChangeToProtectedPath(@TempDir final Path dir) throws Exception {
    wf.requirePlugin("PATCH_GUARD");
    git(dir, "init", "-q");
    java.nio.file.Files.writeString(dir.resolve("a.txt"), "one\n");
    git(dir, "add", "a.txt");
    git(
        dir,
        "-c",
        "user.name=t",
        "-c",
        "user.email=t@t",
        "-c",
        "commit.gpgsign=false",
        "commit",
        "-q",
        "-m",
        "init");
    java.nio.file.Files.writeString(dir.resolve("a.txt"), "two\n");
    final var key =
        wf.chain(
            node(
                "g",
                "PATCH_GUARD",
                Map.of("dir", dir.toString(), "protectedPaths", List.of("a.txt"))));

    final Map<String, Object> out = wf.runOne(key, Map.of());

    assertThat(out).containsEntry("files", List.of("a.txt"));
    assertThat(out.get("violations").toString()).contains("protectedPaths");
  }

  private static void git(final Path dir, final String... args) throws Exception {
    final var command = new java.util.ArrayList<>(List.of("git", "-C", dir.toString()));
    command.addAll(List.of(args));
    assertThat(new ProcessBuilder(command).inheritIO().start().waitFor()).isZero();
  }

  @Test
  void sqlQuery_createsInsertsAndSelects() {
    wf.requirePlugin("SQL_QUERY");
    wf.runOne(wf.chain(sql("create table t(id int, name varchar(20))", null)), Map.of());
    wf.runOne(
        wf.chain(sql("insert into t values (?, ?)", List.of("id", "name"))),
        Map.of("id", 1, "name", "ann"));

    final Map<String, Object> out =
        wf.runOne(wf.chain(sql("select id, name from t", null)), Map.of());

    assertThat(out).containsEntry("count", 1);
    assertThat(out.toString()).contains("ann");
  }

  private static com.infenia.yukta.model.workflow.WorkflowDefinition.Node sql(
      final String statement, final List<String> params) {
    final Map<String, Object> config =
        new java.util.HashMap<>(
            Map.of("url", H2, "username", "sa", "password", "", "sql", statement));
    if (params != null) {
      config.put("params", params);
    }
    return node("q", "SQL_QUERY", config);
  }
}
