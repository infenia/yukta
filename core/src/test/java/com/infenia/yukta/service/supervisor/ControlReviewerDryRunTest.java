// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor;

import static com.infenia.yukta.service.supervisor.ControlReviewerTest.REQUEST;
import static com.infenia.yukta.service.supervisor.SupervisorTestSupport.providerOf;

import com.infenia.yukta.plugin.control.Decision;
import com.infenia.yukta.plugin.control.SupervisorBinding;
import com.infenia.yukta.plugin.control.SupervisorScope;
import com.infenia.yukta.service.supervisor.ControlReviewerTest.Reviewer;
import java.time.Duration;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ControlReviewerDryRunTest {

  private static final String REFUSER = "refuser";

  private static ObjectProvider<SupervisorHost> hostProvider(final SupervisorHost host) {
    final var factory = new org.springframework.beans.factory.support.StaticListableBeanFactory();
    if (host != null) {
      factory.addBean("host", host);
    }
    return factory.getBeanProvider(SupervisorHost.class);
  }

  @Test
  void review_dryRunSupervisor_isRecordedNotEnforced() {
    final var binding = new SupervisorBinding("try-it", "p", new SupervisorScope.Global(), true);
    final Reviewer refuser =
        new Reviewer("try-it", 0, _ -> Mono.just(Decision.deny("no")), new ArrayList<>());
    final ControlReviewer reviewer = new ControlReviewer(providerOf());
    final AttachedSupervisors attached = new AttachedSupervisors();
    attached.put(new BoundSupervisor(binding, refuser, true));
    reviewer.setAttachedSupervisors(attached);

    StepVerifier.create(reviewer.review(REQUEST)).verifyComplete();
  }

  @Test
  void review_dryRunModeRecordsTheRefusalOnTheHost() {
    final Reviewer refuser =
        new Reviewer(REFUSER, 0, _ -> Mono.just(Decision.deny("no")), new ArrayList<>());
    final SupervisorHost host = Mockito.mock(SupervisorHost.class);
    final ControlReviewer reviewer = new ControlReviewer(providerOf(refuser));
    reviewer.setDryRun(hostProvider(host), true);

    StepVerifier.create(reviewer.review(REQUEST)).verifyComplete();

    Mockito.verify(host).wouldHaveRefused(REFUSER, REQUEST.command(), "no");
  }

  @Test
  void review_dryRunModeWithoutHostStillAllowsTheCommand() {
    final Reviewer refuser =
        new Reviewer(REFUSER, 0, _ -> Mono.just(Decision.deny("no")), new ArrayList<>());
    final ControlReviewer reviewer = new ControlReviewer(providerOf(refuser));
    reviewer.setDryRun(hostProvider(null), true);

    StepVerifier.create(reviewer.review(REQUEST)).verifyComplete();
  }

  @Test
  void review_enforcedRefusalIsNotRecordedAsDryRun() {
    final Reviewer refuser =
        new Reviewer(REFUSER, 0, _ -> Mono.just(Decision.deny("no")), new ArrayList<>());
    final SupervisorHost host = Mockito.mock(SupervisorHost.class);
    final ControlReviewer reviewer = new ControlReviewer(providerOf(refuser));
    reviewer.setDryRun(hostProvider(host), false);

    StepVerifier.create(reviewer.review(REQUEST))
        .expectErrorMessage("refuser: no")
        .verify(Duration.ofSeconds(5));

    Mockito.verify(host, Mockito.never()).wouldHaveRefused(REFUSER, REQUEST.command(), "no");
  }
}
