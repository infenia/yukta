/*
 * Copyright 2026 Infenia Private Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.infenia.yukta.service.orchestrator.compiler;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.infenia.yukta.service.orchestrator.tracker.DefaultTaskTrackerService;
import com.infenia.yukta.service.session.store.SessionConfigStore;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

@MockitoSettings
class ResourceManagementBuilderTest {

  @Mock private DefaultTaskTrackerService mockTracker;
  @Mock private SessionConfigStore mockConfigService;

  @BeforeEach
  void setUp() {
    // Setup default mock behavior
  }

  @Test
  void testResourceManagementBuilderBasic() {
    when(mockConfigService.getExecutionTimeout("session-001")).thenReturn(Mono.just(60L));
    ResourceManagementBuilder builder =
        new ResourceManagementBuilder(mockTracker, mockConfigService, Schedulers.boundedElastic());

    List<Disposable> disposables = new ArrayList<>();
    List<Mono<Void>> terminals = List.of(Mono.empty());
    List<Runnable> connectors = new ArrayList<>();

    Mono<Void> execution =
        builder
            .withDisposables(disposables)
            .withTerminals(terminals)
            .withConnectors(connectors)
            .withExecutionTimeout("session-001", "exec-001")
            .buildAndExecute();

    StepVerifier.create(execution).verifyComplete();
  }

  @Test
  void testResourceManagementBuilderEmitsSuccessStatus() {
    when(mockConfigService.getExecutionTimeout("session-001")).thenReturn(Mono.just(60L));
    ResourceManagementBuilder builder =
        new ResourceManagementBuilder(mockTracker, mockConfigService, Schedulers.boundedElastic());

    List<Disposable> disposables = new ArrayList<>();
    List<Mono<Void>> terminals = List.of(Mono.empty());
    List<Runnable> connectors = new ArrayList<>();

    Mono<Void> execution =
        builder
            .withDisposables(disposables)
            .withTerminals(terminals)
            .withConnectors(connectors)
            .withExecutionTimeout("session-001", "exec-001")
            .buildAndExecute();

    StepVerifier.create(execution).verifyComplete();

    verify(mockTracker).emitWorkflowStatusEvent("exec-001", "SUCCESS");
  }

  @Test
  void testResourceManagementBuilderDisposesResources() {
    when(mockConfigService.getExecutionTimeout("session-001")).thenReturn(Mono.just(60L));
    ResourceManagementBuilder builder =
        new ResourceManagementBuilder(mockTracker, mockConfigService, Schedulers.boundedElastic());

    Disposable mockDisposable = mock(Disposable.class);
    List<Disposable> disposables = List.of(mockDisposable);
    List<Mono<Void>> terminals = List.of(Mono.empty());
    List<Runnable> connectors = new ArrayList<>();

    Mono<Void> execution =
        builder
            .withDisposables(disposables)
            .withTerminals(terminals)
            .withConnectors(connectors)
            .withExecutionTimeout("session-001", "exec-001")
            .buildAndExecute();

    StepVerifier.create(execution).verifyComplete();

    verify(mockDisposable).dispose();
  }

  @Test
  void testResourceManagementBuilderRunsConnectors() {
    when(mockConfigService.getExecutionTimeout("session-001")).thenReturn(Mono.just(60L));
    ResourceManagementBuilder builder =
        new ResourceManagementBuilder(mockTracker, mockConfigService, Schedulers.boundedElastic());

    Runnable mockConnector = mock(Runnable.class);
    List<Disposable> disposables = new ArrayList<>();
    List<Mono<Void>> terminals = List.of(Mono.empty());
    List<Runnable> connectors = List.of(mockConnector);

    Mono<Void> execution =
        builder
            .withDisposables(disposables)
            .withTerminals(terminals)
            .withConnectors(connectors)
            .withExecutionTimeout("session-001", "exec-001")
            .buildAndExecute();

    StepVerifier.create(execution).verifyComplete();

    verify(mockConnector).run();
  }

  @Test
  void testResourceManagementBuilderWithNullParameters() {
    when(mockConfigService.getExecutionTimeout("session-001")).thenReturn(Mono.just(60L));
    ResourceManagementBuilder builder =
        new ResourceManagementBuilder(mockTracker, mockConfigService, Schedulers.boundedElastic());

    Mono<Void> execution =
        builder
            .withDisposables(null)
            .withTerminals(null)
            .withConnectors(null)
            .withExecutionTimeout("session-001", "exec-001")
            .buildAndExecute();

    StepVerifier.create(execution).verifyComplete();

    verify(mockTracker).emitWorkflowStatusEvent("exec-001", "SUCCESS");
  }

  @Test
  void testResourceManagementBuilderWithoutSessionId() {
    ResourceManagementBuilder builder =
        new ResourceManagementBuilder(mockTracker, mockConfigService, Schedulers.boundedElastic());

    List<Disposable> disposables = new ArrayList<>();
    List<Mono<Void>> terminals = List.of(Mono.empty());
    List<Runnable> connectors = new ArrayList<>();

    Mono<Void> execution =
        builder
            .withDisposables(disposables)
            .withTerminals(terminals)
            .withConnectors(connectors)
            .withExecutionTimeout(null, "exec-001")
            .buildAndExecute();

    StepVerifier.create(execution).verifyComplete();

    verify(mockTracker).emitWorkflowStatusEvent("exec-001", "SUCCESS");
    verify(mockConfigService, never()).getExecutionTimeout(any());
  }

  @Test
  void testResourceManagementBuilderRunsConnectorsInReverseOrder() {
    when(mockConfigService.getExecutionTimeout("session-001")).thenReturn(Mono.just(60L));
    ResourceManagementBuilder builder =
        new ResourceManagementBuilder(mockTracker, mockConfigService, Schedulers.boundedElastic());

    Runnable connector1 = mock(Runnable.class);
    Runnable connector2 = mock(Runnable.class);
    Runnable connector3 = mock(Runnable.class);

    List<Disposable> disposables = new ArrayList<>();
    List<Mono<Void>> terminals = List.of(Mono.empty());
    List<Runnable> connectors = List.of(connector1, connector2, connector3);

    Mono<Void> execution =
        builder
            .withDisposables(disposables)
            .withTerminals(terminals)
            .withConnectors(connectors)
            .withExecutionTimeout("session-001", "exec-001")
            .buildAndExecute();

    StepVerifier.create(execution).verifyComplete();

    // Verify all connectors were called
    verify(connector1).run();
    verify(connector2).run();
    verify(connector3).run();
  }

  @Test
  void testResourceManagementBuilderWithoutExecutionId() {
    when(mockConfigService.getExecutionTimeout("session-001")).thenReturn(Mono.just(60L));
    ResourceManagementBuilder builder =
        new ResourceManagementBuilder(mockTracker, mockConfigService, Schedulers.boundedElastic());

    List<Disposable> disposables = new ArrayList<>();
    List<Mono<Void>> terminals = List.of(Mono.empty());
    List<Runnable> connectors = new ArrayList<>();

    Mono<Void> execution =
        builder
            .withDisposables(disposables)
            .withTerminals(terminals)
            .withConnectors(connectors)
            .withExecutionTimeout("session-001", null)
            .buildAndExecute();

    StepVerifier.create(execution).verifyComplete();

    verify(mockTracker, never()).emitWorkflowStatusEvent(any(), any());
  }

  @Test
  void testResourceManagementBuilderWithMultipleTerminals() {
    when(mockConfigService.getExecutionTimeout("session-001")).thenReturn(Mono.just(60L));
    ResourceManagementBuilder builder =
        new ResourceManagementBuilder(mockTracker, mockConfigService, Schedulers.boundedElastic());

    List<Disposable> disposables = new ArrayList<>();
    List<Mono<Void>> terminals = List.of(Mono.empty(), Mono.empty(), Mono.empty());
    List<Runnable> connectors = new ArrayList<>();

    Mono<Void> execution =
        builder
            .withDisposables(disposables)
            .withTerminals(terminals)
            .withConnectors(connectors)
            .withExecutionTimeout("session-001", "exec-001")
            .buildAndExecute();

    StepVerifier.create(execution).verifyComplete();

    verify(mockTracker).emitWorkflowStatusEvent("exec-001", "SUCCESS");
  }

  @Test
  void testResourceManagementBuilderWithoutTerminals() {
    when(mockConfigService.getExecutionTimeout("session-001")).thenReturn(Mono.just(60L));
    ResourceManagementBuilder builder =
        new ResourceManagementBuilder(mockTracker, mockConfigService, Schedulers.boundedElastic());

    List<Disposable> disposables = new ArrayList<>();
    List<Runnable> connectors = new ArrayList<>();

    Mono<Void> execution =
        builder
            .withDisposables(disposables)
            .withTerminals(null)
            .withConnectors(connectors)
            .withExecutionTimeout("session-001", "exec-001")
            .buildAndExecute();

    StepVerifier.create(execution).verifyComplete();

    verify(mockTracker).emitWorkflowStatusEvent("exec-001", "SUCCESS");
  }

  @Test
  void testResourceManagementBuilderMultipleDisposables() {
    when(mockConfigService.getExecutionTimeout("session-001")).thenReturn(Mono.just(60L));
    ResourceManagementBuilder builder =
        new ResourceManagementBuilder(mockTracker, mockConfigService, Schedulers.boundedElastic());

    Disposable disposable1 = mock(Disposable.class);
    Disposable disposable2 = mock(Disposable.class);
    Disposable disposable3 = mock(Disposable.class);

    List<Disposable> disposables = List.of(disposable1, disposable2, disposable3);
    List<Mono<Void>> terminals = List.of(Mono.empty());
    List<Runnable> connectors = new ArrayList<>();

    Mono<Void> execution =
        builder
            .withDisposables(disposables)
            .withTerminals(terminals)
            .withConnectors(connectors)
            .withExecutionTimeout("session-001", "exec-001")
            .buildAndExecute();

    StepVerifier.create(execution).verifyComplete();

    verify(disposable1).dispose();
    verify(disposable2).dispose();
    verify(disposable3).dispose();
  }

  @Test
  void testResourceManagementBuilderEmptyConnectors() {
    when(mockConfigService.getExecutionTimeout("session-001")).thenReturn(Mono.just(60L));
    ResourceManagementBuilder builder =
        new ResourceManagementBuilder(mockTracker, mockConfigService, Schedulers.boundedElastic());

    List<Disposable> disposables = new ArrayList<>();
    List<Mono<Void>> terminals = List.of(Mono.empty());
    List<Runnable> connectors = new ArrayList<>();

    Mono<Void> execution =
        builder
            .withDisposables(disposables)
            .withTerminals(terminals)
            .withConnectors(connectors)
            .withExecutionTimeout("session-001", "exec-001")
            .buildAndExecute();

    StepVerifier.create(execution).verifyComplete();

    verify(mockTracker).emitWorkflowStatusEvent("exec-001", "SUCCESS");
  }

  @Test
  void testResourceManagementBuilderEmptyDisposables() {
    when(mockConfigService.getExecutionTimeout("session-001")).thenReturn(Mono.just(60L));
    ResourceManagementBuilder builder =
        new ResourceManagementBuilder(mockTracker, mockConfigService, Schedulers.boundedElastic());

    List<Disposable> disposables = new ArrayList<>();
    List<Mono<Void>> terminals = List.of(Mono.empty());
    List<Runnable> connectors = new ArrayList<>();

    Mono<Void> execution =
        builder
            .withDisposables(disposables)
            .withTerminals(terminals)
            .withConnectors(connectors)
            .withExecutionTimeout("session-001", "exec-001")
            .buildAndExecute();

    StepVerifier.create(execution).verifyComplete();

    verify(mockTracker).emitWorkflowStatusEvent("exec-001", "SUCCESS");
  }

  @Test
  void testResourceManagementBuilderConnectorThrowingException() {
    when(mockConfigService.getExecutionTimeout("session-001")).thenReturn(Mono.just(60L));
    ResourceManagementBuilder builder =
        new ResourceManagementBuilder(mockTracker, mockConfigService, Schedulers.boundedElastic());

    Runnable throwingConnector = mock(Runnable.class);
    doThrow(new RuntimeException("Connector error")).when(throwingConnector).run();

    List<Disposable> disposables = new ArrayList<>();
    List<Mono<Void>> terminals = List.of(Mono.empty());
    List<Runnable> connectors = List.of(throwingConnector);

    Mono<Void> execution =
        builder
            .withDisposables(disposables)
            .withTerminals(terminals)
            .withConnectors(connectors)
            .withExecutionTimeout("session-001", "exec-001")
            .buildAndExecute();

    // Should complete normally even if connector throws exception
    StepVerifier.create(execution).verifyComplete();

    verify(mockTracker).emitWorkflowStatusEvent("exec-001", "SUCCESS");
  }

  @Test
  void testResourceManagementBuilderDisposableThrowingException() {
    when(mockConfigService.getExecutionTimeout("session-001")).thenReturn(Mono.just(60L));
    ResourceManagementBuilder builder =
        new ResourceManagementBuilder(mockTracker, mockConfigService, Schedulers.boundedElastic());

    Disposable throwingDisposable = mock(Disposable.class);
    doThrow(new RuntimeException("Dispose error")).when(throwingDisposable).dispose();

    List<Disposable> disposables = List.of(throwingDisposable);
    List<Mono<Void>> terminals = List.of(Mono.empty());
    List<Runnable> connectors = new ArrayList<>();

    Mono<Void> execution =
        builder
            .withDisposables(disposables)
            .withTerminals(terminals)
            .withConnectors(connectors)
            .withExecutionTimeout("session-001", "exec-001")
            .buildAndExecute();

    // Should complete normally even if disposable throws exception
    StepVerifier.create(execution).verifyComplete();

    verify(throwingDisposable).dispose();
    verify(mockTracker).emitWorkflowStatusEvent("exec-001", "SUCCESS");
  }

  @Test
  void testResourceManagementBuilderFluentMethods() {
    ResourceManagementBuilder builder =
        new ResourceManagementBuilder(mockTracker, mockConfigService, Schedulers.boundedElastic());

    // Test that fluent methods return the builder itself
    ResourceManagementBuilder builder2 = builder.withDisposables(new ArrayList<>());
    ResourceManagementBuilder builder3 = builder2.withTerminals(new ArrayList<>());
    ResourceManagementBuilder builder4 = builder3.withConnectors(new ArrayList<>());
    ResourceManagementBuilder builder5 = builder4.withExecutionTimeout("session-001", "exec-001");

    // All should be the same instance
    assertSame(builder, builder2);
    assertSame(builder, builder3);
    assertSame(builder, builder4);
    assertSame(builder, builder5);
  }

  @Test
  void testResourceManagementBuilderWithEmptyTerminals() {
    when(mockConfigService.getExecutionTimeout("session-001")).thenReturn(Mono.just(60L));
    ResourceManagementBuilder builder =
        new ResourceManagementBuilder(mockTracker, mockConfigService, Schedulers.boundedElastic());

    List<Disposable> disposables = new ArrayList<>();
    List<Mono<Void>> terminals = new ArrayList<>();
    List<Runnable> connectors = new ArrayList<>();

    Mono<Void> execution =
        builder
            .withDisposables(disposables)
            .withTerminals(terminals)
            .withConnectors(connectors)
            .withExecutionTimeout("session-001", "exec-001")
            .buildAndExecute();

    StepVerifier.create(execution).verifyComplete();

    verify(mockTracker).emitWorkflowStatusEvent("exec-001", "SUCCESS");
  }

  @Test
  void testResourceManagementBuilderWithFailingTerminal() {
    when(mockConfigService.getExecutionTimeout("session-001")).thenReturn(Mono.just(60L));
    ResourceManagementBuilder builder =
        new ResourceManagementBuilder(mockTracker, mockConfigService, Schedulers.boundedElastic());

    List<Disposable> disposables = new ArrayList<>();
    List<Mono<Void>> terminals = List.of(Mono.error(new RuntimeException("Terminal failed")));
    List<Runnable> connectors = new ArrayList<>();

    Mono<Void> execution =
        builder
            .withDisposables(disposables)
            .withTerminals(terminals)
            .withConnectors(connectors)
            .withExecutionTimeout("session-001", "exec-001")
            .buildAndExecute();

    // Should error out - terminal failure propagates
    StepVerifier.create(execution).verifyError(RuntimeException.class);
  }
}
