// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.input;

import static com.infenia.yukta.service.input.InputTestSupport.limits;
import static com.infenia.yukta.service.input.InputTestSupport.who;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.model.session.SessionSnapshot;
import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.plugin.control.SupervisorScope;
import com.infenia.yukta.plugin.input.Audience;
import com.infenia.yukta.plugin.input.InputKind;
import com.infenia.yukta.plugin.input.InputOption;
import com.infenia.yukta.plugin.input.InputRequest;
import com.infenia.yukta.plugin.input.InputRequestSpec;
import com.infenia.yukta.plugin.input.InputResponse;
import com.infenia.yukta.plugin.input.InputRisk;
import com.infenia.yukta.plugin.input.InputState;
import com.infenia.yukta.plugin.input.Responder;
import com.infenia.yukta.plugin.input.TimeoutAction;
import com.infenia.yukta.service.input.InputTestSupport.MutableClock;
import com.infenia.yukta.service.input.route.FinalAction;
import com.infenia.yukta.service.input.route.InputRoute;
import com.infenia.yukta.service.input.route.Mode;
import com.infenia.yukta.service.input.route.ResponderType;
import com.infenia.yukta.service.input.route.RouteResolver;
import com.infenia.yukta.service.input.route.Tier;
import com.infenia.yukta.service.log.InMemoryExecutionLogStore;
import com.infenia.yukta.service.redact.InputRedactor;
import com.infenia.yukta.service.session.store.SessionConfigStore;
import com.infenia.yukta.service.supervisor.ExecutionEventFeed;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** Routes: tiers, their timeouts, confidence, votes and the supervisors a tier reaches. */
@NoArgsConstructor
@SuppressWarnings({
  "PMD.TooManyMethods",
  "PMD.ExcessiveImports",
  "PMD.AvoidDuplicateLiterals",
  "PMD.CouplingBetweenObjects",
  "PMD.TooManyStaticImports",
  "PMD.UseConcurrentHashMap"
})
class InputBrokerRoutesTest {

  private static final SupervisorScope WORKFLOW =
      SupervisorScope.parse("workflow:session/workflow");

  private static final SupervisorScope SECTION =
      SupervisorScope.parse("workflow:session/workflow//node");

  private static final SupervisorScope OTHER_SECTION =
      SupervisorScope.parse("workflow:session/workflow//elsewhere");

  private static final Duration THIRTY_SECONDS = Duration.ofSeconds(30);

  private final InMemoryInputRequestStore store = new InMemoryInputRequestStore(100);
  private final ExecutionEventFeed feed = new ExecutionEventFeed();
  private final MutableClock clock = new MutableClock();
  private final List<ExecutionEvent> events = new CopyOnWriteArrayList<>();
  private final List<EligibleSupervisors.Candidate> attached = new ArrayList<>();
  private final Map<String, InputRoute> routes = new LinkedHashMap<>();
  private final SessionConfigStore sessions = mock(SessionConfigStore.class);

  private static Tier auto(final String selector, final Duration timeout) {
    return new Tier(
        "auto", List.of(selector), timeout, Mode.FIRST, 0, 0.8, ResponderType.AUTO, null);
  }

  private static Tier humans(final Duration timeout) {
    return new Tier(
        "ops", List.of("role:OPERATOR"), timeout, Mode.FIRST, 0, 0, ResponderType.HUMAN, null);
  }

  private static Tier voting(final Mode mode, final int quorum) {
    return new Tier(
        "panel", List.of("supervisor:*"), null, mode, quorum, 0, ResponderType.AUTO, null);
  }

  private static InputRequestSpec spec(final String key, final String route) {
    return new InputRequestSpec(
        key,
        InputKind.APPROVAL,
        "Deploy?",
        Map.of(),
        List.of(new InputOption("approve", "Approve"), new InputOption("reject", "Reject")),
        null,
        InputRisk.LOW,
        Audience.CURRENT_TIER,
        Duration.ofHours(2),
        TimeoutAction.FAIL,
        null,
        route,
        "TEST");
  }

  private static InputRequestSpec specWithDefault(final String key, final String route) {
    return new InputRequestSpec(
        key,
        InputKind.APPROVAL,
        "Deploy?",
        Map.of(),
        List.of(new InputOption("approve", "Approve"), new InputOption("reject", "Reject")),
        null,
        InputRisk.LOW,
        Audience.CURRENT_TIER,
        Duration.ofHours(2),
        TimeoutAction.USE_DEFAULT,
        "reject",
        route,
        "TEST");
  }

  private static InputRequestSpec escalating(final String key, final String route) {
    return new InputRequestSpec(
        key,
        InputKind.APPROVAL,
        "Deploy?",
        Map.of(),
        List.of(new InputOption("approve", "Approve"), new InputOption("reject", "Reject")),
        null,
        InputRisk.LOW,
        Audience.CURRENT_TIER,
        Duration.ofMinutes(5),
        TimeoutAction.ESCALATE,
        null,
        route,
        "TEST");
  }

  @SuppressWarnings("unchecked")
  private InputBroker broker() {
    final ObjectProvider<EligibleSupervisors> directory = mock(ObjectProvider.class);
    when(directory.getIfAvailable()).thenReturn(() -> List.copyOf(attached));
    final ObjectProvider<SessionConfigStore> sessionStore = mock(ObjectProvider.class);
    when(sessionStore.getIfAvailable()).thenReturn(sessions);
    feed.events().subscribe(events::add);
    return new InputBroker(
        store,
        feed,
        new InMemoryExecutionLogStore(),
        new SimpleMeterRegistry(),
        new InputRedactor(List.of()),
        limits(),
        clock,
        new RouteResolver(routes, sessionStore),
        directory);
  }

  private void supervisor(final String id, final SupervisorScope scope) {
    attached.add(new EligibleSupervisors.Candidate(id, scope));
  }

  private static InputRoute route(final String name, final FinalAction last, final Tier... tiers) {
    return new InputRoute(name, List.of(tiers), last);
  }

  private InputRequest open(final InputBroker broker, final InputRequestSpec spec) {
    broker.ask(who("e1", "node"), spec).subscribe(r -> {}, e -> {});
    return store.listOpen(null, null).blockFirst();
  }

  private InputRequest current(final InputRequest request) {
    return store.findById(request.id()).block();
  }

  private static InputResponse answer(
      final InputRequest request,
      final Responder who,
      final String decision,
      final Double confidence) {
    return new InputResponse(
        request.id(),
        decision,
        null,
        Map.of(),
        who,
        "because",
        confidence,
        Instant.parse("2026-11-02T10:00:01Z"));
  }

  private static Responder bot(final String id) {
    return new Responder(Responder.Type.SUPERVISOR, id);
  }

  @Test
  void requestStartsInTheFirstTierOfItsNamedRoute() {
    routes.put(
        "release", route("release", null, auto("supervisor:*", THIRTY_SECONDS), humans(null)));
    supervisor("watch", WORKFLOW);
    final InputBroker broker = broker();

    final InputRequest request = open(broker, spec("k1", "release"));

    assertThat(request.tier()).isEqualTo("auto");
    assertThat(request.targets()).containsExactly("supervisor:*");
    assertThat(request.tierDeadline()).isEqualTo(clock.instant().plus(THIRTY_SECONDS));
    assertThat(request.deadline()).isEqualTo(clock.instant().plus(Duration.ofHours(2)));
  }

  @Test
  void anUnknownRouteNameIsRefused() {
    StepVerifier.create(broker().ask(who("e1", "node"), spec("k1", "nope")))
        .expectErrorMessage("No input route is called 'nope'")
        .verify();
  }

  @Test
  void theDefaultRouteAppliesWhenNothingIsNamedAndTheBuiltInOtherwise() {
    final InputBroker builtIn = broker();
    assertThat(open(builtIn, spec("k1", null)).tier()).isEqualTo("default");

    routes.put("default", route("default", FinalAction.FAIL, humans(Duration.ofHours(1))));
    final InputBroker configured = broker();
    assertThat(openAnother(configured, "k2", "e2", null).tier()).isEqualTo("ops");
  }

  @Test
  void sessionNamesItsRouteInTheInputRouteTag() {
    routes.put("ops-route", route("ops-route", FinalAction.FAIL, humans(null)));
    final SessionSnapshot snapshot = mock(SessionSnapshot.class);
    when(snapshot.tags()).thenReturn(Map.of("input.route", "ops-route"));
    when(sessions.getAllConfigs("session")).thenReturn(Mono.just(snapshot));

    assertThat(open(broker(), spec("k1", null)).tier()).isEqualTo("ops");
  }

  @Test
  void sessionWithoutTheTagOrWithFailingLookupUsesTheDefault() {
    routes.put("default", route("default", FinalAction.FAIL, humans(null)));
    final SessionSnapshot snapshot = mock(SessionSnapshot.class);
    when(snapshot.tags()).thenReturn(Map.of());
    when(sessions.getAllConfigs("session")).thenReturn(Mono.just(snapshot));
    assertThat(open(broker(), spec("k1", null)).tier()).isEqualTo("ops");

    when(sessions.getAllConfigs("session")).thenReturn(Mono.error(new IllegalStateException("x")));
    final InputBroker again = broker();
    again.ask(who("e2", "node"), spec("k2", null)).subscribe(r -> {}, e -> {});
    assertThat(store.listOpen(null, "e2").blockFirst().tier()).isEqualTo("ops");
  }

  @Test
  void tierThatTimesOutPassesTheRequestOnAndTheLastOneEndsIt() {
    routes.put(
        "release",
        route(
            "release",
            FinalAction.FAIL,
            auto("supervisor:*", THIRTY_SECONDS),
            humans(Duration.ofHours(1))));
    supervisor("watch", WORKFLOW);
    final InputBroker broker = broker();
    final InputRequest request = open(broker, spec("k1", "release"));

    clock.advance(Duration.ofSeconds(31));
    broker.sweep().block();
    final InputRequest second = current(request);
    assertThat(second.tier()).isEqualTo("ops");
    assertThat(second.state()).isEqualTo(InputState.ESCALATED);
    assertThat(second.version()).isEqualTo(1);
    assertThat(second.targets()).containsExactly("role:OPERATOR");
    assertThat(second.tierDeadline()).isEqualTo(clock.instant().plus(Duration.ofHours(1)));
    assertThat(events).anyMatch(ExecutionEvent.InputEscalated.class::isInstance);

    clock.advance(Duration.ofMinutes(61));
    broker.sweep().block();
    assertThat(current(request).state()).isEqualTo(InputState.EXPIRED);
  }

  @Test
  void theFinalActionCanAnswerWithTheDefaultOrFollowTheRequest() {
    routes.put("a", route("a", FinalAction.USE_DEFAULT, humans(Duration.ofMinutes(1))));
    routes.put("b", route("b", FinalAction.USE_DEFAULT, humans(Duration.ofMinutes(1))));
    routes.put("c", route("c", null, humans(Duration.ofMinutes(1))));
    routes.put("d", route("d", null, humans(Duration.ofMinutes(1))));
    final InputBroker broker = broker();
    final InputRequest withDefault = open(broker, specWithDefault("k1", "a"));
    broker.ask(who("e2", "node"), spec("k2", "b")).subscribe(r -> {}, e -> {});
    broker.ask(who("e3", "node"), specWithDefault("k3", "c")).subscribe(r -> {}, e -> {});
    broker.ask(who("e4", "node"), spec("k4", "d")).subscribe(r -> {}, e -> {});

    clock.advance(Duration.ofMinutes(2));
    broker.sweep().block();

    assertThat(current(withDefault).state()).isEqualTo(InputState.ANSWERED);
    assertThat(store.listOpen(null, "e2").collectList().block()).isEmpty();
    assertThat(store.findAnsweredByKey("k3").block()).isNotNull();
    assertThat(store.findAnsweredByKey("k2").block()).isNull();
    assertThat(store.listOpen(null, "e4").collectList().block()).isEmpty();
  }

  @Test
  void tierWithNoSupervisorToAskIsSkippedAtOnce() {
    routes.put(
        "release",
        route("release", FinalAction.FAIL, auto("supervisor:*", THIRTY_SECONDS), humans(null)));
    final InputBroker broker = broker();

    broker.ask(who("e1", "node"), spec("k1", "release")).subscribe(r -> {}, e -> {});

    final InputRequest request = store.listOpen(null, null).blockFirst();
    assertThat(request.tier()).isEqualTo("ops");
    assertThat(request.state()).isEqualTo(InputState.ESCALATED);
  }

  @Test
  void supervisorsOfOtherNodesAreNotAskedAndTheLastTierStaysWhereItIs() {
    routes.put(
        "only-auto", route("only-auto", FinalAction.FAIL, auto("supervisor:*", THIRTY_SECONDS)));
    supervisor("elsewhere", OTHER_SECTION);
    final InputBroker broker = broker();

    final InputRequest request = open(broker, spec("k1", "only-auto"));

    assertThat(request.tier()).isEqualTo("auto");
    assertThat(request.state()).isEqualTo(InputState.OPEN);
  }

  @Test
  void lowConfidenceAnswerBecomesAdviceAndMovesTheRequestUp() {
    routes.put(
        "release", route("release", FinalAction.FAIL, auto("supervisor:*", null), humans(null)));
    supervisor("watch", WORKFLOW);
    final InputBroker broker = broker();
    final InputRequest low = open(broker, spec("k1", "release"));
    broker.answer(low.id(), null, answer(low, bot("watch"), "approve", 0.5)).block();
    final InputRequest missing = openAnother(broker, "k2", "e2");
    broker.answer(missing.id(), null, answer(missing, bot("watch"), "approve", null)).block();

    for (final InputRequest request : List.of(low, missing)) {
      final InputRequest now = current(request);
      assertThat(now.state()).isEqualTo(InputState.ESCALATED);
      assertThat(now.tier()).isEqualTo("ops");
      assertThat(now.advice()).contains("supervisor watch answered approve");
    }
  }

  private InputRequest openAnother(final InputBroker broker, final String key, final String run) {
    return openAnother(broker, key, run, "release");
  }

  private InputRequest openAnother(
      final InputBroker broker, final String key, final String run, final String route) {
    broker.ask(who(run, "node"), spec(key, route)).subscribe(r -> {}, e -> {});
    return store.listOpen(null, run).blockFirst();
  }

  @Test
  void sureEnoughAnswerEndsTheRequestAndPersonAlwaysDoes() {
    routes.put(
        "release", route("release", FinalAction.FAIL, auto("supervisor:*", null), humans(null)));
    supervisor("watch", WORKFLOW);
    final InputBroker broker = broker();
    final InputRequest sure = open(broker, spec("k1", "release"));
    broker.answer(sure.id(), null, answer(sure, bot("watch"), "approve", 0.95)).block();
    final InputRequest byPerson = openAnother(broker, "k2", "e2");
    broker
        .answer(
            byPerson.id(),
            null,
            answer(byPerson, new Responder(Responder.Type.HUMAN, "ann"), "reject", null))
        .block();

    assertThat(current(sure).state()).isEqualTo(InputState.ANSWERED);
    assertThat(current(byPerson).state()).isEqualTo(InputState.ANSWERED);
  }

  @Test
  void lowConfidenceAnswerInTheLastTierIsKeptAsAdviceAndFlagsTheRequest() {
    routes.put("only-auto", route("only-auto", FinalAction.FAIL, auto("supervisor:*", null)));
    supervisor("watch", WORKFLOW);
    final InputBroker broker = broker();
    final InputRequest request = open(broker, spec("k1", "only-auto"));

    broker.answer(request.id(), null, answer(request, bot("watch"), "approve", 0.1)).block();

    final InputRequest now = current(request);
    assertThat(now.state()).isEqualTo(InputState.ESCALATED);
    assertThat(now.tier()).isEqualTo("auto");
    assertThat(now.advice()).contains("approve");
  }

  @Test
  void quorumDecidesWhenEnoughSupervisorsAgree() {
    routes.put("panel", route("panel", FinalAction.FAIL, voting(Mode.QUORUM, 2), humans(null)));
    supervisor("one", WORKFLOW);
    supervisor("two", WORKFLOW);
    supervisor("three", WORKFLOW);
    final InputBroker broker = broker();
    final InputRequest request = open(broker, spec("k1", "panel"));

    broker.answer(request.id(), null, answer(request, bot("one"), "approve", 1.0)).block();
    assertThat(current(request).state()).isEqualTo(InputState.OPEN);
    assertThat(current(request).votes()).hasSize(1);

    // a second vote from the same supervisor changes nothing
    broker.answer(request.id(), null, answer(request, bot("one"), "reject", 1.0)).block();
    assertThat(current(request).votes()).hasSize(1);

    broker.answer(request.id(), null, answer(request, bot("two"), "approve", 1.0)).block();
    final InputRequest decided = current(request);
    assertThat(decided.state()).isEqualTo(InputState.ANSWERED);
    assertThat(store.findResponse(request.id()).block().rationale()).contains("voted by one, two");
    assertThat(store.findResponse(request.id()).block().decision()).isEqualTo("approve");
  }

  @Test
  void splitVoteMovesTheRequestUp() {
    routes.put("panel", route("panel", FinalAction.FAIL, voting(Mode.QUORUM, 2), humans(null)));
    supervisor("one", WORKFLOW);
    supervisor("two", WORKFLOW);
    final InputBroker broker = broker();
    final InputRequest request = open(broker, spec("k1", "panel"));

    broker.answer(request.id(), null, answer(request, bot("one"), "approve", 1.0)).block();
    broker.answer(request.id(), null, answer(request, bot("two"), "reject", 1.0)).block();

    final InputRequest now = current(request);
    assertThat(now.tier()).isEqualTo("ops");
    assertThat(now.votes()).isEmpty();
  }

  @Test
  void fewerSupervisorsThanTheQuorumSkipsTheTierAtOnce() {
    routes.put("panel", route("panel", FinalAction.FAIL, voting(Mode.QUORUM, 3), humans(null)));
    supervisor("one", WORKFLOW);
    supervisor("two", WORKFLOW);
    final InputBroker broker = broker();

    broker.ask(who("e1", "node"), spec("k1", "panel")).subscribe(r -> {}, e -> {});

    assertThat(store.listOpen(null, null).blockFirst().tier()).isEqualTo("ops");
  }

  @Test
  void unanimityNeedsEveryEligibleSupervisorToAgree() {
    routes.put("panel", route("panel", FinalAction.FAIL, voting(Mode.UNANIMOUS, 0), humans(null)));
    supervisor("one", WORKFLOW);
    supervisor("two", WORKFLOW);
    final InputBroker broker = broker();
    final InputRequest agreed = open(broker, spec("k1", "panel"));
    broker.answer(agreed.id(), null, answer(agreed, bot("one"), "approve", 1.0)).block();
    assertThat(current(agreed).state()).isEqualTo(InputState.OPEN);
    broker.answer(agreed.id(), null, answer(agreed, bot("two"), "approve", 1.0)).block();
    assertThat(current(agreed).state()).isEqualTo(InputState.ANSWERED);

    final InputRequest split = openAnother(broker, "k2", "e2", "panel");
    broker.answer(split.id(), null, answer(split, bot("one"), "approve", 1.0)).block();
    broker.answer(split.id(), null, answer(split, bot("two"), "reject", 1.0)).block();
    assertThat(current(split).tier()).isEqualTo("ops");
  }

  @Test
  void supervisorThatEscalatesIsFollowedByTheOnesAboveIt() {
    final Tier above =
        new Tier(
            "above", List.of("supervisor:above"), null, Mode.FIRST, 0, 0, ResponderType.AUTO, null);
    routes.put(
        "chain", route("chain", FinalAction.FAIL, auto("supervisor:*", null), above, humans(null)));
    supervisor("section", SECTION);
    supervisor("workflow", WORKFLOW);
    supervisor("peer", SECTION);
    final InputBroker broker = broker();
    final InputRequest request = open(broker, spec("k1", "chain"));

    broker.escalate(request.id(), "unsure", null, bot("section")).block();

    final InputRequest now = current(request);
    assertThat(now.tier()).isEqualTo("above");
    assertThat(now.targets()).containsExactly("supervisor:workflow");
  }

  @Test
  void aboveFollowsTheTierBeforeWhenNoSupervisorEscalated() {
    final Tier above =
        new Tier(
            "above",
            List.of("supervisor:above"),
            THIRTY_SECONDS,
            Mode.FIRST,
            0,
            0,
            ResponderType.AUTO,
            null);
    final Tier first =
        new Tier(
            "first",
            List.of("supervisor:section"),
            THIRTY_SECONDS,
            Mode.FIRST,
            0,
            0,
            ResponderType.AUTO,
            null);
    routes.put("chain", route("chain", FinalAction.FAIL, first, above, humans(null)));
    supervisor("section", SECTION);
    supervisor("workflow", WORKFLOW);
    final InputBroker broker = broker();
    final InputRequest request = open(broker, spec("k1", "chain"));

    clock.advance(Duration.ofSeconds(31));
    broker.sweep().block();

    assertThat(current(request).targets()).containsExactly("supervisor:workflow");
  }

  @Test
  void aboveWithNobodyAboveSkipsToTheNextTier() {
    final Tier above =
        new Tier(
            "above", List.of("supervisor:above"), null, Mode.FIRST, 0, 0, ResponderType.AUTO, null);
    routes.put(
        "chain", route("chain", FinalAction.FAIL, auto("supervisor:*", null), above, humans(null)));
    supervisor("workflow", WORKFLOW);
    final InputBroker broker = broker();
    final InputRequest request = open(broker, spec("k1", "chain"));

    broker.escalate(request.id(), "unsure", null, bot("workflow")).block();

    assertThat(current(request).tier()).isEqualTo("ops");
  }

  @Test
  void escalatingWithNothingHigherJustFlagsTheRequestAndUnknownOrEndedRequestsAreLeftAlone() {
    final InputBroker broker = broker();
    final InputRequest request = open(broker, spec("k1", null));

    broker.escalate(request.id(), "unsure", "ask Ann", null).block();
    assertThat(current(request).state()).isEqualTo(InputState.ESCALATED);
    assertThat(current(request).tier()).isEqualTo("default");

    broker.cancel(request.id(), "gone").block();
    assertThat(broker.escalate(request.id(), "again", null, null).block().state())
        .isEqualTo(InputState.CANCELLED);
    assertThat(broker.escalate("missing", "x", null, null).block()).isNull();
  }

  @Test
  void requestThatAsksToEscalateAtItsDeadlineMovesUpAndThenEnds() {
    routes.put(
        "release", route("release", FinalAction.FAIL, auto("supervisor:*", null), humans(null)));
    supervisor("watch", WORKFLOW);
    final InputBroker broker = broker();
    final InputRequest request = open(broker, escalating("k1", "release"));

    clock.advance(Duration.ofMinutes(6));
    broker.sweep().block();
    assertThat(current(request).tier()).isEqualTo("ops");

    broker.sweep().block();
    assertThat(current(request).state()).isEqualTo(InputState.EXPIRED);
  }

  private InputRequest stored(
      final String run, final String tier, final Instant deadline, final Instant tierDeadline) {
    final InputRequest request =
        new InputRequest(
            "id-" + run,
            "key-" + run,
            "session",
            "workflow",
            run,
            "node",
            spec("key-" + run, "release"),
            InputState.OPEN,
            0,
            tier,
            List.of("supervisor:*", "role:OPERATOR"),
            null,
            deadline,
            tierDeadline,
            List.of(),
            clock.instant(),
            clock.instant());
    return store.createIfAbsent(request).block();
  }

  private static InputResponse bare(
      final InputRequest request, final Responder who, final String decision) {
    return new InputResponse(
        request.id(),
        decision,
        null,
        Map.of(),
        who,
        null,
        1.0,
        Instant.parse("2026-11-02T10:00:01Z"));
  }

  @Test
  void requestInTierThatNoLongerExistsIsAnsweredAsIsAndFlaggedWhenHandedUp() {
    routes.put(
        "release", route("release", FinalAction.FAIL, auto("supervisor:*", null), humans(null)));
    final InputBroker broker = broker();
    final InputRequest answered = stored("e8", "gone", clock.instant().plusSeconds(3600), null);
    final InputRequest flagged = stored("e9", "gone", clock.instant().plusSeconds(3600), null);

    broker.answer(answered.id(), null, bare(answered, bot("watch"), "approve")).block();
    broker.escalate(flagged.id(), "unsure", null, null).block();

    assertThat(current(answered).state()).isEqualTo(InputState.ANSWERED);
    assertThat(current(flagged).state()).isEqualTo(InputState.ESCALATED);
    assertThat(current(flagged).tier()).isEqualTo("gone");
  }

  @Test
  void supervisorAnswerToAnUnknownOrEndedRequestChangesNothing() {
    routes.put(
        "release", route("release", FinalAction.FAIL, auto("supervisor:*", null), humans(null)));
    supervisor("watch", WORKFLOW);
    final InputBroker broker = broker();
    final InputRequest request = open(broker, spec("k1", "release"));
    broker.cancel(request.id(), "gone").block();

    assertThat(
            broker
                .answer(request.id(), null, bare(request, bot("watch"), "approve"))
                .block()
                .state())
        .isEqualTo(InputState.CANCELLED);
    assertThat(
            broker
                .answer(
                    "missing",
                    null,
                    new InputResponse(
                        "missing",
                        "approve",
                        null,
                        Map.of(),
                        bot("watch"),
                        null,
                        1.0,
                        clock.instant()))
                .block())
        .isNull();
  }

  @Test
  void lowConfidenceAdviceWithoutRationaleAndVoteAtStaleVersionAreHandled() {
    routes.put(
        "release", route("release", FinalAction.FAIL, auto("supervisor:*", null), humans(null)));
    routes.put("panel", route("panel", FinalAction.FAIL, voting(Mode.QUORUM, 2), humans(null)));
    supervisor("watch", WORKFLOW);
    supervisor("two", WORKFLOW);
    final InputBroker broker = broker();
    final InputRequest unsure = open(broker, spec("k1", "release"));
    broker
        .answer(
            unsure.id(),
            null,
            new InputResponse(
                unsure.id(), "approve", null, Map.of(), bot("watch"), null, 0.1, clock.instant()))
        .block();
    assertThat(current(unsure).advice()).isEqualTo("supervisor watch answered approve");

    final InputRequest panel = openAnother(broker, "k2", "e2", "panel");
    broker.answer(panel.id(), 99L, bare(panel, bot("watch"), "approve")).block();
    assertThat(current(panel).votes()).isEmpty();

    broker.answer(panel.id(), null, bare(panel, bot("watch"), "approve")).block();
    broker.answer(panel.id(), null, bare(panel, bot("two"), "approve")).block();
    assertThat(store.findResponse(panel.id()).block().rationale()).isEqualTo("voted by watch, two");
  }

  @Test
  void humanWhoEscalatesLeavesTheSupervisorsAboveToTheTierBefore() {
    final Tier above =
        new Tier(
            "above", List.of("supervisor:above"), null, Mode.FIRST, 0, 0, ResponderType.AUTO, null);
    final Tier first =
        new Tier(
            "first",
            List.of("supervisor:section"),
            null,
            Mode.FIRST,
            0,
            0,
            ResponderType.AUTO,
            null);
    routes.put("chain", route("chain", FinalAction.FAIL, first, above, humans(null)));
    supervisor("section", SECTION);
    supervisor("workflow", WORKFLOW);
    final InputBroker broker = broker();
    final InputRequest request = open(broker, spec("k1", "chain"));

    broker
        .escalate(request.id(), "unsure", null, new Responder(Responder.Type.HUMAN, "ann"))
        .block();

    assertThat(current(request).targets()).containsExactly("supervisor:workflow");
  }

  @Test
  void tierThatRanOutIsHandedUpEvenWhenTheRequestHasNoDeadline() {
    routes.put(
        "release", route("release", FinalAction.FAIL, auto("supervisor:*", null), humans(null)));
    supervisor("watch", WORKFLOW);
    final InputBroker broker = broker();
    final InputRequest request = stored("e7", "auto", null, clock.instant().minusSeconds(1));

    broker.sweep().block();

    assertThat(current(request).tier()).isEqualTo("ops");
  }
}
