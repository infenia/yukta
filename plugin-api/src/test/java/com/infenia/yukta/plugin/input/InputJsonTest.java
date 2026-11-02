// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.input;

import static com.infenia.yukta.plugin.input.InputFixtures.NOW;
import static com.infenia.yukta.plugin.input.InputFixtures.request;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The records cross the wire as JSON (REST, SSE, the stores), so they must round-trip. */
class InputJsonTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static <T> T roundTrip(final T value, final Class<T> type) {
    return JSON.readValue(JSON.writeValueAsString(value), type);
  }

  @Test
  void requestRoundTrips() {
    final InputRequest request = request();
    assertThat(roundTrip(request, InputRequest.class)).isEqualTo(request);
  }

  @Test
  void enumsAreWrittenAsNames() {
    final String json = JSON.writeValueAsString(request());
    assertThat(json).contains("\"state\":\"OPEN\"").contains("\"kind\":\"APPROVAL\"");
    assertThat(json).contains("\"risk\":\"HIGH\"").contains("\"onTimeout\":\"FAIL\"");
  }

  @Test
  void responseRoundTrips() {
    final InputResponse response =
        new InputResponse(
            "r1",
            "approve",
            "fine",
            Map.of("n", 1),
            new Responder(Responder.Type.SUPERVISOR, "risk-watch"),
            "policy 7",
            0.8,
            NOW);
    assertThat(roundTrip(response, InputResponse.class)).isEqualTo(response);
  }

  @Test
  void specRoundTrips() {
    final InputRequestSpec spec = request().spec();
    assertThat(roundTrip(spec, InputRequestSpec.class)).isEqualTo(spec);
  }
}
