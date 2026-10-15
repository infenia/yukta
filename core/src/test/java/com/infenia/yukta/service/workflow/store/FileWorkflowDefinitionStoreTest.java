// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.workflow.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.config.SessionConfigProperties;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

@NoArgsConstructor
@SuppressWarnings({"PMD.CommentRequired", "PMD.AvoidDuplicateLiterals", "PMD.TooManyMethods"})
class FileWorkflowDefinitionStoreTest {

  @TempDir private Path dir;

  private SessionConfigProperties props;
  private FileWorkflowDefinitionStore store;

  private static WorkflowDefinition definition(final String workflowId, final String description) {
    return new WorkflowDefinition(
        workflowId,
        description,
        List.of(new WorkflowDefinition.Node("n1", "CONSTANT_SOURCE", Map.of("k", "v"))),
        List.of());
  }

  @BeforeEach
  void setUp() {
    props = new SessionConfigProperties();
    props.setBaseDir(dir.toString());
    store = new FileWorkflowDefinitionStore(props, new ObjectMapper());
  }

  @Test
  void definitionsSurviveRestart_andCanBeFoundListedAndRemoved() {
    store.save("s", definition("w1", "one")).block();
    store.save("s", definition("w2", "two")).block();
    store.save("other", definition("w1", "elsewhere")).block();

    final FileWorkflowDefinitionStore restarted =
        new FileWorkflowDefinitionStore(props, new ObjectMapper());

    assertThat(restarted.find("s", "w1").block()).isEqualTo(definition("w1", "one"));
    assertThat(restarted.find("s", "nope").block()).isNull();
    assertThat(restarted.findAll("s").block()).containsOnlyKeys("w1", "w2");
    assertThat(restarted.findAll("nobody").block()).isEmpty();

    restarted.remove("s", "w1").block();
    restarted.remove("s", "never-there").block();
    assertThat(restarted.findAll("s").block()).containsOnlyKeys("w2");
    restarted.removeAll("s").block();
    restarted.removeAll("s").block();
    assertThat(restarted.findAll("s").block()).isEmpty();
    assertThat(restarted.find("other", "w1").block()).isNotNull();
  }

  @Test
  void versionsAreListedNewestFirst_andRestorable_butIdenticalSavesAddNone()
      throws InterruptedException {
    store.save("s", definition("w", "one")).block();
    store.save("s", definition("w", "one")).block();
    Thread.sleep(2);
    store.save("s", definition("w", "two")).block();

    final List<DefinitionRevision> revisions = store.revisions("s", "w").collectList().block();

    assertThat(revisions).hasSize(2);
    assertThat(revisions.get(0).savedAt()).isAfterOrEqualTo(revisions.get(1).savedAt());
    assertThat(store.findRevision("s", "w", revisions.get(0).id()).block())
        .isEqualTo(definition("w", "two"));
    assertThat(store.findRevision("s", "w", revisions.get(1).id()).block())
        .isEqualTo(definition("w", "one"));
    assertThat(store.findRevision("s", "w", "nope").block()).isNull();
    assertThat(store.revisions("s", "unknown").collectList().block()).isEmpty();
  }

  @Test
  void onlyTheNewestVersionsAreKept() throws InterruptedException {
    for (int i = 0; i < FileWorkflowDefinitionStore.MAX_REVISIONS + 3; i++) {
      store.save("s", definition("w", "v" + i)).block();
      Thread.sleep(1);
    }

    final List<DefinitionRevision> revisions = store.revisions("s", "w").collectList().block();

    assertThat(revisions).hasSize(FileWorkflowDefinitionStore.MAX_REVISIONS);
    assertThat(store.findRevision("s", "w", revisions.get(0).id()).block().description())
        .isEqualTo("v" + (FileWorkflowDefinitionStore.MAX_REVISIONS + 2));
  }

  @Test
  void idsCannotNamePathOutsideDirectory() throws IOException {
    store.save("../../evil", definition("../w", "x")).block();

    assertThat(dir.getParent().resolve("evil")).doesNotExist();
    try (var files = Files.list(dir.resolve("workflows"))) {
      assertThat(files.map(f -> dir.relativize(f).toString()).toList())
          .containsExactly("workflows/..%2F..%2Fevil");
    }
    assertThat(store.findRevision("../../evil", "../w", "../../../etc/passwd").block()).isNull();
  }

  @Test
  void dotDotAndEmptyIds_areRefused() {
    for (final String bad : List.of("", ".", "..")) {
      org.assertj.core.api.Assertions.assertThatThrownBy(() -> store.find("s", bad).block())
          .isInstanceOf(IllegalArgumentException.class);
      org.assertj.core.api.Assertions.assertThatThrownBy(() -> store.findAll(bad).block())
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void pathsOutsideTheRoot_areRefused() {
    final Path root = dir.resolve("workflows").toAbsolutePath().normalize();
    final Path inside = root.resolve("s").resolve("w");

    assertThat(FileWorkflowDefinitionStore.inside(root, inside)).isEqualTo(inside);
    assertThat(
            FileWorkflowDefinitionStore.inside(root, root.resolve("s").resolve("..").resolve("w")))
        .isEqualTo(root.resolve("w"));
    for (final Path bad : List.of(root, root.resolve("..").resolve("evil"), root.getParent())) {
      org.assertj.core.api.Assertions.assertThatThrownBy(
              () -> FileWorkflowDefinitionStore.inside(root, bad))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void strayFilesInHistory_areSkippedWhenListingRevisions() throws IOException {
    store.save("s", definition("w", "one")).block();
    final Path history = dir.resolve("workflows").resolve("s").resolve("w").resolve("history");
    Files.writeString(history.resolve("notes.json"), "{}");
    Files.writeString(history.resolve("not-a-timestamp-at-all.json"), "{}");
    Files.writeString(history.resolve("readme.txt"), "x");

    assertThat(store.revisions("s", "w").collectList().block()).hasSize(1);
  }

  @Test
  void workflowDirectoryWithoutCurrentFile_isSkippedWhenListing() throws IOException {
    store.save("s", definition("w1", "one")).block();
    Files.createDirectories(dir.resolve("workflows").resolve("s").resolve("empty"));

    assertThat(store.findAll("s").block()).containsOnlyKeys("w1");
  }

  @Test
  void savingBeneathFile_failsTheSave() throws IOException {
    final Path blocker = Files.createFile(dir.resolve("blocker"));
    props.setBaseDir(blocker.toString());

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> store.save("s", definition("w", "x")).block())
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void logsItsLocationOnStartup() {
    store.logInitialization();

    assertThat(store.find("s", "none").block()).isNull();
  }

  @Test
  void revisionIdsSortInSaveOrder() {
    final WorkflowDefinition one = definition("w", "one");
    final String early = DefinitionRevisions.idOf(java.time.Instant.ofEpochMilli(1_000), one);
    final String late = DefinitionRevisions.idOf(java.time.Instant.ofEpochMilli(2_000), one);

    assertThat(early).hasSize(22).isLessThan(late);
  }

  @Test
  void noteSurvivesRestart_pruneDropsIt() throws InterruptedException {
    store.save("s", definition("w1", "one")).block();
    final String revisionId =
        java.util.Objects.requireNonNull(store.revisions("s", "w1").blockFirst()).id();
    store.saveNote("s", "w1", revisionId, "first cut").block();

    final FileWorkflowDefinitionStore restarted =
        new FileWorkflowDefinitionStore(props, new ObjectMapper());
    assertThat(restarted.revisions("s", "w1").blockFirst().note()).isEqualTo("first cut");

    for (int i = 0; i < FileWorkflowDefinitionStore.MAX_REVISIONS + 2; i++) {
      Thread.sleep(2);
      restarted.save("s", definition("w1", "v" + i)).block();
    }
    assertThat(restarted.revisions("s", "w1").collectList().block())
        .hasSize(FileWorkflowDefinitionStore.MAX_REVISIONS);
    assertThat(restarted.revisions("s", "w1").map(DefinitionRevision::id).collectList().block())
        .doesNotContain(revisionId);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> restarted.saveNote("s", "w1", revisionId, "late").block())
        .isInstanceOf(com.infenia.yukta.service.NotFoundException.class);
  }

  @Test
  void blankNoteClearsTheNoteFile() {
    store.save("s", definition("w1", "one")).block();
    final String revisionId =
        java.util.Objects.requireNonNull(store.revisions("s", "w1").blockFirst()).id();
    store.saveNote("s", "w1", revisionId, "text").block();

    store.saveNote("s", "w1", revisionId, " ").block();
    store.saveNote("s", "w1", revisionId, null).block();

    assertThat(store.revisions("s", "w1").blockFirst().note()).isNull();
  }

  @Test
  void atomicWrite_refusesNamesOutsideTheDirectory() {
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> FileWorkflowDefinitionStore.atomically(dir, "../escape.json", "{}"))
        .isInstanceOf(IllegalArgumentException.class);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> FileWorkflowDefinitionStore.atomically(dir, ".", "{}"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
