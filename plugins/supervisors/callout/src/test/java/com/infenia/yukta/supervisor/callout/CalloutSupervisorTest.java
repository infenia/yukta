// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.supervisor.callout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.infenia.yukta.plugin.control.ControlRequest;
import com.infenia.yukta.plugin.control.Decision;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.PauseNodeCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.PauseWorkflowCommand;
import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.plugin.control.Supervisor;
import com.infenia.yukta.plugin.control.SupervisorCommonSettings;
import com.infenia.yukta.plugin.core.ConfigField;
import com.infenia.yukta.plugin.core.LogLevel;
import com.infenia.yukta.plugin.util.EgressPolicy;
import com.infenia.yukta.plugin.util.HostAllowList;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/** The webhook against a real local server. */
@NoArgsConstructor
@SuppressWarnings({
  "PMD.AvoidDuplicateLiterals",
  "PMD.TooManyMethods",
  "PMD.ExcessiveImports",
  "PMD.FieldDeclarationsShouldBeAtStartOfClass",
  "PMD.SignatureDeclareThrowsException",
  "PMD.AvoidUsingHardCodedIP",
  "PMD.UseConcurrentHashMap"
})
class CalloutSupervisorTest {

  private static final String SECRET = "s3cr3t-value";

  /** A reply the server gives: status, body, optional redirect. */
  private record Reply(int status, String body, String location) {}

  private HttpServer server;

  private final AtomicReference<Reply> reply = new AtomicReference<>(new Reply(200, "{}", null));

  private final List<String> bodies = new CopyOnWriteArrayList<>();

  private final List<String> secrets = new CopyOnWriteArrayList<>();

  private final List<String> paths = new CopyOnWriteArrayList<>();

  private long delayMillis;

  private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/",
        exchange -> {
          paths.add(exchange.getRequestURI().getPath());
          bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          secrets.add(String.valueOf(exchange.getRequestHeaders().getFirst("X-Secret")));
          try {
            Thread.sleep(delayMillis);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          final Reply r = reply.get();
          if (r.location() != null) {
            exchange.getResponseHeaders().add("Location", r.location());
          }
          final byte[] bytes = r.body().getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(r.status(), bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    final Logger logger = (Logger) LoggerFactory.getLogger(CalloutSupervisor.class);
    logs.start();
    logger.addAppender(logs);
  }

  @AfterEach
  void shutDown() {
    server.stop(0);
    ((Logger) LoggerFactory.getLogger(CalloutSupervisor.class)).detachAppender(logs);
  }

  private String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
  }

  private static Egress allowLoopback() {
    return new Egress(EgressPolicy.OPEN, HostAllowList.from(List.of("127.0.0.1")));
  }

  private CalloutSupervisor.Config config(final boolean review, final boolean deny, final long ms) {
    return new CalloutSupervisor.Config(
        url(),
        Map.of("X-Secret", SECRET),
        Set.of("NodeStatusChanged"),
        Duration.ofMillis(ms),
        review,
        deny,
        2);
  }

  private CalloutSupervisor hook(final Set<String> allowed, final CalloutSupervisor.Config config) {
    return new CalloutSupervisor("hook", config, allowed, allowLoopback());
  }

  private static ExecutionEvent node(final String status) {
    return new ExecutionEvent.NodeStatusChanged(
        "e1",
        "s",
        "w",
        "n",
        "HTTP_REQUEST",
        status,
        Map.of("apiKey", "k-123", "code", 500),
        Instant.EPOCH);
  }

  private static ControlRequest stopRequest() {
    return new ControlRequest(
        new ExecutionControlCommand.StopWorkflowCommand("e1", "r", null), "ai", "s", "w", null);
  }

  private List<ExecutionControlCommand> run(
      final CalloutSupervisor hook, final ExecutionEvent... events) {
    return hook.supervise(Flux.just(events)).collectList().block(Duration.ofSeconds(15));
  }

  @Test
  void postsListedEventsMasksSecretsAndSendsOnlyGrantedCommands() {
    reply.set(
        new Reply(
            200,
            "{\"commands\":[{\"type\":\"PauseWorkflow\"},{\"type\":\"StopWorkflow\"},"
                + "{\"type\":\"PauseNode\",\"nodeId\":\"n\"}]}",
            null));
    final CalloutSupervisor hook = hook(Set.of("PauseWorkflowCommand"), config(false, true, 3000));
    hook.bindTo("att-1");

    final List<ExecutionControlCommand> commands =
        run(
            hook,
            node("FAILURE"),
            new ExecutionEvent.LogLine("e1", "s", "w", "n", "x", LogLevel.INFO, Instant.EPOCH));

    assertThat(commands).containsExactly(new PauseWorkflowCommand("e1"));
    assertThat(bodies).hasSize(1);
    assertThat(bodies.getFirst())
        .contains("\"kind\":\"event\"", "\"attachmentId\":\"att-1\"", "\"supervisorId\":\"hook\"")
        .contains("NodeStatusChanged", "\"apiKey\":\"***\"")
        .doesNotContain("k-123");
    assertThat(secrets).containsExactly(SECRET);
    assertThat(hook.id()).isEqualTo("att-1");
  }

  @Test
  void sendsNothingWhenNoCommandIsGranted() {
    reply.set(new Reply(200, "{\"commands\":[{\"type\":\"PauseWorkflow\"}]}", null));

    assertThat(run(hook(Set.of(), config(false, true, 3000)), node("FAILURE"))).isEmpty();
  }

  @Test
  void takesAtMostTenCommandsFromOneReply() {
    final List<String> entries = new ArrayList<>();
    for (int i = 0; i < 12; i++) {
      entries.add("{\"type\":\"PauseNode\",\"nodeId\":\"n" + i + "\"}");
    }
    reply.set(new Reply(200, "{\"commands\":[" + String.join(",", entries) + "]}", null));

    final List<ExecutionControlCommand> commands =
        run(hook(Set.of("PauseNodeCommand"), config(false, true, 3000)), node("FAILURE"));

    assertThat(commands)
        .hasSize(CalloutSupervisor.MAX_COMMANDS)
        .allMatch(c -> c instanceof PauseNodeCommand);
  }

  @Test
  void errorsTimeoutsAndBadRepliesAreNoOpinionForEvents() {
    final CalloutSupervisor quick = hook(Set.of("PauseWorkflowCommand"), config(false, true, 3000));
    for (final Reply bad :
        List.of(
            new Reply(500, "{\"commands\":[{\"type\":\"PauseWorkflow\"}]}", null),
            new Reply(200, "not json", null),
            new Reply(200, "[1,2]", null))) {
      reply.set(bad);
      assertThat(run(quick, node("FAILURE"))).isEmpty();
    }
    reply.set(new Reply(200, "{\"commands\":[{\"type\":\"PauseWorkflow\"}]}", null));
    delayMillis = 800;
    assertThat(run(hook(Set.of("PauseWorkflowCommand"), config(false, true, 200)), node("FAILURE")))
        .isEmpty();
  }

  @Test
  void reviewMapsTheVerdict() {
    final CalloutSupervisor hook = hook(Set.of(), config(true, true, 3000));

    reply.set(new Reply(200, "{\"decision\":\"allow\"}", null));
    assertThat(hook.review(stopRequest()).block()).isEqualTo(Decision.ALLOW);
    reply.set(new Reply(200, "{\"decision\":\"deny\",\"reason\":\"no\"}", null));
    assertThat(hook.review(stopRequest()).block()).isEqualTo(Decision.deny("no"));
    reply.set(new Reply(200, "{\"decision\":\"deny\"}", null));
    assertThat(hook.review(stopRequest()).block().reason()).isEqualTo("refused by the webhook");
    reply.set(new Reply(200, "{\"decision\":\"requireApproval\",\"reason\":\"ask\"}", null));
    final Decision gated = hook.review(stopRequest()).block();
    assertThat(gated.requiresApproval()).isTrue();
    assertThat(gated.reason()).isEqualTo("ask");
    reply.set(new Reply(200, "{\"decision\":\"requireApproval\",\"reason\":\" \"}", null));
    assertThat(hook.review(stopRequest()).block().reason())
        .isEqualTo("approval required by the webhook");
    reply.set(
        new Reply(200, "{\"decision\":\"deny\",\"reason\":\"" + "x".repeat(500) + "\"}", null));
    assertThat(hook.review(stopRequest()).block().reason()).hasSize(200);
    assertThat(bodies.getLast()).contains("\"kind\":\"review\"", "StopWorkflowCommand");
  }

  @Test
  void reviewThatFailsFollowsReviewOnError() {
    reply.set(new Reply(500, "{}", null));
    assertThat(hook(Set.of(), config(true, true, 3000)).review(stopRequest()).block().allowed())
        .isFalse();
    assertThat(hook(Set.of(), config(true, false, 3000)).review(stopRequest()).block())
        .isEqualTo(Decision.ALLOW);
    reply.set(new Reply(200, "{\"decision\":\"maybe\"}", null));
    assertThat(hook(Set.of(), config(true, true, 3000)).review(stopRequest()).block().allowed())
        .isFalse();
  }

  @Test
  void withoutReviewNothingIsPosted() {
    assertThat(hook(Set.of(), config(false, true, 3000)).review(stopRequest()).block())
        .isEqualTo(Decision.ALLOW);
    assertThat(bodies).isEmpty();
  }

  @Test
  void loopbackTargetIsRefusedUnlessTheListNamesIt() {
    reply.set(new Reply(200, "{\"commands\":[{\"type\":\"PauseWorkflow\"}]}", null));
    final CalloutSupervisor closed =
        new CalloutSupervisor(
            "hook",
            config(true, true, 3000),
            Set.of("PauseWorkflowCommand"),
            new Egress(EgressPolicy.OPEN, HostAllowList.from(null)));

    assertThat(run(closed, node("FAILURE"))).isEmpty();
    assertThat(closed.review(stopRequest()).block().allowed()).isFalse();
    assertThat(bodies).isEmpty();
  }

  @Test
  void redirectsAreNotFollowed() {
    reply.set(new Reply(302, "", "/elsewhere"));

    assertThat(
            run(hook(Set.of("PauseWorkflowCommand"), config(false, true, 3000)), node("FAILURE")))
        .isEmpty();
    assertThat(paths).containsExactly("/hook");
  }

  @Test
  void headerSecretIsNeverLoggedOrShown() {
    reply.set(new Reply(500, "boom " + SECRET, null));
    final CalloutSupervisor.Config config = config(true, true, 3000);

    run(hook(Set.of(), config), node("FAILURE"));
    hook(Set.of(), config).review(stopRequest()).block();

    assertThat(logs.list).isNotEmpty();
    for (final ILoggingEvent event : logs.list) {
      assertThat(event.getFormattedMessage()).doesNotContain(SECRET);
      assertThat(String.valueOf(event.getKeyValuePairs())).doesNotContain(SECRET);
      assertThat(String.valueOf(event.getThrowableProxy())).doesNotContain(SECRET);
    }
    assertThat(config.toString()).doesNotContain(SECRET);
  }

  @Test
  void maskingHidesSecretKeysAtEveryDepth() {
    assertThat(
            Redaction.mask(
                Map.of("a", Map.of("token", "t", "ok", 1), "l", List.of(Map.of("password", "p")))))
        .isEqualTo(
            Map.of("a", Map.of("token", "***", "ok", 1), "l", List.of(Map.of("password", "***"))));
  }

  @Test
  void addressesInsideTheNetworkAreTold() throws Exception {
    for (final String inside :
        List.of(
            "127.0.0.1",
            "10.1.2.3",
            "192.168.0.9",
            "172.16.0.1",
            "169.254.1.1",
            "0.0.0.0",
            "100.64.0.1",
            "::1",
            "fd00::1",
            "fe80::1")) {
      assertThat(Egress.inside(InetAddress.getByName(inside))).as(inside).isTrue();
    }
    for (final String outside : List.of("8.8.8.8", "100.128.0.1", "2606:4700::1111")) {
      assertThat(Egress.inside(InetAddress.getByName(outside))).as(outside).isFalse();
    }
  }

  @Test
  void factoryReadsSettingsAndMakesWebhookThatMaySendNothingByDefault() {
    final CalloutSupervisorFactory factory =
        new CalloutSupervisorFactory(
            allowLoopback(), name -> "SECRET_ENV".equals(name) ? SECRET : null);
    final CalloutSupervisor.Config config =
        factory.config(
            Map.of(
                "url",
                "https://hooks.example.com/x",
                "headers",
                Map.of("X-A", "1"),
                "secretHeaders",
                Map.of("X-Secret", "${env.SECRET_ENV}"),
                "events",
                List.of("LogLine"),
                "timeoutMs",
                500,
                "review",
                "true",
                "reviewOnError",
                "allow",
                "maxConcurrent",
                1));

    assertThat(config.headers()).containsEntry("X-Secret", SECRET).containsEntry("X-A", "1");
    assertThat(config.events()).containsExactly("LogLine");
    assertThat(config.timeout()).isEqualTo(Duration.ofMillis(500));
    assertThat(config.review()).isTrue();
    assertThat(config.denyOnError()).isFalse();
    assertThat(factory.defaultAllowedCommands()).isEmpty();
    assertThat(
            factory
                .config(Map.of("url", "http://a.example", "reviewOnError", "deny"))
                .denyOnError())
        .isTrue();
    assertThat(
            factory
                .config(Map.of("url", "http://a.example", "headers", Map.of("X", "${env.OPEN")))
                .headers())
        .containsEntry("X", "${env.OPEN");
    assertThat(new CalloutSupervisorFactory(EgressPolicy.OPEN, List.of()).type())
        .isEqualTo("callout");
    assertThat(factory.config(Map.of("url", "http://a.example")).events())
        .containsExactlyInAnyOrder("InputRequested", "NodeStatusChanged");
    assertThat(
            factory
                .create(new Supervisor("w", "callout", Map.of("url", "http://a.example"), false))
                .id())
        .isEqualTo("w");
  }

  @Test
  void factoryRefusesBadSettingsWithoutRepeatingValues() {
    final CalloutSupervisorFactory factory =
        new CalloutSupervisorFactory(allowLoopback(), name -> null);
    final Map<String, Object> ok = Map.of("url", "http://a.example");

    assertThatThrownBy(() -> factory.config(Map.of())).hasMessageContaining("url is required");
    assertThatThrownBy(() -> factory.config(Map.of("url", "ftp://a.example")))
        .hasMessageContaining("http or https");
    assertThatThrownBy(() -> factory.config(Map.of("url", "http:///x")))
        .hasMessageContaining("http or https");
    assertThatThrownBy(() -> factory.config(with(ok, "timeoutMs", 20_000)))
        .hasMessageContaining("timeoutMs");
    assertThatThrownBy(() -> factory.config(with(ok, "timeoutMs", "x")))
        .hasMessageContaining("timeoutMs");
    assertThatThrownBy(() -> factory.config(with(ok, "events", List.of())))
        .hasMessageContaining("events");
    assertThatThrownBy(() -> factory.config(with(ok, "events", "x")))
        .hasMessageContaining("events");
    assertThatThrownBy(() -> factory.config(with(ok, "reviewOnError", "MAYBE")))
        .hasMessageContaining("reviewOnError");
    assertThatThrownBy(() -> factory.config(with(ok, "review", 1))).hasMessageContaining("review");
    assertThatThrownBy(() -> factory.config(with(ok, "headers", "x")))
        .hasMessageContaining("headers");
    assertThatThrownBy(() -> factory.config(with(ok, "secretHeaders", Map.of("X", "${env.NOPE}"))))
        .hasMessageContaining("secretHeaders.X")
        .hasMessageContaining("NOPE");
  }

  private static Map<String, Object> with(
      final Map<String, Object> base, final String key, final Object value) {
    final Map<String, Object> out = new java.util.HashMap<>(base);
    out.put(key, value);
    return out;
  }

  @Test
  void catalogEntryIsCompleteAndItsExampleIsValid() {
    final CalloutSupervisorFactory factory =
        new CalloutSupervisorFactory(allowLoopback(), name -> null);
    final var descriptor = factory.describe();

    assertThat(descriptor.displayName()).isEqualTo("Webhook");
    assertThat(descriptor.category().name()).isEqualTo("INTEGRATION");
    assertThat(factory.getConfigSchema())
        .extracting(ConfigField::name)
        .containsExactly(
            "url",
            "headers",
            "secretHeaders",
            "events",
            "timeoutMs",
            "review",
            "reviewOnError",
            "maxConcurrent");
    assertThat(factory.getConfigSchema())
        .filteredOn(f -> "secretHeaders".equals(f.name()))
        .singleElement()
        .satisfies(f -> assertThat(f.type()).isEqualTo(ConfigField.Type.SECRET));
    factory.validate(new Supervisor("w", factory.type(), descriptor.exampleSettings(), false));
    assertThat(SupervisorCommonSettings.KEYS).contains("allowedCommands");
    StepVerifier.create(
            factory
                .create(new Supervisor("w", "callout", descriptor.exampleSettings(), false))
                .supervise(Flux.empty()))
        .verifyComplete();
  }

  @Test
  void privateRefusalReadsTheHostAndTheList() throws Exception {
    final Egress closed = new Egress(EgressPolicy.OPEN, HostAllowList.from(null));

    assertThat(closed.privateRefusal("http:///x")).contains("no host");
    assertThat(closed.privateRefusal("http://8.8.8.8/x")).isNull();
    assertThat(closed.privateRefusal("http://10.0.0.5/x")).contains("private");
    assertThat(closed.privateRefusal("http://224.0.0.1/x")).contains("private");
    assertThat(allowLoopback().privateRefusal("http://127.0.0.1/x")).isNull();
  }

  @Test
  void settingReadersCoverTheirEdges() {
    final CalloutSupervisorFactory factory =
        new CalloutSupervisorFactory(allowLoopback(), name -> null);
    final Map<String, Object> ok = Map.of("url", "http://a.example");

    assertThatThrownBy(() -> factory.config(Map.of("url", " "))).hasMessageContaining("url");
    assertThatThrownBy(() -> factory.config(Map.of("url", 5))).hasMessageContaining("url");
    assertThatThrownBy(() -> factory.config(with(ok, "timeoutMs", 1.5)))
        .hasMessageContaining("timeoutMs");
    assertThatThrownBy(() -> factory.config(with(ok, "timeoutMs", 0)))
        .hasMessageContaining("timeoutMs");
    assertThatThrownBy(() -> factory.config(with(ok, "review", "maybe")))
        .hasMessageContaining("review");
    assertThat(factory.config(with(ok, "review", true)).review()).isTrue();
    assertThat(factory.config(with(ok, "review", "false")).review()).isFalse();
    assertThat(factory.config(with(ok, "review", "TRUE")).review()).isTrue();
    assertThat(factory.config(with(ok, "reviewOnError", "ALLOW")).denyOnError()).isFalse();
  }

  @Test
  void calloutAdoptsTheRetiredWebhookTypeWithTheSameSettings() {
    final CalloutSupervisorFactory factory =
        new CalloutSupervisorFactory(EgressPolicy.OPEN, List.of());
    final Supervisor webhook =
        new Supervisor("w", "webhook", Map.of("url", "https://example.org/hook"), true);

    assertThat(factory.adopt(webhook).orElseThrow().supervisor())
        .isEqualTo(webhook.withEngine("callout", webhook.settings()));
    assertThat(factory.adopt(new Supervisor("r", "rules", Map.of(), false))).isEmpty();
  }
}
