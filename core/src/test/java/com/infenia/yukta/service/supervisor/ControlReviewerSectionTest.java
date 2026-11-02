// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor;

import static com.infenia.yukta.service.supervisor.SupervisorTestSupport.providerOf;
import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.control.ControlRequest;
import com.infenia.yukta.plugin.control.Decision;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.plugin.control.ExecutionSupervisor;
import com.infenia.yukta.plugin.control.SupervisorScope;
import java.util.ArrayList;
import java.util.List;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** Tests that section supervisors review only the node commands of their section. */
@SuppressWarnings({
  "PMD.AvoidDuplicateLiterals",
  "PMD.AvoidFieldNameMatchingMethodName",
  "PMD.ShortMethodName"
})
@NoArgsConstructor
class ControlReviewerSectionTest {

  private static final SupervisorScope BUILD = SupervisorScope.parse("workflow:s/w//build.*");

  private static final SupervisorScope DEPLOY = SupervisorScope.parse("workflow:s/w//deploy.*");

  private static final SupervisorScope WORKFLOW = SupervisorScope.parse("workflow:s/w");

  @RequiredArgsConstructor
  private static final class Recorder implements ExecutionSupervisor {
    private final String id;
    private final int order;
    private final SupervisorScope scope;
    private final boolean allow;
    private final List<String> asked;

    @Override
    public String id() {
      return id;
    }

    @Override
    public int order() {
      return order;
    }

    @Override
    public SupervisorScope scope() {
      return scope;
    }

    @Override
    public Flux<ExecutionControlCommand> supervise(final Flux<ExecutionEvent> events) {
      return Flux.empty();
    }

    @Override
    public Mono<Decision> review(final ControlRequest request) {
      asked.add(id);
      return Mono.just(allow ? Decision.ALLOW : Decision.deny("no"));
    }
  }

  private static ControlRequest request(final ExecutionControlCommand command) {
    return new ControlRequest(command, null, "s", "w");
  }

  @Test
  void sectionReviewsNodeCommandsOfItsSectionOnly() {
    final List<String> asked = new ArrayList<>();
    final ControlReviewer reviewer =
        new ControlReviewer(
            providerOf(
                new Recorder("build", 1, BUILD, true, asked),
                new Recorder("deploy", 2, DEPLOY, true, asked)));

    StepVerifier.create(
            reviewer.review(
                request(new ExecutionControlCommand.PauseNodeCommand("e", "build.compile"))))
        .verifyComplete();
    assertThat(asked).containsExactly("build");

    asked.clear();
    StepVerifier.create(
            reviewer.review(request(new ExecutionControlCommand.PauseNodeCommand("e", "deploy.x"))))
        .verifyComplete();
    assertThat(asked).containsExactly("deploy");
  }

  @Test
  void workflowWideCommandIsReviewedByTheWorkflowSupervisorNotTheSection() {
    final List<String> asked = new ArrayList<>();
    final ControlReviewer reviewer =
        new ControlReviewer(
            providerOf(
                new Recorder("build", 1, BUILD, false, asked),
                new Recorder("workflow", 2, WORKFLOW, true, asked)));

    StepVerifier.create(
            reviewer.review(request(new ExecutionControlCommand.PauseWorkflowCommand("e"))))
        .verifyComplete();
    assertThat(asked).containsExactly("workflow");
  }

  @Test
  void sectionAndWorkflowSupervisorBothReviewNodeOfTheSection() {
    final List<String> asked = new ArrayList<>();
    final ControlReviewer reviewer =
        new ControlReviewer(
            providerOf(
                new Recorder("workflow", 2, WORKFLOW, true, asked),
                new Recorder("build", 1, BUILD, true, asked)));

    StepVerifier.create(
            reviewer.review(request(new ExecutionControlCommand.StepNodeCommand("e", "build.a"))))
        .verifyComplete();
    assertThat(asked).containsExactly("build", "workflow");
  }

  @Test
  void overlappingSectionsBothReviewAndTheFirstDenialWins() {
    final List<String> asked = new ArrayList<>();
    final ControlReviewer reviewer =
        new ControlReviewer(
            providerOf(
                new Recorder("wide", 2, BUILD, true, asked),
                new Recorder(
                    "narrow", 1, SupervisorScope.parse("workflow:w//build.a"), false, asked),
                new Recorder("later", 3, BUILD, false, asked)));

    StepVerifier.create(
            reviewer.review(request(new ExecutionControlCommand.StepNodeCommand("e", "build.a"))))
        .expectErrorMessage("narrow: no")
        .verify();
    assertThat(asked).containsExactly("narrow");

    asked.clear();
    StepVerifier.create(
            reviewer.review(request(new ExecutionControlCommand.StepNodeCommand("e", "build.b"))))
        .expectErrorMessage("later: no")
        .verify();
    assertThat(asked).containsExactly("wide", "later");
  }

  @Test
  void anInputAnswerIsReviewedByTheSectionOfTheAskingNode() {
    final List<String> asked = new ArrayList<>();
    final ControlReviewer reviewer =
        new ControlReviewer(providerOf(new Recorder("build", 1, BUILD, false, asked)));
    final ExecutionControlCommand escalate =
        new ExecutionControlCommand.EscalateInputCommand("e", "r", "why", null);

    StepVerifier.create(reviewer.review(new ControlRequest(escalate, null, "s", "w", "build.x")))
        .expectError(ControlRejectedException.class)
        .verify();
    StepVerifier.create(reviewer.review(new ControlRequest(escalate, null, "s", "w", "other")))
        .verifyComplete();
    assertThat(asked).containsExactly("build");
  }
}
