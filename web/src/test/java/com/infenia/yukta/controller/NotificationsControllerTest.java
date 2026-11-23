// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.dto.response.NotificationEvent;
import com.infenia.yukta.exception.GlobalExceptionHandler;
import com.infenia.yukta.filter.ApiKeyFilter;
import com.infenia.yukta.plugin.control.ControlActor;
import com.infenia.yukta.plugin.input.Audience;
import com.infenia.yukta.plugin.input.InputKind;
import com.infenia.yukta.plugin.input.InputRequest;
import com.infenia.yukta.plugin.input.InputRequestSpec;
import com.infenia.yukta.plugin.input.InputRisk;
import com.infenia.yukta.plugin.input.InputState;
import com.infenia.yukta.plugin.input.TimeoutAction;
import com.infenia.yukta.plugin.notify.Notification;
import com.infenia.yukta.plugin.notify.NotificationKind;
import com.infenia.yukta.plugin.notify.NotificationSeverity;
import com.infenia.yukta.security.ApiKeyAuthenticator;
import com.infenia.yukta.security.ApiKeys;
import com.infenia.yukta.security.ApiRole;
import com.infenia.yukta.security.CallerIdentity;
import com.infenia.yukta.service.NotFoundException;
import com.infenia.yukta.service.input.InputRequestQueries;
import com.infenia.yukta.service.leader.NotLeaderException;
import com.infenia.yukta.service.notify.InMemoryNotificationStore;
import com.infenia.yukta.service.notify.NotificationDraft;
import com.infenia.yukta.service.notify.NotificationService;
import com.infenia.yukta.service.redact.InputRedactor;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** Tests for {@link NotificationsController}, behind the API key filter. */
@NoArgsConstructor
@SuppressWarnings({
  "PMD.TooManyMethods",
  "PMD.AvoidDuplicateLiterals",
  "PMD.ExcessiveImports",
  "PMD.UnitTestShouldIncludeAssert"
})
class NotificationsControllerTest {

  private static final String KEY = "X-API-Key";
  private static final String BASE = "/api/notifications";
  private static final Instant NOW = Instant.parse("2026-11-15T10:00:00Z");
  private static final Duration WAIT = Duration.ofSeconds(10);

  private final InMemoryNotificationStore store = new InMemoryNotificationStore(5000);
  private final NotificationService service =
      new NotificationService(store, new InputRedactor(List.of()));
  private final InputRequestQueries queries = mock(InputRequestQueries.class);
  private final AtomicBoolean leader = new AtomicBoolean(true);

  private NotificationsController controller;
  private WebTestClient keyed;
  private WebTestClient open;

  @BeforeEach
  void setUp() {
    controller = new NotificationsController(service, queries, leader::get);
    when(queries.require(anyString(), anyString()))
        .thenReturn(Mono.error(new NotFoundException("gone")));
    when(queries.answer(org.mockito.ArgumentMatchers.any())).thenReturn(Mono.empty());
    open =
        WebTestClient.bindToController(controller)
            .controllerAdvice(new GlobalExceptionHandler())
            .build();
    final MockEnvironment keys =
        new MockEnvironment()
            .withProperty("yukta.api-keys[0].name", "admin")
            .withProperty("yukta.api-keys[0].key", "a-key")
            .withProperty("yukta.api-keys[0].role", "ADMIN")
            .withProperty("yukta.api-keys[1].name", "viewer")
            .withProperty("yukta.api-keys[1].key", "v-key")
            .withProperty("yukta.api-keys[1].role", "VIEWER")
            .withProperty("yukta.api-keys[1].sessions", "*")
            .withProperty("yukta.api-keys[2].name", "scoped")
            .withProperty("yukta.api-keys[2].key", "s-key")
            .withProperty("yukta.api-keys[2].role", "OPERATOR")
            .withProperty("yukta.api-keys[2].sessions", "s1");
    keyed =
        WebTestClient.bindToController(controller)
            .webFilter(new ApiKeyFilter(ApiKeyAuthenticator.read(keys), null, null))
            .controllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  private Notification raise(final String key, final String session, final String... audience) {
    return service
        .raise(
            NotificationDraft.builder()
                .key(key)
                .kind(NotificationKind.INFO)
                .severity(NotificationSeverity.WARN)
                .category("run.failed")
                .title("t-" + key)
                .sessionId(session)
                .audience(List.of(audience))
                .cooldown(Duration.ZERO)
                .build())
        .block();
  }

  private Notification action(final String key, final String session, final String... audience) {
    return service
        .raise(
            NotificationDraft.builder()
                .key(key)
                .kind(NotificationKind.ACTION)
                .severity(NotificationSeverity.ERROR)
                .category("input")
                .title("q-" + key)
                .sessionId(session)
                .executionId("e1")
                .inputRequestId("r-" + key)
                .audience(List.of(audience))
                .cooldown(Duration.ZERO)
                .build())
        .block();
  }

  private WebTestClient.ResponseSpec get(final String key, final String path) {
    return keyed.get().uri(path).header(KEY, key).exchange();
  }

  private WebTestClient.ResponseSpec post(final String key, final String path, final String body) {
    return keyed
        .post()
        .uri(path)
        .header(KEY, key)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body)
        .exchange();
  }

  // ---- visibility ----

  @Test
  void everyoneSeesOnlyWhatTheirKeyReaches() {
    raise("a", "s1", "role:VIEWER");
    raise("b", "s2", "role:VIEWER");
    raise("c", "s1", "role:ADMIN");

    get("a-key", BASE).expectStatus().isOk().expectBody().jsonPath("$.data.length()").isEqualTo(3);
    get("v-key", BASE).expectStatus().isOk().expectBody().jsonPath("$.data.length()").isEqualTo(2);
    get("s-key", BASE)
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data.length()")
        .isEqualTo(1)
        .jsonPath("$.data[0].sessionId")
        .isEqualTo("s1");
  }

  @Test
  void sessionLimitedKeyAndViewerMayUseTheInboxWritesToo() {
    final Notification n = raise("a", "s1", "role:VIEWER");

    post("s-key", BASE + "/read", "{\"ids\":[\"" + n.id() + "\"]}").expectStatus().isOk();
    post("v-key", BASE + "/read", "{\"ids\":[\"" + n.id() + "\"]}").expectStatus().isOk();
    keyed
        .put()
        .uri(BASE + "/preferences")
        .header(KEY, "v-key")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{}")
        .exchange()
        .expectStatus()
        .isOk();
  }

  @Test
  void listFiltersByStateKindSeverityUnreadAndSessionAndPages() {
    final Notification info = raise("a", "s1", "role:VIEWER");
    raise("b", "s2", "role:VIEWER");
    action("input:x", "s1", "role:VIEWER");
    service.resolve("input:x", "done").block();
    service.markRead("admin", List.of(info.id())).block();

    get("a-key", BASE + "?state=RESOLVED").expectBody().jsonPath("$.data.length()").isEqualTo(1);
    get("a-key", BASE + "?kind=INFO").expectBody().jsonPath("$.data.length()").isEqualTo(2);
    get("a-key", BASE + "?severity=ERROR").expectBody().jsonPath("$.data.length()").isEqualTo(1);
    get("a-key", BASE + "?unread=true").expectBody().jsonPath("$.data.length()").isEqualTo(2);
    get("a-key", BASE + "?sessionId=s2").expectBody().jsonPath("$.data.length()").isEqualTo(1);
    get("a-key", BASE + "?size=1&page=1")
        .expectHeader()
        .valueEquals("X-Total-Count", "3")
        .expectBody()
        .jsonPath("$.data.length()")
        .isEqualTo(1);
  }

  @Test
  void actionCarriesItsInputRequestAndAnInfoDoesNot() {
    action("input:y", "s1", "role:OPERATOR");
    when(queries.require("e1", "r-input:y")).thenReturn(Mono.just(inputRequest()));

    get("a-key", BASE)
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data[0].input.id")
        .isEqualTo("r1")
        .jsonPath("$.data[0].read")
        .isEqualTo(false);
  }

  private static InputRequest inputRequest() {
    final InputRequestSpec spec =
        new InputRequestSpec(
            "k",
            InputKind.APPROVAL,
            "Deploy?",
            Map.of(),
            List.of(),
            null,
            InputRisk.LOW,
            Audience.CURRENT_TIER,
            Duration.ofMinutes(5),
            TimeoutAction.FAIL,
            null,
            null,
            "T");
    return new InputRequest(
        "r1",
        "k",
        "s1",
        "wf",
        "e1",
        "n",
        spec,
        InputState.OPEN,
        0,
        "default",
        List.of("role:OPERATOR"),
        null,
        NOW.plusSeconds(300),
        NOW.plusSeconds(300),
        List.of(),
        NOW,
        NOW,
        List.of());
  }

  // ---- summary, read, dismiss ----

  @Test
  void summaryCountsActionsAndUnreadInfoAndTheHighestSeverity() {
    final Notification info = raise("a", "s1", "role:VIEWER");
    raise("b", "s1", "role:VIEWER");
    action("input:x", "s1", "role:OPERATOR");

    get("a-key", BASE + "/summary")
        .expectBody()
        .jsonPath("$.data.actions")
        .isEqualTo(1)
        .jsonPath("$.data.unread")
        .isEqualTo(2)
        .jsonPath("$.data.highestSeverity")
        .isEqualTo("ERROR");
    service.markRead("admin", List.of(info.id())).block();
    get("a-key", BASE + "/summary").expectBody().jsonPath("$.data.unread").isEqualTo(1);
    get("v-key", BASE + "/summary")
        .expectBody()
        .jsonPath("$.data.actions")
        .isEqualTo(0)
        .jsonPath("$.data.highestSeverity")
        .isEqualTo("WARN");
  }

  @Test
  void emptyInboxHasNoHighestSeverity() {
    get("a-key", BASE + "/summary")
        .expectBody()
        .jsonPath("$.data.actions")
        .isEqualTo(0)
        .jsonPath("$.data.highestSeverity")
        .doesNotExist();
  }

  @Test
  void readMarksTheListedIdsOrEverythingUpToSequenceAndIgnoresWhatIsNotVisible() {
    final Notification a = raise("a", "s1", "role:VIEWER");
    final Notification b = raise("b", "s1", "role:VIEWER");
    final Notification secret = raise("c", "s2", "role:ADMIN");

    post("s-key", BASE + "/read", "{\"ids\":[\"" + a.id() + "\",\"" + secret.id() + "\"]}")
        .expectBody()
        .jsonPath("$.data.marked")
        .isEqualTo(1);
    post("a-key", BASE + "/read", "{\"all\":true,\"upToSeq\":" + a.seq() + "}")
        .expectBody()
        .jsonPath("$.data.marked")
        .isEqualTo(1);
    post("a-key", BASE + "/read", "{\"all\":true}")
        .expectBody()
        .jsonPath("$.data.marked")
        .isEqualTo(2);
    post("a-key", BASE + "/read", "{}").expectBody().jsonPath("$.data.marked").isEqualTo(0);
    assertThat(b.id()).isNotEqualTo(a.id());
  }

  @Test
  void dismissHidesAnInfoNotAnOpenActionAndNotWhatIsNotVisible() {
    final Notification info = raise("a", "s1", "role:VIEWER");
    final Notification act = action("input:x", "s1", "role:OPERATOR");
    final Notification other = raise("b", "s2", "role:VIEWER");

    post("a-key", BASE + "/" + info.id() + "/dismiss", "{}").expectStatus().isOk();
    get("a-key", BASE).expectBody().jsonPath("$.data.length()").isEqualTo(2);
    post("a-key", BASE + "/" + act.id() + "/dismiss", "{}").expectStatus().isEqualTo(409);
    post("s-key", BASE + "/" + other.id() + "/dismiss", "{}").expectStatus().isNotFound();
    post("a-key", BASE + "/nope/dismiss", "{}").expectStatus().isNotFound();
    service.resolve("input:x", "answered").block();
    post("a-key", BASE + "/" + act.id() + "/dismiss", "{}").expectStatus().isOk();
  }

  // ---- preferences ----

  @Test
  void preferencesAreKeptPerKeyAndInputCannotBeTurnedOff() {
    keyed
        .put()
        .uri(BASE + "/preferences")
        .header(KEY, "a-key")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"levels\":{\"run.failed\":\"DESKTOP\"},\"mutedSessions\":[\"s2\"]}")
        .exchange()
        .expectStatus()
        .isOk();

    get("a-key", BASE + "/preferences")
        .expectBody()
        .jsonPath("$.data.levels['run.failed']")
        .isEqualTo("DESKTOP")
        .jsonPath("$.data.mutedSessions[0]")
        .isEqualTo("s2");
    get("v-key", BASE + "/preferences")
        .expectBody()
        .jsonPath("$.data.levels.length()")
        .isEqualTo(0);
    keyed
        .put()
        .uri(BASE + "/preferences")
        .header(KEY, "a-key")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"levels\":{\"input\":\"OFF\"}}")
        .exchange()
        .expectStatus()
        .isBadRequest();
    keyed
        .put()
        .uri(BASE + "/preferences")
        .header(KEY, "a-key")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"levels\":{\"nope\":\"OFF\"}}")
        .exchange()
        .expectStatus()
        .isBadRequest();
  }

  @Test
  void nullBodyFieldMeansEmpty() {
    open.put()
        .uri(BASE + "/preferences")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"levels\":null,\"mutedSessions\":null}")
        .exchange()
        .expectStatus()
        .isOk();
  }

  // ---- standby, answering ----

  @Test
  void standbyServerAnswers503() {
    leader.set(false);

    get("a-key", BASE).expectStatus().isEqualTo(503);
    get("a-key", BASE + "/summary").expectStatus().isEqualTo(503);
    StepVerifier.create(stream("a-key", null)).expectError(NotLeaderException.class).verify(WAIT);
  }

  @Test
  void openServerHasOneSharedInbox() {
    raise("a", null, "key:local");

    open.get().uri(BASE).exchange().expectBody().jsonPath("$.data.length()").isEqualTo(1);
  }

  // ---- stream ----

  private Flux<ServerSentEvent<NotificationEvent>> stream(final String who, final String lastId) {
    final ApiKeys.Match match =
        switch (who) {
          case "v-key" -> new ApiKeys.Match("viewer", ApiRole.VIEWER, Set.of());
          case "s-key" -> new ApiKeys.Match("scoped", ApiRole.OPERATOR, Set.of("s1"));
          default -> new ApiKeys.Match("admin", ApiRole.ADMIN, Set.of());
        };
    return controller.stream(lastId)
        .contextWrite(ctx ->
            ctx.put(CallerIdentity.CONTEXT_KEY, match)
                .put(ControlActor.CONTEXT_KEY, "key:" + match.name()));
  }

  @Test
  void streamsOfTheSameCallerAndTheOpenServerAllGetTheEvent() {
    final Flux<ServerSentEvent<NotificationEvent>> openServer =
        controller.stream(null).contextWrite(ctx -> ctx.put(ControlActor.CONTEXT_KEY, "anonymous"));
    final var first = stream("v-key", null).take(1).collectList().toFuture();
    final var second = stream("v-key", null).take(1).collectList().toFuture();
    final var anyone = openServer.take(1).collectList().toFuture();
    // let the three streams subscribe before the change
    org.awaitility.Awaitility.await()
        .atMost(WAIT)
        .until(() -> {
          raise("shared-" + System.nanoTime(), "s1", "role:VIEWER");
          return first.isDone() && second.isDone() && anyone.isDone();
        });

    assertThat(first.join().get(0).data().notification().key()).startsWith("shared-");
    assertThat(second.join().get(0).data().notification().key()).startsWith("shared-");
    assertThat(anyone.join().get(0).data().type()).isEqualTo("created");
  }

  @Test
  void theStreamSendsWhatChangesToTheCallerOnly() {
    StepVerifier.create(stream("v-key", null).take(4))
        .then(() -> {
          final Notification mine = raise("a", "s1", "role:VIEWER");
          raise("hidden", "s1", "role:ADMIN");
          service.raise(NotifyDrafts.again(mine)).block();
          service.markRead("viewer", List.of(mine.id())).block();
          service.markRead("admin", List.of(mine.id())).block();
          raise("b", "s1", "role:VIEWER");
        })
        .assertNext(e -> {
          assertThat(e.data().type()).isEqualTo("created");
          assertThat(e.id()).startsWith(service.epoch().block() + "-");
          assertThat(e.data().notification().key()).isEqualTo("a");
        })
        .assertNext(e -> assertThat(e.data().type()).isEqualTo("updated"))
        .assertNext(e -> {
          assertThat(e.data().type()).isEqualTo("receipt");
          assertThat(e.data().readAt()).isNotNull();
        })
        .assertNext(e -> assertThat(e.data().notification().key()).isEqualTo("b"))
        .expectComplete()
        .verify(WAIT);
  }

  @Test
  void resolvedChangeIsSentAsResolved() {
    action("input:x", "s1", "role:OPERATOR");

    StepVerifier.create(stream("a-key", null).take(1))
        .then(() -> service.resolve("input:x", "answered").block())
        .assertNext(e -> assertThat(e.data().type()).isEqualTo("resolved"))
        .expectComplete()
        .verify(WAIT);
  }

  @Test
  void reconnectingReplaysOnlyNewerChanges() {
    final Notification first = raise("a", "s1", "role:VIEWER");
    raise("hidden", "s2", "role:ADMIN");
    final Notification second = raise("b", "s1", "role:VIEWER");
    service.resolve("b", "over").block();
    action("input:x", "s1", "role:VIEWER");
    service.raise(NotifyDrafts.again(first)).block();

    StepVerifier.create(stream("s-key", service.epoch().block() + "-" + first.seq()).take(3))
        .assertNext(e -> assertThat(e.data().type()).isEqualTo("resolved"))
        .assertNext(e -> assertThat(e.data().type()).isEqualTo("created"))
        .assertNext(e -> assertThat(e.data().type()).isEqualTo("updated"))
        .expectComplete()
        .verify(WAIT);
    assertThat(second.seq()).isGreaterThan(first.seq());
  }

  @Test
  void replayedItemIsNotSentTwiceWhenItAlsoArrivesLive() {
    final Notification first = raise("a", "s1", "role:VIEWER");

    StepVerifier.create(stream("a-key", service.epoch().block() + "-0").take(2))
        .assertNext(e -> assertThat(e.data().notification().id()).isEqualTo(first.id()))
        .then(() -> raise("b", "s1", "role:VIEWER"))
        .assertNext(e -> assertThat(e.data().notification().key()).isEqualTo("b"))
        .expectComplete()
        .verify(WAIT);
  }

  @Test
  void anotherEpochOrBadIdOrTooManyChangesGivesReset() {
    for (int i = 0; i < NotificationsController.MAX_REPLAY + 1; i++) {
      raise("k" + i, "s1", "role:VIEWER");
    }

    StepVerifier.create(stream("a-key", "other-epoch-5").take(1))
        .assertNext(e -> assertThat(e.data().type()).isEqualTo("reset"))
        .expectComplete()
        .verify(WAIT);
    StepVerifier.create(stream("a-key", "garbage").take(1))
        .assertNext(e -> assertThat(e.data().type()).isEqualTo("reset"))
        .expectComplete()
        .verify(WAIT);
    StepVerifier.create(stream("a-key", service.epoch().block() + "-x").take(1))
        .assertNext(e -> assertThat(e.data().type()).isEqualTo("reset"))
        .expectComplete()
        .verify(WAIT);
    StepVerifier.create(stream("a-key", service.epoch().block() + "-0").take(1))
        .assertNext(e -> assertThat(e.data().type()).isEqualTo("reset"))
        .expectComplete()
        .verify(WAIT);
  }

  @Test
  void blankLastEventIdIsFreshConnection() {
    raise("old", "s1", "role:VIEWER");

    StepVerifier.create(stream("a-key", " ").take(1))
        .then(() -> raise("new", "s1", "role:VIEWER"))
        .assertNext(e -> assertThat(e.data().notification().key()).isEqualTo("new"))
        .expectComplete()
        .verify(WAIT);
  }

  @Test
  void ninthStreamOfOneKeyIsRefused() {
    final List<reactor.core.Disposable> held = new java.util.ArrayList<>();
    try {
      for (int i = 0; i < NotificationsController.MAX_STREAMS_PER_KEY; i++) {
        held.add(stream("v-key", null).subscribe());
      }

      StepVerifier.create(stream("v-key", null))
          .expectErrorSatisfies(e ->
              assertThat(e)
                  .isInstanceOf(org.springframework.web.server.ResponseStatusException.class))
          .verify(WAIT);
    } finally {
      held.forEach(reactor.core.Disposable::dispose);
    }
  }

  @Test
  void answeredActionCarriesTheAnswerAndResolvedOneDoesNotCountAsOpen() {
    action("input:y", "s1", "role:OPERATOR");
    action("input:z", "s1", "role:OPERATOR");
    service.resolve("input:z", "answered").block();
    final InputRequest request = inputRequest();
    when(queries.require("e1", "r-input:y")).thenReturn(Mono.just(request));
    when(queries.answer(request))
        .thenReturn(
            Mono.just(
                new com.infenia.yukta.plugin.input.InputResponse(
                    "r1",
                    "approve",
                    null,
                    Map.of(),
                    new com.infenia.yukta.plugin.input.Responder(
                        com.infenia.yukta.plugin.input.Responder.Type.HUMAN, "ann"),
                    null,
                    null,
                    NOW)));

    get("a-key", BASE + "?state=ACTIVE")
        .expectBody()
        .jsonPath("$.data[0].input.answer.decision")
        .isEqualTo("approve");
    get("a-key", BASE + "/summary").expectBody().jsonPath("$.data.actions").isEqualTo(1);
  }

  @Test
  void liveChangeToItemTheCallerReadAndDismissedCarriesThatState() {
    final Notification n = raise("a", "s1", "role:VIEWER");
    service.dismiss("viewer", n.id()).block();

    StepVerifier.create(stream("v-key", null).take(1))
        .then(() -> service.raise(NotifyDrafts.again(n)).block())
        .assertNext(e -> {
          assertThat(e.data().notification().read()).isTrue();
          assertThat(e.data().notification().dismissed()).isTrue();
        })
        .expectComplete()
        .verify(WAIT);
  }

  @Test
  void liveEventTheReplayAlreadySentIsSkipped() {
    final java.util.concurrent.atomic.AtomicLong sent =
        new java.util.concurrent.atomic.AtomicLong(5);
    final ServerSentEvent<NotificationEvent> old =
        ServerSentEvent.<NotificationEvent>builder().id("epoch-5").build();
    final ServerSentEvent<NotificationEvent> fresh =
        ServerSentEvent.<NotificationEvent>builder().id("epoch-6").build();
    final ServerSentEvent<NotificationEvent> receipt =
        ServerSentEvent.<NotificationEvent>builder().build();

    assertThat(NotificationsController.after(old, sent)).isFalse();
    assertThat(NotificationsController.after(fresh, sent)).isTrue();
    assertThat(NotificationsController.after(receipt, sent)).isTrue();
  }

  @Test
  void liveChangeToItemTheCallerOnlyReadIsReadButNotDismissed() {
    final Notification n = raise("a", "s1", "role:VIEWER");
    service.markRead("viewer", List.of(n.id())).block();

    StepVerifier.create(stream("v-key", null).take(1))
        .then(() -> service.raise(NotifyDrafts.again(n)).block())
        .assertNext(e -> {
          assertThat(e.data().notification().read()).isTrue();
          assertThat(e.data().notification().dismissed()).isFalse();
        })
        .expectComplete()
        .verify(WAIT);
  }

  @Test
  void anEventIdWeDidNotMakeIsLetThrough() {
    final var sent = new java.util.concurrent.atomic.AtomicLong(5);
    assertThat(
            NotificationsController.after(
                org.springframework.http.codec.ServerSentEvent.<NotificationEvent>builder()
                    .id("x-nope")
                    .build(),
                sent))
        .isTrue();
  }
}
