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
import java.util.UUID;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@NoArgsConstructor
@SuppressWarnings("PMD.CommentRequired")
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
}
