// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.file;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/** Tests for FileReadPlugin and FileWritePlugin. */
class FileIoPluginsTest {

  /** Sandbox directory. */
  @TempDir Path dir;

  private final FileReadPlugin reader = new FileReadPlugin();
  private final FileWritePlugin writer = new FileWritePlugin();

  private static Flux<Message<?>> in(final Object payload) {
    return Flux.just(DefaultMessage.create(UUID.randomUUID(), payload));
  }

  @Test
  void write_thenRead_roundTrips() throws Exception {
    final Map<String, Object> config = Map.of("baseDir", dir.toString(), "path", "sub/a.txt");
    StepVerifier.create(writer.process(in(Map.of("content", "hello")), config))
        .expectNextCount(1)
        .verifyComplete();
    assertThat(Files.readString(dir.resolve("sub/a.txt"))).isEqualTo("hello");
    StepVerifier.create(reader.process(in(Map.of()), config))
        .assertNext(m -> assertThat(((Map<?, ?>) m.payload()).get("content")).isEqualTo("hello"))
        .verifyComplete();
  }

  @Test
  void write_append_andStringPayloadAndPayloadPath() throws Exception {
    final Map<String, Object> config = Map.of("baseDir", dir.toString(), "append", true);
    StepVerifier.create(writer.process(in(Map.of("path", "l.log", "content", "one")), config))
        .expectNextCount(1)
        .verifyComplete();
    final Map<String, Object> fixed =
        Map.of("baseDir", dir.toString(), "path", "l.log", "append", true);
    StepVerifier.create(writer.process(in("two"), fixed)).expectNextCount(1).verifyComplete();
    assertThat(Files.readAllLines(dir.resolve("l.log"))).containsExactly("one", "two");
  }

  @Test
  void traversalAndMissingInputsAreRejected() {
    final Map<String, Object> evil = Map.of("baseDir", dir.toString(), "path", "../x.txt");
    StepVerifier.create(reader.process(in(Map.of()), evil))
        .expectError(IllegalArgumentException.class)
        .verify();
    StepVerifier.create(reader.process(in(Map.of()), Map.of("baseDir", dir.toString())))
        .expectError(IllegalArgumentException.class)
        .verify();
    StepVerifier.create(
            writer.process(in(Map.of("other", 1)), Map.of("baseDir", dir.toString(), "path", "a")))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void metadataAndConfigValidation() {
    assertThat(reader.getType()).isEqualTo("FILE_READ");
    assertThat(writer.getType()).isEqualTo("FILE_WRITE");
    assertThat(reader.describe().usagePattern()).contains("baseDir");
    assertThat(writer.describe().usagePattern()).contains("append");
    final Map<String, Object> ok = Map.of("baseDir", dir.toString());
    StepVerifier.create(reader.validateConfig(ok)).verifyComplete();
    StepVerifier.create(writer.validateConfig(ok)).verifyComplete();
    StepVerifier.create(reader.validateConfig(Map.of()))
        .expectError(IllegalArgumentException.class)
        .verify();
    StepVerifier.create(writer.validateConfig(Map.of("baseDir", " ")))
        .expectError(IllegalArgumentException.class)
        .verify();
  }
}
