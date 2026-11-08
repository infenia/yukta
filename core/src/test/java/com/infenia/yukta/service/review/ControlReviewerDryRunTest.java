// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.review;

import static com.infenia.yukta.service.review.ControlReviewerTest.REQUEST;
import static com.infenia.yukta.service.supervisor.SupervisorTestSupport.providerOf;

import com.infenia.yukta.plugin.control.Decision;
import com.infenia.yukta.plugin.control.SupervisorAttachment;
import com.infenia.yukta.plugin.control.SupervisorScope;
import com.infenia.yukta.service.review.ControlReviewerTest.Reviewer;
import com.infenia.yukta.testing.Fixtures;
import com.infenia.yukta.testsupport.YuktaSettings;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ControlReviewerDryRunTest {

  private static final String REFUSER = "refuser";

  private static ObjectProvider<DryRunRecorder> hostProvider(final DryRunRecorder host) {
    final var factory = new org.springframework.beans.factory.support.StaticListableBeanFactory();
    if (host != null) {
      factory.addBean("host", host);
    }
    return factory.getBeanProvider(DryRunRecorder.class);
  }

  @Test
  void review_dryRunSupervisor_isRecordedNotEnforced() {
    final var attachment =
        new SupervisorAttachment("try-it", "p", new SupervisorScope.Global(), true);
    final Reviewer refuser =
        new Reviewer("try-it", 0, _ -> Mono.just(Decision.deny("no")), new ArrayList<>());
    final ControlReviewer reviewer = ControlReviewerFixture.create(providerOf());
    final AttachedSupervisors attached = new AttachedSupervisors();
    attached.put(Fixtures.boundSupervisor(attachment, refuser, true));
    reviewer.setAttachedSupervisors(attached);

    StepVerifier.create(reviewer.review(REQUEST)).verifyComplete();
  }

  @Test
  void review_dryRunModeRecordsTheRefusalOnTheHost() {
    final Reviewer refuser =
        new Reviewer(REFUSER, 0, _ -> Mono.just(Decision.deny("no")), new ArrayList<>());
    final DryRunRecorder host = Mockito.mock(DryRunRecorder.class);
    final ControlReviewer reviewer = ControlReviewerFixture.create(providerOf(refuser));
    reviewer.setDryRun(
        hostProvider(host), YuktaSettings.bind(Map.of("yukta.supervisor.dry-run", "true")));

    StepVerifier.create(reviewer.review(REQUEST)).verifyComplete();

    Mockito.verify(host).wouldHaveRefused(REFUSER, REQUEST.command(), "no");
  }

  @Test
  void review_dryRunModeWithoutHostStillAllowsTheCommand() {
    final Reviewer refuser =
        new Reviewer(REFUSER, 0, _ -> Mono.just(Decision.deny("no")), new ArrayList<>());
    final ControlReviewer reviewer = ControlReviewerFixture.create(providerOf(refuser));
    reviewer.setDryRun(
        hostProvider(null), YuktaSettings.bind(Map.of("yukta.supervisor.dry-run", "true")));

    StepVerifier.create(reviewer.review(REQUEST)).verifyComplete();
  }

  @Test
  void review_enforcedRefusalIsNotRecordedAsDryRun() {
    final Reviewer refuser =
        new Reviewer(REFUSER, 0, _ -> Mono.just(Decision.deny("no")), new ArrayList<>());
    final DryRunRecorder host = Mockito.mock(DryRunRecorder.class);
    final ControlReviewer reviewer = ControlReviewerFixture.create(providerOf(refuser));
    reviewer.setDryRun(
        hostProvider(host), YuktaSettings.bind(Map.of("yukta.supervisor.dry-run", "false")));

    StepVerifier.create(reviewer.review(REQUEST))
        .expectErrorMessage("refuser: no")
        .verify(Duration.ofSeconds(5));

    Mockito.verify(host, Mockito.never()).wouldHaveRefused(REFUSER, REQUEST.command(), "no");
  }
}
