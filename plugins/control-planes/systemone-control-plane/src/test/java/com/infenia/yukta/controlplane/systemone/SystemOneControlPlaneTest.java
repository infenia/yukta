// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controlplane.systemone;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.control.ControlDecision;
import com.infenia.yukta.plugin.control.ControlDecision.Source;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

/** Tests for SystemOneControlPlane against a local JDK HTTP server posing as Ollama. */
@NoArgsConstructor
@SuppressWarnings({"PMD.TooManyMethods", "PMD.LawOfDemeter"})
class SystemOneControlPlaneTest {

  /** The answer that pauses. */
  private static final String PAUSE = "pause";

  /** Last request body the stub received. */
  private final AtomicReference<String> received = new AtomicReference<>();

  /** What the stub answers with. */
  private final AtomicReference<String> reply = new AtomicReference<>("");

  /** HTTP status of the stub. */
  private final AtomicInteger status = new AtomicInteger(200);

  /** Delay before the stub answers, in milliseconds. */
  private final AtomicLong delayMillis = new AtomicLong();

  /** How long the plugin waits; generous so a cold JVM does not trip it. */
  private Duration timeout = Duration.ofSeconds(5);

  /** Stub Ollama. */
  private HttpServer server;

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/v1/systemone",
        exchange -> {
          received.set(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          try {
            Thread.sleep(delayMillis.get());
          } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          final byte[] body = reply.get().getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(status.get(), body.length == 0 ? -1 : body.length);
          if (body.length > 0) {
            exchange.getResponseBody().write(body);
          }
          exchange.close();
        });
    server.start();
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  private SystemOneControlPlane plane() {
    return new SystemOneControlPlane(
        WebClient.builder(),
        "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort(),
        "nimble",
        0.6,
        timeout);
  }

  private static String answer(final String choice, final double probability) {
    return "{\"model\":\"nimble\",\"answers\":{\"action\":{\"type\":\"choice\",\"choice\":\""
        + choice
        + "\",\"probabilities\":{\""
        + choice
        + "\":"
        + probability
        + "},\"confidence\":0.01}}}";
  }

  private static ExecutionEvent failure() {
    return new ExecutionEvent.NodeStatusChanged(
        "e1", "wf", "n1", "HTTP_REQUEST", "FAILURE", Map.of("code", 500), Instant.now());
  }

  @Test
  void id_isSystemOneControlPlane() {
    assertThat(plane().id()).isEqualTo("systemone-control-plane");
  }

  @Test
  void decide_pause_probable_pausesAndDescribesEventInWords() {
    reply.set(answer(PAUSE, 0.93));

    final ControlDecision decision = plane().decide(failure()).block();

    assertThat(decision.source()).isEqualTo(Source.MODEL);
    assertThat(decision.confidence()).isEqualTo(0.93);
    assertThat(decision.commands())
        .containsExactly(new ExecutionControlCommand.PauseWorkflowCommand("e1"));
    assertThat(received.get())
        .contains("\"model\":\"nimble\"", "\"type\":\"choice\"")
        .contains(
            "Step HTTP_REQUEST changed status to FAILURE. Details reported by the step: code=500.");
  }

  @Test
  void decide_stop_neverStops() {
    reply.set(answer("stop", 0.99));

    assertThat(plane().decide(failure()).block().commands()).isEmpty();
  }

  @Test
  void decide_continue_noCommands() {
    reply.set(answer("continue", 0.99));

    assertThat(plane().decide(failure()).block().commands()).isEmpty();
  }

  @Test
  void decide_improbablePause_noCommands() {
    reply.set(answer(PAUSE, 0.5));

    assertThat(plane().decide(failure()).block().commands()).isEmpty();
  }

  @Test
  void decide_modelNotDownloaded_noOpinion() {
    status.set(404);
    reply.set("{\"error\":\"model not found\"}");

    assertThat(plane().decide(failure()).block().commands()).isEmpty();
  }

  @Test
  void decide_garbageReply_noOpinion() {
    reply.set("not json");

    assertThat(plane().decide(failure()).block().commands()).isEmpty();
  }

  @Test
  void decide_slowServer_noOpinion() {
    reply.set(answer(PAUSE, 0.99));
    delayMillis.set(1500);
    timeout = Duration.ofMillis(300);

    assertThat(plane().decide(failure()).block().commands()).isEmpty();
  }

  @Test
  void decide_logLine_skippedWithoutCallingModel() {
    final var line = new ExecutionEvent.LogLine("e1", "n1", "hello", Instant.now());

    assertThat(plane().decide(line).block().commands()).isEmpty();
    assertThat(received.get()).isNull();
  }

  @Test
  void decide_workflowStatus_asksModel() {
    reply.set(answer(PAUSE, 0.9));
    final var event =
        new ExecutionEvent.WorkflowStatusChanged("e1", "wf", "RUNNING", Instant.now());

    assertThat(plane().decide(event).block().commands()).hasSize(1);
    assertThat(received.get()).contains("The workflow run changed status to RUNNING.");
  }

  @Test
  void decide_noMetadata_saysNone() {
    reply.set(answer("continue", 0.9));
    final var event =
        new ExecutionEvent.NodeStatusChanged(
            "e1", "wf", "n1", "HTTP_REQUEST", "RUNNING", Map.of(), Instant.now());

    plane().decide(event).block();

    assertThat(received.get()).contains("Details reported by the step: none.");
  }

  @Test
  void decide_hugeMetadata_stateIsCut() {
    reply.set(answer("continue", 0.9));
    final var event =
        new ExecutionEvent.NodeStatusChanged(
            "e1",
            "wf",
            "n1",
            "HTTP_REQUEST",
            "FAILURE",
            Map.of("body", "x".repeat(70_000)),
            Instant.now());

    plane().decide(event).block();

    assertThat(received.get()).hasSizeLessThan(12_000);
  }
}
