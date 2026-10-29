// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.AbstractList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@NoArgsConstructor
@SuppressWarnings("PMD.TooManyMethods")
class InMemoryNodeCheckpointStoreSpillTest {

  @TempDir /* default */ Path dir;

  @SuppressWarnings("unchecked")
  private InMemoryNodeCheckpointStore store(final int messages, final String spillDir) {
    final ObjectProvider<ObjectMapper> mapper = mock(ObjectProvider.class);
    when(mapper.getObject()).thenReturn(JsonMapper.builder().build());
    return new InMemoryNodeCheckpointStore(messages, 4_194_304L, spillDir, mapper);
  }

  private static Message<?> message(final String payload) {
    return DefaultMessage.create(UUID.randomUUID(), payload);
  }

  @Test
  void outputBeyondTheCap_movesToDisk_andReplaysCompleteInOrder() {
    final InMemoryNodeCheckpointStore store = store(2, dir.toString());
    for (int i = 0; i < 5; i++) {
      store.save("e1", "n1", message("m" + i)).block();
    }
    store.markComplete("e1", "n1").block();

    StepVerifier.create(store.getAll("e1", "n1").map(m -> (Object) m.payload()))
        .expectNext("m0", "m1", "m2", "m3", "m4")
        .verifyComplete();
    StepVerifier.create(store.get("e1", "n1").map(m -> (Object) m.payload()))
        .expectNext("m4")
        .verifyComplete();
    assertThat(store.isComplete("e1", "n1")).isTrue();
    assertThat(store.isFinished("e1", "n1")).isTrue();
    assertThat(store.bytesHeld()).isZero();
  }

  @Test
  void saversThatDoNotWait_stillReplayInEmissionOrder() {
    final InMemoryNodeCheckpointStore store = store(2, dir.toString());
    final List<Mono<Void>> writes = new java.util.ArrayList<>();
    for (int i = 0; i < 200; i++) {
      final Mono<Void> write = store.save("e1", "n1", message("m" + i)).cache();
      write.subscribe();
      writes.add(write);
    }
    Mono.when(writes).block();

    StepVerifier.create(store.getAll("e1", "n1").map(m -> (String) m.payload()).collectList())
        .assertNext(
            payloads ->
                assertThat(payloads)
                    .containsExactlyElementsOf(
                        java.util.stream.IntStream.range(0, 200).mapToObj(i -> "m" + i).toList()))
        .verifyComplete();
  }

  @Test
  void saveAfterClear_isDropped_andHoldsNoBytes() {
    final InMemoryNodeCheckpointStore store = store(5, dir.toString());
    store.save("e1", "n1", message("a")).block();
    store.clear("e1");
    store.save("e1", "n1", message("late")).block();

    assertThat(store.bytesHeld()).isZero();
    StepVerifier.create(store.getAll("e1", "n1")).verifyComplete();
  }

  @Test
  void nodeWithinTheCap_staysInMemory() throws IOException {
    final InMemoryNodeCheckpointStore store = store(5, dir.toString());
    store.save("e1", "n1", message("a")).block();

    assertThat(store.isComplete("e1", "n1")).isTrue();
    assertThat(store.bytesHeld()).isPositive();
    try (var files = Files.list(dir)) {
      assertThat(files).isEmpty();
    }
  }

  @Test
  void incompleteSpill_isNotComplete() throws IOException {
    final InMemoryNodeCheckpointStore store = store(1, dir.toString());
    store.save("e1", "n1", message("a")).block();
    store.save("e1", "n1", message("b")).block();
    try (var files = Files.walk(dir)) {
      final Path jsonl =
          files.filter(p -> p.toString().endsWith(".jsonl")).findFirst().orElseThrow();
      Files.writeString(jsonl, "{\"cut", java.nio.file.StandardOpenOption.APPEND);
    }

    assertThat(store.isComplete("e1", "n1")).isFalse();
  }

  @Test
  void clear_deletesSpillFiles_ofThatExecutionOnly() throws IOException {
    final InMemoryNodeCheckpointStore store = store(1, dir.toString());
    for (final String execution : new String[] {"e1", "e2"}) {
      store.save(execution, "n", message("a")).block();
      store.save(execution, "n", message("b")).block();
    }

    store.clear("never-saved");
    store.clear("e1");

    try (var files = Files.list(dir)) {
      assertThat(files).hasSize(1);
    }
    StepVerifier.create(store.getAll("e1", "n")).verifyComplete();
    StepVerifier.create(store.getAll("e2", "n")).expectNextCount(2).verifyComplete();
  }

  @Test
  void staleSpillOfAnEarlierProcess_isRemovedAtStartup_otherFilesAreKept() throws IOException {
    Files.createDirectories(dir.resolve("e-old"));
    Files.writeString(dir.resolve("e-old").resolve("n.jsonl"), "{}\n");
    Files.writeString(dir.resolve("keep.txt"), "x");

    store(1, dir.toString());

    assertThat(dir.resolve("e-old")).doesNotExist();
    assertThat(dir.resolve("keep.txt")).exists();
  }

  @Test
  void withoutSpillDir_overflowStillMarksTheNodeIncomplete() {
    final InMemoryNodeCheckpointStore store = store(1, "");
    store.save("e1", "n1", message("a")).block();
    store.save("e1", "n1", message("b")).block();

    assertThat(store.isComplete("e1", "n1")).isFalse();
  }

  @Test
  void nullOrMissingSpillDir_isHandled() {
    assertThat(store(1, null).isComplete("e", "n")).isTrue();
    assertThat(store(1, dir.resolve("not-yet").toString()).isComplete("e", "n")).isTrue();
  }

  @Test
  void unreadableSpillDir_isLoggedNotFatal() {
    final java.io.File folder = dir.toFile();
    org.junit.jupiter.api.Assumptions.assumeTrue(folder.setReadable(false));
    try {
      org.junit.jupiter.api.Assumptions.assumeFalse(Files.isReadable(dir));
      assertThat(store(1, dir.toString()).isComplete("e", "n")).isTrue();
    } finally {
      assertThat(folder.setReadable(true)).isTrue();
    }
  }

  @Test
  void clearedRunsBeyondTheCap_areForgotten_soTheOldestRecordsAgain() {
    final InMemoryNodeCheckpointStore store = store(5, dir.toString());
    for (int i = 0; i <= 4096; i++) {
      store.clear("old-" + i);
    }

    store.save("old-0", "n1", message("again")).block();
    store.save("old-4096", "n1", message("late")).block();

    StepVerifier.create(store.getAll("old-0", "n1")).expectNextCount(1).verifyComplete();
    StepVerifier.create(store.getAll("old-4096", "n1")).verifyComplete();
  }

  @Test
  void saveThatIsClearedWhileItIsMeasured_isDropped() {
    final InMemoryNodeCheckpointStore store = store(5, dir.toString());
    final List<String> payload =
        new AbstractList<>() {
          @Override
          public String get(final int index) {
            throw new IndexOutOfBoundsException();
          }

          @Override
          public int size() {
            return 0;
          }

          @Override
          public Stream<String> stream() {
            // the run is cleared after the save found its node and before it takes the node's lock
            store.clear("e1");
            return Stream.empty();
          }
        };

    store.save("e1", "n1", DefaultMessage.create(UUID.randomUUID(), payload)).block();

    assertThat(store.bytesHeld()).isZero();
    StepVerifier.create(store.getAll("e1", "n1")).verifyComplete();
  }

  @Test
  void failedSpillWrite_doesNotBlockTheNextOne() {
    final FileNodeCheckpointStore spill = mock(FileNodeCheckpointStore.class);
    final AtomicInteger saves = new AtomicInteger();
    when(spill.save(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any()))
        .thenAnswer(
            _ ->
                saves.getAndIncrement() == 0
                    ? Mono.<Void>error(new IllegalStateException("disk full"))
                        .delaySubscription(Duration.ofMillis(200))
                    : Mono.<Void>empty());
    final InMemoryNodeCheckpointStore store = store(1, dir.toString());
    ReflectionTestUtils.setField(store, "spill", spill);
    store.save("e1", "n1", message("m0")).block();

    final Mono<Void> failing = store.save("e1", "n1", message("m1")).cache();
    failing.subscribe(_ -> {}, _ -> {});
    store.save("e1", "n1", message("m2")).block();

    StepVerifier.create(failing).expectErrorMessage("disk full").verify();
    assertThat(saves).hasValue(2);
  }
}
