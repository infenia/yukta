// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.dto.request.ConfigRequest;
import com.infenia.yukta.dto.request.WorkflowDefinitionRequest;
import com.infenia.yukta.dto.request.WorkflowDefinitionRequest.EdgeRequest;
import com.infenia.yukta.dto.request.WorkflowDefinitionRequest.NodeRequest;
import com.infenia.yukta.model.session.SessionConfigData;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.model.workflow.WorkflowDefinition.Edge;
import com.infenia.yukta.model.workflow.WorkflowDefinition.Node;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

/** Tests for AppConfigMapper. */
@NoArgsConstructor
@SuppressWarnings({"PMD.AvoidAccessibilityAlteration", "PMD.TooManyMethods"})
class AppConfigMapperTest {

  /** Application Configuration Mapper. */
  private AppConfigMapper mapper;

  /** Node identifier 1. */
  private static final String NODE_1 = "node-1";

  /** Node identifier 2. */
  private static final String NODE_2 = "node-2";

  /** Node identifier 3. */
  private static final String NODE_3 = "node-3";

  /** Processor plugin type. */
  private static final String PROCESSOR_PLUGIN = "ProcessorPlugin";

  /** Trigger plugin type. */
  private static final String TRIGGER_PLUGIN = "TriggerPlugin";

  /** Terminal plugin type. */
  private static final String TERMINAL_PLUGIN = "TerminalPlugin";

  /** Workflow identifier 1. */
  private static final String WORKFLOW_1 = "workflow-1";

  /** Method name for workflow definition request map transformation. */
  private static final String WF_DEF_REQ_MAP_TO_WF_DEF_MAP =
      "stringWorkflowDefinitionRequestMapToStringWorkflowDefinitionMap";

  /** Method name for node request list to node list transformation. */
  private static final String NODE_REQUEST_LIST_TO_NODE_LIST = "nodeRequestListToNodeList";

  /** Method name for edge request list to edge list transformation. */
  private static final String EDGE_REQUEST_LIST_TO_EDGE_LIST = "edgeRequestListToEdgeList";

  /** Suppression type for unchecked casts. */
  private static final String UNCHECKED = "unchecked";

  /** Source node identifier. */
  private static final String SOURCE_NODE = "source-node";

  /** Target node identifier. */
  private static final String TARGET_NODE = "target-node";

  @BeforeEach
  void setUp() {
    mapper = Mappers.getMapper(AppConfigMapper.class);
  }

  @Test
  void testToDataMapsConfigRequest() {
    final ConfigRequest request =
        new ConfigRequest(
            "session-123",
            "Test Session",
            "test-user",
            "test-initiator",
            Map.of("env", "test", "version", "1.0"),
            "/home/user/project",
            Map.of(
                WORKFLOW_1,
                new WorkflowDefinitionRequest(
                    WORKFLOW_1,
                    "Test Workflow",
                    List.of(new NodeRequest(NODE_1, PROCESSOR_PLUGIN, Map.of("key", "value"))),
                    List.of(new EdgeRequest(NODE_1, NODE_1, null)))));

    final SessionConfigData result = mapper.toData(request);

    assertThat(result).isNotNull();
    assertThat(result.sessionId()).isEqualTo("session-123");
    assertThat(result.name()).isEqualTo("Test Session");
    assertThat(result.description()).isEqualTo("test-user");
    assertThat(result.initiator()).isEqualTo("test-initiator");
    assertThat(result.projectPath()).isEqualTo("/home/user/project");
  }

  @Test
  void testToWorkflowDefinitionMapsRequest() {
    final WorkflowDefinitionRequest request =
        new WorkflowDefinitionRequest(
            WORKFLOW_1,
            "Test Workflow",
            List.of(
                new NodeRequest(NODE_1, PROCESSOR_PLUGIN, Map.of()),
                new NodeRequest(NODE_2, TERMINAL_PLUGIN, Map.of())),
            List.of(new EdgeRequest(NODE_1, NODE_2, "success")));

    final WorkflowDefinition result = mapper.toWorkflowDefinition(request);

    assertThat(result).isNotNull();
    assertThat(result.nodes()).hasSize(2);
    assertThat(result.edges()).hasSize(1);
  }

  @Test
  void testToNodeMapsNodeRequest() {
    final NodeRequest nodeRequest = new NodeRequest(NODE_1, PROCESSOR_PLUGIN, Map.of("key", "val"));

    final Node result = mapper.toNode(nodeRequest);

    assertThat(result).isNotNull();
    assertThat(result.nodeId()).isEqualTo(NODE_1);
    assertThat(result.type()).isEqualTo(PROCESSOR_PLUGIN);
  }

  @Test
  void testToEdgeMapsEdgeRequest() {
    final EdgeRequest edgeRequest = new EdgeRequest(NODE_1, NODE_2, "output");

    final Edge result = mapper.toEdge(edgeRequest);

    assertThat(result).isNotNull();
    assertThat(result.source()).isEqualTo(NODE_1);
    assertThat(result.target()).isEqualTo(NODE_2);
  }

  @Test
  void testToDataWithNullTags() {
    final ConfigRequest request =
        new ConfigRequest(
            "session-456",
            "Session Without Tags",
            "system",
            "initiator",
            null,
            "/home/user/project2",
            Map.of(
                WORKFLOW_1,
                new WorkflowDefinitionRequest(
                    WORKFLOW_1,
                    "Test",
                    List.of(new NodeRequest(NODE_1, TRIGGER_PLUGIN, Map.of())),
                    List.of())));

    final SessionConfigData result = mapper.toData(request);

    assertThat(result).isNotNull();
    assertThat(result.sessionId()).isEqualTo("session-456");
  }

  @Test
  void testToWorkflowDefinitionWithMultipleNodes() {
    final WorkflowDefinitionRequest request =
        new WorkflowDefinitionRequest(
            "workflow-complex",
            "Complex Workflow",
            List.of(
                new NodeRequest(NODE_1, TRIGGER_PLUGIN, Map.of()),
                new NodeRequest(NODE_2, PROCESSOR_PLUGIN, Map.of("step", "1")),
                new NodeRequest(NODE_3, PROCESSOR_PLUGIN, Map.of("step", "2")),
                new NodeRequest("node-4", TERMINAL_PLUGIN, Map.of())),
            List.of(
                new EdgeRequest(NODE_1, NODE_2, "success"),
                new EdgeRequest(NODE_2, NODE_3, null),
                new EdgeRequest(NODE_3, "node-4", "default")));

    final WorkflowDefinition result = mapper.toWorkflowDefinition(request);

    assertThat(result).isNotNull();
    assertThat(result.nodes()).hasSize(4);
    assertThat(result.edges()).hasSize(3);
    assertThat(result.nodes().getFirst().nodeId()).isEqualTo(NODE_1);
  }

  @Test
  void testToNodeWithDifferentTypes() {
    final String[] types = {TRIGGER_PLUGIN, PROCESSOR_PLUGIN, TERMINAL_PLUGIN};
    for (final String type : types) {
      final String nodeId = "node-" + type;
      final NodeRequest nodeRequest = buildNodeRequest(nodeId, type);
      final Node result = mapper.toNode(nodeRequest);

      assertThat(result).isNotNull();
      assertThat(result.type()).isEqualTo(type);
    }
  }

  @Test
  void testToEdgeWithNullSourcePort() {
    final EdgeRequest edgeRequest = new EdgeRequest(NODE_1, NODE_2, null);

    final Edge result = mapper.toEdge(edgeRequest);

    assertThat(result).isNotNull();
    assertThat(result.source()).isEqualTo(NODE_1);
    assertThat(result.target()).isEqualTo(NODE_2);
  }

  @Test
  void testToEdgeWithEmptySourcePort() {
    final EdgeRequest edgeRequest = new EdgeRequest(NODE_1, NODE_2, "");

    final Edge result = mapper.toEdge(edgeRequest);

    assertThat(result).isNotNull();
    assertThat(result.source()).isEqualTo(NODE_1);
    assertThat(result.target()).isEqualTo(NODE_2);
  }

  @Test
  void testToDataWithManyWorkflows() {
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
            "initiator",
            Map.of("key1", "val1", "key2", "val2"),
            "/home/user/project",
            workflows);

    final SessionConfigData result = mapper.toData(request);

    assertThat(result).isNotNull();
    assertThat(result.sessionId()).isEqualTo("session-many");
  }

  @Test
  void testToWorkflowDefinitionWithEmptyEdges() {
    final WorkflowDefinitionRequest request =
        new WorkflowDefinitionRequest(
            "workflow-no-edges",
            "No Edges Workflow",
            List.of(
                new NodeRequest("node-1", "TriggerPlugin", Map.of()),
                new NodeRequest("node-2", "TerminalPlugin", Map.of())),
            List.of());

    final WorkflowDefinition result = mapper.toWorkflowDefinition(request);

    assertThat(result).isNotNull();
    assertThat(result.nodes()).hasSize(2);
    assertThat(result.edges()).isEmpty();
  }

  @Test
  void testToNodeWithComplexConfig() {
    final Map<String, Object> config =
        Map.of("timeout", 5000, "retries", 3, "options", List.of("opt1", "opt2"));
    final NodeRequest nodeRequest = new NodeRequest("node-complex", "ProcessorPlugin", config);

    final Node result = mapper.toNode(nodeRequest);

    assertThat(result).isNotNull();
    assertThat(result.nodeId()).isEqualTo("node-complex");
    assertThat(result.config()).containsKeys("timeout", "retries", "options");
  }

  @Test
  void testToDataWithNull() {
    final SessionConfigData result = mapper.toData(null);

    assertThat(result).isNull();
  }

  @Test
  void testToWorkflowDefinitionWithNull() {
    final WorkflowDefinition result = mapper.toWorkflowDefinition(null);

    assertThat(result).isNull();
  }

  @Test
  void testToNodeWithNull() {
    final Node result = mapper.toNode(null);

    assertThat(result).isNull();
  }

  @Test
  void testToEdgeWithNull() {
    final Edge result = mapper.toEdge(null);

    assertThat(result).isNull();
  }

  @Test
  void testStringWorkflowDefinitionRequestMapToStringWorkflowDefinitionMapWithNull()
      throws NoSuchMethodException,
          IllegalAccessException,
          java.lang.reflect.InvocationTargetException {
    final java.lang.reflect.Method method =
        AppConfigMapperImpl.class.getDeclaredMethod(WF_DEF_REQ_MAP_TO_WF_DEF_MAP, Map.class);
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
        AppConfigMapperImpl.class.getDeclaredMethod(NODE_REQUEST_LIST_TO_NODE_LIST, List.class);
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
        AppConfigMapperImpl.class.getDeclaredMethod(EDGE_REQUEST_LIST_TO_EDGE_LIST, List.class);
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
        AppConfigMapperImpl.class.getDeclaredMethod(WF_DEF_REQ_MAP_TO_WF_DEF_MAP, Map.class);
    method.setAccessible(true);

    final Map<String, WorkflowDefinitionRequest> input =
        Map.of(
            WORKFLOW_1,
            new WorkflowDefinitionRequest(
                WORKFLOW_1,
                "Test Workflow",
                List.of(new NodeRequest(NODE_1, PROCESSOR_PLUGIN, Map.of())),
                List.of()));

    final Object result = method.invoke(mapper, input);

    assertThat(result).isNotNull().isInstanceOf(Map.class);
    @SuppressWarnings(UNCHECKED)
    final Map<String, WorkflowDefinition> mapResult = (Map<String, WorkflowDefinition>) result;
    assertThat(mapResult).hasSize(1).containsKey(WORKFLOW_1);
  }

  @Test
  void testNodeRequestListToNodeListWithNonNullValue()
      throws NoSuchMethodException,
          IllegalAccessException,
          java.lang.reflect.InvocationTargetException {
    final java.lang.reflect.Method method =
        AppConfigMapperImpl.class.getDeclaredMethod(NODE_REQUEST_LIST_TO_NODE_LIST, List.class);
    method.setAccessible(true);

    final List<NodeRequest> input =
        List.of(
            new NodeRequest(NODE_1, PROCESSOR_PLUGIN, Map.of()),
            new NodeRequest(NODE_2, TERMINAL_PLUGIN, Map.of()));

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
        AppConfigMapperImpl.class.getDeclaredMethod(EDGE_REQUEST_LIST_TO_EDGE_LIST, List.class);
    method.setAccessible(true);

    final List<EdgeRequest> input =
        List.of(new EdgeRequest(NODE_1, NODE_2, null), new EdgeRequest(NODE_2, "node-3", "output"));

    final Object result = method.invoke(mapper, input);

    assertThat(result).isNotNull().isInstanceOf(List.class);
    final List<?> listResult = (List<?>) result;
    assertThat(listResult).hasSize(2);
  }

  @Test
  void testStringWorkflowDefinitionRequestMapToStringWorkflowDefinitionMapWithMultipleEntries()
      throws NoSuchMethodException,
          IllegalAccessException,
          java.lang.reflect.InvocationTargetException {
    final java.lang.reflect.Method method =
        AppConfigMapperImpl.class.getDeclaredMethod(WF_DEF_REQ_MAP_TO_WF_DEF_MAP, Map.class);
    method.setAccessible(true);

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

    final Object result = method.invoke(mapper, input);

    assertThat(result).isNotNull().isInstanceOf(Map.class);
    @SuppressWarnings(UNCHECKED)
    final Map<String, WorkflowDefinition> mapResult = (Map<String, WorkflowDefinition>) result;
    assertThat(mapResult).hasSize(2).containsKeys("w1", "w2");
  }

  @Test
  void testNodeRequestListToNodeListWithMultipleNodes()
      throws NoSuchMethodException,
          IllegalAccessException,
          java.lang.reflect.InvocationTargetException {
    final java.lang.reflect.Method method =
        AppConfigMapperImpl.class.getDeclaredMethod(NODE_REQUEST_LIST_TO_NODE_LIST, List.class);
    method.setAccessible(true);

    final List<NodeRequest> input =
        List.of(
            new NodeRequest(NODE_1, TRIGGER_PLUGIN, Map.of()),
            new NodeRequest(NODE_2, PROCESSOR_PLUGIN, Map.of()),
            new NodeRequest("node-3", TERMINAL_PLUGIN, Map.of()));

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
        AppConfigMapperImpl.class.getDeclaredMethod(EDGE_REQUEST_LIST_TO_EDGE_LIST, List.class);
    method.setAccessible(true);

    final List<EdgeRequest> input =
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
        AppConfigMapperImpl.class.getDeclaredMethod(WF_DEF_REQ_MAP_TO_WF_DEF_MAP, Map.class);
    method.setAccessible(true);

    final Map<String, WorkflowDefinitionRequest> input = new ConcurrentHashMap<>();

    final Object result = method.invoke(mapper, input);

    assertThat(result).isNotNull().isInstanceOf(Map.class);
    @SuppressWarnings(UNCHECKED)
    final Map<String, WorkflowDefinition> mapResult = (Map<String, WorkflowDefinition>) result;
    assertThat(mapResult).isEmpty();
  }

  @Test
  void testNodeRequestListToNodeListWithEmptyList()
      throws NoSuchMethodException,
          IllegalAccessException,
          java.lang.reflect.InvocationTargetException {
    final java.lang.reflect.Method method =
        AppConfigMapperImpl.class.getDeclaredMethod(NODE_REQUEST_LIST_TO_NODE_LIST, List.class);
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
        AppConfigMapperImpl.class.getDeclaredMethod(EDGE_REQUEST_LIST_TO_EDGE_LIST, List.class);
    method.setAccessible(true);

    final List<EdgeRequest> input = new java.util.ArrayList<>();

    final Object result = method.invoke(mapper, input);

    assertThat(result).isNotNull().isInstanceOf(List.class);
    final List<?> listResult = (List<?>) result;
    assertThat(listResult).isEmpty();
  }

  @Test
  void testStringWorkflowDefinitionRequestMapToStringWorkflowDefinitionMapWithSingleEntry()
      throws NoSuchMethodException,
          IllegalAccessException,
          java.lang.reflect.InvocationTargetException {
    final java.lang.reflect.Method method =
        AppConfigMapperImpl.class.getDeclaredMethod(WF_DEF_REQ_MAP_TO_WF_DEF_MAP, Map.class);
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
    @SuppressWarnings(UNCHECKED)
    final Map<String, WorkflowDefinition> mapResult = (Map<String, WorkflowDefinition>) result;
    assertThat(mapResult).hasSize(1).containsKey("only-one");
  }

  @Test
  void testNodeRequestWithNonNullConfigMapValue()
      throws NoSuchMethodException,
          IllegalAccessException,
          java.lang.reflect.InvocationTargetException {
    final java.lang.reflect.Method method =
        AppConfigMapperImpl.class.getDeclaredMethod(
            "toNode", WorkflowDefinitionRequest.NodeRequest.class);
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
  void testEdgeWithNonNullSourcePort()
      throws NoSuchMethodException,
          IllegalAccessException,
          java.lang.reflect.InvocationTargetException {
    final java.lang.reflect.Method method =
        AppConfigMapperImpl.class.getDeclaredMethod(
            "toEdge", WorkflowDefinitionRequest.EdgeRequest.class);
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
  void testEdgeWithEmptyStringSourcePort()
      throws NoSuchMethodException,
          IllegalAccessException,
          java.lang.reflect.InvocationTargetException {
    final java.lang.reflect.Method method =
        AppConfigMapperImpl.class.getDeclaredMethod(
            "toEdge", WorkflowDefinitionRequest.EdgeRequest.class);
    method.setAccessible(true);

    final EdgeRequest input = new EdgeRequest(SOURCE_NODE, TARGET_NODE, "");

    final Object result = method.invoke(mapper, input);

    assertThat(result).isNotNull().isInstanceOf(WorkflowDefinition.Edge.class);
    final WorkflowDefinition.Edge edgeResult = (WorkflowDefinition.Edge) result;
    assertThat(edgeResult.source()).isEqualTo(SOURCE_NODE);
    assertThat(edgeResult.target()).isEqualTo(TARGET_NODE);
    assertThat(edgeResult.sourcePort()).isEqualTo("");
  }

  /* default */ NodeRequest buildNodeRequest(final String nodeId, final String type) {
    return new NodeRequest(nodeId, type, Map.of());
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
