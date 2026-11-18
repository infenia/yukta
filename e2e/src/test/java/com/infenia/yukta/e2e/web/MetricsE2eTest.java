// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.e2e.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.e2e.support.Api;
import com.infenia.yukta.e2e.support.E2eTest;
import com.infenia.yukta.security.ApiRole;
import java.time.Duration;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** {@code /api/metrics/*}: the series the dashboard charts, and the live stream. */
@E2eTest
@NoArgsConstructor
@SuppressWarnings("PMD.UnitTestShouldIncludeAssert")
class MetricsE2eTest {

  private final Api api = Api.forRole(ApiRole.ADMIN);

  private JsonNode get(final String uri) {
    return api.data(api.client().get().uri(uri).exchange());
  }

  @Test
  void theCatalogListsSeries() {
    assertThat(get("/api/metrics/catalog").path("series").isArray()).isTrue();
  }

  @Test
  void seriesNeedsNames() {
    api.failure(api.client().get().uri("/api/metrics/series").exchange(), 400);
  }

  @Test
  void seriesIsAnsweredPerName() {
    final JsonNode series = get("/api/metrics/series?names=jvm.threads.live&range=1h");

    assertThat(series.path("range").asString()).isEqualTo("1h");
    assertThat(series.path("series").isObject()).isTrue();
  }

  @Test
  void snapshotHasTimeAndValues() {
    final JsonNode snapshot = get("/api/metrics/snapshot");

    assertThat(snapshot.has("time")).isTrue();
    assertThat(snapshot.path("values").isObject()).isTrue();
  }

  @Test
  void heatmapTakesRangeAndRefusesUnknownOne() {
    assertThat(get("/api/metrics/heatmap").isArray()).isTrue();
    api.failure(api.client().get().uri("/api/metrics/heatmap?range=bogus").exchange(), 400);
  }

  @Test
  void modulesAnnotationsAndLiveRunsAreLists() {
    assertThat(get("/api/metrics/modules").isArray()).isTrue();
    assertThat(get("/api/metrics/annotations").isArray()).isTrue();
    assertThat(get("/api/metrics/runs/live").isArray()).isTrue();
  }

  @Test
  void theStreamSendsSnapshots() {
    final String events =
        Api.events(api.client().get().uri("/api/metrics/stream"), Duration.ofSeconds(7));

    assertThat(events).contains("\"values\"");
  }
}
