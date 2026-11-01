// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.model.control;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.input.Audience;
import com.infenia.yukta.plugin.input.InputKind;
import com.infenia.yukta.plugin.input.InputOption;
import com.infenia.yukta.plugin.input.InputRequest;
import com.infenia.yukta.plugin.input.InputRisk;
import com.infenia.yukta.plugin.input.InputState;
import com.infenia.yukta.service.input.InputTestSupport;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** A pending input is the redacted view of a request that a client lists. */
@NoArgsConstructor
class PendingInputTest {

  @Test
  void requestIsListedWithItsPromptOptionsAndVersionButNeverItsContext() {
    final InputRequest request =
        InputTestSupport.request(
            "r1",
            InputTestSupport.spec(
                "k",
                InputKind.CHOICE,
                InputRisk.MEDIUM,
                Audience.CURRENT_TIER,
                List.of(new InputOption("a"), new InputOption("b")),
                null,
                Map.of("secret-ish", "context")));

    final PendingInput pending = PendingInput.from(request);

    assertThat(pending.requestId()).isEqualTo("r1");
    assertThat(pending.sessionId()).isEqualTo("session");
    assertThat(pending.executionId()).isEqualTo("exec");
    assertThat(pending.workflowId()).isEqualTo("workflow");
    assertThat(pending.nodeId()).isEqualTo("node");
    assertThat(pending.kind()).isEqualTo(InputKind.CHOICE);
    assertThat(pending.prompt()).isEqualTo("Which way?");
    assertThat(pending.options()).extracting(InputOption::id).containsExactly("a", "b");
    assertThat(pending.risk()).isEqualTo(InputRisk.MEDIUM);
    assertThat(pending.tier()).isEqualTo("default");
    assertThat(pending.state()).isEqualTo(InputState.OPEN);
    assertThat(pending.version()).isZero();
    assertThat(pending.since()).isEqualTo(request.createdAt());
    assertThat(pending.deadline()).isEqualTo(request.deadline());
    assertThat(pending.toString()).doesNotContain("secret-ish");
  }
}
