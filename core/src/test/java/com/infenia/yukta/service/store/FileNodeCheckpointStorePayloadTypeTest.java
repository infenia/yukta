// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/** Keeps the type of a payload across a restart of the file store. */
@NoArgsConstructor
class FileNodeCheckpointStorePayloadTypeTest {

  @TempDir /* default */ Path dir;

  private FileNodeCheckpointStore store() {
    return new FileNodeCheckpointStore(dir.toString(), JsonMapper.builder().build());
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
    store().save("e1", "n1", DefaultMessage.create(UUID.randomUUID(), payload)).block();
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
  void payloadOfConfiguredPackage_isRestoredWithItsType() {
    CheckpointJson.allowPackages(List.of("java.util.concurrent.atomic."));
    try {
      assertThat(roundTrip(new AtomicInteger(5)).payload()).isInstanceOf(AtomicInteger.class);
    } finally {
      CheckpointJson.allowPackages(List.of());
    }
  }

  @Test
  void payloadTypeNamedInTheFile_isNotLoadedUnlessAllowed() throws IOException {
    store().save("e1", "n1", DefaultMessage.create(UUID.randomUUID(), Map.of("k", 1))).block();
    final Path file =
        dir.resolve("e-e1").resolve(FileNodeCheckpointStore.fileName("n1") + ".jsonl");
    final String forged =
        Files.readString(file)
            .replaceAll(
                "\"payloadType\":\"[^\"]*\"",
                "\"payloadType\":\"javax.script.ScriptEngineManager\"");
    assertThat(forged).contains("javax.script.ScriptEngineManager");
    Files.writeString(file, forged);
    assertThat(store().get("e1", "n1").block().payload()).isEqualTo(Map.of("k", 1));
  }

  @Test
  void payloadTypeThatIsMissingOrDoesNotFit_comesBackAsJson() throws IOException {
    store().save("e1", "n1", DefaultMessage.create(UUID.randomUUID(), Map.of("k", 1))).block();
    final Path file =
        dir.resolve("e-e1").resolve(FileNodeCheckpointStore.fileName("n1") + ".jsonl");
    for (final String type : List.of("com.infenia.yukta.NoSuchClass", "java.time.Instant")) {
      Files.writeString(
          file,
          Files.readString(file)
              .replaceAll("\"payloadType\":\"[^\"]*\"", "\"payloadType\":\"" + type + "\""));
      assertThat(store().get("e1", "n1").block().payload()).isEqualTo(Map.of("k", 1));
    }
  }

  @Test
  void lineWithoutPayloadType_andNullPayload_areRead() throws IOException {
    store().save("e1", "n1", DefaultMessage.create(UUID.randomUUID(), Map.of("k", 1))).block();
    final Path file =
        dir.resolve("e-e1").resolve(FileNodeCheckpointStore.fileName("n1") + ".jsonl");
    Files.writeString(file, Files.readString(file).replaceAll(",\"payloadType\":\"[^\"]*\"", ""));
    assertThat(store().get("e1", "n1").block().payload()).isEqualTo(Map.of("k", 1));
    assertThat(roundTrip(null).payload()).isNull();
  }
}
