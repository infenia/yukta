// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.exception.GlobalExceptionHandler;
import com.infenia.yukta.filter.ApiKeyFilter;
import com.infenia.yukta.mapper.ConversationMapper;
import com.infenia.yukta.model.execution.TaskStatus;
import com.infenia.yukta.model.execution.WorkflowProgress;
import com.infenia.yukta.plugin.agent.AgentCapability;
import com.infenia.yukta.plugin.agent.AgentConversationHandle;
import com.infenia.yukta.plugin.agent.AgentConversationSpec;
import com.infenia.yukta.plugin.agent.AgentEventKind;
import com.infenia.yukta.plugin.agent.AgentProtocol;
import com.infenia.yukta.plugin.agent.AgentState;
import com.infenia.yukta.plugin.input.Audience;
import com.infenia.yukta.plugin.input.InputIdentity;
import com.infenia.yukta.security.ApiKeyAuthenticator;
import com.infenia.yukta.service.agent.AgentConversationQueries;
import com.infenia.yukta.service.agent.AgentConversationService;
import com.infenia.yukta.service.agent.InMemoryAgentConversationStore;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import com.infenia.yukta.service.orchestrator.tracker.TaskTrackerService;
import com.infenia.yukta.service.redact.InputRedactor;
import com.infenia.yukta.testing.Fixtures;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;

/** Reading the conversations over REST, and the live stream with its catch-up. */
@SuppressWarnings({
  "PMD.UnitTestShouldIncludeAssert",
  "PMD.AvoidDuplicateLiterals",
  "PMD.TooManyMethods",
  "PMD.TooManyStaticImports",
  "PMD.ExcessiveImports",
  "PMD.UseConcurrentHashMap"
})
@org.junit.jupiter.api.Timeout(30)
class ConversationsControllerTest {

  private static final String KEY = "X-API-Key";
  private static final ParameterizedTypeReference<ServerSentEvent<String>> SSE =
      new ParameterizedTypeReference<>() {};

  private final InMemoryAgentConversationStore store =
      new InMemoryAgentConversationStore(100, 5000, 262_144);
  private final AgentConversationService service =
      new AgentConversationService(
          store,
          new InputRedactor(List.of()),
          new com.infenia.yukta.config.YuktaProperties.Agents(
              true,
              Duration.ofDays(7),
              500,
              5000,
              65_536,
              4096,
              262_144,
              30,
              new com.infenia.yukta.config.YuktaProperties.Agents.Pty(false, "yukta-cli", 120, 40)),
          JsonMapper.builder().build(),
          Clock.fixed(Instant.parse("2026-11-15T10:00:00Z"), ZoneOffset.UTC));
  private final TaskTrackerService tracker = mock(TaskTrackerService.class);
  private final ControlBusGateway controlBus = mock(ControlBusGateway.class);
  private final Sinks.Many<WorkflowProgress> runEvents = Sinks.many().replay().latest();
  private final WebTestClient open;
  private final WebTestClient keyed;

  /* default */ ConversationsControllerTest() {
    when(tracker.lineage(anyString())).thenReturn(new TaskTrackerService.Lineage("root", 0));
    when(tracker.isWithin(anyString(), anyString()))
        .thenAnswer(call -> {
          final String run = call.getArgument(0);
          final String ancestor = call.getArgument(1);
          return run.equals(ancestor) || ("root".equals(ancestor) && run.startsWith("child"));
        });
    when(controlBus.getCurrentProgress("root")).thenReturn(progress("root", "s1"));
    when(controlBus.getCurrentProgress("elsewhere")).thenReturn(progress("elsewhere", "s2"));
    when(controlBus.watchExecution(anyString(), eq(true))).thenReturn(runEvents.asFlux());
    final ConversationsController controller =
        new ConversationsController(
            new AgentConversationQueries(store, service, tracker, controlBus),
            new ConversationMapper(),
            new ExecutionAccess(controlBus),
            org.mockito.Mockito.mock(com.infenia.yukta.service.control.SupervisedControl.class));
    open =
        WebTestClient.bindToController(controller)
            .controllerAdvice(new GlobalExceptionHandler())
            .build();
    final MockEnvironment keys =
        new MockEnvironment()
            .withProperty("yukta.api-keys[0].name", "viewer")
            .withProperty("yukta.api-keys[0].key", "v-key")
            .withProperty("yukta.api-keys[0].role", "VIEWER")
            .withProperty("yukta.api-keys[0].sessions", "*")
            .withProperty("yukta.api-keys[1].name", "team")
            .withProperty("yukta.api-keys[1].key", "t-key")
            .withProperty("yukta.api-keys[1].role", "VIEWER")
            .withProperty("yukta.api-keys[1].sessions", "s1");
    keyed =
        WebTestClient.bindToController(controller)
            .webFilter(new ApiKeyFilter(ApiKeyAuthenticator.read(keys), controlBus, null))
            .controllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @AfterEach
  void tearDown() {
    service.destroy();
  }

  private static WorkflowProgress progress(final String execution, final String session) {
    return Fixtures.workflowProgress(
        execution, session, "wf", TaskStatus.RUNNING, List.of(), Instant.now(), null, null);
  }

  private AgentConversationHandle conversation(
      final String execution, final String session, final String... capabilities) {
    return service
        .open(
            new AgentConversationSpec(
                new InputIdentity(session, "wf", execution, "node-1"),
                "message-1",
                "claude-code",
                AgentProtocol.JSONL,
                null,
                Set.of(capabilities),
                Audience.CURRENT_TIER),
            "root")
        .block();
  }

  private void say(final AgentConversationHandle handle, final String text) {
    final Map<String, Object> data = new LinkedHashMap<>();
    data.put("role", "agent");
    data.put("text", text);
    data.put("more", false);
    handle.emit(AgentEventKind.MESSAGE, "m-" + text, data);
    service.flush().block();
  }

  private void endRun() {
    final WorkflowProgress done = mock(WorkflowProgress.class);
    when(done.status()).thenReturn(TaskStatus.SUCCESS);
    when(done.endTime()).thenReturn(Instant.now());
    runEvents.tryEmitNext(done);
  }

  private WebTestClient.ResponseSpec get(final String uri) {
    return open.get().uri(uri).exchange();
  }

  private Flux<ServerSentEvent<String>> stream(final String uri, final String lastEventId) {
    final var request = open.get().uri(uri).accept(MediaType.TEXT_EVENT_STREAM);
    if (lastEventId != null) {
      request.header("Last-Event-ID", lastEventId);
    }
    return request.exchange().expectStatus().isOk().returnResult(SSE).getResponseBody();
  }

  private List<ServerSentEvent<String>> streamed(final String uri, final String lastEventId) {
    endRun();
    return stream(uri, lastEventId)
        .filter(event -> event.event() != null)
        .collectList()
        .block(Duration.ofSeconds(10));
  }

  // --- the lists ---

  @Test
  void listsTheConversationsNewestFirstInTheEnvelope() {
    conversation("root", "s1");
    conversation("root", "s1", AgentCapability.RESUME);

    get("/api/conversations")
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.status")
        .isEqualTo(200)
        .jsonPath("$.data.length()")
        .isEqualTo(2)
        .jsonPath("$.data[0].capabilities[0]")
        .isEqualTo("resume")
        .jsonPath("$.data[1].state")
        .isEqualTo("STARTING")
        .jsonPath("$.data[0].protocol")
        .isEqualTo("JSONL")
        .jsonPath("$.data[0].steerAudience.length()")
        .isEqualTo(0);
  }

  @Test
  void listsNarrowedByStateSessionAndLimitAndRefusesAnUnknownState() {
    final AgentConversationHandle running = conversation("root", "s1");
    conversation("root", "s2");
    running.state(AgentState.RUNNING, null);
    service.flush().block();

    get("/api/conversations?state=running").expectBody().jsonPath("$.data.length()").isEqualTo(1);
    get("/api/conversations?sessionId=s2").expectBody().jsonPath("$.data.length()").isEqualTo(1);
    get("/api/conversations?limit=1").expectBody().jsonPath("$.data.length()").isEqualTo(1);
    get("/api/conversations?state=sleeping").expectStatus().isBadRequest();
  }

  @Test
  void childRunsConversationIsListedWithTreeAndNotWithoutIt() {
    conversation("root", "s1");
    conversation("child-1", "s1");

    get("/api/executions/root/conversations").expectBody().jsonPath("$.data.length()").isEqualTo(2);
    get("/api/executions/root/conversations?tree=true")
        .expectBody()
        .jsonPath("$.data.length()")
        .isEqualTo(2);
    get("/api/executions/root/conversations?limit=1")
        .expectBody()
        .jsonPath("$.data.length()")
        .isEqualTo(1);
    get("/api/executions/root/conversations?tree=false")
        .expectBody()
        .jsonPath("$.data.length()")
        .isEqualTo(1)
        .jsonPath("$.data[0].executionId")
        .isEqualTo("root");
    get("/api/conversations?executionId=root&tree=false")
        .expectBody()
        .jsonPath("$.data.length()")
        .isEqualTo(1);
  }

  @Test
  void unknownRunIsNotFound() {
    get("/api/executions/nope/conversations").expectStatus().isNotFound();
    get("/api/executions/nope/conversations/stream").expectStatus().isNotFound();
  }

  // --- one conversation ---

  @Test
  void readsOneConversationAndAnUnknownOneIsNotFound() {
    final AgentConversationHandle handle = conversation("root", "s1");

    get("/api/conversations/" + handle.id())
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data.id")
        .isEqualTo(handle.id())
        .jsonPath("$.data.rootExecutionId")
        .isEqualTo("root");
    get("/api/conversations/nope").expectStatus().isNotFound();
  }

  @Test
  void theEventsComeInPagesWithTheNextNumberInTheHeader() {
    final AgentConversationHandle handle = conversation("root", "s1");
    for (int i = 0; i < 5; i++) {
      say(handle, "t" + i);
    }

    final String next =
        get("/api/conversations/" + handle.id() + "/events?limit=3")
            .expectStatus()
            .isOk()
            .expectHeader()
            .exists(Pagination.NEXT_CURSOR)
            .expectBody()
            .jsonPath("$.data.length()")
            .isEqualTo(3)
            .returnResult()
            .getResponseHeaders()
            .getFirst(Pagination.NEXT_CURSOR);
    get("/api/conversations/" + handle.id() + "/events?limit=100&after=" + next)
        .expectHeader()
        .doesNotExist(Pagination.NEXT_CURSOR)
        .expectBody()
        .jsonPath("$.data.length()")
        .isEqualTo(3); // 6 events in all: STARTING and five messages
  }

  @Test
  void theEventsCanBeNarrowedByKindAndAnUnknownKindOrConversationIsRefused() {
    final AgentConversationHandle handle = conversation("root", "s1");
    say(handle, "hello");

    get("/api/conversations/" + handle.id() + "/events?kinds=message")
        .expectBody()
        .jsonPath("$.data.length()")
        .isEqualTo(1)
        .jsonPath("$.data[0].kind")
        .isEqualTo("MESSAGE")
        .jsonPath("$.data[0].data.text")
        .isEqualTo("hello");
    get("/api/conversations/" + handle.id() + "/events?kinds=gossip").expectStatus().isBadRequest();
    get("/api/conversations/nope/events").expectStatus().isNotFound();
  }

  @Test
  void theScreenOfTheTerminalIsReadWithItsCursor() {
    final AgentConversationHandle plain = conversation("root", "s1");
    final AgentConversationHandle pty = conversation("root", "s1", AgentCapability.TERMINAL);

    get("/api/conversations/" + plain.id() + "/terminal/screen").expectStatus().isEqualTo(409);
    get("/api/conversations/" + pty.id() + "/terminal/screen").expectStatus().isNotFound();
    pty.emit(
        AgentEventKind.TERMINAL,
        null,
        Map.of(
            "screen",
            "$ ",
            "cols",
            80,
            "rows",
            24,
            "cursorRow",
            0,
            "cursorCol",
            2,
            "settled",
            true));
    service.flush().block();

    get("/api/conversations/" + pty.id() + "/terminal/screen")
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data.text")
        .isEqualTo("$ ")
        .jsonPath("$.data.cols")
        .isEqualTo(80)
        .jsonPath("$.data.rows")
        .isEqualTo(24)
        .jsonPath("$.data.cursorCol")
        .isEqualTo(2);
  }

  // --- the stream ---

  @Test
  void theStreamSendsNamedEventsWhoseIdIsTheSequenceNumber() {
    final AgentConversationHandle handle = conversation("root", "s1");
    say(handle, "one");

    final List<ServerSentEvent<String>> events =
        streamed("/api/executions/root/conversations/stream", null);

    assertThat(events)
        .extracting(ServerSentEvent::event)
        .containsExactly("conversation", "event", "event");
    final List<Long> ids = events.stream().map(event -> Long.parseLong(event.id())).toList();
    assertThat(ids).isSorted().doesNotHaveDuplicates();
    assertThat(events.getFirst().data()).contains("\"id\":\"" + handle.id() + "\"");
    assertThat(events.getLast().data()).contains("\"text\":\"one\"");
  }

  @Test
  void reconnectingClientGetsExactlyWhatItMissed() {
    final AgentConversationHandle handle = conversation("root", "s1");
    say(handle, "one");
    say(handle, "two");
    final List<ServerSentEvent<String>> first =
        streamed("/api/executions/root/conversations/stream", null);
    final String last = first.getLast().id();

    say(handle, "three");
    say(handle, "four");
    final List<ServerSentEvent<String>> resumed =
        streamed("/api/executions/root/conversations/stream", last);

    assertThat(resumed).extracting(ServerSentEvent::event).containsOnly("event");
    assertThat(resumed).hasSize(2);
    assertThat(resumed.getFirst().data()).contains("three");
    assertThat(resumed.getLast().data()).contains("four");
    assertThat(Long.parseLong(resumed.getFirst().id())).isGreaterThan(Long.parseLong(last));
  }

  @Test
  void headerThatIsNotTheNumberStartsFromTheBeginning() {
    final AgentConversationHandle handle = conversation("root", "s1");
    say(handle, "one");

    assertThat(streamed("/api/executions/root/conversations/stream", "garbage")).hasSize(3);
    assertThat(streamed("/api/executions/root/conversations/stream", "-4")).hasSize(3);
  }

  @Test
  void liveEventsFollowTheHistoryAndTheStreamEndsWithTheRun() {
    final AgentConversationHandle handle = conversation("root", "s1");

    final List<String> seen = new ArrayList<>();
    StepVerifier.create(
            stream("/api/executions/root/conversations/stream", null)
                .filter(event -> event.event() != null)
                .doOnNext(event -> seen.add(event.event())))
        .expectNextCount(2)
        .then(() -> {
          say(handle, "live");
          endRun();
        })
        .expectNextCount(1)
        .verifyComplete();

    assertThat(seen).containsExactly("conversation", "event", "event");
  }

  @Test
  void treeOptionKindsTerminalAndTheNamedConversationShapeTheStream() {
    final AgentConversationHandle mine = conversation("root", "s1", AgentCapability.TERMINAL);
    final AgentConversationHandle child = conversation("child-1", "s1");
    say(mine, "mine");
    say(child, "from the child");
    final String bytes =
        Base64.getEncoder().encodeToString("x".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    mine.emit(AgentEventKind.TERMINAL, null, Map.of("data", bytes, "cols", 80, "rows", 24));
    service.flush().block();

    final String base = "/api/executions/root/conversations/stream";
    final String everything =
        String.valueOf(streamed(base, null).stream().map(ServerSentEvent::data).toList());

    assertThat(everything).contains("from the child").contains("\"data\":\"" + bytes + "\"");
    assertThat(
            String.valueOf(
                streamed(base + "?tree=false", null).stream().map(ServerSentEvent::data).toList()))
        .doesNotContain("from the child");
    assertThat(
            String.valueOf(
                streamed(base + "?terminal=false", null).stream()
                    .map(ServerSentEvent::data)
                    .toList()))
        .doesNotContain("\"data\":\"" + bytes + "\"");
    assertThat(
            String.valueOf(
                streamed(base + "?conversationId=" + child.id(), null).stream()
                    .map(ServerSentEvent::data)
                    .toList()))
        .contains("from the child")
        .doesNotContain("\"text\":\"mine\"");
    assertThat(streamed(base + "?kinds=tool", null).stream().map(ServerSentEvent::event).toList())
        .containsOnly("conversation");
  }

  @Test
  void unknownKindOnTheStreamIsTheBadRequest() {
    get("/api/executions/root/conversations/stream?kinds=gossip").expectStatus().isBadRequest();
  }

  // --- who may read ---

  @Test
  void keyLimitedToTheSessionReadsOnlyItsOwnRunsAndHidesTheOthers() {
    conversation("root", "s1");
    conversation("elsewhere", "s2");
    endRun();

    keyed
        .get()
        .uri("/api/executions/root/conversations")
        .header(KEY, "t-key")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data.length()")
        .isEqualTo(1);
    keyed
        .get()
        .uri("/api/executions/elsewhere/conversations")
        .header(KEY, "t-key")
        .exchange()
        .expectStatus()
        .isForbidden();
    keyed
        .get()
        .uri("/api/conversations")
        .header(KEY, "t-key")
        .exchange()
        .expectStatus()
        .isForbidden();
    keyed
        .get()
        .uri("/api/conversations")
        .header(KEY, "v-key")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data.length()")
        .isEqualTo(2);
    keyed.get().uri("/api/conversations").exchange().expectStatus().isUnauthorized();
  }

  @Test
  void conversationOfAnotherSessionIsNotFoundEvenWhenTheFilterLetsTheRequestThrough() {
    final AgentConversationHandle foreign = conversation("elsewhere", "s2");
    final WebTestClient scoped =
        WebTestClient.bindToController(
                new ConversationsController(
                    new AgentConversationQueries(store, service, tracker, controlBus),
                    new ConversationMapper(),
                    new ExecutionAccess(controlBus),
                    org.mockito.Mockito.mock(com.infenia.yukta.service.control.SupervisedControl.class)))
            .webFilter((exchange, chain) ->
                chain
                    .filter(exchange)
                    .contextWrite(context ->
                        context.put(
                            com.infenia.yukta.security.CallerIdentity.CONTEXT_KEY,
                            new com.infenia.yukta.security.ApiKeys.Match(
                                "team", com.infenia.yukta.security.ApiRole.VIEWER, Set.of("s1")))))
            .controllerAdvice(new GlobalExceptionHandler())
            .build();

    scoped.get().uri("/api/conversations/" + foreign.id()).exchange().expectStatus().isNotFound();
    scoped
        .get()
        .uri("/api/conversations/" + foreign.id() + "/events")
        .exchange()
        .expectStatus()
        .isNotFound();
    scoped
        .get()
        .uri("/api/executions/elsewhere/conversations")
        .exchange()
        .expectStatus()
        .isNotFound();
    scoped
        .get()
        .uri("/api/conversations")
        .exchange()
        .expectBody()
        .jsonPath("$.data.length()")
        .isEqualTo(0);
  }

  @Test
  void screenEventWithoutNumbersOrTextMapsToZerosAndEmptyText() {
    final var screen =
        new ConversationMapper()
            .toScreen(
                new com.infenia.yukta.plugin.agent.AgentEvent(
                    7, "c", Instant.now(), AgentEventKind.TERMINAL, null, Map.of()));

    assertThat(screen.cols()).isZero();
    assertThat(screen.cursorRow()).isZero();
    assertThat(screen.text()).isEmpty();
    assertThat(screen.seq()).isEqualTo(7);
  }
}
