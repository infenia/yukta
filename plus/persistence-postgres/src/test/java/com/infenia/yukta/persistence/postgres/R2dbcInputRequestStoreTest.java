// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.persistence.postgres;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.infenia.yukta.plugin.input.Vote;
import com.infenia.yukta.service.input.delivery.InputDelivery;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;

/** What the shared contract does not cover: the whole request survives, and two writers race. */
@NoArgsConstructor
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class R2dbcInputRequestStoreTest extends PostgresTestSupport {

  private static final Instant T0 = Instant.parse("2026-11-02T10:00:00Z");

  private final R2dbcInputRequestStore store =
      new R2dbcInputRequestStore(db, JsonMapper.builder().build());

  private static InputRequestSpec spec(final String key) {
    return new InputRequestSpec(
        key,
        InputKind.APPROVAL,
        "Deploy?",
        Map.of("env", "staging", "n", 3),
        List.of(new InputOption("approve", "Approve"), new InputOption("reject", "Reject")),
        null,
        InputRisk.MEDIUM,
        Audience.CURRENT_TIER,
        Duration.ofMinutes(90),
        TimeoutAction.FAIL,
        null,
        "release",
        "TEST");
  }

  private static InputRequest request(final String id, final String key) {
    return new InputRequest(
        id,
        key,
        "s",
        "w",
        "e1",
        "n",
        spec(key),
        InputState.OPEN,
        0,
        "auto",
        List.of("supervisor:*", "role:OPERATOR"),
        "look at the logs",
        T0.plusSeconds(5400),
        T0.plusSeconds(30),
        List.of(new Vote("guard", "approve", 0.9, T0.plusSeconds(1))),
        T0,
        T0);
  }

  @Test
  void storedRequestComesBackWithEverythingItCarried() {
    final InputRequest request = request("r1", "k1");

    StepVerifier.create(store.createIfAbsent(request)).expectNext(request).verifyComplete();
    StepVerifier.create(store.findById("r1")).expectNext(request).verifyComplete();
  }

  @Test
  void changeKeepsTheTierTargetsAndVotes() {
    final InputRequest first = request("r1", "k1");
    store.createIfAbsent(first).block();
    final InputRequest next =
        new InputRequest(
            "r1",
            "k1",
            "s",
            "w",
            "e1",
            "n",
            first.spec(),
            InputState.ESCALATED,
            1,
            "ops",
            List.of("role:OPERATOR"),
            null,
            first.deadline(),
            T0.plusSeconds(3600),
            List.of(),
            T0,
            T0.plusSeconds(31));

    StepVerifier.create(store.compareAndSet("r1", 0, next)).expectNext(true).verifyComplete();
    StepVerifier.create(store.findById("r1")).expectNext(next).verifyComplete();
    StepVerifier.create(store.dueBefore(T0.plusSeconds(3601))).expectNext(next).verifyComplete();
  }

  @Test
  void changeOfAnotherRequestIsRefused() {
    final InputRequest first = request("r1", "k1");
    store.createIfAbsent(first).block();

    StepVerifier.create(store.compareAndSet("r1", 0, request("other", "k1")))
        .expectNext(false)
        .verifyComplete();
  }

  @Test
  void ofManyChangesAtOnceExactlyOneWins() {
    final InputRequest first = request("r1", "k1");
    store.createIfAbsent(first).block();
    final List<Mono<Boolean>> tries =
        java.util.stream.IntStream.range(0, 8)
            .mapToObj(
                i ->
                    store.compareAndSet(
                        "r1",
                        0,
                        new InputRequest(
                            "r1",
                            "k1",
                            "s",
                            "w",
                            "e1",
                            "n",
                            first.spec(),
                            InputState.ANSWERED,
                            1,
                            "auto",
                            List.of(),
                            "by " + i,
                            null,
                            null,
                            List.of(),
                            T0,
                            T0.plusSeconds(1))))
            .toList();

    final List<Boolean> results = Flux.merge(tries).collectList().block();

    assertThat(results).filteredOn(Boolean::booleanValue).hasSize(1);
    assertThat(store.findById("r1").block().state()).isEqualTo(InputState.ANSWERED);
  }

  @Test
  void ofManyAsksWithOneKeyAtOnceAllGetTheSameRequest() {
    final List<Mono<InputRequest>> asks =
        java.util.stream.IntStream.range(0, 8)
            .mapToObj(i -> store.createIfAbsent(request("r" + i, "same")))
            .toList();

    final List<String> ids =
        Flux.merge(asks).map(InputRequest::id).distinct().collectList().block();

    assertThat(ids).hasSize(1);
    assertThat(store.listOpen(null, null).collectList().block()).hasSize(1);
  }

  @Test
  void deliveriesAreDueUntilSentAndGoWithTheirRequest() {
    store.createIfAbsent(request("r1", "k1")).block();
    final InputDelivery delivery =
        new InputDelivery(
            "d1", "r1", "auto", "external:webhook:ops", InputDelivery.State.PENDING, 0, T0, null);
    store.enqueueDelivery(delivery).block();

    StepVerifier.create(store.dueDeliveries(T0)).expectNext(delivery).verifyComplete();
    StepVerifier.create(store.dueDeliveries(T0.minusSeconds(1))).verifyComplete();

    final InputDelivery retry = delivery.failed("503", T0.plusSeconds(60), false);
    store.saveDelivery(retry).block();
    StepVerifier.create(store.dueDeliveries(T0.plusSeconds(30))).verifyComplete();
    StepVerifier.create(store.dueDeliveries(T0.plusSeconds(60))).expectNext(retry).verifyComplete();

    store.saveDelivery(retry.sent()).block();
    StepVerifier.create(store.dueDeliveries(T0.plusSeconds(600))).verifyComplete();
  }

  @Test
  void purgeTakesTheAnswersAndTheDeliveriesOfWhatItRemoves() {
    final InputRequest first = request("r1", "k1");
    store.createIfAbsent(first).block();
    store
        .compareAndSet(
            "r1",
            0,
            new InputRequest(
                "r1",
                "k1",
                "s",
                "w",
                "e1",
                "n",
                first.spec(),
                InputState.ANSWERED,
                1,
                "auto",
                List.of(),
                null,
                null,
                null,
                List.of(),
                T0,
                T0.plusSeconds(1)))
        .block();
    store
        .saveResponse(
            new InputResponse(
                "r1",
                "approve",
                null,
                Map.of(),
                new Responder(Responder.Type.HUMAN, "ann"),
                null,
                null,
                T0))
        .block();
    store
        .enqueueDelivery(
            new InputDelivery(
                "d1", "r1", "auto", "external:x", InputDelivery.State.SENT, 1, T0, null))
        .block();

    StepVerifier.create(store.purgeTerminalOlderThan(T0.plusSeconds(10)))
        .expectNext(1L)
        .verifyComplete();

    StepVerifier.create(store.findById("r1")).verifyComplete();
    StepVerifier.create(store.findResponse("r1")).verifyComplete();
    assertThat(
            db.sql("SELECT count(*) AS n FROM input_delivery")
                .map(row -> row.get("n", Long.class))
                .one()
                .block())
        .isZero();
  }
}
