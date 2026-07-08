# Step-Mode REST Endpoints Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expose `enableStepMode`, `disableStepMode`, and `stepNode` (already implemented in `ControlBusGateway`) as REST endpoints, and fix a validation gap where these three gateway methods skip the execution/node existence check that all sibling node-control methods perform.

**Architecture:** Follow the exact pattern already used by `pauseNode`/`resumeNode`/`skipNode`: gateway methods validate via `requireNodeControl(executionId, nodeId)` before emitting a command; controller methods delegate to the existing private `executeControlSignal(...)` helper, which handles progress lookup, session-ownership check, and 404 translation.

**Tech Stack:** Java 25, Spring Boot WebFlux, Project Reactor (`Mono`), JUnit 5, Mockito, `reactor-test` (`StepVerifier`), `WebTestClient`.

## Global Constraints

- Every Java file must carry the Apache License 2.0 header (Spotless-managed) — copy it verbatim from an existing file in the same module.
- Google Java Style: 2-space indent, 100-char line limit.
- Run `./gradlew spotlessApply` before considering any task done, and `./gradlew :core:test` / `./gradlew :web:test` to verify.
- No new abstractions: reuse `requireNodeControl`, `executeCommand`, `buildCommand`, `executeControlSignal` exactly as they exist today. Do not introduce new DTOs or response types.
- Follow Conventional Commits (`fix:`, `feat:`, `test:`) per `.claude/rules/git-workflow.md`.

---

### Task 1: Fix `DefaultControlBusGateway` step-mode methods to validate execution/node existence

**Files:**
- Modify: `core/src/main/java/com/infenia/yukta/service/control/gateway/DefaultControlBusGateway.java:570-629`
- Test: `core/src/test/java/com/infenia/yukta/service/control/gateway/DefaultControlBusGatewayTest.java`

**Interfaces:**
- Consumes: existing private helpers `requireNodeControl(String executionId, String nodeId): ExecutionControl` (throws `IllegalArgumentException` — "Execution not found: " + executionId, or "Node not found: " + nodeId), `executeCommand(Message<T>): Mono<Void>`, `buildCommand(T payload, int priority): Message<T>`, and constant `CONTROL_COMMAND_PRIORITY`.
- Produces: `enableStepMode`, `disableStepMode`, `stepNode` now reject unknown executions/nodes the same way `pauseNode`/`resumeNode`/`skipNode` do. No signature changes — `ControlBusGateway` interface is untouched.

- [ ] **Step 1: Write failing gateway tests for execution-not-found and node-not-found on all three step-mode methods**

Add these six tests to `DefaultControlBusGatewayTest.java`, immediately after the existing `stepNode_emitError_logsErrorAndPropagates` test (currently ending around line 1306, right before `restartWorkflow_emitError_logsErrorAndPropagates`):

```java
  @Test
  void enableStepMode_executionNotFound_throwsIllegalArgumentException() {
    // Given
    final String executionId = "exec-step-enable-not-found";
    final String nodeId = "node-x";
    when(executionControlRegistry.findByExecutionId(executionId)).thenReturn(Optional.empty());

    // When
    final Mono<Void> result = gateway.enableStepMode(executionId, nodeId);

    // Then
    StepVerifier.create(result)
        .expectErrorMatches(
            err ->
                err instanceof IllegalArgumentException
                    && err.getMessage().contains("Execution not found")
                    && err.getMessage().contains(executionId))
        .verify();
    verify(controlBusService, never()).emit(any());
  }

  @Test
  void enableStepMode_nodeNotFound_throwsIllegalArgumentException() {
    // Given
    final String executionId = "exec-step-enable-node-not-found";
    final String nodeId = "missing-node";
    stubNoNodes(executionId);

    // When
    final Mono<Void> result = gateway.enableStepMode(executionId, nodeId);

    // Then
    StepVerifier.create(result)
        .expectErrorMatches(
            err ->
                err instanceof IllegalArgumentException
                    && err.getMessage().contains("Node not found")
                    && err.getMessage().contains(nodeId))
        .verify();
    verify(controlBusService, never()).emit(any());
  }

  @Test
  void disableStepMode_executionNotFound_throwsIllegalArgumentException() {
    // Given
    final String executionId = "exec-step-disable-not-found";
    final String nodeId = "node-x";
    when(executionControlRegistry.findByExecutionId(executionId)).thenReturn(Optional.empty());

    // When
    final Mono<Void> result = gateway.disableStepMode(executionId, nodeId);

    // Then
    StepVerifier.create(result)
        .expectErrorMatches(
            err ->
                err instanceof IllegalArgumentException
                    && err.getMessage().contains("Execution not found")
                    && err.getMessage().contains(executionId))
        .verify();
    verify(controlBusService, never()).emit(any());
  }

  @Test
  void disableStepMode_nodeNotFound_throwsIllegalArgumentException() {
    // Given
    final String executionId = "exec-step-disable-node-not-found";
    final String nodeId = "missing-node";
    stubNoNodes(executionId);

    // When
    final Mono<Void> result = gateway.disableStepMode(executionId, nodeId);

    // Then
    StepVerifier.create(result)
        .expectErrorMatches(
            err ->
                err instanceof IllegalArgumentException
                    && err.getMessage().contains("Node not found")
                    && err.getMessage().contains(nodeId))
        .verify();
    verify(controlBusService, never()).emit(any());
  }

  @Test
  void stepNode_executionNotFound_throwsIllegalArgumentException() {
    // Given
    final String executionId = "exec-step-node-not-found";
    final String nodeId = "node-x";
    when(executionControlRegistry.findByExecutionId(executionId)).thenReturn(Optional.empty());

    // When
    final Mono<Void> result = gateway.stepNode(executionId, nodeId);

    // Then
    StepVerifier.create(result)
        .expectErrorMatches(
            err ->
                err instanceof IllegalArgumentException
                    && err.getMessage().contains("Execution not found")
                    && err.getMessage().contains(executionId))
        .verify();
    verify(controlBusService, never()).emit(any());
  }

  @Test
  void stepNode_nodeNotFound_throwsIllegalArgumentException() {
    // Given
    final String executionId = "exec-step-node-node-not-found";
    final String nodeId = "missing-node";
    stubNoNodes(executionId);

    // When
    final Mono<Void> result = gateway.stepNode(executionId, nodeId);

    // Then
    StepVerifier.create(result)
        .expectErrorMatches(
            err ->
                err instanceof IllegalArgumentException
                    && err.getMessage().contains("Node not found")
                    && err.getMessage().contains(nodeId))
        .verify();
    verify(controlBusService, never()).emit(any());
  }
```

- [ ] **Step 2: Run the new tests to verify they fail**

Run: `./gradlew :core:test --tests com.infenia.yukta.service.control.gateway.DefaultControlBusGatewayTest`

Expected: The 6 new tests FAIL — `verify(controlBusService, never()).emit(any())` fails because the current implementation calls `emit` unconditionally (no existence check), so `Mono.error` is never produced and `StepVerifier.expectErrorMatches` times out/fails instead.

- [ ] **Step 3: Update the three existing happy-path tests to stub a registered execution/node**

The existing tests `enableStepMode_validInputs_emitsEnableStepModeCommand` (line ~787),
`disableStepMode_validInputs_emitsDisableStepModeCommand` (line ~809), and
`stepNode_validInputs_emitsStepNodeCommand` (line ~831) currently only stub
`controlBusService.emit(any())`. Once `requireNodeControl` is added, these will fail with
"Execution not found" because `executionControlRegistry.findByExecutionId` is never
stubbed. Add a `stubNodeExists(executionId, nodeId);` call as the first line of the
`// Given` block in each of these three tests, e.g.:

```java
  @Test
  void enableStepMode_validInputs_emitsEnableStepModeCommand() {
    // Given
    final String executionId = "exec-9";
    final String nodeId = "node-8";
    stubNodeExists(executionId, nodeId);
    when(controlBusService.emit(any())).thenReturn(Mono.empty());
```

Apply the same one-line addition (`stubNodeExists(executionId, nodeId);` right after the
`nodeId` declaration) to the `disableStepMode_validInputs_emitsDisableStepModeCommand` and
`stepNode_validInputs_emitsStepNodeCommand` tests, using their respective `executionId`/
`nodeId` values (`"exec-10"`/`"node-9"` and `"exec-11"`/`"node-10"`).

Also check the three error-path tests `enableStepMode_emitError_logsErrorAndPropagates`,
`disableStepMode_emitError_logsErrorAndPropagates`, `stepNode_emitError_logsErrorAndPropagates`
(around lines 1264-1306): they use `executionId = "exec-error"` and `nodeId = "node-error"`
and only stub `controlBusService.emit(...)` to return `Mono.error(...)`. Add
`stubNodeExists(executionId, nodeId);` before that stub in all three so the flow reaches
`executeCommand` (and therefore `controlBusService.emit`) instead of failing earlier at
`requireNodeControl`.

- [ ] **Step 4: Fix `enableStepMode`, `disableStepMode`, `stepNode` to validate via `requireNodeControl`**

In `core/src/main/java/com/infenia/yukta/service/control/gateway/DefaultControlBusGateway.java`, replace lines 570-629 with:

```java
  @Override
  public Mono<Void> enableStepMode(final String executionId, final String nodeId) {
    return Mono.fromSupplier(() -> requireNodeControl(executionId, nodeId))
        .flatMap(
            control ->
                executeCommand(
                    buildCommand(
                        new EnableStepModeCommand(executionId, nodeId), CONTROL_COMMAND_PRIORITY)))
        .doOnSubscribe(
            _ ->
                log.atInfo()
                    .addKeyValue("executionId", executionId)
                    .addKeyValue("nodeId", nodeId)
                    .log("Enabling step mode"))
        .doOnSuccess(_ -> log.atDebug().addKeyValue("nodeId", nodeId).log("Step mode enabled"))
        .doOnError(
            err ->
                log.atError()
                    .setCause(err)
                    .addKeyValue("executionId", executionId)
                    .addKeyValue("nodeId", nodeId)
                    .log("Failed to enable step mode"));
  }

  @Override
  public Mono<Void> disableStepMode(final String executionId, final String nodeId) {
    return Mono.fromSupplier(() -> requireNodeControl(executionId, nodeId))
        .flatMap(
            control ->
                executeCommand(
                    buildCommand(
                        new DisableStepModeCommand(executionId, nodeId),
                        CONTROL_COMMAND_PRIORITY)))
        .doOnSubscribe(
            _ ->
                log.atInfo()
                    .addKeyValue("executionId", executionId)
                    .addKeyValue("nodeId", nodeId)
                    .log("Disabling step mode"))
        .doOnSuccess(_ -> log.atDebug().addKeyValue("nodeId", nodeId).log("Step mode disabled"))
        .doOnError(
            err ->
                log.atError()
                    .setCause(err)
                    .addKeyValue("executionId", executionId)
                    .addKeyValue("nodeId", nodeId)
                    .log("Failed to disable step mode"));
  }

  @Override
  public Mono<Void> stepNode(final String executionId, final String nodeId) {
    return Mono.fromSupplier(() -> requireNodeControl(executionId, nodeId))
        .flatMap(
            control ->
                executeCommand(
                    buildCommand(new StepNodeCommand(executionId, nodeId), CONTROL_COMMAND_PRIORITY)))
        .doOnSubscribe(
            _ ->
                log.atInfo()
                    .addKeyValue("executionId", executionId)
                    .addKeyValue("nodeId", nodeId)
                    .log("Stepping through node"))
        .doOnSuccess(
            _ -> log.atDebug().addKeyValue("nodeId", nodeId).log("Node step command executed"))
        .doOnError(
            err ->
                log.atError()
                    .setCause(err)
                    .addKeyValue("executionId", executionId)
                    .addKeyValue("nodeId", nodeId)
                    .log("Failed to step node"));
  }
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :core:test --tests com.infenia.yukta.service.control.gateway.DefaultControlBusGatewayTest`

Expected: PASS — all tests in the class, including the 6 new not-found tests, the 3 updated happy-path tests, and the 3 updated emit-error tests.

- [ ] **Step 6: Run spotless**

Run: `./gradlew :core:spotlessApply`

- [ ] **Step 7: Commit**

```bash
git add core/src/main/java/com/infenia/yukta/service/control/gateway/DefaultControlBusGateway.java core/src/test/java/com/infenia/yukta/service/control/gateway/DefaultControlBusGatewayTest.java
git commit -m "fix: validate execution/node existence in step-mode gateway methods

enableStepMode, disableStepMode, and stepNode previously emitted control
commands without checking that the execution/node exists, unlike
pauseNode/resumeNode/stopNode/skipNode."
```

---

### Task 2: Add REST endpoints for step-mode control to `WorkflowController`

**Files:**
- Modify: `web/src/main/java/com/infenia/yukta/controller/WorkflowController.java:552` (insert after the `skipNode` method, before `getWorkflowStatus`)
- Test: `web/src/test/java/com/infenia/yukta/controller/WorkflowControllerTest.java`

**Interfaces:**
- Consumes: `ControlBusGateway.enableStepMode(String, String): Mono<Void>`, `disableStepMode(String, String): Mono<Void>`, `stepNode(String, String): Mono<Void>` (from Task 1 — signatures unchanged); private helper `executeControlSignal(String operationName, String sessionId, String executionId, String nodeId, String successMessage, Supplier<Mono<Void>> action, ServerWebExchange exchange): Mono<ResponseEntity<ApiResponse<WorkflowStartResponse>>>` (`WorkflowController.java:288`).
- Produces: three new public controller methods `enableStepMode`, `disableStepMode`, `stepNode` mapped to POST endpoints.

- [ ] **Step 1: Add endpoint path/message constants and write failing controller tests**

In `WorkflowControllerTest.java`, add these constants right after `NODE_SKIP_ACCEPTED` (line 167):

```java
  /** Enable step mode endpoint path. */
  private static final String ENABLE_STEP_MODE_ENDPOINT =
      "/api/workflow/sess-1/exec-1/node/node-1/step/enable";

  /** Disable step mode endpoint path. */
  private static final String DISABLE_STEP_MODE_ENDPOINT =
      "/api/workflow/sess-1/exec-1/node/node-1/step/disable";

  /** Step node endpoint path. */
  private static final String STEP_NODE_ENDPOINT = "/api/workflow/sess-1/exec-1/node/node-1/step";

  /** Enable step mode accepted message. */
  private static final String STEP_MODE_ENABLE_ACCEPTED = "Step mode enable signal accepted";

  /** Disable step mode accepted message. */
  private static final String STEP_MODE_DISABLE_ACCEPTED = "Step mode disable signal accepted";

  /** Step node accepted message. */
  private static final String NODE_STEP_ACCEPTED = "Node step signal accepted";
```

Then add this test section at the end of the class, right before the final closing `}` (after `testSkipNodeNonNotFoundErrorPropagates`, currently ending at line 1700-1701):

```java

  // --- Enable Step Mode Tests ---

  @Test
  void testEnableStepModeSuccess() {
    final WorkflowProgress progress =
        new WorkflowProgress(
            EXEC_ID_1,
            SESS_ID_1,
            WF_ID_1,
            RUNNING,
            List.of(),
            LocalDateTime.now(ZoneId.systemDefault()),
            null);
    when(controlBusGateway.getCurrentProgress(EXEC_ID_1)).thenReturn(progress);
    when(controlBusGateway.enableStepMode(EXEC_ID_1, NODE_ID_1)).thenReturn(Mono.empty());

    final var result =
        webClient
            .post()
            .uri(ENABLE_STEP_MODE_ENDPOINT)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody()
            .jsonPath(DOLLAR_STATUS)
            .isEqualTo(200)
            .jsonPath(DOLLAR_MESSAGE)
            .isEqualTo(STEP_MODE_ENABLE_ACCEPTED)
            .jsonPath(DOLLAR_DATA_EXECUTION_ID)
            .isEqualTo(EXEC_ID_1)
            .returnResult();
    assertThat(result.getStatus().value()).isEqualTo(200);
  }

  @Test
  void testEnableStepModeSuccessLogging(final CapturedOutput output) {
    final WorkflowProgress progress =
        new WorkflowProgress(
            EXEC_ID_1,
            SESS_ID_1,
            WF_ID_1,
            RUNNING,
            List.of(),
            LocalDateTime.now(ZoneId.systemDefault()),
            null);
    when(controlBusGateway.getCurrentProgress(EXEC_ID_1)).thenReturn(progress);
    when(controlBusGateway.enableStepMode(EXEC_ID_1, NODE_ID_1)).thenReturn(Mono.empty());

    webClient.post().uri(ENABLE_STEP_MODE_ENDPOINT).exchange().expectStatus().isOk();

    assertThat(output.toString())
        .contains("enableStepMode: executionId=" + EXEC_ID_1)
        .contains("enableStepMode command accepted")
        .contains("enableStepMode response sent successfully");
  }

  @Test
  void testEnableStepModeExecutionNotFound() {
    when(controlBusGateway.getCurrentProgress(EXEC_ID_1)).thenReturn(null);

    final var result =
        webClient
            .post()
            .uri(ENABLE_STEP_MODE_ENDPOINT)
            .exchange()
            .expectStatus()
            .isNotFound()
            .expectBody()
            .jsonPath(DOLLAR_STATUS)
            .isEqualTo(404)
            .jsonPath(DOLLAR_MESSAGE)
            .isEqualTo(EXECUTION_NOT_FOUND)
            .returnResult();
    assertThat(result.getStatus().value()).isEqualTo(404);
  }

  @Test
  void testEnableStepModeNodeNotFound() {
    final WorkflowProgress progress =
        new WorkflowProgress(
            EXEC_ID_1,
            SESS_ID_1,
            WF_ID_1,
            RUNNING,
            List.of(),
            LocalDateTime.now(ZoneId.systemDefault()),
            null);
    when(controlBusGateway.getCurrentProgress(EXEC_ID_1)).thenReturn(progress);
    when(controlBusGateway.enableStepMode(EXEC_ID_1, NODE_ID_1))
        .thenReturn(Mono.error(new IllegalArgumentException(NODE_NOT_FOUND_PREFIX + NODE_ID_1)));

    final var result =
        webClient
            .post()
            .uri(ENABLE_STEP_MODE_ENDPOINT)
            .exchange()
            .expectStatus()
            .isNotFound()
            .expectBody()
            .jsonPath(DOLLAR_STATUS)
            .isEqualTo(404)
            .jsonPath(DOLLAR_MESSAGE)
            .isEqualTo(NODE_NOT_FOUND)
            .returnResult();
    assertThat(result.getStatus().value()).isEqualTo(404);
  }

  @Test
  void testEnableStepModeSessionMismatch() {
    final WorkflowProgress progress =
        new WorkflowProgress(
            EXEC_ID_1,
            OTHER_SESSION,
            WF_ID_1,
            RUNNING,
            List.of(),
            LocalDateTime.now(ZoneId.systemDefault()),
            null);
    when(controlBusGateway.getCurrentProgress(EXEC_ID_1)).thenReturn(progress);

    final var result =
        webClient
            .post()
            .uri(ENABLE_STEP_MODE_ENDPOINT)
            .exchange()
            .expectStatus()
            .isNotFound()
            .expectBody()
            .jsonPath(DOLLAR_STATUS)
            .isEqualTo(404)
            .jsonPath(DOLLAR_MESSAGE)
            .isEqualTo(EXECUTION_NOT_FOUND)
            .returnResult();
    assertThat(result.getStatus().value()).isEqualTo(404);
  }

  @Test
  void testEnableStepModeNonNotFoundErrorPropagates() {
    final WorkflowProgress progress =
        new WorkflowProgress(
            EXEC_ID_1,
            SESS_ID_1,
            WF_ID_1,
            RUNNING,
            List.of(),
            LocalDateTime.now(ZoneId.systemDefault()),
            null);
    when(controlBusGateway.getCurrentProgress(EXEC_ID_1)).thenReturn(progress);
    when(controlBusGateway.enableStepMode(EXEC_ID_1, NODE_ID_1))
        .thenReturn(Mono.error(new RuntimeException(CONTROL_BUS_FAILURE)));

    final var result =
        webClient
            .post()
            .uri(ENABLE_STEP_MODE_ENDPOINT)
            .exchange()
            .expectStatus()
            .is5xxServerError()
            .returnResult();
    assertThat(result.getStatus().value()).isEqualTo(500);
  }

  // --- Disable Step Mode Tests ---

  @Test
  void testDisableStepModeSuccess() {
    final WorkflowProgress progress =
        new WorkflowProgress(
            EXEC_ID_1,
            SESS_ID_1,
            WF_ID_1,
            RUNNING,
            List.of(),
            LocalDateTime.now(ZoneId.systemDefault()),
            null);
    when(controlBusGateway.getCurrentProgress(EXEC_ID_1)).thenReturn(progress);
    when(controlBusGateway.disableStepMode(EXEC_ID_1, NODE_ID_1)).thenReturn(Mono.empty());

    final var result =
        webClient
            .post()
            .uri(DISABLE_STEP_MODE_ENDPOINT)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody()
            .jsonPath(DOLLAR_STATUS)
            .isEqualTo(200)
            .jsonPath(DOLLAR_MESSAGE)
            .isEqualTo(STEP_MODE_DISABLE_ACCEPTED)
            .jsonPath(DOLLAR_DATA_EXECUTION_ID)
            .isEqualTo(EXEC_ID_1)
            .returnResult();
    assertThat(result.getStatus().value()).isEqualTo(200);
  }

  @Test
  void testDisableStepModeSuccessLogging(final CapturedOutput output) {
    final WorkflowProgress progress =
        new WorkflowProgress(
            EXEC_ID_1,
            SESS_ID_1,
            WF_ID_1,
            RUNNING,
            List.of(),
            LocalDateTime.now(ZoneId.systemDefault()),
            null);
    when(controlBusGateway.getCurrentProgress(EXEC_ID_1)).thenReturn(progress);
    when(controlBusGateway.disableStepMode(EXEC_ID_1, NODE_ID_1)).thenReturn(Mono.empty());

    webClient.post().uri(DISABLE_STEP_MODE_ENDPOINT).exchange().expectStatus().isOk();

    assertThat(output.toString())
        .contains("disableStepMode: executionId=" + EXEC_ID_1)
        .contains("disableStepMode command accepted")
        .contains("disableStepMode response sent successfully");
  }

  @Test
  void testDisableStepModeExecutionNotFound() {
    when(controlBusGateway.getCurrentProgress(EXEC_ID_1)).thenReturn(null);

    final var result =
        webClient
            .post()
            .uri(DISABLE_STEP_MODE_ENDPOINT)
            .exchange()
            .expectStatus()
            .isNotFound()
            .expectBody()
            .jsonPath(DOLLAR_STATUS)
            .isEqualTo(404)
            .jsonPath(DOLLAR_MESSAGE)
            .isEqualTo(EXECUTION_NOT_FOUND)
            .returnResult();
    assertThat(result.getStatus().value()).isEqualTo(404);
  }

  @Test
  void testDisableStepModeNodeNotFound() {
    final WorkflowProgress progress =
        new WorkflowProgress(
            EXEC_ID_1,
            SESS_ID_1,
            WF_ID_1,
            RUNNING,
            List.of(),
            LocalDateTime.now(ZoneId.systemDefault()),
            null);
    when(controlBusGateway.getCurrentProgress(EXEC_ID_1)).thenReturn(progress);
    when(controlBusGateway.disableStepMode(EXEC_ID_1, NODE_ID_1))
        .thenReturn(Mono.error(new IllegalArgumentException(NODE_NOT_FOUND_PREFIX + NODE_ID_1)));

    final var result =
        webClient
            .post()
            .uri(DISABLE_STEP_MODE_ENDPOINT)
            .exchange()
            .expectStatus()
            .isNotFound()
            .expectBody()
            .jsonPath(DOLLAR_STATUS)
            .isEqualTo(404)
            .jsonPath(DOLLAR_MESSAGE)
            .isEqualTo(NODE_NOT_FOUND)
            .returnResult();
    assertThat(result.getStatus().value()).isEqualTo(404);
  }

  @Test
  void testDisableStepModeSessionMismatch() {
    final WorkflowProgress progress =
        new WorkflowProgress(
            EXEC_ID_1,
            OTHER_SESSION,
            WF_ID_1,
            RUNNING,
            List.of(),
            LocalDateTime.now(ZoneId.systemDefault()),
            null);
    when(controlBusGateway.getCurrentProgress(EXEC_ID_1)).thenReturn(progress);

    final var result =
        webClient
            .post()
            .uri(DISABLE_STEP_MODE_ENDPOINT)
            .exchange()
            .expectStatus()
            .isNotFound()
            .expectBody()
            .jsonPath(DOLLAR_STATUS)
            .isEqualTo(404)
            .jsonPath(DOLLAR_MESSAGE)
            .isEqualTo(EXECUTION_NOT_FOUND)
            .returnResult();
    assertThat(result.getStatus().value()).isEqualTo(404);
  }

  @Test
  void testDisableStepModeNonNotFoundErrorPropagates() {
    final WorkflowProgress progress =
        new WorkflowProgress(
            EXEC_ID_1,
            SESS_ID_1,
            WF_ID_1,
            RUNNING,
            List.of(),
            LocalDateTime.now(ZoneId.systemDefault()),
            null);
    when(controlBusGateway.getCurrentProgress(EXEC_ID_1)).thenReturn(progress);
    when(controlBusGateway.disableStepMode(EXEC_ID_1, NODE_ID_1))
        .thenReturn(Mono.error(new RuntimeException(CONTROL_BUS_FAILURE)));

    final var result =
        webClient
            .post()
            .uri(DISABLE_STEP_MODE_ENDPOINT)
            .exchange()
            .expectStatus()
            .is5xxServerError()
            .returnResult();
    assertThat(result.getStatus().value()).isEqualTo(500);
  }

  // --- Step Node Tests ---

  @Test
  void testStepNodeSuccess() {
    final WorkflowProgress progress =
        new WorkflowProgress(
            EXEC_ID_1,
            SESS_ID_1,
            WF_ID_1,
            RUNNING,
            List.of(),
            LocalDateTime.now(ZoneId.systemDefault()),
            null);
    when(controlBusGateway.getCurrentProgress(EXEC_ID_1)).thenReturn(progress);
    when(controlBusGateway.stepNode(EXEC_ID_1, NODE_ID_1)).thenReturn(Mono.empty());

    final var result =
        webClient
            .post()
            .uri(STEP_NODE_ENDPOINT)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody()
            .jsonPath(DOLLAR_STATUS)
            .isEqualTo(200)
            .jsonPath(DOLLAR_MESSAGE)
            .isEqualTo(NODE_STEP_ACCEPTED)
            .jsonPath(DOLLAR_DATA_EXECUTION_ID)
            .isEqualTo(EXEC_ID_1)
            .returnResult();
    assertThat(result.getStatus().value()).isEqualTo(200);
  }

  @Test
  void testStepNodeSuccessLogging(final CapturedOutput output) {
    final WorkflowProgress progress =
        new WorkflowProgress(
            EXEC_ID_1,
            SESS_ID_1,
            WF_ID_1,
            RUNNING,
            List.of(),
            LocalDateTime.now(ZoneId.systemDefault()),
            null);
    when(controlBusGateway.getCurrentProgress(EXEC_ID_1)).thenReturn(progress);
    when(controlBusGateway.stepNode(EXEC_ID_1, NODE_ID_1)).thenReturn(Mono.empty());

    webClient.post().uri(STEP_NODE_ENDPOINT).exchange().expectStatus().isOk();

    assertThat(output.toString())
        .contains("stepNode: executionId=" + EXEC_ID_1)
        .contains("stepNode command accepted")
        .contains("stepNode response sent successfully");
  }

  @Test
  void testStepNodeExecutionNotFound() {
    when(controlBusGateway.getCurrentProgress(EXEC_ID_1)).thenReturn(null);

    final var result =
        webClient
            .post()
            .uri(STEP_NODE_ENDPOINT)
            .exchange()
            .expectStatus()
            .isNotFound()
            .expectBody()
            .jsonPath(DOLLAR_STATUS)
            .isEqualTo(404)
            .jsonPath(DOLLAR_MESSAGE)
            .isEqualTo(EXECUTION_NOT_FOUND)
            .returnResult();
    assertThat(result.getStatus().value()).isEqualTo(404);
  }

  @Test
  void testStepNodeNodeNotFound() {
    final WorkflowProgress progress =
        new WorkflowProgress(
            EXEC_ID_1,
            SESS_ID_1,
            WF_ID_1,
            RUNNING,
            List.of(),
            LocalDateTime.now(ZoneId.systemDefault()),
            null);
    when(controlBusGateway.getCurrentProgress(EXEC_ID_1)).thenReturn(progress);
    when(controlBusGateway.stepNode(EXEC_ID_1, NODE_ID_1))
        .thenReturn(Mono.error(new IllegalArgumentException(NODE_NOT_FOUND_PREFIX + NODE_ID_1)));

    final var result =
        webClient
            .post()
            .uri(STEP_NODE_ENDPOINT)
            .exchange()
            .expectStatus()
            .isNotFound()
            .expectBody()
            .jsonPath(DOLLAR_STATUS)
            .isEqualTo(404)
            .jsonPath(DOLLAR_MESSAGE)
            .isEqualTo(NODE_NOT_FOUND)
            .returnResult();
    assertThat(result.getStatus().value()).isEqualTo(404);
  }

  @Test
  void testStepNodeSessionMismatch() {
    final WorkflowProgress progress =
        new WorkflowProgress(
            EXEC_ID_1,
            OTHER_SESSION,
            WF_ID_1,
            RUNNING,
            List.of(),
            LocalDateTime.now(ZoneId.systemDefault()),
            null);
    when(controlBusGateway.getCurrentProgress(EXEC_ID_1)).thenReturn(progress);

    final var result =
        webClient
            .post()
            .uri(STEP_NODE_ENDPOINT)
            .exchange()
            .expectStatus()
            .isNotFound()
            .expectBody()
            .jsonPath(DOLLAR_STATUS)
            .isEqualTo(404)
            .jsonPath(DOLLAR_MESSAGE)
            .isEqualTo(EXECUTION_NOT_FOUND)
            .returnResult();
    assertThat(result.getStatus().value()).isEqualTo(404);
  }

  @Test
  void testStepNodeNonNotFoundErrorPropagates() {
    final WorkflowProgress progress =
        new WorkflowProgress(
            EXEC_ID_1,
            SESS_ID_1,
            WF_ID_1,
            RUNNING,
            List.of(),
            LocalDateTime.now(ZoneId.systemDefault()),
            null);
    when(controlBusGateway.getCurrentProgress(EXEC_ID_1)).thenReturn(progress);
    when(controlBusGateway.stepNode(EXEC_ID_1, NODE_ID_1))
        .thenReturn(Mono.error(new RuntimeException(CONTROL_BUS_FAILURE)));

    final var result =
        webClient
            .post()
            .uri(STEP_NODE_ENDPOINT)
            .exchange()
            .expectStatus()
            .is5xxServerError()
            .returnResult();
    assertThat(result.getStatus().value()).isEqualTo(500);
  }
```

- [ ] **Step 2: Run the new tests to verify they fail**

Run: `./gradlew :web:test --tests com.infenia.yukta.controller.WorkflowControllerTest`

Expected: FAIL — compile error or 404s, since `WorkflowController` has no mapping for `/step/enable`, `/step/disable`, or `/step` yet (`controlBusGateway.enableStepMode`/`disableStepMode`/`stepNode` mocks are also unused, but the endpoints themselves don't exist so the client gets a 404 Not Found from Spring's routing, which conflicts with the "Execution not found" JSON body assertions).

- [ ] **Step 3: Add the three controller methods**

In `web/src/main/java/com/infenia/yukta/controller/WorkflowController.java`, insert immediately after the `skipNode` method (after line 552, before the `getWorkflowStatus` Javadoc at line 554):

```java

  /**
   * Enable step-through debug mode on a node within a workflow execution.
   *
   * <p>Each element must be explicitly stepped via {@link #stepNode}. The node automatically
   * pauses until step signals are sent.
   *
   * @param sessionId the session identifier
   * @param executionId the execution to target
   * @param nodeId the node to enable step mode on
   * @return response entity with the execution ID
   */
  @PostMapping("/workflow/{sessionId}/{executionId}/node/{nodeId}/step/enable")
  @Operation(
      summary = "Enable step mode on a node",
      description = "Enables step-through debug mode on a single node in a workflow execution")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = HTTP_200,
      description = "Step mode enable signal accepted")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "404",
      description = "Execution or node not found")
  public Mono<ResponseEntity<ApiResponse<WorkflowStartResponse>>> enableStepMode(
      @Parameter(description = SESSION_ID_PARAM) @PathVariable final String sessionId,
      @Parameter(description = "Execution ID") @PathVariable final String executionId,
      @Parameter(description = "Node ID") @PathVariable final String nodeId,
      final ServerWebExchange exchange) {
    return executeControlSignal(
        "enableStepMode",
        sessionId,
        executionId,
        nodeId,
        "Step mode enable signal accepted",
        () -> controlBus.enableStepMode(executionId, nodeId),
        exchange);
  }

  /**
   * Disable step-through debug mode on a node within a workflow execution.
   *
   * <p>The node returns to normal pause/resume behavior.
   *
   * @param sessionId the session identifier
   * @param executionId the execution to target
   * @param nodeId the node to disable step mode on
   * @return response entity with the execution ID
   */
  @PostMapping("/workflow/{sessionId}/{executionId}/node/{nodeId}/step/disable")
  @Operation(
      summary = "Disable step mode on a node",
      description = "Disables step-through debug mode on a single node in a workflow execution")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = HTTP_200,
      description = "Step mode disable signal accepted")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "404",
      description = "Execution or node not found")
  public Mono<ResponseEntity<ApiResponse<WorkflowStartResponse>>> disableStepMode(
      @Parameter(description = SESSION_ID_PARAM) @PathVariable final String sessionId,
      @Parameter(description = "Execution ID") @PathVariable final String executionId,
      @Parameter(description = "Node ID") @PathVariable final String nodeId,
      final ServerWebExchange exchange) {
    return executeControlSignal(
        "disableStepMode",
        sessionId,
        executionId,
        nodeId,
        "Step mode disable signal accepted",
        () -> controlBus.disableStepMode(executionId, nodeId),
        exchange);
  }

  /**
   * Step to the next element on a node that is in step-through mode.
   *
   * <p>Allows exactly one element to pass through the node before blocking again.
   *
   * @param sessionId the session identifier
   * @param executionId the execution to target
   * @param nodeId the node to step
   * @return response entity with the execution ID
   */
  @PostMapping("/workflow/{sessionId}/{executionId}/node/{nodeId}/step")
  @Operation(
      summary = "Step a node",
      description = "Allows exactly one element to pass through a node in step-through mode")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = HTTP_200,
      description = "Node step signal accepted")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "404",
      description = "Execution or node not found")
  public Mono<ResponseEntity<ApiResponse<WorkflowStartResponse>>> stepNode(
      @Parameter(description = SESSION_ID_PARAM) @PathVariable final String sessionId,
      @Parameter(description = "Execution ID") @PathVariable final String executionId,
      @Parameter(description = "Node ID") @PathVariable final String nodeId,
      final ServerWebExchange exchange) {
    return executeControlSignal(
        "stepNode",
        sessionId,
        executionId,
        nodeId,
        "Node step signal accepted",
        () -> controlBus.stepNode(executionId, nodeId),
        exchange);
  }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :web:test --tests com.infenia.yukta.controller.WorkflowControllerTest`

Expected: PASS — all tests including the 18 new ones.

- [ ] **Step 5: Run spotless**

Run: `./gradlew :web:spotlessApply`

- [ ] **Step 6: Commit**

```bash
git add web/src/main/java/com/infenia/yukta/controller/WorkflowController.java web/src/test/java/com/infenia/yukta/controller/WorkflowControllerTest.java
git commit -m "feat: add REST endpoints for enabling, disabling, and stepping node step-mode"
```

---

### Task 3: Full verification

**Files:** none (verification only)

- [ ] **Step 1: Run full quality gate**

Run: `./gradlew check`

Expected: BUILD SUCCESSFUL — all tests, Checkstyle, PMD, SpotBugs pass across `core` and `web` modules (and any others affected).

- [ ] **Step 2: Manually confirm route wiring**

Run: `./gradlew bootRun` (in one terminal), then in another:

```bash
curl -s -X POST "http://localhost:8080/api/workflow/some-session/some-exec/node/some-node/step/enable" | head -c 500
```

Expected: A 404 JSON body with `"message":"Execution not found"` (since `some-exec` doesn't exist) — confirms the route is wired and reaches the gateway's `requireNodeControl` check rather than 404-ing at the Spring routing layer (which would look identical in body shape but is worth confirming visually against `/pause` behavior for the same nonexistent IDs, e.g. `curl -s -X POST ".../node/some-node/pause"` should give the same shape).

Stop the app afterward (Ctrl+C in the `bootRun` terminal).
