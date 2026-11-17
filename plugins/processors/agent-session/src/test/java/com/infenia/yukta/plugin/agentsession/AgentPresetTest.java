// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.agentsession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.infenia.yukta.plugin.agent.AgentProtocol;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Reading a harness preset from its JSON. */
@NoArgsConstructor
@SuppressWarnings({"PMD.TooManyMethods", "PMD.AvoidDuplicateLiterals"})
class AgentPresetTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private static ObjectNode claudeJson() throws IOException {
    try (var in = AgentPresetTest.class.getResourceAsStream("/agent-harnesses/claude-code.json")) {
      return (ObjectNode) JSON.readTree(in);
    }
  }

  @Test
  void presetWithoutId_isRefused() throws IOException {
    final ObjectNode root = claudeJson();
    root.remove("id");

    assertThatThrownBy(() -> AgentPreset.parse(root))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("agent harness preset: 'id' is required");
  }

  @Test
  void nullInteractiveBlock_meansTheHarnessCannotConverse() throws IOException {
    final ObjectNode root = claudeJson();
    root.set("interactive", JSON.nullNode());

    assertThat(AgentPreset.parse(root).interactive()).isNull();
  }

  @Test
  void theRecordsCopyWhatTheyAreGiven() throws IOException {
    final var output = AgentPreset.parse(claudeJson()).output();

    assertThat(output.live()).isNotEmpty().isUnmodifiable();
    assertThat(output.resultWhen()).containsEntry("type", "result").isUnmodifiable();
  }

  @Test
  void harnessesLoadWithoutOperatorDirectory() throws IOException {
    assertThat(new AgentHarnesses(JSON, null).all().stream().map(AgentPreset::id).toList())
        .contains("claude-code", "codex")
        .isInstanceOf(List.class);
  }

  @Test
  void agentTerminalPresetsPasteThePromptAndClaudeWaitsForItsPromptBox() throws IOException {
    final Map<String, AgentPreset> shipped =
        new AgentHarnesses(JSON, null)
            .all().stream().collect(Collectors.toMap(AgentPreset::id, preset -> preset));

    for (final String id : List.of("claude-tui", "codex-tui", "antigravity-tui")) {
      final AgentPreset preset = shipped.get(id);
      assertThat(preset.protocol()).as(id).isEqualTo(AgentProtocol.PTY);
      assertThat(preset.command()).as(id).isNotEmpty();
      assertThat(preset.modelArgs()).as(id).containsExactly("--model", "{model}");
      assertThat(preset.pty().type()).as(id).isEqualTo("\u001b[200~{prompt}\u001b[201~");
      assertThat(preset.pty().submitDelayMs()).as(id).isPositive();
    }
    final AgentPreset.Pty claude = shipped.get("claude-tui").pty();
    // the screens of Claude Code 2.1: the prompt box, and its trust dialog
    assertThat(claude.readyPattern().matcher("────────────\n❯ Try \"fix lint errors\"").find())
        .isTrue();
    assertThat(claude.readyPattern().matcher("Security guide\n❯ No, exit").find()).isFalse();
    assertThat(claude.attentionPattern().matcher("Enter to confirm · Esc to cancel").find())
        .isTrue();
  }

  @Test
  void onlyCapabilitiesSetToTrueAreDeclared() throws IOException {
    final ObjectNode root = claudeJson();
    root.set("capabilities", JSON.readTree("{\"stream\":true,\"resume\":false}"));

    assertThat(AgentPreset.parse(root).capabilities()).containsExactly("stream");
  }

  @Test
  void presetWithoutTheProtocolIsJsonlAndTheFieldCanNameAnother() throws IOException {
    final ObjectNode root = claudeJson();
    assertThat(AgentPreset.parse(root).protocol()).isEqualTo(AgentProtocol.JSONL);

    root.put("protocol", "acp");
    assertThat(AgentPreset.parse(root).protocol()).isEqualTo(AgentProtocol.ACP);
    root.put("protocol", "PTY");
    assertThat(AgentPreset.parse(root).protocol()).isEqualTo(AgentProtocol.PTY);
    root.set("protocol", JSON.nullNode());
    assertThat(AgentPreset.parse(root).protocol()).isEqualTo(AgentProtocol.JSONL);
  }

  @Test
  void unknownProtocolIsRefusedNamingThePresetAndTheChoices() throws IOException {
    final ObjectNode root = claudeJson();
    root.put("protocol", "ftp");

    assertThatThrownBy(() -> AgentPreset.parse(root))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(
            "agent harness preset 'claude-code': 'protocol' must be jsonl, acp or pty, not 'ftp'");
  }

  @Test
  void liveRuleIsTheToolWhenItHasThePrefixAndTheMessageWhenItHasNone() throws IOException {
    final var live = AgentPreset.parse(claudeJson()).output().live();

    assertThat(live.get(0).kind()).isEqualTo(AgentPreset.Live.Kind.MESSAGE);
    assertThat(live.get(1).kind()).isEqualTo(AgentPreset.Live.Kind.TOOL);
  }

  @Test
  void liveRuleCanSayWhatItIsInsteadOfGuessingFromThePrefix() throws IOException {
    final ObjectNode root = claudeJson();
    final var rules = (tools.jackson.databind.node.ArrayNode) root.path("output").path("live");
    ((ObjectNode) rules.get(0)).put("kind", "thought");
    ((ObjectNode) rules.get(1)).put("kind", "message");
    ((ObjectNode) rules.get(1)).put("prefix", "");
    rules
        .addObject()
        .put("text", "x")
        .put("kind", "TOOL")
        .set("when", JSON.createObjectNode().put("type", "t"));

    final var live = AgentPreset.parse(root).output().live();

    assertThat(live.get(0).kind()).isEqualTo(AgentPreset.Live.Kind.THOUGHT);
    assertThat(live.get(1).kind()).isEqualTo(AgentPreset.Live.Kind.MESSAGE);
    assertThat(live.get(2).kind()).isEqualTo(AgentPreset.Live.Kind.TOOL);
  }

  @Test
  void nullKindFallsBackToTheDefaultAndAnUnknownKindIsRefused() throws IOException {
    final ObjectNode root = claudeJson();
    final var rules = (tools.jackson.databind.node.ArrayNode) root.path("output").path("live");
    ((ObjectNode) rules.get(1)).set("kind", JSON.nullNode());
    assertThat(AgentPreset.parse(root).output().live().get(1).kind())
        .isEqualTo(AgentPreset.Live.Kind.TOOL);

    ((ObjectNode) rules.get(1)).put("kind", "banner");
    assertThatThrownBy(() -> AgentPreset.parse(root))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(
            "agent harness preset: 'live.kind' must be message, thought or tool, not 'banner'");
  }

  @Test
  void interactiveBlockSaysWhetherTheHarnessSteersAndHowItIsInterrupted() throws IOException {
    final var root = claudeJson();
    final var talk = AgentPreset.parse(root).interactive();
    assertThat(talk.steer()).isTrue();
    assertThat(talk.canInterrupt()).isTrue();

    final var plain = (ObjectNode) claudeJson().get("interactive");
    plain.remove("steer");
    plain.remove("interrupt");
    final var without = AgentPreset.parse(claudeJson().set("interactive", plain)).interactive();
    assertThat(without.steer()).isFalse();
    assertThat(without.canInterrupt()).isFalse();

    plain.set("interrupt", JSON.nullNode());
    assertThat(
            AgentPreset.parse(claudeJson().set("interactive", plain)).interactive().canInterrupt())
        .isFalse();
  }

  @Test
  void anAcpPresetConversesAndNamesItsAuthMethod() throws IOException {
    final AgentPreset acp =
        AgentPreset.parse(
            JSON.readTree(
                "{\"id\":\"a\",\"protocol\":\"acp\",\"command\":[\"a\"],"
                    + "\"acp\":{\"authMethod\":\"k\"}}"));
    final AgentPreset plain =
        AgentPreset.parse(
            JSON.readTree("{\"id\":\"a\",\"protocol\":\"acp\",\"acp\":{\"authMethod\":null}}"));
    final AgentPreset blank =
        AgentPreset.parse(JSON.readTree("{\"id\":\"b\",\"acp\":{\"authMethod\":\" \"}}"));

    assertThat(acp.authMethod()).isEqualTo("k");
    assertThat(acp.converses()).isTrue();
    assertThat(plain.authMethod()).isNull();
    assertThat(blank.authMethod()).isNull();
    assertThat(blank.converses()).isFalse();
  }

  @Test
  void terminalBlockReadsItsPatternsAndRefusesBadOne() throws IOException {
    final ObjectNode root = claudeJson();
    root.set("pty", JSON.readTree("{\"readyPattern\":\"\",\"donePattern\":null}"));
    assertThat(AgentPreset.parse(root).pty().readyPattern()).isNull();
    assertThat(AgentPreset.parse(root).pty().donePattern()).isNull();

    root.set("pty", JSON.readTree("{\"attentionPattern\":\"(\"}"));
    assertThatThrownBy(() -> AgentPreset.parse(root))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("'attentionPattern' is not a regular expression");

    root.set("pty", JSON.nullNode());
    assertThat(AgentPreset.parse(root).pty()).isNull();
  }
}
