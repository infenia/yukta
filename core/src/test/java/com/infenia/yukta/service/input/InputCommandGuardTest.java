// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.input;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.control.ControlActor;
import com.infenia.yukta.plugin.input.InputRequest;
import com.infenia.yukta.plugin.input.InputRisk;
import com.infenia.yukta.plugin.input.Responder;
import com.infenia.yukta.security.ApiRole;
import java.util.concurrent.atomic.AtomicReference;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

/** The guard maps the sender to a responder, and a replacement authoriser takes over the rules. */
@NoArgsConstructor
class InputCommandGuardTest {

  private final InputFlow flow = new InputFlow();

  private InputRequest ask() {
    flow.ask(InputTestSupport.spec("k"));
    return flow.onlyOpen();
  }

  @Test
  void replacementAuthorizerDecidesInsteadOfTheDefaultRules() {
    final InputRequest request = ask();
    final AtomicReference<AnswerContext> seen = new AtomicReference<>();
    final InputCommandGuard guard =
        new InputCommandGuard(
            flow.leader,
            flow.broker,
            flow.actors,
            context -> {
              seen.set(context);
              return Mono.empty();
            });

    // a token caller the default rules would refuse: the replacement lets it through
    StepVerifier.create(
            guard
                .clear("exec", request.id(), false)
                .contextWrite(
                    Context.of(
                        ControlActor.CONTEXT_KEY,
                        "token:ops",
                        ControlActor.KIND_KEY,
                        ControlActor.Kind.TOKEN)))
        .assertNext(
            cleared -> {
              assertThat(cleared.responder())
                  .isEqualTo(new Responder(Responder.Type.EXTERNAL, "ops"));
              assertThat(cleared.request().id()).isEqualTo(request.id());
            })
        .verifyComplete();
    assertThat(seen.get().escalation()).isFalse();
    assertThat(seen.get().actor().type()).isEqualTo(ActorInfo.Type.EXTERNAL);
  }

  @Test
  void supervisorAndKeyBecomeSupervisorAndHumanResponders() {
    final InputRequest request = ask();
    flow.attach("guard", true, InputRisk.MEDIUM);
    final InputCommandGuard allowAll =
        new InputCommandGuard(flow.leader, flow.broker, flow.actors, context -> Mono.empty());

    StepVerifier.create(
            allowAll
                .clear("exec", request.id(), true)
                .contextWrite(InputFlow.asSupervisor("guard")))
        .assertNext(
            cleared ->
                assertThat(cleared.responder())
                    .isEqualTo(new Responder(Responder.Type.SUPERVISOR, "guard")))
        .verifyComplete();
    StepVerifier.create(
            allowAll
                .clear("exec", request.id(), false)
                .contextWrite(InputFlow.asKey("alice", ApiRole.ADMIN)))
        .assertNext(
            cleared ->
                assertThat(cleared.responder())
                    .isEqualTo(new Responder(Responder.Type.HUMAN, "alice")))
        .verifyComplete();
  }
}
