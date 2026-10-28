// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.aot.DisabledInAotMode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The REST API description the UI types are generated from ({@code ui/openapi.json}). A controller
 * change shows up here as a diff; refresh the snapshot with {@code yk gradle :boot:test --tests
 * "*OpenApiSnapshotTest" -Pyukta.openapi.update=true}, then run {@code pnpm run api:types --
 * openapi.json} in {@code ui/}. Skipped in builds with optional modules, which add endpoints.
 */
@DisabledIfSystemProperty(named = "yukta.edition", matches = ".+")
@DisabledIfSystemProperty(named = "yukta.persistence", matches = ".+")
@DisabledIfSystemProperty(named = "yukta.workers", matches = ".+")
@DisabledIfSystemProperty(named = "yukta.plugins", matches = ".+")
@DisabledInAotMode
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"yukta.supervisor.rules.file=false", "springdoc.api-docs.enabled=true"})
@SuppressWarnings("PMD.CommentRequired")
@NoArgsConstructor
class OpenApiSnapshotTest {

  private static final Path SNAPSHOT = Path.of("../ui/openapi.json");

  @LocalServerPort private int port;

  @Test
  void theApiDescriptionIsTheCheckedInSnapshot() throws Exception {
    final HttpResponse<String> response =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v3/api-docs"))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
    final JsonMapper mapper =
        JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();
    // The server URL is the random port of this run.
    final ObjectNode spec = (ObjectNode) mapper.readTree(response.body());
    spec.remove("servers");
    final String actual = mapper.writeValueAsString(spec) + "\n";

    if (Boolean.getBoolean("yukta.openapi.update")) {
      Files.writeString(SNAPSHOT, actual, StandardCharsets.UTF_8);
    }

    assertThat(actual)
        .as("ui/openapi.json is stale; see the Javadoc of OpenApiSnapshotTest to refresh it")
        .isEqualTo(Files.readString(SNAPSHOT, StandardCharsets.UTF_8));
  }
}
