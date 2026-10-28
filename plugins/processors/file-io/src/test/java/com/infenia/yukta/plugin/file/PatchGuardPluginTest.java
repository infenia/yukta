// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.file;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/** One test per rule of {@link PatchGuardPlugin}, on a real git checkout. */
@NoArgsConstructor
@SuppressWarnings("PMD.CommentRequired")
class PatchGuardPluginTest {

  private static final String TEST_FILE = "src/test/java/FooTest.java";

  @TempDir private Path dir;

  private final PatchGuardPlugin plugin = new PatchGuardPlugin();

  private void git(final String... args) throws IOException, InterruptedException {
    final var command = new java.util.ArrayList<>(List.of("git", "-C", dir.toString()));
    command.addAll(
        List.of("-c", "user.name=t", "-c", "user.email=t@t", "-c", "commit.gpgsign=false"));
    command.addAll(List.of(args));
    assertThat(new ProcessBuilder(command).inheritIO().start().waitFor()).isZero();
  }

  private void write(final String file, final String content) throws IOException {
    Files.createDirectories(dir.resolve(file).getParent());
    Files.writeString(dir.resolve(file), content);
  }

  @BeforeEach
  void repo() throws Exception {
    git("init", "-q");
    write("src/Main.java", "class Main {}\n");
    write("secret/key.txt", "k\n");
    write(TEST_FILE, "class FooTest {\n@Test void a() {}\n@Test void b() {}\n}\n");
    git("add", ".");
    git("commit", "-q", "-m", "init");
  }

  @SuppressWarnings("unchecked")
  private Message<?> run(final Map<String, Object> options) {
    final var config = new java.util.HashMap<String, Object>(options);
    config.put("dir", dir.toString());
    return plugin
        .process(Flux.just(DefaultMessage.create(UUID.randomUUID(), "x")), config)
        .blockLast();
  }

  private static List<String> rules(final Message<?> out) {
    return ((List<Map<String, String>>) ((Map<?, ?>) out.payload()).get("violations"))
        .stream().map(v -> v.get("rule")).toList();
  }

  @Test
  void cleanChangePasses() throws Exception {
    write("src/Main.java", "class Main { int x; }\n");

    final Message<?> out = run(Map.of("requireChange", true));

    assertThat(out.sourcePort()).isEqualTo("pass");
    assertThat(((Map<?, ?>) out.payload()).get("files")).isEqualTo(List.of("src/Main.java"));
  }

  @Test
  void protectedPathIsRejected() throws Exception {
    write("secret/key.txt", "changed\n");

    final Message<?> out = run(Map.of("protectedPaths", List.of("secret/**")));

    assertThat(out.sourcePort()).isEqualTo("reject");
    assertThat(rules(out)).containsExactly("protectedPaths");
  }

  @Test
  void forbiddenPatternInAnAddedLineIsRejected_butNotInARemovedOne() throws Exception {
    write("src/Main.java", "class Main { // @SuppressWarnings\n}\n");
    assertThat(rules(run(Map.of("forbidPatterns", List.of("@SuppressWarnings")))))
        .containsExactly("forbidPatterns");

    write("src/Main.java", "class Other {}\n");
    assertThat(run(Map.of("forbidPatterns", List.of("class Main"))).sourcePort()).isEqualTo("pass");
  }

  @Test
  void forbiddenPatternInANewUntrackedFileIsRejected() throws Exception {
    write("src/New.java", "// TODO-FORBIDDEN\n");

    assertThat(rules(run(Map.of("forbidPatterns", List.of("FORBIDDEN")))))
        .containsExactly("forbidPatterns");
  }

  @Test
  void removedTestsAreRejected() throws Exception {
    write(TEST_FILE, "class FooTest {\n@Test void a() {}\n}\n");

    assertThat(rules(run(Map.of("noTestRemoval", true)))).containsExactly("noTestRemoval");
    assertThat(run(Map.of()).sourcePort()).isEqualTo("pass");
  }

  @Test
  void deletedTestFileIsRejected() throws Exception {
    Files.delete(dir.resolve(TEST_FILE));

    assertThat(rules(run(Map.of("noTestRemoval", true)))).containsExactly("noTestRemoval");
  }

  @Test
  void aTestMovedWithinAFileIsNotARemoval() throws Exception {
    write(TEST_FILE, "class FooTest {\n@Test void b() {}\n@Test void a() {}\n}\n");

    assertThat(run(Map.of("noTestRemoval", true)).sourcePort()).isEqualTo("pass");
  }

  @Test
  void emptyChangeIsRejectedWhenRequired() {
    assertThat(rules(run(Map.of("requireChange", true)))).containsExactly("requireChange");
    assertThat(run(Map.of()).sourcePort()).isEqualTo("pass");
  }

  @Test
  void baseIsComparedWith() throws Exception {
    write("src/Main.java", "class Main { int x; }\n");
    git("commit", "-q", "-am", "more");

    assertThat(run(Map.of("base", "HEAD~1", "requireChange", true)).sourcePort()).isEqualTo("pass");
    assertThat(rules(run(Map.of("requireChange", true)))).containsExactly("requireChange");
  }

  @Test
  void badConfigIsRefused() {
    StepVerifier.create(plugin.validateConfig(Map.of()))
        .expectErrorMessage("dir is required")
        .verify();
    StepVerifier.create(plugin.validateConfig(Map.of("dir", "/tmp", "base", "--output=x")))
        .expectError(IllegalArgumentException.class)
        .verify();
    StepVerifier.create(
            plugin.validateConfig(Map.of("dir", "/tmp", "protectedPaths", "not-a-list")))
        .expectErrorMessage("protectedPaths must be a list")
        .verify();
  }
}
