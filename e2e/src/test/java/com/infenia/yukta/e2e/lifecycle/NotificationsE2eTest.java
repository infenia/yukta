// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.e2e.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.infenia.yukta.e2e.support.Api;
import com.infenia.yukta.e2e.support.Api.Reply;
import com.infenia.yukta.e2e.support.E2eTest;
import com.infenia.yukta.e2e.support.Scenarios;
import java.time.Duration;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** A workflow raises an inbox notification; it is listed, counted, marked read and dismissed. */
@E2eTest
@NoArgsConstructor
class NotificationsE2eTest {

  private static final Duration WAIT = Duration.ofSeconds(30);

  private final Api admin = Api.forRole(com.infenia.yukta.security.ApiRole.ADMIN);

  private static String notifying(final String session, final String title) {
    return Workflows.ending(
        session,
        "notifying",
        Map.of(
            "nodeId",
            "n",
            "type",
            "NOTIFY_TERMINAL",
            "config",
            Map.of(
                "target",
                "inbox",
                "title",
                title,
                "severity",
                "INFO",
                "text",
                "e2e notification")));
  }

  private JsonNode awaitNotification(final String title) {
    final JsonNode[] found = new JsonNode[1];
    await()
        .atMost(WAIT)
        .until(() -> {
          for (final JsonNode item :
              admin.data(admin.client().get().uri("/api/notifications?kind=INFO").exchange())) {
            if (title.equals(item.path("title").asString())) {
              found[0] = item;
            }
          }
          return found[0] != null;
        });
    return found[0];
  }

  @Test
  void notificationAppearsIsMarkedReadAndDismissed(final Scenarios scenarios) {
    final String session = Scenarios.unique("notify");
    final String title = "e2e " + session;
    scenarios.applySession(notifying(session, title));
    scenarios.startWorkflow(session, "w", Map.of());

    final JsonNode item = awaitNotification(title);
    final String id = item.path("id").asString();
    assertThat(item.path("category").asString()).isEqualTo("workflow");
    assertThat(item.path("severity").asString()).isEqualTo("INFO");
    assertThat(item.path("sessionId").asString()).isEqualTo(session);
    assertThat(item.path("body").asString()).isEqualTo("e2e notification");

    final long unread =
        admin
            .data(admin.client().get().uri("/api/notifications/summary").exchange())
            .path("unread")
            .asLong();
    assertThat(unread).isPositive();
    final JsonNode marked =
        admin.data(
            admin
                .client()
                .post()
                .uri("/api/notifications/read")
                .header("Content-Type", "application/json")
                .bodyValue("{\"ids\":[\"" + id + "\"]}")
                .exchange());
    assertThat(marked.path("marked").asInt()).isEqualTo(1);
    assertThat(
            admin
                .data(admin.client().get().uri("/api/notifications/summary").exchange())
                .path("unread")
                .asLong())
        .isLessThan(unread);

    assertThat(admin.call("POST", "/api/notifications/" + id + "/dismiss", null).status())
        .isEqualTo(200);
    assertThat(admin.call("POST", "/api/notifications/e2e-nope/dismiss", null).status())
        .isEqualTo(404);
  }

  @Test
  void thePreferencesAreReadAndSaved() {
    final JsonNode preferences =
        admin.data(admin.client().get().uri("/api/notifications/preferences").exchange());

    assertThat(preferences.has("levels")).isTrue();
    assertThat(preferences.has("mutedSessions")).isTrue();
    final Reply saved =
        admin.call(
            "PUT",
            "/api/notifications/preferences",
            "{\"levels\":" + preferences.path("levels") + ",\"mutedSessions\":[]}");
    assertThat(saved.status()).as(saved.body()).isEqualTo(200);
  }

  @Test
  void theStreamOpens(final Scenarios scenarios) {
    final String session = Scenarios.unique("notify");
    scenarios.applySession(notifying(session, "e2e stream " + session));
    final java.util.concurrent.CompletableFuture<String> stream =
        java.util.concurrent.CompletableFuture.supplyAsync(() ->
            Api.events(
                admin.client().get().uri("/api/notifications/stream"), Duration.ofSeconds(2)));

    await()
        .atMost(WAIT)
        .pollInterval(Duration.ofMillis(300))
        .until(() -> {
          scenarios.startWorkflow(session, "w", Map.of());
          return stream.isDone();
        });

    assertThat(stream.join()).isNotNull();
  }
}
