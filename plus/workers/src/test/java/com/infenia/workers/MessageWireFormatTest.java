// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.workers;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.message.DefaultMessage;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** The message crosses to a worker as JSON; this pins what survives the trip. */
@NoArgsConstructor
class MessageWireFormatTest {

  private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {};

  private final ObjectMapper mapper = new ObjectMapper();

  private DefaultMessage<?> roundTrip(final DefaultMessage<?> message) {
    return mapper.convertValue(mapper.convertValue(message, JSON_OBJECT), DefaultMessage.class);
  }

  @Test
  void everyComponentWithJsonValuesSurvives() {
    final DefaultMessage<Map<String, Object>> sent =
        DefaultMessage.<Map<String, Object>>builder()
            .id("id")
            .traceId("trace")
            .correlationId("corr")
            .replyTo("reply")
            .expiration(7L)
            .formatIndicator("fmt")
            .metadata(Map.of("s", "v", "n", 3, "b", true, "l", List.of("x"), "m", Map.of("k", "v")))
            .messageHistory(List.of("a", "b"))
            .sequenceId("seq")
            .sequenceNumber(2)
            .sequenceSize(5)
            .priority(4)
            .controlMessage(true)
            .origDest("dest")
            .failureReason("why")
            .exceptionDetail("detail")
            .retryCount(1)
            .payload(Map.of("p", "q"))
            .timestamp(Instant.parse("2026-01-02T03:04:05.123456789Z"))
            .sourcePort("out")
            .sourceNodeId("node")
            .workflowId("wf")
            .build();

    assertThat(roundTrip(sent)).isEqualTo(sent);
  }

  @Test
  void headerOfNonJsonTypeArrivesAsPlainJsonValue() {
    final DefaultMessage<String> sent =
        DefaultMessage.<String>builder()
            .metadata(Map.of("ttl", Duration.ofSeconds(5)))
            .payload("p")
            .build();

    assertThat(roundTrip(sent).metadata().get("ttl")).isNotInstanceOf(Duration.class);
  }
}
