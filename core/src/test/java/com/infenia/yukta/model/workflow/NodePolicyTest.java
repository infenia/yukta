// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.model.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import Duration;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

@NoArgsConstructor
@SuppressWarnings({"PMD.CommentRequired", "PMD.AvoidDuplicateLiterals", "PMD.TooManyMethods"})
class NodePolicyTest {

  private static Map<String, Object> engine(final Map<String, Object> engine) {
    return Map.of(NodePolicy.CONFIG_KEY, engine);
  }

  @Test
  void noEngineKey_isTheDefaultPolicy() {
    assertThat(NodePolicy.from(null)).isSameAs(NodePolicy.DEFAULT);
    assertThat(NodePolicy.from(Map.of("other", 1))).isSameAs(NodePolicy.DEFAULT);
    assertThat(NodePolicy.DEFAULT.perMessage()).isFalse();
  }

  @Test
  void fullPolicy_isParsed_caseInsensitively() {
    final NodePolicy policy =
        NodePolicy.from(
            engine(
                Map.of(
                    "retry", Map.of("maxAttempts", 4, "backoffMs", 100, "maxBackoffMs", 2000),
                    "onError", "route",
                    "trigger", "ALL_done")));

    assertThat(policy.retry().maxAttempts()).isEqualTo(4);
    assertThat(policy.retry().backoff()).isEqualTo(Duration.ofMillis(100));
    assertThat(policy.retry().maxBackoff()).isEqualTo(Duration.ofSeconds(2));
    assertThat(policy.onError()).isEqualTo(NodePolicy.OnError.ROUTE);
    assertThat(policy.trigger()).isEqualTo(NodePolicy.JoinRule.ALL_DONE);
    assertThat(policy.perMessage()).isTrue();
  }

  @Test
  void retryDefaults_applyWhenOnlyMaxAttemptsIsGiven() {
    final NodePolicy policy = NodePolicy.from(engine(Map.of("retry", Map.of())));

    assertThat(policy.retry().maxAttempts()).isEqualTo(3);
    assertThat(policy.retry().backoff()).isEqualTo(Duration.ofMillis(200));
    assertThat(policy.retry().maxBackoff()).isEqualTo(Duration.ofSeconds(10));
  }

  @Test
  void onErrorAlone_needsPerMessageProcessing() {
    assertThat(NodePolicy.from(engine(Map.of("onError", "CONTINUE"))).perMessage()).isTrue();
    assertThat(NodePolicy.from(engine(Map.of("trigger", "ALL_DONE"))).perMessage()).isFalse();
  }

  @Test
  void malformedPolicies_failWithPreciseMessage() {
    assertThatThrownBy(() -> NodePolicy.from(Map.of("engine", "nope")))
        .hasMessage("'engine' must be an object");
    assertThatThrownBy(() -> NodePolicy.from(engine(Map.of("retries", 3))))
        .hasMessageContaining("Unknown option 'retries'");
    assertThatThrownBy(() -> NodePolicy.from(engine(Map.of("onError", "EXPLODE"))))
        .hasMessageContaining("engine.onError");
    assertThatThrownBy(() -> NodePolicy.from(engine(Map.of("retry", "3"))))
        .hasMessage("'retry' must be an object");
    assertThatThrownBy(() -> NodePolicy.from(engine(Map.of("retry", Map.of("maxAttempts", 0)))))
        .hasMessageContaining("between 1 and 10");
    assertThatThrownBy(() -> NodePolicy.from(engine(Map.of("retry", Map.of("maxAttempts", 11)))))
        .hasMessageContaining("between 1 and 10");
    assertThatThrownBy(
            () ->
                NodePolicy.from(
                    engine(Map.of("retry", Map.of("backoffMs", 500, "maxBackoffMs", 100)))))
        .hasMessageContaining("backoffMs");
    assertThatThrownBy(() -> NodePolicy.from(engine(Map.of("retry", Map.of("maxAttempts", "x")))))
        .hasMessage("'engine.retry.maxAttempts' must be a number");
    assertThatThrownBy(() -> NodePolicy.from(engine(Map.of("retry", Map.of("speed", 1)))))
        .hasMessageContaining("Unknown option 'speed' in 'engine.retry'");
  }

  @Test
  void negativeBackoff_isRejected() {
    assertThatThrownBy(() -> NodePolicy.from(engine(Map.of("retry", Map.of("backoffMs", -1)))))
        .hasMessageContaining("backoffMs");
  }

  @Test
  void deadline_isParsed_andRejectedWhenNotPositiveOrFractional() {
    assertThat(NodePolicy.from(engine(Map.of("deadlineMs", 5000))).deadline())
        .isEqualTo(Duration.ofSeconds(5));
    assertThat(NodePolicy.DEFAULT.deadline()).isNull();
    assertThatThrownBy(() -> NodePolicy.from(engine(Map.of("deadlineMs", 0))))
        .hasMessageContaining("engine.deadlineMs must be positive");
    assertThatThrownBy(() -> NodePolicy.from(engine(Map.of("deadlineMs", 1.5))))
        .hasMessageContaining("engine.deadlineMs must be a whole number");
  }

  @Test
  void elementTimeout_rejectsFractionalMilliseconds() {
    assertThatThrownBy(() -> NodePolicy.from(engine(Map.of("elementTimeoutMs", 0.5))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("whole number");
  }

  @Test
  void retryClassLists_andFailureBudget_areParsed() {
    final NodePolicy policy =
        NodePolicy.from(
            engine(
                Map.of(
                    "onError", "ROUTE",
                    "retry",
                        Map.of(
                            "maxAttempts", 2,
                            "retryOn", java.util.List.of("IOException"),
                            "noRetryOn", java.util.List.of("SecurityException")),
                    "failureBudget", Map.of("maxFailures", 5, "windowMs", 60_000))));

    assertThat(policy.retry().retryOn()).containsExactly("IOException");
    assertThat(policy.retry().noRetryOn()).containsExactly("SecurityException");
    assertThat(policy.failureBudget())
        .isEqualTo(new NodePolicy.FailureBudget(5, Duration.ofMinutes(1)));
  }

  @Test
  void invalidRetryClassLists_areRejected() {
    assertThatThrownBy(
            () ->
                NodePolicy.from(
                    engine(
                        Map.of(
                            "retry",
                            Map.of(
                                "retryOn",
                                java.util.List.of("A"),
                                "noRetryOn",
                                java.util.List.of("A"))))))
        .hasMessageContaining("must not share");
    assertThatThrownBy(
            () -> NodePolicy.from(engine(Map.of("retry", Map.of("retryOn", "IOException")))))
        .hasMessageContaining("list of class names");
    assertThatThrownBy(
            () ->
                NodePolicy.from(engine(Map.of("retry", Map.of("noRetryOn", java.util.List.of(1))))))
        .hasMessageContaining("list of class names");
  }

  @Test
  void failureBudget_needsAnOnErrorThatKeepsTheNodeGoing_andValidNumbers() {
    final Map<String, Object> budget = Map.of("maxFailures", 1, "windowMs", 1000);
    assertThatThrownBy(() -> NodePolicy.from(engine(Map.of("failureBudget", budget))))
        .hasMessageContaining("CONTINUE or ROUTE");
    assertThatThrownBy(
            () ->
                NodePolicy.from(
                    engine(Map.of("onError", "CONTINUE", "failureBudget", Map.of("windowMs", 5)))))
        .hasMessageContaining("maxFailures is required");
    assertThatThrownBy(
            () ->
                NodePolicy.from(
                    engine(
                        Map.of(
                            "onError",
                            "CONTINUE",
                            "failureBudget",
                            Map.of("maxFailures", 1, "windowMs", 0)))))
        .hasMessageContaining("windowMs must be positive");
    assertThatThrownBy(
            () ->
                NodePolicy.from(
                    engine(
                        Map.of(
                            "onError",
                            "CONTINUE",
                            "failureBudget",
                            Map.of("maxFailures", 1, "windowMs", 5, "x", 1)))))
        .hasMessageContaining("Unknown option 'x'");
  }
}
