// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;

@NoArgsConstructor
@SuppressWarnings({"PMD.CommentRequired", "PMD.TooManyMethods", "PMD.AvoidDuplicateLiterals"})
class FileNodeCheckpointStoreTest {

  @TempDir /* default */ Path dir;

  private FileNodeCheckpointStore store() {
    return new FileNodeCheckpointStore(dir.toString(), JsonMapper.builder().build());
  }

  private static Message<?> message(final Object payload) {
    return DefaultMessage.create(UUID.randomUUID(), payload);
  }

  /** Not serialisable: reading a property throws. */
  @SuppressWarnings({"PMD.CommentRequired", "PMD.PublicMemberInNonPublicType"})
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

  /** A record payload. */
  public record Point(int column, int row) {}

  /** A bean payload. */
  @Data
  @NoArgsConstructor
  @SuppressWarnings({"PMD.PublicMemberInNonPublicType"})
  public static class Person {
    /** Name. */
    private String name;
  }

  private Message<?> roundTrip(final Object payload) {
    store().save("e1", "n1", message(payload)).block();
    return store().get("e1", "n1").block();
  }

  @Test
  void payloadKeepsItsType_record() {
    assertThat(roundTrip(new Point(1, 2)).payload()).isEqualTo(new Point(1, 2));
  }

  @Test
  void payloadKeepsItsType_bean() {
    final Person person = new Person();
    person.setName("a");
    assertThat(roundTrip(person).payload()).isEqualTo(person);
  }

  @Test
  void payloadKeepsItsType_valueTypes() {
    final Instant now = Instant.parse("2026-10-23T10:00:00Z");
    assertThat(roundTrip(now).payload()).isEqualTo(now);
    assertThat(roundTrip(5L).payload()).isEqualTo(5L);
    assertThat(roundTrip(new byte[] {1, 2}).payload()).isEqualTo(new byte[] {1, 2});
  }

  @Test
  void payloadOfDisallowedType_comesBackAsJson() {
    assertThat(roundTrip(new AtomicInteger(5)).payload()).isEqualTo(5);
  }

  @Test
  void payloadTypeNamedInTheFile_isNotLoadedUnlessAllowed() throws IOException {
    store().save("e1", "n1", message(Map.of("k", 1))).block();
    final Path file = dir.resolve("e-e1").resolve("n1.jsonl");
    final String forged =
        Files.readString(file)
            .replaceAll(
                "\"payloadType\":\"[^\"]*\"",
                "\"payloadType\":\"javax.script.ScriptEngineManager\"");
    assertThat(forged).contains("javax.script.ScriptEngineManager");
    Files.writeString(file, forged);
    assertThat(store().get("e1", "n1").block().payload()).isEqualTo(Map.of("k", 1));
  }

  private void forgeType(final String type) throws IOException {
    final Path file = dir.resolve("e-e1").resolve("n1.jsonl");
    Files.writeString(
        file,
        Files.readString(file)
            .replaceAll("\"payloadType\":\"[^\"]*\"", "\"payloadType\":\"" + type + "\""));
  }

  @Test
  void payloadTypeThatIsMissingOrDoesNotFit_comesBackAsJson() throws IOException {
    store().save("e1", "n1", message(Map.of("k", 1))).block();
    forgeType("com.infenia.yukta.NoSuchClass");
    assertThat(store().get("e1", "n1").block().payload()).isEqualTo(Map.of("k", 1));
    forgeType("java.time.Instant");
    assertThat(store().get("e1", "n1").block().payload()).isEqualTo(Map.of("k", 1));
  }

  @Test
  void lineWithoutPayloadType_andNullPayload_areRead() throws IOException {
    store().save("e1", "n1", message(Map.of("k", 1))).block();
    final Path file = dir.resolve("e-e1").resolve("n1.jsonl");
    Files.writeString(file, Files.readString(file).replaceAll(",\"payloadType\":\"[^\"]*\"", ""));
    assertThat(store().get("e1", "n1").block().payload()).isEqualTo(Map.of("k", 1));
    assertThat(roundTrip(null).payload()).isNull();
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
  void clearOfAnUnknownExecution_isHarmless() {
    store().clear("never-seen");

    assertThat(dir).isEmptyDirectory();
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
    Files.createDirectories(dir.resolve("e-e1").resolve("n1.jsonl")); // a directory, not a file

    StepVerifier.create(store().getAll("e1", "n1"))
        .expectError(java.io.UncheckedIOException.class)
        .verify();
  }

  @Test
  void tornLastLine_makesTheNodeIncomplete_soResumeRunsItAgain() throws IOException {
    final FileNodeCheckpointStore store = store();
    store.save("e1", "n1", message("a")).block();
    final Path file = dir.resolve("e-e1").resolve("n1.jsonl");
    Files.writeString(
        file,
        Files.readString(file) + "{\"id\":\"cut",
        java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);

    assertThat(store.isComplete("e1", "n1")).isFalse();
  }

  @Test
  void emptyOutputFile_isComplete() throws IOException {
    Files.createDirectories(dir.resolve("e-e1"));
    Files.createFile(dir.resolve("e-e1").resolve("n1.jsonl"));

    assertThat(store().isComplete("e1", "n1")).isTrue();
  }

  @Test
  void unparsableLine_failsTheReplayCleanly() throws IOException {
    Files.createDirectories(dir.resolve("e-e1"));
    Files.writeString(dir.resolve("e-e1").resolve("n1.jsonl"), "not json\n");

    StepVerifier.create(store().getAll("e1", "n1"))
        .expectErrorSatisfies(
            e ->
                assertThat(e)
                    .isInstanceOf(java.io.UncheckedIOException.class)
                    .hasMessageContaining("Unreadable output of node n1"))
        .verify();
  }

  @Test
  void outputThatCannotBeInspected_countsAsIncomplete() throws IOException {
    Files.createDirectories(dir.resolve("e-e1").resolve("n1.jsonl")); // a directory, not a file

    assertThat(store().isComplete("e1", "n1")).isFalse();
  }

  @Test
  void blankLinesInTheOutputAreSkipped() throws IOException {
    final FileNodeCheckpointStore store = store();
    store.save("e1", "n1", message("a")).block();
    final Path file = dir.resolve("e-e1").resolve("n1.jsonl");
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
    final Path file = dir.resolve("e-e1").resolve("n1.jsonl");
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

  @SuppressWarnings({"unchecked", "PMD.AvoidAccessibilityAlteration"})
  private static Map<String, FileChannel> appenders(final FileNodeCheckpointStore store)
      throws ReflectiveOperationException {
    final java.lang.reflect.Field field =
        FileNodeCheckpointStore.class.getDeclaredField("appenders");
    field.setAccessible(true);
    return (Map<String, FileChannel>) field.get(store);
  }

  @Test
  @SuppressWarnings("PMD.CloseResource")
  void closedAppender_isReplacedByFreshOne() throws ReflectiveOperationException {
    final FileNodeCheckpointStore store = store();
    store.save("e1", "n1", message("a")).block();
    final FileChannel first = appenders(store).get("e1\0n1");
    org.junit.jupiter.api.Assertions.assertDoesNotThrow(first::close);

    store.save("e1", "n1", message("b")).block();

    assertThat(appenders(store).get("e1\0n1")).isNotSameAs(first);
    assertThat(store.getAll("e1", "n1").collectList().block()).hasSize(2);
  }

  @Test
  @SuppressWarnings("PMD.CloseResource")
  void stuckChannel_doesNotStopClear() throws ReflectiveOperationException, IOException {
    final FileNodeCheckpointStore store = store();
    final FileChannel stuck = org.mockito.Mockito.mock(FileChannel.class);
    org.mockito.Mockito.doThrow(new IOException("stuck")).when(stuck).close();
    appenders(store).put("e1\0n1", stuck);
    appenders(store).put("other\0n1", stuck);

    store.clear("e1");

    assertThat(appenders(store)).containsOnlyKeys("other\0n1");
  }
}
