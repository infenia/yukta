// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.agentsession.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.plugin.agentsession.AgentPreset;
import com.infenia.yukta.plugin.input.InputIdentity;
import com.infenia.yukta.plugin.process.ProcessExecutionSpec;
import com.infenia.yukta.plugin.process.ProcessExecutorGateway;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

/**
 * The adapter with the real {@code yukta-cli pty-host} running {@code sh}. Skipped when the binary
 * is not built ({@code make build} in {@code cli/}, or {@code YUKTA_CLI} names one).
 */
@NoArgsConstructor
class PtyHostRealTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private static Path binary() {
    final String named = System.getenv("YUKTA_CLI");
    if (named != null && !named.isBlank()) {
      return Path.of(named);
    }
    final String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    final String arch = System.getProperty("os.arch", "").contains("aarch64") ? "arm64" : "amd64";
    return Path.of(
            "../../../cli/build/yukta-cli-"
                + (os.startsWith("mac") ? "darwin" : "linux")
                + "-"
                + arch)
        .toAbsolutePath()
        .normalize();
  }

  @Test
  void programInTheHostEndsWithItsExitCode_andItsOutputIsInTheConversation() {
    final Path host = binary();
    assumeTrue(Files.isExecutable(host), "yukta-cli is not built: " + host);
    final ProcessExecutorGateway gateway = new ProcessExecutorGateway();
    // "sh" is allowed, "yukta-cli" is not: the host only launches the program
    ReflectionTestUtils.setField(gateway, "allowedCommands", java.util.Set.of("sh"));
    final RecordingConversation conversation = new RecordingConversation();
    final AgentPreset preset =
        AgentPreset.parse(JSON.readTree("{\"id\":\"terminal\",\"protocol\":\"pty\",\"pty\":{}}"));
    final AgentRun run =
        new AgentRun(
            DefaultMessage.create(UUID.randomUUID(), "in"),
            preset,
            ProcessExecutionSpec.builder()
                .launcher(
                    List.of(host.toString(), "pty-host", "--cols", "80", "--rows", "24", "--"))
                .command(List.of("sh", "-c", "echo hello-from-pty; exit 3"))
                .timeoutSeconds(20)
                .build(),
            "",
            null,
            null,
            20,
            conversation,
            (stream, line, level) -> {},
            new InputIdentity("s", "w", "e", "n"),
            false,
            Duration.ofSeconds(30),
            () -> {},
            null,
            true);

    final Map<String, Object> outcome =
        new PtyAdapter(gateway, JSON, Duration.ofSeconds(30))
            .run(run)
            .block(Duration.ofSeconds(20));

    assertThat(outcome).containsEntry("status", "failure").containsEntry("exitCode", 3);
    assertThat(String.valueOf(outcome.get("text"))).contains("hello-from-pty");
    assertThat(conversation.calls()).anyMatch(c -> c.startsWith("emit TERMINAL"));
  }
}
