// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.testsupport.ExpectedErrorLog;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;

@NoArgsConstructor
@SuppressWarnings({"PMD.TooManyMethods", "PMD.AvoidDuplicateLiterals"})
@ExpectedErrorLog("ERROR com.infenia.yukta.service.store.FileNodeCheckpointStore - ")
class FileNodeCheckpointStoreTest {

  @TempDir /* default */ Path dir;

  private FileNodeCheckpointStore store() {
    return new FileNodeCheckpointStore(dir.toString(), JsonMapper.builder().build());
  }

  private static Message<?> message(final Object payload) {
    return DefaultMessage.create(UUID.randomUUID(), payload);
  }

  /** Not serialisable: reading a property throws. */
  @SuppressWarnings("PMD.PublicMemberInNonPublicType")
  public static final class Broken {
    public String getValue() {
      throw new IllegalStateException("boom");
    }
  }

  @Test
  void messagesSurviveAnewStore_inOrder() {
    store().save("e1", "n1", message("a")).block();
    store().save("e1", "n1", message(Map.of("k", 1))).block();

    final FileNodeCheckpointStore reopened = store();
    StepVerifier.create(reopened.getAll("e1", "n1"))
        .assertNext(m -> assertThat(m.payload()).isEqualTo("a"))
        .assertNext(m -> assertThat(m.payload()).isEqualTo(Map.of("k", 1)))
        .verifyComplete();
    StepVerifier.create(reopened.get("e1", "n1"))
        .assertNext(m -> assertThat(m.payload()).isEqualTo(Map.of("k", 1)))
        .verifyComplete();
    assertThat(reopened.isComplete("e1", "n1")).isTrue();
  }

  @Test
  void unknownNode_isEmpty() {
    StepVerifier.create(store().getAll("e1", "missing")).verifyComplete();
    StepVerifier.create(store().get("e1", "missing")).verifyComplete();
  }

  @Test
  void payloadThatCannotBeWritten_marksTheNodeIncomplete_withoutFailingTheRun() {
    final FileNodeCheckpointStore store = store();

    StepVerifier.create(store.save("e1", "n1", message(new Broken()))).verifyComplete();

    assertThat(store.isComplete("e1", "n1")).isFalse();
    assertThat(store.isComplete("e1", "other")).isTrue();
  }

  @Test
  void clearRemovesOnlyThatExecution() {
    final FileNodeCheckpointStore store = store();
    store.save("e1", "n1", message("a")).block();
    store.save("e2", "n1", message("b")).block();

    store.clear("e1");

    StepVerifier.create(store.getAll("e1", "n1")).verifyComplete();
    StepVerifier.create(store.getAll("e2", "n1")).expectNextCount(1).verifyComplete();
  }

  @Test
  void idsNeverEscapeTheDirectory() throws IOException {
    final FileNodeCheckpointStore store = store();

    store.save("..", "../../evil", message("a")).block();

    try (var files = Files.walk(dir)) {
      assertThat(files.allMatch(p -> p.startsWith(dir))).isTrue();
    }
    assertThat(dir.getParent().resolve("evil.jsonl")).doesNotExist();
    StepVerifier.create(store.getAll("..", "../../evil")).expectNextCount(1).verifyComplete();
  }

  @Test
  void idsThatDifferOnlyInCase_orAreVeryLong_keepTheirOwnFiles() {
    final FileNodeCheckpointStore store = store();
    final String longId = "x".repeat(400);

    store.save("e1", "A", message("upper")).block();
    store.save("e1", "a", message("lower")).block();
    store.save("e1", longId, message("long")).block();

    assertThat(store.get("e1", "A").block().payload()).isEqualTo("upper");
    assertThat(store.get("e1", "a").block().payload()).isEqualTo("lower");
    assertThat(store.get("e1", longId).block().payload()).isEqualTo("long");
    assertThat(FileNodeCheckpointStore.fileName("A"))
        .isNotEqualToIgnoringCase(FileNodeCheckpointStore.fileName("a"));
  }

  @Test
  void unknownHashAlgorithm_failsLoudly() {
    assertThatThrownBy(() -> FileNodeCheckpointStore.hash("NOPE", "x"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void runDataIsReadableByTheOwnerOnly() throws IOException {
    final FileNodeCheckpointStore store = store();
    store.save("e1", "n1", message("secret")).block();
    store.markComplete("e1", "n1").block();

    final Path run = dir.resolve("e-e1");
    assertThat(
            java.nio.file.attribute.PosixFilePermissions.toString(
                Files.getPosixFilePermissions(run)))
        .isEqualTo("rwx------");
    try (var files = Files.list(run)) {
      assertThat(files.toList())
          .isNotEmpty()
          .allSatisfy(file ->
              assertThat(
                      java.nio.file.attribute.PosixFilePermissions.toString(
                          Files.getPosixFilePermissions(file)))
                  .isEqualTo("rw-------"));
    }
  }

  @Test
  void withoutPosixViewsNoPermissionsAreSet() {
    assertThat(FileNodeCheckpointStore.attributes(Set.of("basic"), "rwx------")).isEmpty();
    assertThat(FileNodeCheckpointStore.attributes(Set.of("posix"), "rwx------")).hasSize(1);
  }

  @Test
  void nodeWhoseOutputCouldNotBeWritten_getsNoEndMarker() {
    final FileNodeCheckpointStore store = store();
    store.save("e1", "n1", message("fine")).block();
    store.save("e1", "n1", message(new Broken())).block();

    store.markComplete("e1", "n1").block();

    assertThat(store.isFinished("e1", "n1")).isFalse();
    // a clear forgets the failure with the run
    store.clear("e1");
    store.save("e2", "n1", message("fine")).block();
    store.markComplete("e2", "n1").block();
    assertThat(store.isFinished("e2", "n1")).isTrue();
  }

  @Test
  void removeNotIn_keepsTheWantedRunsAndIgnoresMissingRoot() throws IOException {
    final FileNodeCheckpointStore store = store();
    store.save("keep", "n1", message("a")).block();
    store.save("drop one", "n1", message("b")).block();

    store.removeNotIn(Set.of("keep"));

    try (var left = Files.list(dir)) {
      assertThat(left.map(path -> dir.relativize(path).toString()).toList())
          .containsExactly("e-keep");
    }
    // no directory yet: nothing to do
    new FileNodeCheckpointStore(dir.resolve("missing").toString(), JsonMapper.builder().build())
        .removeNotIn(Set.of());
  }

  @Test
  void removeNotIn_logsAndGoesOnWhenTheRootCannotBeRead() throws IOException {
    final FileNodeCheckpointStore store = store();
    store.save("e1", "n1", message("a")).block();
    final java.io.File root = dir.toFile();
    org.junit.jupiter.api.Assumptions.assumeTrue(root.setReadable(false));
    try {
      org.junit.jupiter.api.Assumptions.assumeFalse(Files.isReadable(dir));

      final long before = store.bytesHeld();

      store.removeNotIn(Set.of());

      // nothing could be listed, so nothing was removed and nothing was thrown
      assertThat(store.bytesHeld()).isEqualTo(before);
    } finally {
      root.setReadable(true);
    }
  }

  @Test
  void lateMarkerAfterClear_leavesNoDirectory() throws IOException {
    final FileNodeCheckpointStore store = store();
    store.clear("e1");

    store.markComplete("e1", "n1").block();

    assertThat(files("e-e1")).as("only the cleared marker").hasSize(1);
  }

  @Test
  void lateSaveAfterClear_bringsNothingBack() throws IOException {
    final FileNodeCheckpointStore store = store();
    store.save("e1", "n1", message("first")).block();
    store.clear("e1");

    store.save("e1", "n1", message("late")).block();
    store.save("e1", "n2", message("late")).block();

    assertThat(files("e-e1")).as("only the cleared marker").hasSize(1);
    assertThat(store.bytesHeld()).isZero();
    assertThat(store.getAll("e1", "n1").collectList().block()).isEmpty();
    // another run is not affected
    store.save("e2", "n1", message("fine")).block();
    assertThat(store.getAll("e2", "n1").collectList().block()).hasSize(1);
  }

  @Test
  void clearedRun_staysCleared_beyondAnyCountAndAcrossRestarts() {
    store().clear("old");
    for (int i = 0; i <= 5000; i++) {
      store().clear("other-" + i);
    }

    store().save("old", "n1", message("late")).block();

    assertThat(store().getAll("old", "n1").collectList().block()).isEmpty();
  }

  @Test
  void sweepOfAnEarlierProcess_removesTheMarkerToo() throws IOException {
    store().clear("old");

    store().removeNotIn(Set.of());

    assertThat(dir).isEmptyDirectory();
  }

  @Test
  void failedWriteOfARemovedRun_isForgottenWithTheRun() {
    final FileNodeCheckpointStore store = store();
    store.save("gone", "n1", message(new Broken())).block();

    store.removeNotIn(Set.of());

    assertThat(dir).isEmptyDirectory();
    // the failure went with the run: a new run of the same id can finish its node
    store.save("gone", "n1", message("fine")).block();
    store.markComplete("gone", "n1").block();
    assertThat(store.isFinished("gone", "n1")).isTrue();
  }

  @Test
  void oldMarkers_arePruned_freshOnesAndRunsWithFilesStay() {
    final FileNodeCheckpointStore store = store();
    store.clear("done");
    store.save("live", "n1", message("a")).block();

    store.prune(System.currentTimeMillis() + 60_000L);
    assertThat(dir.resolve("e-done")).exists();

    store.prune(System.currentTimeMillis() + 7_200_000L);
    assertThat(dir.resolve("e-done")).doesNotExist();
    assertThat(dir.resolve("e-live")).exists();
  }

  private List<Path> files(final String directory) throws IOException {
    try (var left = Files.list(dir.resolve(directory))) {
      return left.toList();
    }
  }

  @Test
  void clearOfAnUnknownExecution_isHarmless() {
    store().clear("never-seen");

    assertThat(store().getAll("never-seen", "n1").collectList().block()).isEmpty();
  }

  @Test
  void unwritableDirectory_marksIncompleteQuietly() throws IOException {
    Files.createFile(dir.resolve("e-e1")); // a file where the execution directory should be
    final FileNodeCheckpointStore store = store();

    StepVerifier.create(store.save("e1", "n1", message("a"))).verifyComplete();

    // the marker could not be written either, which must not fail the run
    StepVerifier.create(store.getAll("e1", "n1")).verifyComplete();
  }

  @Test
  void unreadableOutput_failsTheReplay() throws IOException {
    Files.createDirectories(
        dir.resolve("e-e1")
            .resolve(FileNodeCheckpointStore.fileName("n1") + ".jsonl")); // a directory, not a file

    StepVerifier.create(store().getAll("e1", "n1"))
        .expectError(java.io.UncheckedIOException.class)
        .verify();
  }

  @Test
  void tornLastLine_makesTheNodeIncomplete_soResumeRunsItAgain() throws IOException {
    final FileNodeCheckpointStore store = store();
    store.save("e1", "n1", message("a")).block();
    final Path file =
        dir.resolve("e-e1").resolve(FileNodeCheckpointStore.fileName("n1") + ".jsonl");
    Files.writeString(
        file,
        Files.readString(file) + "{\"id\":\"cut",
        java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);

    assertThat(store.isComplete("e1", "n1")).isFalse();
  }

  @Test
  void emptyOutputFile_isComplete() throws IOException {
    Files.createDirectories(dir.resolve("e-e1"));
    Files.createFile(
        dir.resolve("e-e1").resolve(FileNodeCheckpointStore.fileName("n1") + ".jsonl"));

    assertThat(store().isComplete("e1", "n1")).isTrue();
  }

  @Test
  void unparsableLine_failsTheReplayCleanly() throws IOException {
    Files.createDirectories(dir.resolve("e-e1"));
    Files.writeString(
        dir.resolve("e-e1").resolve(FileNodeCheckpointStore.fileName("n1") + ".jsonl"),
        "not json\n");

    StepVerifier.create(store().getAll("e1", "n1"))
        .expectErrorSatisfies(e ->
            assertThat(e)
                .isInstanceOf(java.io.UncheckedIOException.class)
                .hasMessageContaining("Unreadable output of node n1"))
        .verify();
  }

  @Test
  void outputThatCannotBeInspected_countsAsIncomplete() throws IOException {
    Files.createDirectories(
        dir.resolve("e-e1")
            .resolve(FileNodeCheckpointStore.fileName("n1") + ".jsonl")); // a directory, not a file

    assertThat(store().isComplete("e1", "n1")).isFalse();
  }

  @Test
  void blankLinesInTheOutputAreSkipped() throws IOException {
    final FileNodeCheckpointStore store = store();
    store.save("e1", "n1", message("a")).block();
    final Path file =
        dir.resolve("e-e1").resolve(FileNodeCheckpointStore.fileName("n1") + ".jsonl");
    Files.writeString(file, "\n" + Files.readString(file) + "\n");

    StepVerifier.create(store.getAll("e1", "n1"))
        .assertNext(m -> assertThat(m.payload()).isEqualTo("a"))
        .verifyComplete();
  }

  @Test
  void clearThatCannotWalkTheDirectory_isLoggedNotThrown() throws IOException {
    final FileNodeCheckpointStore store = store();
    store.save("e1", "n1", message("a")).block();
    final java.io.File executionDir = dir.resolve("e-e1").toFile();
    org.junit.jupiter.api.Assumptions.assumeTrue(executionDir.setReadable(false));
    try {
      org.junit.jupiter.api.Assumptions.assumeFalse(Files.isReadable(executionDir.toPath()));
      store.clear("e1");
      assertThat(store.bytesHeld()).isPositive();
    } finally {
      executionDir.setReadable(true);
    }
  }

  @Test
  void markComplete_makesTheNodeFinished_andClearForgetsIt() {
    final FileNodeCheckpointStore store = store();
    store.save("e1", "n1", message("a")).block();
    assertThat(store.isFinished("e1", "n1")).isFalse();

    StepVerifier.create(store.markComplete("e1", "n1")).verifyComplete();

    assertThat(store.isFinished("e1", "n1")).isTrue();
    assertThat(store.isFinished("e1", "other")).isFalse();
    assertThat(store.markerFailures()).isZero();
    store.clear("e1");
    assertThat(store.isFinished("e1", "n1")).isFalse();
  }

  @Test
  void nodeWithoutOutput_canStillBeMarkedFinished() {
    final FileNodeCheckpointStore store = store();

    store.markComplete("e1", "quiet").block();

    assertThat(store.isFinished("e1", "quiet")).isTrue();
  }

  @Test
  void tornOutput_isNeverFinished_evenWithMarker() throws IOException {
    final FileNodeCheckpointStore store = store();
    store.save("e1", "n1", message("a")).block();
    store.markComplete("e1", "n1").block();
    final Path file =
        dir.resolve("e-e1").resolve(FileNodeCheckpointStore.fileName("n1") + ".jsonl");
    Files.writeString(file, Files.readString(file) + "{\"id\":\"cut");

    assertThat(store.isFinished("e1", "n1")).isFalse();
  }

  @Test
  void markerThatCannotBeWritten_isCounted_andLeavesTheNodeUnfinished() throws IOException {
    Files.createFile(dir.resolve("e-e1")); // a file where the execution directory should be
    final FileNodeCheckpointStore store = store();

    StepVerifier.create(store.markComplete("e1", "n1")).verifyComplete();

    assertThat(store.markerFailures()).isEqualTo(1);
    assertThat(store.isFinished("e1", "n1")).isFalse();
  }

  @Test
  void concurrentSaves_keepLinesWhole_completedNodeReopens() {
    final FileNodeCheckpointStore store = store();
    reactor.core.publisher.Flux.range(0, 500)
        .flatMap(
            i ->
                store
                    .save("e1", "n1", message("m" + i))
                    .subscribeOn(reactor.core.scheduler.Schedulers.parallel()),
            16)
        .blockLast();

    assertThat(store.getAll("e1", "n1").collectList().block()).hasSize(500);
    assertThat(store.isComplete("e1", "n1")).isTrue();

    store.markComplete("e1", "n1").block();
    store.save("e1", "n1", message("again")).block();
    assertThat(store.getAll("e1", "n1").collectList().block()).hasSize(501);
    assertThat(store.isFinished("e1", "n1")).isTrue();
    store.clear("e1");
    assertThat(store.getAll("e1", "n1").collectList().block()).isEmpty();
  }

  @Test
  void manyNodes_doNotKeepFilesOpen() throws IOException {
    final FileNodeCheckpointStore store = store();
    final Path fds = Path.of("/proc/self/fd");
    org.junit.jupiter.api.Assumptions.assumeTrue(Files.isDirectory(fds));
    final long before;
    try (var open = Files.list(fds)) {
      before = open.count();
    }

    for (int i = 0; i < 300; i++) {
      store.save("e1", "n" + i, message("a")).block();
    }

    try (var open = Files.list(fds)) {
      assertThat(open.count()).isLessThan(before + 50);
    }
    assertThat(store.getAll("e1", "n7").collectList().block()).hasSize(1);
  }

  @Test
  void clearTwiceInAMinute_pruneRunsOnlyOnce() throws IOException {
    final FileNodeCheckpointStore store = store();
    store.clear("first");
    final Path marker =
        dir.resolve("e-first").resolve(FileNodeCheckpointStore.fileName("") + ".cleared");
    Files.setLastModifiedTime(marker, FileTime.fromMillis(0L));
    store.clear("second");
    // the second clear is within the minute of the first prune: the old marker is kept
    assertThat(Files.exists(dir.resolve("e-first"))).isTrue();
  }

  @Test
  void prune_keepsAClearedRunThatHasOtherFiles() throws IOException {
    final FileNodeCheckpointStore store = store();
    store.clear("busy");
    final Path extra = Files.writeString(dir.resolve("e-busy").resolve("extra.jsonl"), "x\n");

    store.prune(System.currentTimeMillis() + 7_200_000L);

    assertThat(extra).exists();
    assertThat(dir.resolve("e-busy")).exists();
  }

  @Test
  void prune_keepsADirectoryWhoseOnlyFileIsNotAMarker() throws IOException {
    final FileNodeCheckpointStore store = store();
    final Path solo = Files.createDirectories(dir.resolve("e-solo"));
    Files.writeString(solo.resolve("output.jsonl"), "x\n");
    store.prune(Long.MAX_VALUE);
    assertThat(Files.exists(solo.resolve("output.jsonl"))).isTrue();
  }

  @Test
  void clearWhereTheRootIsAFile_isLoggedNotThrown() throws IOException {
    final Path root = Files.writeString(dir.resolve("not-a-dir"), "x");
    final FileNodeCheckpointStore store =
        new FileNodeCheckpointStore(root.toString(), JsonMapper.builder().build());
    store.clear("gone");
    assertThat(Files.isRegularFile(root)).isTrue();
  }
}
