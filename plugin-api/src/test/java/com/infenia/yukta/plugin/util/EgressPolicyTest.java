// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

/** Tests for {@link EgressPolicy}. */
@NoArgsConstructor
class EgressPolicyTest {

  /** Reactor context key of the workflow id. */
  private static final String WORKFLOW_ID = "workflowId";

  /** A URL on the host that only the deployment and session lists allow. */
  private static final String B_URL = "https://b.example.com/";

  /** Hosts the deployment allows. */
  private static final List<String> DEPLOYMENT = List.of("a.example.com", "b.example.com");

  /** Deployment, workflow {@code wf} and session {@code s1} lists. */
  private static final EgressPolicy POLICY =
      new EgressPolicy(
          HostAllowList.from(DEPLOYMENT),
          Map.of("wf", HostAllowList.from(List.of("a.example.com", "c.example.com"))),
          Map.of("s1", HostAllowList.from(List.of("b.example.com"))));

  private static String refusal(final String url, final Context ctx) {
    return POLICY.refusalFor(url, ctx);
  }

  @Test
  void open_allowsEveryHost() {
    assertThat(EgressPolicy.OPEN.refusalFor("https://anywhere.org/", Context.empty())).isNull();
  }

  @Test
  void deploymentList_appliesWithoutRun() {
    assertThat(refusal("https://a.example.com/x", Context.empty())).isNull();
    assertThat(refusal("https://evil.org/x", Context.empty()))
        .isEqualTo("Host 'evil.org' is not in yukta.egress.allowed-hosts");
  }

  @Test
  void workflowList_narrowsTheDeploymentList() {
    final Context ctx = Context.of(WORKFLOW_ID, "wf");
    assertThat(refusal("https://a.example.com/", ctx)).isNull();
    assertThat(refusal(B_URL, ctx))
        .isEqualTo("Host 'b.example.com' is not in yukta.egress.by-workflow.wf");
  }

  @Test
  void workflowList_cannotWidenTheDeploymentList() {
    assertThat(refusal("https://c.example.com/", Context.of(WORKFLOW_ID, "wf")))
        .isEqualTo("Host 'c.example.com' is not in yukta.egress.allowed-hosts");
  }

  @Test
  void sessionList_narrowsTheDeploymentList() {
    final Context ctx = Context.of("sessionId", "s1");
    assertThat(refusal(B_URL, ctx)).isNull();
    assertThat(refusal("https://a.example.com/", ctx))
        .isEqualTo("Host 'a.example.com' is not in yukta.egress.by-session.s1");
  }

  @Test
  void bothLayers_mustAgree() {
    final Context ctx = Context.of(WORKFLOW_ID, "wf", "sessionId", "s1");
    assertThat(refusal("https://a.example.com/", ctx)).contains("by-session.s1");
    assertThat(refusal(B_URL, ctx)).contains("by-workflow.wf");
  }

  @Test
  void otherWorkflowsAndSessions_haveOnlyTheDeploymentList() {
    final Context ctx = Context.of(WORKFLOW_ID, "other", "sessionId", "other");
    assertThat(refusal(B_URL, ctx)).isNull();
  }

  @Test
  void check_failsOrCompletesByTheContextOfTheCall() {
    StepVerifier.create(POLICY.check(B_URL).contextWrite(Context.of(WORKFLOW_ID, "wf")))
        .expectErrorMessage("Host 'b.example.com' is not in yukta.egress.by-workflow.wf")
        .verify();
    StepVerifier.create(POLICY.check(B_URL)).verifyComplete();
  }
}
