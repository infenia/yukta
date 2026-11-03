// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.input.token;

import static com.infenia.yukta.service.input.InputTestSupport.limits;
import static com.infenia.yukta.service.input.InputTestSupport.who;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.plugin.control.ControlActor;
import com.infenia.yukta.plugin.input.Audience;
import com.infenia.yukta.plugin.input.InputKind;
import com.infenia.yukta.plugin.input.InputOption;
import com.infenia.yukta.plugin.input.InputRequest;
import com.infenia.yukta.plugin.input.InputRequestSpec;
import com.infenia.yukta.plugin.input.InputRisk;
import com.infenia.yukta.plugin.input.TimeoutAction;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import com.infenia.yukta.service.input.InMemoryInputRequestStore;
import com.infenia.yukta.service.input.InputBroker;
import com.infenia.yukta.service.input.InputTestSupport.MutableClock;
import com.infenia.yukta.service.input.route.FinalAction;
import com.infenia.yukta.service.input.route.InputRoute;
import com.infenia.yukta.service.input.route.Mode;
import com.infenia.yukta.service.input.route.ResponderType;
import com.infenia.yukta.service.input.route.RouteResolver;
import com.infenia.yukta.service.input.route.Tier;
import com.infenia.yukta.service.log.InMemoryExecutionLogStore;
import com.infenia.yukta.service.redact.InputRedactor;
import com.infenia.yukta.service.supervisor.ExecutionEventFeed;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.context.ContextView;

/** A one-time token answers for the tier it was delivered to, once, and only for a good reason. */
@NoArgsConstructor
@SuppressWarnings({
  "PMD.AvoidDuplicateLiterals",
  "PMD.ExcessiveImports",
  "PMD.TooManyMethods",
  "PMD.TooManyStaticImports",
  "PMD.UnitTestShouldIncludeAssert"
})
class TokenAnswerServiceTest {

  private final MutableClock clock = new MutableClock();
  private final InMemoryInputRequestStore store = new InMemoryInputRequestStore(100);
  private final AnswerTokens tokens = new AnswerTokens("test-secret", clock);
  private final ControlBusGateway controlBus = mock(ControlBusGateway.class);
  private final AtomicReference<ContextView> seen = new AtomicReference<>();
  private InputBroker broker;
  private TokenAnswerService service;

  private static InputRoute route(
      final String name, final ResponderType type, final String responder) {
    return new InputRoute(
        name,
        List.of(new Tier("hook", List.of(responder), null, Mode.FIRST, 0, 0, type, null)),
        FinalAction.FAIL);
  }

  @BeforeEach
  void setUp() {
    final RouteResolver routes =
        new RouteResolver(
            Map.of(
                "human", route("human", ResponderType.HUMAN, "external:webhook:ops"),
                "auto", route("auto", ResponderType.AUTO, "external:webhook:ops"),
                "internal", route("internal", ResponderType.HUMAN, "role:OPERATOR")),
            null);
    broker =
        new InputBroker(
            store,
            new ExecutionEventFeed(),
            new InMemoryExecutionLogStore(),
            new SimpleMeterRegistry(),
            new InputRedactor(List.of()),
            limits(),
            clock,
            routes,
            null);
    service = new TokenAnswerService(broker, tokens, routes, controlBus, new SimpleMeterRegistry());
    when(controlBus.answerInput(
            anyString(), anyString(), any(), anyString(), any(), any(), any(), any()))
        .thenAnswer(
            call ->
                Mono.deferContextual(
                    ctx -> {
                      seen.set(ctx);
                      return Mono.empty();
                    }));
  }

  private InputRequest ask(final String run, final String route, final InputRisk risk) {
    final InputRequestSpec spec =
        new InputRequestSpec(
            "key-" + run,
            InputKind.APPROVAL,
            "Deploy?",
            Map.of(),
            List.of(new InputOption("approve", "Approve"), new InputOption("reject", "Reject")),
            null,
            risk,
            Audience.CURRENT_TIER,
            Duration.ofHours(2),
            TimeoutAction.FAIL,
            null,
            route,
            "TEST");
    broker.ask(who(run, "node"), spec).subscribe(r -> {}, e -> {});
    return store.listOpen(null, run).blockFirst();
  }

  private TokenAnswerService.Answer answer(final InputRequest request, final long expires) {
    return new TokenAnswerService.Answer(
        request.id(),
        request.tier(),
        expires,
        tokens.mint(request.id(), request.tier(), Instant.ofEpochSecond(expires)),
        "approve",
        "ok",
        Map.of(),
        "because");
  }

  private long expiry() {
    return clock.instant().plus(Duration.ofHours(1)).getEpochSecond();
  }

  private void expectRefused(final TokenAnswerService.Answer answer) {
    StepVerifier.create(service.answer(answer))
        .expectErrorMessage(InvalidAnswerTokenException.MESSAGE)
        .verify();
  }

  @Test
  void goodTokenAnswersAsTheTierThroughTheControlBus() {
    final InputRequest request = ask("e1", "human", InputRisk.MEDIUM);

    StepVerifier.create(service.answer(answer(request, expiry()))).verifyComplete();

    verify(controlBus)
        .answerInput(
            eq("e1"),
            eq(request.id()),
            any(),
            eq("approve"),
            eq("ok"),
            any(),
            eq("because"),
            any());
    assertThat(seen.get().<String>get(ControlActor.CONTEXT_KEY)).isEqualTo("token:hook");
    assertThat(seen.get().<ControlActor.Kind>get(ControlActor.KIND_KEY))
        .isEqualTo(ControlActor.Kind.TOKEN);
  }

  @Test
  void tokenWorksOnce() {
    final InputRequest request = ask("e1", "human", InputRisk.MEDIUM);
    final TokenAnswerService.Answer answer = answer(request, expiry());

    StepVerifier.create(service.answer(answer)).verifyComplete();
    expectRefused(answer);

    verify(controlBus, times(1))
        .answerInput(anyString(), anyString(), any(), anyString(), any(), any(), any(), any());
  }

  @Test
  void tokenIsGivenBackWhenTheAnswerFails() {
    final InputRequest request = ask("e1", "human", InputRisk.MEDIUM);
    when(controlBus.answerInput(
            anyString(), anyString(), any(), anyString(), any(), any(), any(), any()))
        .thenReturn(Mono.error(new IllegalStateException("no")))
        .thenReturn(Mono.empty());
    final TokenAnswerService.Answer answer = answer(request, expiry());

    StepVerifier.create(service.answer(answer)).expectError(IllegalStateException.class).verify();
    StepVerifier.create(service.answer(answer)).verifyComplete();
  }

  @Test
  void everyKindOfBadTokenIsTheSameRefusal() {
    final InputRequest request = ask("e1", "human", InputRisk.MEDIUM);
    final long expires = expiry();
    final TokenAnswerService.Answer good = answer(request, expires);

    expectRefused(
        new TokenAnswerService.Answer(
            "unknown", good.tier(), expires, good.token(), "approve", null, null, null));
    expectRefused(
        new TokenAnswerService.Answer(
            good.requestId(), "other", expires, good.token(), "approve", null, null, null));
    expectRefused(
        new TokenAnswerService.Answer(
            good.requestId(), good.tier(), expires, "tampered", "approve", null, null, null));
    expectRefused(
        new TokenAnswerService.Answer(
            good.requestId(), good.tier(), expires + 1, good.token(), "approve", null, null, null));
    clock.advance(Duration.ofHours(2));
    expectRefused(good);

    verify(controlBus, never())
        .answerInput(anyString(), anyString(), any(), anyString(), any(), any(), any(), any());
  }

  @Test
  void tokenOfAnotherTierOfTheRequestIsRefused() {
    final InputRequest request = ask("e1", "human", InputRisk.MEDIUM);
    final long expires = expiry();
    // a good token, made for a tier the request is not in
    final TokenAnswerService.Answer other =
        new TokenAnswerService.Answer(
            request.id(),
            "other",
            expires,
            tokens.mint(request.id(), "other", Instant.ofEpochSecond(expires)),
            "approve",
            null,
            null,
            null);

    expectRefused(other);
  }

  @Test
  void requestWhoseTierIsNoLongerInItsRouteIsRefused() {
    final InputRequest base = ask("e1", "human", InputRisk.MEDIUM);
    final InputRequest moved =
        new InputRequest(
            base.id(),
            base.key(),
            base.sessionId(),
            base.workflowId(),
            base.executionId(),
            base.nodeId(),
            base.spec(),
            base.state(),
            base.version() + 1,
            "gone",
            base.targets(),
            null,
            base.deadline(),
            base.createdAt(),
            base.updatedAt());
    store.compareAndSet(base.id(), base.version(), moved).block();

    expectRefused(answer(moved, expiry()));
  }

  @Test
  void anEndedRequestIsRefusedLikeAnUnknownOne() {
    final InputRequest request = ask("e1", "human", InputRisk.MEDIUM);
    broker.cancel(request.id(), "gone").block();

    expectRefused(answer(request, expiry()));
  }

  @Test
  void tierWithNoExternalResponderCannotBeAnsweredWithToken() {
    final InputRequest request = ask("e1", "internal", InputRisk.MEDIUM);

    expectRefused(answer(request, expiry()));
  }

  @Test
  void highRiskRequestNeedsTierOfTypeHuman() {
    final InputRequest human = ask("e1", "human", InputRisk.HIGH);
    final InputRequest auto = ask("e2", "auto", InputRisk.HIGH);
    final InputRequest autoMedium = ask("e3", "auto", InputRisk.MEDIUM);

    StepVerifier.create(service.answer(answer(human, expiry()))).verifyComplete();
    expectRefused(answer(auto, expiry()));
    StepVerifier.create(service.answer(answer(autoMedium, expiry()))).verifyComplete();
  }
}
