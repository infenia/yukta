// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.result;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

@NoArgsConstructor
@SuppressWarnings({"PMD.CommentRequired", "PMD.AvoidDuplicateLiterals"})
class ExecutionResultsTest {

  @Test
  void missingEventsBecomeAnEmptyList() {
    assertThat(new ExecutionResults("e1", false, null, false, 0, null).events()).isEmpty();
  }

  @Test
  void eventsAreCopied() {
    final List<ResultEvent> source = new ArrayList<>();
    final ExecutionResults results = new ExecutionResults("e1", false, null, false, 0, source);
    source.add(
        new ResultEvent(
            ResultEvent.Kind.RESULT, "t", 0, "x", null, false, java.time.Instant.now()));

    assertThat(results.events()).isEqualTo(List.of());
  }

  private static ExecutionResults withPayload() {
    final Map<String, Object> payload =
        Map.of(
            "exitCode",
            0,
            "stdout",
            "out",
            "stderr",
            "err",
            "results",
            List.of(Map.of("stdout", "inner", "success", true)));
    return new ExecutionResults(
        "e1",
        true,
        "SUCCESS",
        false,
        1,
        List.of(
            new ResultEvent(
                ResultEvent.Kind.RESULT, "t", 0, payload, null, false, java.time.Instant.now())));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> payloadOf(final ExecutionResults results) {
    return (Map<String, Object>) results.events().getFirst().payload();
  }

  @Test
  void outputIsLeftOutByDefault() {
    final Map<String, Object> payload = payloadOf(withPayload().withOutput(null));

    assertThat(payload).containsOnlyKeys("exitCode", "results");
    assertThat(payload.get("results")).isEqualTo(List.of(Map.of("success", true)));
  }

  @Test
  void onlyTheNamedFieldIsKept() {
    assertThat(payloadOf(withPayload().withOutput("stdout")))
        .containsKeys("stdout")
        .doesNotContainKey("stderr");
    assertThat(payloadOf(withPayload().withOutput("stderr")))
        .containsKeys("stderr")
        .doesNotContainKey("stdout");
  }

  @Test
  void bothFieldsAreKeptAndTheSourceIsUnchanged() {
    final ExecutionResults source = withPayload();

    assertThat(payloadOf(source.withOutput("stdout, STDERR,stdout")))
        .containsKeys("stdout", "stderr");
    assertThat(payloadOf(source.withOutput(""))).doesNotContainKeys("stdout", "stderr");
    assertThat(payloadOf(source)).containsKeys("stdout", "stderr");
  }

  @Test
  void nestingBeyondTheSearchDepthIsLeftAsIs() {
    Object deep = Map.of("stdout", "buried");
    for (int level = 0; level < 10; level++) {
      deep = Map.of("next", deep);
    }
    final ExecutionResults results =
        new ExecutionResults(
            "e1",
            true,
            "SUCCESS",
            false,
            1,
            List.of(
                new ResultEvent(
                    ResultEvent.Kind.RESULT, "t", 0, deep, null, false, java.time.Instant.now())));

    assertThat(results.withOutput(null).events().getFirst().payload()).isEqualTo(deep);
  }
}
