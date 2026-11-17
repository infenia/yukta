// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.e2e.support.Api;
import com.infenia.yukta.e2e.support.Api.Reply;
import com.infenia.yukta.e2e.support.E2eTest;
import com.infenia.yukta.e2e.support.Scenarios;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** The supervisor catalog and management API against the real application. */
@E2eTest
@NoArgsConstructor
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class SupervisorApiE2eTest {

  @Test
  void catalogListsEveryShippedTypeWithItsSchema(final Api api) {
    final Reply list = api.call("GET", "/api/supervisor-engines", null);
    assertThat(list.status()).isEqualTo(200);
    assertThat(list.body()).contains("\"rules\"", "\"workflow\"", "\"http\"");
    assertThat(list.body()).doesNotContain("model-control-plane", "failure-guard");

    final Reply one = api.call("GET", "/api/supervisor-engines/rules", null);
    assertThat(one.status()).isEqualTo(200);
    assertThat(one.body()).contains("configSchema", "commonSchema", "exampleSettings", "rules");
    assertThat(api.call("GET", "/api/supervisor-engines/nope", null).status()).isEqualTo(404);
    assertThat(api.call("GET", "/api/supervisor-types", null).status()).isEqualTo(404);
  }

  @Test
  void supervisorIsValidatedCreatedAssignedLockedAndRemoved(final Api api) {
    final String id = Scenarios.unique("sup");
    final String body = "{\"id\":\"" + id + "\",\"type\":\"rules\",\"settings\":{\"rules\":[]}}";

    final Reply bad =
        api.call(
            "POST", "/api/supervisors/validate", "{\"type\":\"rules\",\"settings\":{\"bogus\":1}}");
    assertThat(bad.status()).as(bad.body()).isEqualTo(200);
    assertThat(bad.body()).contains("\"valid\":false", "settings.bogus");

    final Reply refused =
        api.call(
            "POST",
            "/api/supervisors",
            "{\"id\":\"x" + id + "\",\"type\":\"rules\",\"settings\":{\"bogus\":1}}");
    assertThat(refused.status()).isEqualTo(400);
    assertThat(refused.body()).contains("settings.bogus");

    assertThat(api.call("POST", "/api/supervisors", body).status()).isEqualTo(201);
    assertThat(api.call("POST", "/api/supervisors", body).status()).isEqualTo(409);

    final String owns = "[{\"target\":\"session:s1\",\"locked\":true}]";
    assertThat(api.call("POST", "/api/supervisors/" + id + "/assignments/check", owns).body())
        .contains("\"valid\":true");
    assertThat(api.call("PUT", "/api/supervisors/" + id + "/assignments", owns).status())
        .isEqualTo(200);
    final Reply effective =
        api.call("GET", "/api/supervisors/assignments?session=s1&source=MANUAL", null);
    assertThat(effective.body()).contains("\"supervisorId\":\"" + id, "\"source\":\"MANUAL\"");
    assertThat(api.call("GET", "/api/supervisors/" + id + "/assignments", null).body())
        .contains("\"locked\":true");
    assertThat(api.call("GET", "/api/supervisors/status", null).status()).isEqualTo(200);
    assertThat(api.call("GET", "/api/supervisors/decisions?supervisor=" + id, null).status())
        .isEqualTo(200);

    assertThat(api.call("DELETE", "/api/supervisors/" + id, null).status()).isEqualTo(409);
    // a locked assignment stays unless the request asks to unlock
    assertThat(api.call("PUT", "/api/supervisors/" + id + "/assignments", "[]").status())
        .isEqualTo(409);
    assertThat(
            api.call("PUT", "/api/supervisors/" + id + "/assignments?unlock=true", "[]").status())
        .isEqualTo(200);
    assertThat(api.call("DELETE", "/api/supervisors/" + id, null).status()).isEqualTo(200);
    assertThat(api.call("GET", "/api/supervisors/" + id, null).status()).isEqualTo(404);
  }

  @Test
  void theOldProfileAndBindingPathsAreGone(final Api api) {
    assertThat(api.call("GET", "/api/supervisors/profiles", null).status()).isEqualTo(404);
    assertThat(api.call("GET", "/api/supervisors/bindings", null).status()).isEqualTo(404);
    assertThat(api.call("GET", "/api/supervisors/types", null).status()).isEqualTo(404);
    assertThat(api.call("POST", "/api/supervisors/attachments", "{}").status()).isEqualTo(405);
  }

  private static String session(final String id, final String sessionRefs, final String flowRefs) {
    return "{\"sessionId\":\""
        + id
        + "\",\"name\":\"n\",\"description\":\"d\",\"initiator\":\"t\","
        + "\"projectPath\":\"/tmp\",\"assignments\":"
        + sessionRefs
        + ",\"workflows\":{\"flow\":{\"workflowId\":\"flow\",\"description\":\"d\","
        + "\"nodes\":[{\"nodeId\":\"build.a\",\"type\":\"API_TRIGGER\",\"config\":{}},"
        + "{\"nodeId\":\"end\",\"type\":\"CONSOLE_TERMINAL\",\"config\":{}}],"
        + "\"edges\":[{\"source\":\"build.a\",\"target\":\"end\"}],\"assignments\":"
        + flowRefs
        + "}}}";
  }

  @Test
  void supervisorsDeclaredInSessionAreAttachedFollowTheDefinitionAndBlockDeletion(final Api api) {
    final String supervisor = Scenarios.unique("decl");
    final String sessionId = Scenarios.unique("declsession");
    assertThat(
            api.call(
                    "POST",
                    "/api/supervisors",
                    "{\"id\":\""
                        + supervisor
                        + "\",\"type\":\"rules\","
                        + "\"settings\":{\"rules\":[]}}")
                .status())
        .isEqualTo(201);

    final String declared =
        session(
            sessionId,
            "[{\"supervisor\":\"" + supervisor + "\"}]",
            "[{\"supervisor\":\"" + supervisor + "\",\"nodes\":[\"build.*\"]}]");
    final Reply saved = api.call("POST", "/api/sessions", declared);
    assertThat(saved.status()).as(saved.body()).isEqualTo(200);

    final String listed =
        api.call(
                "GET",
                "/api/supervisors/assignments?session=" + sessionId + "&source=DEFINITION",
                null)
            .body();
    assertThat(listed)
        .contains("session:" + sessionId, "workflow:" + sessionId + "/flow//build.*", "def-");
    assertThat(api.call("GET", "/api/sessions/" + sessionId + "/config", null).body())
        .contains("\"assignments\":[{\"supervisor\":\"" + supervisor + "\"");
    final Reply blocked = api.call("DELETE", "/api/supervisors/" + supervisor, null);
    assertThat(blocked.status()).isEqualTo(409);
    assertThat(blocked.body()).contains("session " + sessionId, "workflow " + sessionId + "/flow");
    // setting what the supervisor owns by hand leaves what definitions declare alone
    assertThat(api.call("PUT", "/api/supervisors/" + supervisor + "/assignments", "[]").status())
        .isEqualTo(200);
    assertThat(api.call("GET", "/api/supervisors/" + supervisor + "/assignments", null).body())
        .contains("def-");

    final Reply unknown =
        api.call("POST", "/api/sessions", session(sessionId, "[{\"supervisor\":\"nope\"}]", "[]"));
    assertThat(unknown.status()).isEqualTo(400);
    assertThat(unknown.body()).contains("assignments[0].supervisor");

    assertThat(api.call("POST", "/api/sessions", session(sessionId, "[]", "[]")).status())
        .isEqualTo(200);
    assertThat(api.call("GET", "/api/supervisors/assignments?session=" + sessionId, null).body())
        .doesNotContain("def-");
    assertThat(api.call("DELETE", "/api/sessions/" + sessionId, null).status()).isEqualTo(204);
    assertThat(api.call("DELETE", "/api/supervisors/" + supervisor, null).status()).isEqualTo(200);
  }
}
