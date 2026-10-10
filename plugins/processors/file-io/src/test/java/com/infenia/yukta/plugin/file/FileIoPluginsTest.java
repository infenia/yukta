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
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/** Tests for FileReadPlugin and FileWritePlugin. */
@NoArgsConstructor
class FileIoPluginsTest {

  /** Config key. */
  private static final String BASE_DIR = "baseDir";

  /** Config key. */
  private static final String PATH = "path";

  /** Sandbox directory. */
  @TempDir /* default */ Path dir;

  /** Reader under test. */
  private final FileReadPlugin reader = new FileReadPlugin();

  /** Writer under test. */
  private final FileWritePlugin writer = new FileWritePlugin();

  private static Flux<Message<?>> messages(final Object payload) {
    return Flux.just(DefaultMessage.create(UUID.randomUUID(), payload));
  }

  @Test
  void write_thenRead_roundTrips() throws Exception {
    final Map<String, Object> config = Map.of(BASE_DIR, dir.toString(), PATH, "sub/a.txt");
    StepVerifier.create(writer.process(messages(Map.of("content", "hello")), config))
        .expectNextCount(1)
        .verifyComplete();
    assertThat(Files.readString(dir.resolve("sub/a.txt"))).isEqualTo("hello");
    StepVerifier.create(reader.process(messages(Map.of()), config))
        .assertNext(m -> assertThat(((Map<?, ?>) m.payload()).get("content")).isEqualTo("hello"))
        .verifyComplete();
  }

  @Test
  void write_append_andStringPayloadAndPayloadPath() throws Exception {
    final Map<String, Object> config = Map.of(BASE_DIR, dir.toString(), "append", true);
    StepVerifier.create(writer.process(messages(Map.of(PATH, "l.log", "content", "one")), config))
        .expectNextCount(1)
        .verifyComplete();
    final Map<String, Object> fixed =
        Map.of(BASE_DIR, dir.toString(), PATH, "l.log", "append", true);
    StepVerifier.create(writer.process(messages("two"), fixed)).expectNextCount(1).verifyComplete();
    assertThat(Files.readAllLines(dir.resolve("l.log"))).containsExactly("one", "two");
  }

  @Test
  void traversalAndMissingInputsAreRejected() {
    final Map<String, Object> evil = Map.of(BASE_DIR, dir.toString(), PATH, "../x.txt");
    StepVerifier.create(reader.process(messages(Map.of()), evil))
        .expectError(IllegalArgumentException.class)
        .verify();
    StepVerifier.create(reader.process(messages(Map.of()), Map.of(BASE_DIR, dir.toString())))
        .expectError(IllegalArgumentException.class)
        .verify();
    StepVerifier.create(
            writer.process(
                messages(Map.of("other", 1)), Map.of(BASE_DIR, dir.toString(), PATH, "a")))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void metadataAndConfigValidation() {
    assertThat(reader.getType()).isEqualTo("FILE_READ");
    assertThat(writer.getType()).isEqualTo("FILE_WRITE");
    assertThat(reader.describe().usagePattern()).contains(BASE_DIR);
    assertThat(writer.describe().usagePattern()).contains("append");
    final Map<String, Object> valid = Map.of(BASE_DIR, dir.toString());
    StepVerifier.create(reader.validateConfig(valid)).verifyComplete();
    StepVerifier.create(writer.validateConfig(valid)).verifyComplete();
    StepVerifier.create(reader.validateConfig(Map.of()))
        .expectError(IllegalArgumentException.class)
        .verify();
    StepVerifier.create(writer.validateConfig(Map.of(BASE_DIR, " ")))
        .expectError(IllegalArgumentException.class)
        .verify();
  }
}
