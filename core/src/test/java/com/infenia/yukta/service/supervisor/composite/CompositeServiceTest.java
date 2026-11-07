// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor.composite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.plugin.control.ExecutionSupervisor;
import com.infenia.yukta.plugin.control.Supervisor;
import com.infenia.yukta.plugin.control.SupervisorAttachment;
import com.infenia.yukta.plugin.control.SupervisorCategory;
import com.infenia.yukta.plugin.control.SupervisorDescriptor;
import com.infenia.yukta.plugin.control.SupervisorFactory;
import com.infenia.yukta.plugin.control.SupervisorScope;
import com.infenia.yukta.plugin.core.ConfigField;
import com.infenia.yukta.service.ConflictException;
import com.infenia.yukta.service.log.InMemoryExecutionLogStore;
import com.infenia.yukta.service.supervisor.BoundSupervisor;
import com.infenia.yukta.service.supervisor.InMemorySupervisorStore;
import com.infenia.yukta.service.supervisor.SupervisorHost;
import com.infenia.yukta.service.supervisor.SupervisorService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
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
        public ExecutionSupervisor create(final Supervisor profile) {
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

  @Test
  void describesItselfAndDeclaresItsSettings() {
    final CompositeSupervisorFactory factory =
        new CompositeSupervisorFactory(
            new StaticListableBeanFactory().getBeanProvider(SupervisorResolver.class));

    final SupervisorDescriptor described = factory.describe();

    assertThat(factory.type()).isEqualTo("composite");
    assertThat(described.displayName()).isEqualTo("Composite");
    assertThat(described.category()).isEqualTo(SupervisorCategory.COMPOSITION);
    assertThat(described.uiDesign()).isPresent();
    assertThat(described.exampleSettings()).containsEntry("mode", "PARALLEL");
    assertThat(factory.getConfigSchema())
        .extracting(ConfigField::name)
        .containsExactly("mode", "children", "review", "quorum", "window");
  }

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

  private static Supervisor composite(final String child) {
    return new Supervisor(
        "comp", "composite", Map.of("mode", "PARALLEL", "children", List.of(child)), false);
  }

  @Test
  void compositeNeedsSavedChildrenAndRestartsOnChange() {
    final SupervisorService service = service();
    StepVerifier.create(service.saveSupervisor(composite("kid")))
        .expectErrorMessage("Unknown child profile: kid")
        .verify();
    StepVerifier.create(service.saveSupervisor(new Supervisor("kid", "leaf", Map.of(), false)))
        .expectNextCount(1)
        .verifyComplete();
    StepVerifier.create(service.saveSupervisor(composite("kid")))
        .expectNextCount(1)
        .verifyComplete();
    StepVerifier.create(
            service.attach(
                new SupervisorAttachment("b", "comp", new SupervisorScope.Global(), true)))
        .expectNextCount(1)
        .verifyComplete();

    StepVerifier.create(
            service.saveSupervisor(new Supervisor("kid", "leaf", Map.of("v", 2), false)))
        .expectNextCount(1)
        .verifyComplete();

    verify(host, times(2)).attach(ArgumentMatchers.any(BoundSupervisor.class));
  }

  @Test
  void unrelatedBindingsStayWhenChildChanges() {
    final SupervisorService service = service();
    service.saveSupervisor(new Supervisor("kid", "leaf", Map.of(), false)).block();
    service.saveSupervisor(new Supervisor("other", "leaf", Map.of(), false)).block();
    service
        .attach(new SupervisorAttachment("b", "other", new SupervisorScope.Global(), true))
        .block();

    service.saveSupervisor(new Supervisor("kid", "leaf", Map.of("v", 2), false)).block();

    verify(host, times(1)).attach(ArgumentMatchers.any(BoundSupervisor.class));
  }

  @Test
  void childOfCompositeCannotBeDeleted() {
    final SupervisorService service = service();
    service.saveSupervisor(new Supervisor("kid", "leaf", Map.of(), false)).block();
    service.saveSupervisor(composite("kid")).block();

    StepVerifier.create(service.deleteSupervisor("kid"))
        .expectError(ConflictException.class)
        .verify();
  }
}
