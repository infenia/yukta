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
package com.infenia.yukta.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.dto.request.ConfigRequest;
import com.infenia.yukta.dto.request.WorkflowDefinitionRequest;
import com.infenia.yukta.dto.request.WorkflowDefinitionRequest.EdgeRequest;
import com.infenia.yukta.dto.request.WorkflowDefinitionRequest.NodeRequest;
import com.infenia.yukta.model.session.SessionConfigData;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.model.workflow.WorkflowDefinition.Edge;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

/** Tests for SessionMapper. */
@SuppressWarnings({"PMD.AvoidAccessibilityAlteration", "PMD.TooManyMethods"})
@NoArgsConstructor
class SessionMapperTest {

  /** Workflow identifier for testing. */
  private static final String WORKFLOW_ID_1 = "workflow-1";

  /** First node identifier for testing. */
  private static final String NODE_ID_1 = "node-1";

  /** Second node identifier for testing. */
  private static final String NODE_ID_2 = "node-2";

  /** Third node identifier for testing. */
  private static final String NODE_ID_3 = "node-3";

  /** Processor plugin type for testing. */
  private static final String PROCESSOR_PLUGIN = "ProcessorPlugin";

  /** Terminal plugin type for testing. */
  private static final String TERMINAL_PLUGIN = "TerminalPlugin";

  /** Method name for workflow definition request map conversion. */
  private static final String METHOD_NAME_WF_DEF_MAP =
      "stringWorkflowDefinitionRequestMapToStringWorkflowDefinitionMap";

  /** Method name for node list conversion. */
  private static final String METHOD_NAME_NODE_LIST = "nodeRequestListToNodeList";

  /** Method name for edge list conversion. */
  private static final String METHOD_NAME_EDGE_LIST = "edgeRequestListToEdgeList";

  /** Method name for node request conversion. */
  private static final String METHOD_NAME_NODE_REQUEST = "nodeRequestToNode";

  /** SuppressWarnings annotation value for unchecked casts. */
  private static final String SUPPRESSION_UNCHECKED = "unchecked";

  /** Source node identifier for edge testing. */
  private static final String SOURCE_NODE = "source-node";

  /** Target node identifier for edge testing. */
  private static final String TARGET_NODE = "target-node";

  /** Mapper for session data transformation. */
  private SessionMapper mapper;

  @BeforeEach
  void setUp() {
    mapper = Mappers.getMapper(SessionMapper.class);
  }

  @Test
  void testConfigRequestToSessionConfigData() {
    final ConfigRequest request =
        new ConfigRequest(
            "session-123",
            "Test Session",
            "test-user",
            Map.of("env", "test"),
            "/home/user/project",
            Map.of(
                WORKFLOW_ID_1,
                new WorkflowDefinitionRequest(
                    WORKFLOW_ID_1,
                    "Test Workflow",
                    List.of(new NodeRequest(NODE_ID_1, PROCESSOR_PLUGIN, Map.of("param", "value"))),
                    List.of(new EdgeRequest(NODE_ID_1, NODE_ID_1, null)))));

    final SessionConfigData result = mapper.configRequestToSessionConfigData(request);

    assertThat(result).isNotNull();
    assertThat(result.sessionId()).isEqualTo("session-123");
    assertThat(result.description()).isEqualTo("Test Session");
    assertThat(result.initiator()).isEqualTo("test-user");
    assertThat(result.projectPath()).isEqualTo("/home/user/project");
  }

  @Test
  void testWorkflowDefinitionRequestToWorkflowDefinition() {
    final WorkflowDefinitionRequest request =
        new WorkflowDefinitionRequest(
            WORKFLOW_ID_1,
            "Test Workflow",
            List.of(
                new NodeRequest(NODE_ID_1, PROCESSOR_PLUGIN, Map.of()),
                new NodeRequest(NODE_ID_2, TERMINAL_PLUGIN, Map.of())),
            List.of(new EdgeRequest(NODE_ID_1, NODE_ID_2, null)));

    final WorkflowDefinition result = mapper.workflowDefinitionRequestToWorkflowDefinition(request);

    assertThat(result).isNotNull();
    assertThat(result.nodes()).hasSize(2);
    assertThat(result.edges()).hasSize(1);
  }

  @Test
  void testEdgeRequestToEdgeWithNullSourcePort() {
    final EdgeRequest edgeRequest = new EdgeRequest(NODE_ID_1, NODE_ID_2, null);

    final Edge result = mapper.edgeRequestToEdge(edgeRequest);

    assertThat(result).isNotNull();
    assertThat(result.sourcePort()).isEqualTo("default");
    assertThat(result.source()).isEqualTo(NODE_ID_1);
    assertThat(result.target()).isEqualTo(NODE_ID_2);
  }

  @Test
  void testEdgeRequestToEdgeWithSourcePort() {
    final EdgeRequest edgeRequest = new EdgeRequest(NODE_ID_1, NODE_ID_2, "output");

    final Edge result = mapper.edgeRequestToEdge(edgeRequest);

    assertThat(result).isNotNull();
    assertThat(result.sourcePort()).isEqualTo("output");
    assertThat(result.source()).isEqualTo(NODE_ID_1);
    assertThat(result.target()).isEqualTo(NODE_ID_2);
  }

  @Test
  void testConfigRequestToSessionConfigDataWithEmptyWorkflows() {
    final ConfigRequest request =
        new ConfigRequest(
            "session-456", "Empty Session", "system", null, "/home/user/project2", Map.of());

    final SessionConfigData result = mapper.configRequestToSessionConfigData(request);

    assertThat(result).isNotNull();
    assertThat(result.sessionId()).isEqualTo("session-456");
  }

  @Test
  void testWorkflowDefinitionRequestToWorkflowDefinitionWithMultipleNodes() {
    final WorkflowDefinitionRequest request =
        new WorkflowDefinitionRequest(
            "workflow-complex",
            "Complex Workflow",
            List.of(
                new NodeRequest(NODE_ID_1, "TriggerPlugin", Map.of()),
                new NodeRequest(NODE_ID_2, PROCESSOR_PLUGIN, Map.of("step", "1")),
                new NodeRequest(NODE_ID_3, PROCESSOR_PLUGIN, Map.of("step", "2")),
                new NodeRequest("node-4", TERMINAL_PLUGIN, Map.of())),
            List.of(
                new EdgeRequest(NODE_ID_1, NODE_ID_2, "success"),
                new EdgeRequest(NODE_ID_2, NODE_ID_3, null),
                new EdgeRequest(NODE_ID_3, "node-4", "default")));

    final WorkflowDefinition result = mapper.workflowDefinitionRequestToWorkflowDefinition(request);

    assertThat(result).isNotNull();
    assertThat(result.nodes()).hasSize(4);
    assertThat(result.edges()).hasSize(3);
  }

  @Test
  void testEdgeRequestToEdgeWithoutConfig() {
    final EdgeRequest edgeRequest1 = new EdgeRequest(NODE_ID_1, NODE_ID_2, "");
    final Edge result1 = mapper.edgeRequestToEdge(edgeRequest1);
    assertThat(result1).isNotNull();

    final EdgeRequest edgeRequest2 = new EdgeRequest("node-A", "node-B", "");
    final Edge result2 = mapper.edgeRequestToEdge(edgeRequest2);
    assertThat(result2).isNotNull();
  }

  @Test
  void testConfigRequestWithManyWorkflows() {
    final Map<String, WorkflowDefinitionRequest> workflows = new ConcurrentHashMap<>();
    for (int i = 0; i < 5; i++) {
      final String workflowId = "workflow-" + i;
      final WorkflowDefinitionRequest workflowRequest =
          buildWorkflowDefinitionRequest(workflowId, "Workflow " + i, "node-" + i);
      workflows.put(workflowId, workflowRequest);
    }

    final ConfigRequest request =
        new ConfigRequest(
            "session-many",
            "Many Workflows",
            "test-user",
            Map.of("key1", "val1", "key2", "val2"),
            "/home/user/project",
            workflows);

    final SessionConfigData result = mapper.configRequestToSessionConfigData(request);

    assertThat(result).isNotNull();
    assertThat(result.sessionId()).isEqualTo("session-many");
  }

  @Test
  void testWorkflowWithEmptyEdges() {
    final WorkflowDefinitionRequest request =
        new WorkflowDefinitionRequest(
            "workflow-no-edges",
            "No Edges Workflow",
            List.of(
                new NodeRequest(NODE_ID_1, "TriggerPlugin", Map.of()),
                new NodeRequest(NODE_ID_2, TERMINAL_PLUGIN, Map.of())),
            List.of());

    final WorkflowDefinition result = mapper.workflowDefinitionRequestToWorkflowDefinition(request);

    assertThat(result).isNotNull();
    assertThat(result.nodes()).hasSize(2);
    assertThat(result.edges()).isEmpty();
  }

  @Test
  void testConfigRequestToSessionConfigDataWithNull() {
    final SessionConfigData result = mapper.configRequestToSessionConfigData(null);

    assertThat(result).isNull();
  }

  @Test
  void testWorkflowDefinitionRequestToWorkflowDefinitionWithNull() {
    final WorkflowDefinition result = mapper.workflowDefinitionRequestToWorkflowDefinition(null);

    assertThat(result).isNull();
  }

  @Test
  void testEdgeRequestToEdgeWithNull() {
    final Edge result = mapper.edgeRequestToEdge(null);

    assertThat(result).isNull();
  }

  @Test
  void testStringWorkflowDefinitionRequestMapToStringWorkflowDefinitionMapWithNull()
      throws NoSuchMethodException,
          IllegalAccessException,
          java.lang.reflect.InvocationTargetException {
    final java.lang.reflect.Method method =
        SessionMapperImpl.class.getDeclaredMethod(METHOD_NAME_WF_DEF_MAP, Map.class);
    method.setAccessible(true);

    final Object result = method.invoke(mapper, (Object) null);

    assertThat(result).isNull();
  }

  @Test
  void testNodeRequestListToNodeListWithNull()
      throws NoSuchMethodException,
          IllegalAccessException,
          java.lang.reflect.InvocationTargetException {
    final java.lang.reflect.Method method =
        SessionMapperImpl.class.getDeclaredMethod(METHOD_NAME_NODE_LIST, List.class);
    method.setAccessible(true);

    final Object result = method.invoke(mapper, (Object) null);

    assertThat(result).isNull();
  }

  @Test
  void testEdgeRequestListToEdgeListWithNull()
      throws NoSuchMethodException,
          IllegalAccessException,
          java.lang.reflect.InvocationTargetException {
    final java.lang.reflect.Method method =
        SessionMapperImpl.class.getDeclaredMethod(METHOD_NAME_EDGE_LIST, List.class);
    method.setAccessible(true);

    final Object result = method.invoke(mapper, (Object) null);

    assertThat(result).isNull();
  }

  @Test
  void testNodeRequestToNodeWithNull()
      throws NoSuchMethodException,
          IllegalAccessException,
          java.lang.reflect.InvocationTargetException {
    final java.lang.reflect.Method method =
        SessionMapperImpl.class.getDeclaredMethod(
            METHOD_NAME_NODE_REQUEST, WorkflowDefinitionRequest.NodeRequest.class);
    method.setAccessible(true);

    final Object result = method.invoke(mapper, (Object) null);

    assertThat(result).isNull();
  }

  @Test
  void testStringWorkflowDefinitionRequestMapToStringWorkflowDefinitionMapWithNonNullValue()
      throws NoSuchMethodException,
          IllegalAccessException,
          java.lang.reflect.InvocationTargetException {
    final java.lang.reflect.Method method =
        SessionMapperImpl.class.getDeclaredMethod(METHOD_NAME_WF_DEF_MAP, Map.class);
    method.setAccessible(true);

    final Map<String, WorkflowDefinitionRequest> input =
        Map.of(
            WORKFLOW_ID_1,
            new WorkflowDefinitionRequest(
                WORKFLOW_ID_1,
                "Test Workflow",
                List.of(new NodeRequest(NODE_ID_1, PROCESSOR_PLUGIN, Map.of())),
                List.of()));

    final Object result = method.invoke(mapper, input);

    assertThat(result).isNotNull().isInstanceOf(Map.class);
    @SuppressWarnings(SUPPRESSION_UNCHECKED)
    final Map<String, WorkflowDefinition> mapResult = (Map<String, WorkflowDefinition>) result;
    assertThat(mapResult).hasSize(1).containsKey(WORKFLOW_ID_1);
  }

  @Test
  void testNodeRequestListToNodeListWithNonNullValue()
      throws NoSuchMethodException,
          IllegalAccessException,
          java.lang.reflect.InvocationTargetException {
    final java.lang.reflect.Method method =
        SessionMapperImpl.class.getDeclaredMethod(METHOD_NAME_NODE_LIST, List.class);
    method.setAccessible(true);

    final List<NodeRequest> input =
        List.of(
            new NodeRequest(NODE_ID_1, PROCESSOR_PLUGIN, Map.of()),
            new NodeRequest(NODE_ID_2, TERMINAL_PLUGIN, Map.of()));

    final Object result = method.invoke(mapper, input);

    assertThat(result).isNotNull().isInstanceOf(List.class);
    final List<?> listResult = (List<?>) result;
    assertThat(listResult).hasSize(2);
  }

  @Test
  void testEdgeRequestListToEdgeListWithNonNullValue()
      throws NoSuchMethodException,
          IllegalAccessException,
          java.lang.reflect.InvocationTargetException {
    final java.lang.reflect.Method method =
        SessionMapperImpl.class.getDeclaredMethod(METHOD_NAME_EDGE_LIST, List.class);
    method.setAccessible(true);

    final List<WorkflowDefinitionRequest.EdgeRequest> input =
        List.of(
            new EdgeRequest(NODE_ID_1, NODE_ID_2, null),
            new EdgeRequest(NODE_ID_2, NODE_ID_3, "output"));

    final Object result = method.invoke(mapper, input);

    assertThat(result).isNotNull().isInstanceOf(List.class);
    final List<?> listResult = (List<?>) result;
    assertThat(listResult).hasSize(2);
  }

  @Test
  void testNodeRequestToNodeWithNonNullValue()
      throws NoSuchMethodException,
          IllegalAccessException,
          java.lang.reflect.InvocationTargetException {
    final java.lang.reflect.Method method =
        SessionMapperImpl.class.getDeclaredMethod(
            METHOD_NAME_NODE_REQUEST, WorkflowDefinitionRequest.NodeRequest.class);
    method.setAccessible(true);

    final NodeRequest input = new NodeRequest(NODE_ID_1, PROCESSOR_PLUGIN, Map.of("key", "value"));

    final Object result = method.invoke(mapper, input);

    assertThat(result).isNotNull().isInstanceOf(WorkflowDefinition.Node.class);
    final WorkflowDefinition.Node nodeResult = (WorkflowDefinition.Node) result;
    assertThat(nodeResult.nodeId()).isEqualTo(NODE_ID_1);
    assertThat(nodeResult.type()).isEqualTo(PROCESSOR_PLUGIN);
  }

  @Test
  void testStringWorkflowDefinitionRequestMapToStringWorkflowDefinitionMapWithMultipleEntries()
      throws NoSuchMethodException,
          IllegalAccessException,
          java.lang.reflect.InvocationTargetException {
    final java.lang.reflect.Method method =
        SessionMapperImpl.class.getDeclaredMethod(METHOD_NAME_WF_DEF_MAP, Map.class);
    method.setAccessible(true);

    final var input = getStringWorkflowDefinitionRequestMap();

    final Object result = method.invoke(mapper, input);

    assertThat(result).isNotNull().isInstanceOf(Map.class);
    @SuppressWarnings(SUPPRESSION_UNCHECKED)
    final Map<String, WorkflowDefinition> mapResult = (Map<String, WorkflowDefinition>) result;
    assertThat(mapResult).hasSize(2).containsKeys("w1", "w2");
  }

  private static @NonNull Map<String, WorkflowDefinitionRequest>
      getStringWorkflowDefinitionRequestMap() {
    final Map<String, WorkflowDefinitionRequest> input = new ConcurrentHashMap<>();
    input.put(
        "w1",
        new WorkflowDefinitionRequest(
            "w1",
            "Workflow 1",
            List.of(new NodeRequest("n1", PROCESSOR_PLUGIN, Map.of())),
            List.of()));
    input.put(
        "w2",
        new WorkflowDefinitionRequest(
            "w2",
            "Workflow 2",
            List.of(new NodeRequest("n2", PROCESSOR_PLUGIN, Map.of())),
            List.of()));
    return input;
  }

  @Test
  void testNodeRequestListToNodeListWithMultipleNodes()
      throws NoSuchMethodException,
          IllegalAccessException,
          java.lang.reflect.InvocationTargetException {
    final java.lang.reflect.Method method =
        SessionMapperImpl.class.getDeclaredMethod(METHOD_NAME_NODE_LIST, List.class);
    method.setAccessible(true);

    final List<NodeRequest> input =
        List.of(
            new NodeRequest(NODE_ID_1, "TriggerPlugin", Map.of()),
            new NodeRequest(NODE_ID_2, PROCESSOR_PLUGIN, Map.of()),
            new NodeRequest(NODE_ID_3, TERMINAL_PLUGIN, Map.of()));

    final Object result = method.invoke(mapper, input);

    assertThat(result).isNotNull().isInstanceOf(List.class);
    final List<?> listResult = (List<?>) result;
    assertThat(listResult).hasSize(3);
  }

  @Test
  void testEdgeRequestListToEdgeListWithMultipleEdges()
      throws NoSuchMethodException,
          IllegalAccessException,
          java.lang.reflect.InvocationTargetException {
    final java.lang.reflect.Method method =
        SessionMapperImpl.class.getDeclaredMethod(METHOD_NAME_EDGE_LIST, List.class);
    method.setAccessible(true);

    final List<WorkflowDefinitionRequest.EdgeRequest> input =
        List.of(
            new EdgeRequest("n1", "n2", "port1"),
            new EdgeRequest("n2", "n3", null),
            new EdgeRequest("n3", "n4", "port2"));

    final Object result = method.invoke(mapper, input);

    assertThat(result).isNotNull().isInstanceOf(List.class);
    final List<?> listResult = (List<?>) result;
    assertThat(listResult).hasSize(3);
  }

  @Test
  void testStringWorkflowDefinitionRequestMapToStringWorkflowDefinitionMapWithEmptyMap()
      throws NoSuchMethodException,
          IllegalAccessException,
          java.lang.reflect.InvocationTargetException {
    final java.lang.reflect.Method method =
        SessionMapperImpl.class.getDeclaredMethod(METHOD_NAME_WF_DEF_MAP, Map.class);
    method.setAccessible(true);

    final Map<String, WorkflowDefinitionRequest> input = new ConcurrentHashMap<>();

    final Object result = method.invoke(mapper, input);

    assertThat(result).isNotNull().isInstanceOf(Map.class);
    @SuppressWarnings(SUPPRESSION_UNCHECKED)
    final Map<String, WorkflowDefinition> mapResult = (Map<String, WorkflowDefinition>) result;
    assertThat(mapResult).isEmpty();
  }

  @Test
  void testNodeRequestListToNodeListWithEmptyList()
      throws NoSuchMethodException,
          IllegalAccessException,
          java.lang.reflect.InvocationTargetException {
    final java.lang.reflect.Method method =
        SessionMapperImpl.class.getDeclaredMethod(METHOD_NAME_NODE_LIST, List.class);
    method.setAccessible(true);

    final List<NodeRequest> input = new java.util.ArrayList<>();

    final Object result = method.invoke(mapper, input);

    assertThat(result).isNotNull().isInstanceOf(List.class);
    final List<?> listResult = (List<?>) result;
    assertThat(listResult).isEmpty();
  }

  @Test
  void testEdgeRequestListToEdgeListWithEmptyList()
      throws NoSuchMethodException,
          IllegalAccessException,
          java.lang.reflect.InvocationTargetException {
    final java.lang.reflect.Method method =
        SessionMapperImpl.class.getDeclaredMethod(METHOD_NAME_EDGE_LIST, List.class);
    method.setAccessible(true);

    final List<WorkflowDefinitionRequest.EdgeRequest> input = new java.util.ArrayList<>();

    final Object result = method.invoke(mapper, input);

    assertThat(result).isNotNull().isInstanceOf(List.class);
    final List<?> listResult = (List<?>) result;
    assertThat(listResult).isEmpty();
  }

  @Test
  void testNodeRequestToNodeWithNullConfigMap()
      throws NoSuchMethodException,
          IllegalAccessException,
          java.lang.reflect.InvocationTargetException {
    final java.lang.reflect.Method method =
        SessionMapperImpl.class.getDeclaredMethod(
            METHOD_NAME_NODE_REQUEST, WorkflowDefinitionRequest.NodeRequest.class);
    method.setAccessible(true);

    final NodeRequest input = new NodeRequest(NODE_ID_1, PROCESSOR_PLUGIN, null);

    final Object result = method.invoke(mapper, input);

    assertThat(result).isNotNull().isInstanceOf(WorkflowDefinition.Node.class);
    final WorkflowDefinition.Node nodeResult = (WorkflowDefinition.Node) result;
    assertThat(nodeResult.nodeId()).isEqualTo(NODE_ID_1);
    assertThat(nodeResult.type()).isEqualTo(PROCESSOR_PLUGIN);
    assertThat(nodeResult.config()).isNotNull();
  }

  @Test
  void testStringWorkflowDefinitionRequestMapToStringWorkflowDefinitionMapWithSingleEntry()
      throws NoSuchMethodException,
          IllegalAccessException,
          java.lang.reflect.InvocationTargetException {
    final java.lang.reflect.Method method =
        SessionMapperImpl.class.getDeclaredMethod(METHOD_NAME_WF_DEF_MAP, Map.class);
    method.setAccessible(true);

    final Map<String, WorkflowDefinitionRequest> input = new ConcurrentHashMap<>();
    input.put(
        "only-one",
        new WorkflowDefinitionRequest(
            "only-one",
            "Only Workflow",
            List.of(new NodeRequest("only-node", PROCESSOR_PLUGIN, Map.of())),
            List.of()));

    final Object result = method.invoke(mapper, input);

    assertThat(result).isNotNull().isInstanceOf(Map.class);
    @SuppressWarnings(SUPPRESSION_UNCHECKED)
    final Map<String, WorkflowDefinition> mapResult = (Map<String, WorkflowDefinition>) result;
    assertThat(mapResult).hasSize(1).containsKey("only-one");
  }

  @Test
  void testNodeRequestToNodeWithNonNullConfigMapValue()
      throws NoSuchMethodException,
          IllegalAccessException,
          java.lang.reflect.InvocationTargetException {
    final java.lang.reflect.Method method =
        SessionMapperImpl.class.getDeclaredMethod(
            METHOD_NAME_NODE_REQUEST, WorkflowDefinitionRequest.NodeRequest.class);
    method.setAccessible(true);

    final Map<String, Object> configMap = new ConcurrentHashMap<>();
    configMap.put("key1", "value1");
    configMap.put("key2", 123);
    final NodeRequest input = new NodeRequest("complex-node", PROCESSOR_PLUGIN, configMap);

    final Object result = method.invoke(mapper, input);

    assertThat(result).isNotNull().isInstanceOf(WorkflowDefinition.Node.class);
    final WorkflowDefinition.Node nodeResult = (WorkflowDefinition.Node) result;
    assertThat(nodeResult.nodeId()).isEqualTo("complex-node");
    assertThat(nodeResult.type()).isEqualTo(PROCESSOR_PLUGIN);
    assertThat(nodeResult.config()).isNotNull().containsKeys("key1", "key2");
  }

  @Test
  void testEdgeRequestToEdgeWithNonNullSourcePort()
      throws NoSuchMethodException,
          IllegalAccessException,
          java.lang.reflect.InvocationTargetException {
    final java.lang.reflect.Method method =
        SessionMapperImpl.class.getDeclaredMethod(
            "edgeRequestToEdge", WorkflowDefinitionRequest.EdgeRequest.class);
    method.setAccessible(true);

    final EdgeRequest input = new EdgeRequest(SOURCE_NODE, TARGET_NODE, "custom-port");

    final Object result = method.invoke(mapper, input);

    assertThat(result).isNotNull().isInstanceOf(WorkflowDefinition.Edge.class);
    final WorkflowDefinition.Edge edgeResult = (WorkflowDefinition.Edge) result;
    assertThat(edgeResult.source()).isEqualTo(SOURCE_NODE);
    assertThat(edgeResult.target()).isEqualTo(TARGET_NODE);
    assertThat(edgeResult.sourcePort()).isEqualTo("custom-port");
  }

  @Test
  void testEdgeRequestToEdgeWithEmptyStringSourcePort()
      throws NoSuchMethodException,
          IllegalAccessException,
          java.lang.reflect.InvocationTargetException {
    final java.lang.reflect.Method method =
        SessionMapperImpl.class.getDeclaredMethod(
            "edgeRequestToEdge", WorkflowDefinitionRequest.EdgeRequest.class);
    method.setAccessible(true);

    final EdgeRequest input = new EdgeRequest(SOURCE_NODE, TARGET_NODE, "");

    final Object result = method.invoke(mapper, input);

    assertThat(result).isNotNull().isInstanceOf(WorkflowDefinition.Edge.class);
    final WorkflowDefinition.Edge edgeResult = (WorkflowDefinition.Edge) result;
    assertThat(edgeResult.source()).isEqualTo(SOURCE_NODE);
    assertThat(edgeResult.target()).isEqualTo(TARGET_NODE);
    assertThat(edgeResult.sourcePort()).isEqualTo("");
  }

  /* default */ WorkflowDefinitionRequest buildWorkflowDefinitionRequest(
      final String workflowId, final String description, final String nodeId) {
    return new WorkflowDefinitionRequest(
        workflowId,
        description,
        List.of(new NodeRequest(nodeId, PROCESSOR_PLUGIN, Map.of())),
        List.of());
  }
}
