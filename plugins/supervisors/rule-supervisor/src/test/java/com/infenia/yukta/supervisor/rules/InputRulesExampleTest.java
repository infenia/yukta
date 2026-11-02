// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.supervisor.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.AnswerInputCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.EscalateInputCommand;
import com.infenia.yukta.plugin.control.ExecutionSupervisor;
import com.infenia.yukta.plugin.control.SupervisorProfile;
import com.infenia.yukta.plugin.input.InputRisk;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import tools.jackson.databind.json.JsonMapper;

/** The example profile in {@code examples/supervisor-profiles} loads and does what it says. */
@NoArgsConstructor
class InputRulesExampleTest {

  private static SupervisorProfile example() throws IOException {
    final Path file =
        Path.of(
            Objects.requireNonNull(
                System.getProperty("yukta.example.input-rules"),
                "the build passes the example path"));
    return JsonMapper.builder().build().readValue(Files.readString(file), SupervisorProfile.class);
  }

  @Test
  void exampleProfileApprovesLowRiskStagingAndEscalatesTheRest() throws IOException {
    final SupervisorProfile profile = example();
    final ExecutionSupervisor supervisor = new RuleSupervisorFactory().create(profile);

    final List<ExecutionControlCommand> commands =
        supervisor
            .supervise(
                Flux.just(
                    InputEvents.requested(InputRisk.LOW, Map.of("environment", "staging")),
                    InputEvents.requested(InputRisk.LOW, Map.of("environment", "production")),
                    InputEvents.requested(InputRisk.HIGH, Map.of("environment", "staging"))))
            .collectList()
            .block();

    assertThat(profile.type()).isEqualTo(RuleSupervisorFactory.PROFILE_TYPE);
    assertThat(((RuleSupervisor) supervisor).warning()).isEmpty();
    assertThat(commands).hasSize(3);
    assertThat(commands.getFirst())
        .isInstanceOfSatisfying(
            AnswerInputCommand.class,
            answer -> {
              assertThat(answer.decision()).isEqualTo("approve");
              assertThat(answer.rationale()).startsWith("rule approve-low-risk-staging: policy 7");
            });
    assertThat(commands.subList(1, 3))
        .allSatisfy(
            command ->
                assertThat(command)
                    .isInstanceOfSatisfying(
                        EscalateInputCommand.class,
                        escalate -> assertThat(escalate.advice()).contains("staging")));
  }
}
