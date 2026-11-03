// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor.composite;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.plugin.control.ExecutionSupervisor;
import com.infenia.yukta.plugin.control.SupervisorBinding;
import com.infenia.yukta.plugin.control.SupervisorFactory;
import com.infenia.yukta.plugin.control.SupervisorProfile;
import com.infenia.yukta.plugin.control.SupervisorScope;
import com.infenia.yukta.service.ConflictException;
import com.infenia.yukta.service.log.InMemoryExecutionLogStore;
import com.infenia.yukta.service.supervisor.BoundSupervisor;
import com.infenia.yukta.service.supervisor.InMemorySupervisorStore;
import com.infenia.yukta.service.supervisor.SupervisorHost;
import com.infenia.yukta.service.supervisor.SupervisorService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.ShortMethodName"})
class CompositeServiceTest {

  private final SupervisorHost host = mock(SupervisorHost.class);

  private final SupervisorFactory leaf =
      new SupervisorFactory() {
        @Override
        public String type() {
          return "leaf";
        }

        @Override
        public ExecutionSupervisor create(final SupervisorProfile profile) {
          return new ExecutionSupervisor() {
            @Override
            public String id() {
              return profile.id();
            }

            @Override
            public Flux<ExecutionControlCommand> supervise(final Flux<ExecutionEvent> events) {
              return Flux.empty();
            }
          };
        }
      };

  private SupervisorService service() {
    final StaticListableBeanFactory beans = new StaticListableBeanFactory();
    final SupervisorResolver resolver =
        new SupervisorResolver(beans.getBeanProvider(SupervisorFactory.class));
    final var provider = new StaticListableBeanFactory();
    provider.addBean("resolver", resolver);
    final SupervisorFactory composite =
        new CompositeSupervisorFactory(provider.getBeanProvider(SupervisorResolver.class));
    beans.addBean("leaf", leaf);
    beans.addBean("composite", composite);
    final SupervisorService service =
        new SupervisorService(
            new InMemorySupervisorStore(),
            List.of(leaf, composite),
            host,
            new InMemoryExecutionLogStore());
    service.setResolver(resolver);
    return service;
  }

  private static SupervisorProfile composite(final String child) {
    return new SupervisorProfile(
        "comp", "composite", Map.of("mode", "PARALLEL", "children", List.of(child)), false);
  }

  @Test
  void compositeNeedsSavedChildrenAndRestartsOnChange() {
    final SupervisorService service = service();
    StepVerifier.create(service.saveProfile(composite("kid")))
        .expectErrorMessage("Unknown child profile: kid")
        .verify();
    StepVerifier.create(service.saveProfile(new SupervisorProfile("kid", "leaf", Map.of(), false)))
        .expectNextCount(1)
        .verifyComplete();
    StepVerifier.create(service.saveProfile(composite("kid"))).expectNextCount(1).verifyComplete();
    StepVerifier.create(
            service.attach(new SupervisorBinding("b", "comp", new SupervisorScope.Global(), true)))
        .expectNextCount(1)
        .verifyComplete();

    StepVerifier.create(
            service.saveProfile(new SupervisorProfile("kid", "leaf", Map.of("v", 2), false)))
        .expectNextCount(1)
        .verifyComplete();

    verify(host, times(2)).attach(any(BoundSupervisor.class));
  }

  @Test
  void unrelatedBindingsStayWhenChildChanges() {
    final SupervisorService service = service();
    service.saveProfile(new SupervisorProfile("kid", "leaf", Map.of(), false)).block();
    service.saveProfile(new SupervisorProfile("other", "leaf", Map.of(), false)).block();
    service.attach(new SupervisorBinding("b", "other", new SupervisorScope.Global(), true)).block();

    service.saveProfile(new SupervisorProfile("kid", "leaf", Map.of("v", 2), false)).block();

    verify(host, times(1)).attach(any(BoundSupervisor.class));
  }

  @Test
  void childOfCompositeCannotBeDeleted() {
    final SupervisorService service = service();
    service.saveProfile(new SupervisorProfile("kid", "leaf", Map.of(), false)).block();
    service.saveProfile(composite("kid")).block();

    StepVerifier.create(service.deleteProfile("kid")).expectError(ConflictException.class).verify();
  }
}
