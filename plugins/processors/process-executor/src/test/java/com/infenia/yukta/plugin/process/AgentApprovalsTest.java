// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.process;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.plugin.input.Audience;
import com.infenia.yukta.plugin.input.InputGateway;
import com.infenia.yukta.plugin.input.InputIdentity;
import com.infenia.yukta.plugin.input.InputRequestSpec;
import com.infenia.yukta.plugin.input.InputResponse;
import com.infenia.yukta.plugin.input.InputRisk;
import com.infenia.yukta.plugin.input.Responder;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

/** What is asked for a harness' tool use, and what is written back. */
@NoArgsConstructor
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class AgentApprovalsTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private final List<String> written = new CopyOnWriteArrayList<>();
  private final List<InputRequestSpec> asked = new CopyOnWriteArrayList<>();
  private final ProcessExecutorGateway.StdinWriter stdin =
      new ProcessExecutorGateway.StdinWriter() {
        @Override
        public void write(final String line) {
          written.add(line);
        }

        @Override
        public void close() {
          written.add("closed");
        }
      };

  private static AgentPreset.Interactive claude() {
    try {
      return new AgentHarnesses(JSON, "").get("claude-code").interactive();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private AgentApprovals approvals(final AgentPreset.Interactive talk, final InputGateway gateway) {
    return new AgentApprovals(
        talk,
        new AgentApprovals.Settings(InputRisk.LOW, Duration.ofSeconds(5), Audience.CURRENT_TIER),
        JSON,
        () -> gateway,
        new InputIdentity("s", "w", "e", "n"),
        DefaultMessage.create(UUID.randomUUID(), "in"),
        "claude-code",
        stdin);
  }

  private InputGateway answering(final String decision, final String why) {
    return spec -> {
      asked.add(spec);
      return Mono.just(
          new InputResponse(
              "req", decision, null, Map.of(), Responder.SYSTEM, why, null, Instant.now()));
    };
  }

  private static AgentOutput.Request request(final String description) {
    return new AgentOutput.Request("r1", "Bash", JSON.createObjectNode(), description);
  }

  @Test
  void approvalWithoutDescription_asksForTheToolOnly() {
    approvals(claude(), answering("approve", null)).handle(request(""));

    assertThat(asked).hasSize(1);
    assertThat(asked.getFirst().prompt()).isEqualTo("Agent (claude-code) wants to use Bash");
    assertThat(written).singleElement().asString().contains("\"behavior\":\"allow\"");
  }

  @Test
  void rejectionWithoutReason_isRefusedWithJustRejected() {
    approvals(claude(), answering("reject", null)).handle(request("list"));

    assertThat(written).singleElement().asString().contains("\"message\":\"Rejected\"");
  }

  @Test
  void inputSystemThatThrows_refusesTheTool() {
    final InputGateway broken =
        spec -> {
          throw new IllegalStateException("down");
        };

    approvals(claude(), broken).handle(request("list"));

    assertThat(written).singleElement().asString().contains("Not approved: down");
  }

  @Test
  void repliesKeepTemplateValuesThatAreNeitherTextNorObjects() throws IOException {
    final AgentPreset.Interactive talk =
        new AgentPreset.Interactive(
            List.of("x"),
            JSON.readTree("[1,\"{prompt}\"]"),
            Map.of(),
            "id",
            "tool",
            "input",
            "",
            JSON.readTree("{\"n\":1,\"k\":\"{id}\"}"),
            JSON.readTree("{\"n\":2}"));

    approvals(talk, answering("approve", null)).handle(request("list"));
    approvals(talk, answering("reject", null)).handle(request("list"));

    assertThat(written).containsExactly("{\"n\":1,\"k\":\"r1\"}", "{\"n\":2}");
    assertThat(AgentApprovals.userMessage(talk.userMessage(), "p", JSON))
        .isEqualTo("[1,\"{prompt}\"]");
  }

  @Test
  void disposeStopsWaitingForAnswers() {
    final AgentApprovals open = approvals(claude(), spec -> Mono.never());

    open.handle(request("list"));
    open.dispose();

    assertThat(written).isEmpty();
  }
}
