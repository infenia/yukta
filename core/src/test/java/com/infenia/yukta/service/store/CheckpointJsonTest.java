// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@SuppressWarnings({"PMD.TooManyMethods", "PMD.AvoidDuplicateLiterals"})
class CheckpointJsonTest {

  private final ObjectMapper mapper = new ObjectMapper();

  /** A payload type inside an allowed package, so it comes back with its type. */
  /* default */ record Sample(String name, int count) {}

  @AfterEach
  void resetExtraPackages() {
    CheckpointJson.allowPackages(List.of());
  }

  /** A stored message as {@link CheckpointJson#write} would write it, with the given payload. */
  private String stored(final Object payload, final String payloadType) {
    final ObjectNode node =
        mapper.valueToTree(DefaultMessage.create(UUID.randomUUID(), (Object) null));
    node.set("payload", mapper.valueToTree(payload));
    if (payloadType != null) {
      node.put("payloadType", payloadType);
    }
    return mapper.writeValueAsString(node);
  }

  @Test
  void allowedPayloadKeepsItsJavaType() {
    final Message<Sample> message =
        DefaultMessage.create(UUID.randomUUID(), new Sample("alpha", 3));

    final String json = CheckpointJson.write(mapper, message);
    final Message<?> restored = CheckpointJson.read(mapper, json);

    assertThat(json).contains("payloadType").contains(Sample.class.getName());
    assertThat(restored.payload()).isEqualTo(new Sample("alpha", 3));
    assertThat(restored.id()).isEqualTo(message.id());
  }

  @Test
  void checkpointWrittenWithTheRemovedControlMessageFieldStillReads() {
    final String json =
        CheckpointJson.write(mapper, DefaultMessage.create(null, "text"))
            .replaceFirst("\\{", "{\"controlMessage\":true,");

    assertThat(json).contains("controlMessage");
    assertThat(CheckpointJson.read(mapper, json).payload()).isEqualTo("text");
  }

  @Test
  void payloadOutsideTheAllowedPackagesComesBackAsJson() {
    final Message<String> message = DefaultMessage.create(UUID.randomUUID(), "text");

    final Message<?> restored = CheckpointJson.read(mapper, CheckpointJson.write(mapper, message));

    assertThat(restored.payload()).isEqualTo("text");
  }

  @Test
  void disallowedTypeIsNotLoadedEvenWhenStoredInTheJson() {
    final Message<?> restored =
        CheckpointJson.read(mapper, stored(List.of("x"), "org.example.Evil"));

    assertThat(restored.payload()).isEqualTo(List.of("x"));
  }

  @Test
  void extraPackagePrefixAllowsItsTypes() {
    CheckpointJson.allowPackages(List.of("java.util.ArrayList"));

    final Message<?> restored =
        CheckpointJson.read(mapper, stored(List.of("a", "b"), "java.util.ArrayList"));

    assertThat(restored.payload()).isEqualTo(List.of("a", "b"));
  }

  @Test
  void nullPayloadWritesNoTypeAndReadsBack() {
    final Message<Object> message = DefaultMessage.create(UUID.randomUUID(), null);

    final String json = CheckpointJson.write(mapper, message);

    assertThat(json).doesNotContain("payloadType");
    assertThat(CheckpointJson.read(mapper, json).payload()).isNull();
  }

  @Test
  void olderMessageWithoutTypeIsReadAsPlainJson() {
    final Message<?> restored = CheckpointJson.read(mapper, stored(Map.of("k", "v"), null));

    assertThat(restored.payload()).isEqualTo(Map.of("k", "v"));
  }

  @Test
  void unknownClassOfAnAllowedPrefixFallsBackToJson() {
    final Message<?> restored =
        CheckpointJson.read(mapper, stored(Map.of("a", 1), "com.infenia.yukta.DoesNotExist"));

    assertThat(restored.payload()).isEqualTo(Map.of("a", 1));
  }

  @Test
  void payloadThatDoesNotFitItsClassFallsBackToJson() {
    final Message<?> restored =
        CheckpointJson.read(mapper, stored("not-a-uuid-object", "java.util.UUID"));

    assertThat(restored.payload()).isEqualTo("not-a-uuid-object");
  }

  @Test
  void strictMapperStillReadsTheLine() {
    final ObjectMapper strict =
        tools.jackson.databind.json.JsonMapper.builder()
            .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();
    final Message<Sample> message =
        DefaultMessage.create(UUID.randomUUID(), new Sample("strict", 3));

    final Message<?> restored = CheckpointJson.read(strict, CheckpointJson.write(strict, message));

    assertThat(restored.payload()).isEqualTo(new Sample("strict", 3));
  }

  @Test
  void lineThatIsNotAnObjectIsRefused() {
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> CheckpointJson.read(mapper, "[]"))
        .isInstanceOf(RuntimeException.class);
  }
}
