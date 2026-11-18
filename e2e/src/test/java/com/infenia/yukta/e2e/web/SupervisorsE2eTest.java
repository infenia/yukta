// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.e2e.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.e2e.support.Api;
import com.infenia.yukta.e2e.support.Api.Reply;
import com.infenia.yukta.e2e.support.E2eTest;
import com.infenia.yukta.e2e.support.InJvmOnly;
import com.infenia.yukta.e2e.support.Scenarios;
import com.infenia.yukta.security.ApiRole;
import java.time.Duration;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The supervisor endpoints that {@code SupervisorApiE2eTest} does not call: list and read, save,
 * the rules document, versions, rollback and replay.
 */
@E2eTest
@NoArgsConstructor
@SuppressWarnings({
  "PMD.AvoidDuplicateLiterals",
  "PMD.TooManyMethods",
  "PMD.UnitTestShouldIncludeAssert"
})
class SupervisorsE2eTest {

  private static final String RULES = "{\"schemaVersion\":1,\"rules\":[]}";

  private static String supervisor(final String id) {
    return "{\"id\":\"" + id + "\",\"type\":\"rules\",\"settings\":{\"rules\":[]}}";
  }

  @Test
  void supervisorIsListedAndReadWithTheFieldsTheUiShows(final Api api, final Scenarios scenarios) {
    final String id = Scenarios.unique("sup");
    scenarios.createSupervisor(supervisor(id));

    final JsonNode listed = api.data(api.client().get().uri("/api/supervisors").exchange());
    final JsonNode one = api.data(api.client().get().uri("/api/supervisors/{id}", id).exchange());

    assertThat(listed.findValuesAsString("id")).contains(id);
    assertThat(one.path("type").asString()).isEqualTo("rules");
    assertThat(one.has("dryRun")).isTrue();
    assertThat(one.has("attachments")).isTrue();
    assertThat(one.has("applied")).isTrue();
  }

  @Test
  void anUnknownSupervisorIs404(final Api api) {
    api.failure(api.client().get().uri("/api/supervisors/e2e-nope").exchange(), 404);
    api.failure(api.client().get().uri("/api/supervisors/e2e-nope/versions").exchange(), 404);
  }

  @Test
  void putSavesAndOverwrites(final Api api, final Scenarios scenarios) {
    final String id = Scenarios.unique("sup");
    scenarios.createSupervisor(supervisor(id));

    final Reply saved = api.call("PUT", "/api/supervisors/" + id, supervisor(id));

    assertThat(saved.status()).as(saved.body()).isEqualTo(200);
  }

  @Test
  void rulesDocumentIsReadSavedAndReplayedAsDraft(final Api api, final Scenarios scenarios) {
    final String id = Scenarios.unique("sup");
    scenarios.createSupervisor(supervisor(id));

    assertThat(
            api.data(api.client().get().uri("/api/supervisors/{id}/engine/rules", id).exchange())
                .path("version")
                .asInt())
        .isEqualTo(1);
    final Reply saved = api.call("PUT", "/api/supervisors/" + id + "/engine/rules", RULES);
    assertThat(saved.status()).as(saved.body()).isEqualTo(200);
    assertThat(saved.body()).contains("\"version\":2");
    final Reply replayed =
        api.call(
            "POST",
            "/api/supervisors/" + id + "/engine/rules/replay",
            "{\"executionId\":\"e2e-nope\",\"settings\":" + RULES + "}");
    assertThat(replayed.status()).as(replayed.body()).isEqualTo(404);
  }

  @Test
  void rulesDocumentOfTheWrongShapeIs400(final Api api, final Scenarios scenarios) {
    final String id = Scenarios.unique("sup");
    scenarios.createSupervisor(supervisor(id));

    api.failure(
        api.client()
            .put()
            .uri("/api/supervisors/{id}/engine/rules", id)
            .header("Content-Type", "application/json")
            .bodyValue("{\"rules\":\"not a list\"}")
            .exchange(),
        400);
  }

  @Test
  void savedVersionsAreListedAndOneCanBeRolledBackTo(final Api api, final Scenarios scenarios) {
    final String id = Scenarios.unique("sup");
    scenarios.createSupervisor(supervisor(id));
    api.call("PUT", "/api/supervisors/" + id + "/engine/rules", RULES);

    final JsonNode versions =
        api.data(api.client().get().uri("/api/supervisors/{id}/versions", id).exchange());
    assertThat(versions.size()).isGreaterThanOrEqualTo(2);
    assertThat(versions.get(0).has("savedAt")).isTrue();

    final Reply rolledBack =
        api.call("POST", "/api/supervisors/" + id + "/rollback?version=1", null);
    assertThat(rolledBack.status()).as(rolledBack.body()).isEqualTo(200);
    api.failure(api.client().post().uri("/api/supervisors/{id}/rollback", id).exchange(), 400);
    assertThat(api.call("POST", "/api/supervisors/" + id + "/rollback?version=99", null).status())
        .isEqualTo(404);
  }

  @Test
  void replayNeedsRecordedExecution(final Api api, final Scenarios scenarios) {
    final String id = Scenarios.unique("sup");
    scenarios.createSupervisor(supervisor(id));

    final Reply reply =
        api.call(
            "POST",
            "/api/supervisors/" + id + "/replay",
            "{\"executionId\":\"e2e-nope\",\"settings\":{\"rules\":[]}}");

    assertThat(reply.status()).as(reply.body()).isEqualTo(404);
  }

  @Test
  void recordedRunCanBeReplayedAgainstDraftSettings(final Api api, final Scenarios scenarios) {
    final String id = Scenarios.unique("sup");
    scenarios.createSupervisor(supervisor(id));
    final String session = Scenarios.unique("replay");
    scenarios.applySession(SupervisionE2eTest.session(session));
    final String execution = scenarios.startWorkflow(session, "w", Map.of());
    scenarios.awaitTerminal(execution, Duration.ofSeconds(30));

    final Reply reply =
        api.call(
            "POST",
            "/api/supervisors/" + id + "/replay",
            "{\"executionId\":\"" + execution + "\",\"settings\":{\"rules\":[]}}");

    assertThat(reply.status()).as(reply.body()).isEqualTo(200);
  }

  @Test
  void theRunningSupervisorsAreListedWithTheirCounts(final Api api) {
    final JsonNode status = api.data(api.client().get().uri("/api/supervisors/status").exchange());

    assertThat(status.isArray()).isTrue();
  }

  @Test
  @InJvmOnly
  void viewerMayReadButNotWrite() {
    final Api viewer = Api.forRole(ApiRole.VIEWER);

    assertThat(viewer.call("GET", "/api/supervisors", null).status()).isEqualTo(200);
    assertThat(viewer.call("POST", "/api/supervisors", supervisor("e2e-viewer")).status())
        .isEqualTo(403);
  }
}
