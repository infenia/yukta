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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class SessionMapperTest {

  @Autowired private SessionMapper mapper;

  @Test
  void testConfigRequestToSessionConfigData() {
    ConfigRequest request =
        new ConfigRequest(
            "session-123",
            "Test Session",
            "test-user",
            Map.of("env", "test"),
            "/home/user/project",
            Map.of(
                "workflow-1",
                new WorkflowDefinitionRequest(
                    "workflow-1",
                    "Test Workflow",
                    List.of(new NodeRequest("node-1", "ProcessorPlugin", Map.of("param", "value"))),
                    List.of(new EdgeRequest("node-1", "node-1", null)))));

    SessionConfigData result = mapper.configRequestToSessionConfigData(request);

    assertThat(result).isNotNull();
    assertThat(result.sessionId()).isEqualTo("session-123");
    assertThat(result.description()).isEqualTo("Test Session");
    assertThat(result.initiator()).isEqualTo("test-user");
    assertThat(result.projectPath()).isEqualTo("/home/user/project");
  }

  @Test
  void testWorkflowDefinitionRequestToWorkflowDefinition() {
    WorkflowDefinitionRequest request =
        new WorkflowDefinitionRequest(
            "workflow-1",
            "Test Workflow",
            List.of(
                new NodeRequest("node-1", "ProcessorPlugin", Map.of()),
                new NodeRequest("node-2", "TerminalPlugin", Map.of())),
            List.of(new EdgeRequest("node-1", "node-2", null)));

    WorkflowDefinition result = mapper.workflowDefinitionRequestToWorkflowDefinition(request);

    assertThat(result).isNotNull();
    assertThat(result.nodes()).hasSize(2);
    assertThat(result.edges()).hasSize(1);
  }

  @Test
  void testEdgeRequestToEdgeWithNullSourcePort() {
    EdgeRequest edgeRequest = new EdgeRequest("node-1", "node-2", null);

    Edge result = mapper.edgeRequestToEdge(edgeRequest);

    assertThat(result).isNotNull();
    assertThat(result.sourcePort()).isEqualTo("default");
    assertThat(result.source()).isEqualTo("node-1");
    assertThat(result.target()).isEqualTo("node-2");
  }

  @Test
  void testEdgeRequestToEdgeWithSourcePort() {
    EdgeRequest edgeRequest = new EdgeRequest("node-1", "node-2", "output");

    Edge result = mapper.edgeRequestToEdge(edgeRequest);

    assertThat(result).isNotNull();
    assertThat(result.sourcePort()).isEqualTo("output");
    assertThat(result.source()).isEqualTo("node-1");
    assertThat(result.target()).isEqualTo("node-2");
  }

  @Test
  void testConfigRequestToSessionConfigDataWithEmptyWorkflows() {
    ConfigRequest request =
        new ConfigRequest(
            "session-456", "Empty Session", "system", null, "/home/user/project2", Map.of());

    SessionConfigData result = mapper.configRequestToSessionConfigData(request);

    assertThat(result).isNotNull();
    assertThat(result.sessionId()).isEqualTo("session-456");
  }

  @Test
  void testWorkflowDefinitionRequestToWorkflowDefinitionWithMultipleNodes() {
    WorkflowDefinitionRequest request =
        new WorkflowDefinitionRequest(
            "workflow-complex",
            "Complex Workflow",
            List.of(
                new NodeRequest("node-1", "TriggerPlugin", Map.of()),
                new NodeRequest("node-2", "ProcessorPlugin", Map.of("step", "1")),
                new NodeRequest("node-3", "ProcessorPlugin", Map.of("step", "2")),
                new NodeRequest("node-4", "TerminalPlugin", Map.of())),
            List.of(
                new EdgeRequest("node-1", "node-2", "success"),
                new EdgeRequest("node-2", "node-3", null),
                new EdgeRequest("node-3", "node-4", "default")));

    WorkflowDefinition result = mapper.workflowDefinitionRequestToWorkflowDefinition(request);

    assertThat(result).isNotNull();
    assertThat(result.nodes()).hasSize(4);
    assertThat(result.edges()).hasSize(3);
  }

  @Test
  void testEdgeRequestToEdgeWithoutConfig() {
    EdgeRequest edgeRequest1 = new EdgeRequest("node-1", "node-2", "");
    Edge result1 = mapper.edgeRequestToEdge(edgeRequest1);
    assertThat(result1).isNotNull();

    EdgeRequest edgeRequest2 = new EdgeRequest("node-A", "node-B", "");
    Edge result2 = mapper.edgeRequestToEdge(edgeRequest2);
    assertThat(result2).isNotNull();
  }

  @Test
  void testConfigRequestWithManyWorkflows() {
    Map<String, WorkflowDefinitionRequest> workflows = new java.util.HashMap<>();
    for (int i = 0; i < 5; i++) {
      workflows.put(
          "workflow-" + i,
          new WorkflowDefinitionRequest(
              "workflow-" + i,
              "Workflow " + i,
              List.of(new NodeRequest("node-" + i, "ProcessorPlugin", Map.of())),
              List.of()));
    }

    ConfigRequest request =
        new ConfigRequest(
            "session-many",
            "Many Workflows",
            "test-user",
            Map.of("key1", "val1", "key2", "val2"),
            "/home/user/project",
            workflows);

    SessionConfigData result = mapper.configRequestToSessionConfigData(request);

    assertThat(result).isNotNull();
    assertThat(result.sessionId()).isEqualTo("session-many");
  }

  @Test
  void testWorkflowWithEmptyEdges() {
    WorkflowDefinitionRequest request =
        new WorkflowDefinitionRequest(
            "workflow-no-edges",
            "No Edges Workflow",
            List.of(
                new NodeRequest("node-1", "TriggerPlugin", Map.of()),
                new NodeRequest("node-2", "TerminalPlugin", Map.of())),
            List.of());

    WorkflowDefinition result = mapper.workflowDefinitionRequestToWorkflowDefinition(request);

    assertThat(result).isNotNull();
    assertThat(result.nodes()).hasSize(2);
    assertThat(result.edges()).isEmpty();
  }

  @Test
  void testConfigRequestToSessionConfigDataWithNull() {
    SessionConfigData result = mapper.configRequestToSessionConfigData(null);

    assertThat(result).isNull();
  }

  @Test
  void testWorkflowDefinitionRequestToWorkflowDefinitionWithNull() {
    WorkflowDefinition result = mapper.workflowDefinitionRequestToWorkflowDefinition(null);

    assertThat(result).isNull();
  }

  @Test
  void testEdgeRequestToEdgeWithNull() {
    Edge result = mapper.edgeRequestToEdge(null);

    assertThat(result).isNull();
  }

  @Test
  void testStringWorkflowDefinitionRequestMapToStringWorkflowDefinitionMapWithNull()
      throws NoSuchMethodException,
          IllegalAccessException,
          java.lang.reflect.InvocationTargetException {
    java.lang.reflect.Method method =
        SessionMapperImpl.class.getDeclaredMethod(
            "stringWorkflowDefinitionRequestMapToStringWorkflowDefinitionMap", Map.class);
    method.setAccessible(true);

    Object result = method.invoke(mapper, (Object) null);

    assertThat(result).isNull();
  }

  @Test
  void testNodeRequestListToNodeListWithNull()
      throws NoSuchMethodException,
          IllegalAccessException,
          java.lang.reflect.InvocationTargetException {
    java.lang.reflect.Method method =
        SessionMapperImpl.class.getDeclaredMethod("nodeRequestListToNodeList", List.class);
    method.setAccessible(true);

    Object result = method.invoke(mapper, (Object) null);

    assertThat(result).isNull();
  }

  @Test
  void testEdgeRequestListToEdgeListWithNull()
      throws NoSuchMethodException,
          IllegalAccessException,
          java.lang.reflect.InvocationTargetException {
    java.lang.reflect.Method method =
        SessionMapperImpl.class.getDeclaredMethod("edgeRequestListToEdgeList", List.class);
    method.setAccessible(true);

    Object result = method.invoke(mapper, (Object) null);

    assertThat(result).isNull();
  }

  @Test
  void testNodeRequestToNodeWithNull()
      throws NoSuchMethodException,
          IllegalAccessException,
          java.lang.reflect.InvocationTargetException {
    java.lang.reflect.Method method =
        SessionMapperImpl.class.getDeclaredMethod(
            "nodeRequestToNode", WorkflowDefinitionRequest.NodeRequest.class);
    method.setAccessible(true);

    Object result = method.invoke(mapper, (Object) null);

    assertThat(result).isNull();
  }

  @Test
  void testStringWorkflowDefinitionRequestMapToStringWorkflowDefinitionMapWithNonNullValue()
      throws NoSuchMethodException,
          IllegalAccessException,
          java.lang.reflect.InvocationTargetException {
    java.lang.reflect.Method method =
        SessionMapperImpl.class.getDeclaredMethod(
            "stringWorkflowDefinitionRequestMapToStringWorkflowDefinitionMap", Map.class);
    method.setAccessible(true);

    Map<String, WorkflowDefinitionRequest> input =
        Map.of(
            "workflow-1",
            new WorkflowDefinitionRequest(
                "workflow-1",
                "Test Workflow",
                List.of(new NodeRequest("node-1", "ProcessorPlugin", Map.of())),
                List.of()));

    Object result = method.invoke(mapper, input);

    assertThat(result).isNotNull().isInstanceOf(Map.class);
    @SuppressWarnings("unchecked")
    Map<String, WorkflowDefinition> mapResult = (Map<String, WorkflowDefinition>) result;
    assertThat(mapResult).hasSize(1).containsKey("workflow-1");
  }

  @Test
  void testNodeRequestListToNodeListWithNonNullValue()
      throws NoSuchMethodException,
          IllegalAccessException,
          java.lang.reflect.InvocationTargetException {
    java.lang.reflect.Method method =
        SessionMapperImpl.class.getDeclaredMethod("nodeRequestListToNodeList", List.class);
    method.setAccessible(true);

    List<NodeRequest> input =
        List.of(
            new NodeRequest("node-1", "ProcessorPlugin", Map.of()),
            new NodeRequest("node-2", "TerminalPlugin", Map.of()));

    Object result = method.invoke(mapper, input);

    assertThat(result).isNotNull().isInstanceOf(List.class);
    List<?> listResult = (List<?>) result;
    assertThat(listResult).hasSize(2);
  }

  @Test
  void testEdgeRequestListToEdgeListWithNonNullValue()
      throws NoSuchMethodException,
          IllegalAccessException,
          java.lang.reflect.InvocationTargetException {
    java.lang.reflect.Method method =
        SessionMapperImpl.class.getDeclaredMethod("edgeRequestListToEdgeList", List.class);
    method.setAccessible(true);

    List<WorkflowDefinitionRequest.EdgeRequest> input =
        List.of(
            new EdgeRequest("node-1", "node-2", null),
            new EdgeRequest("node-2", "node-3", "output"));

    Object result = method.invoke(mapper, input);

    assertThat(result).isNotNull().isInstanceOf(List.class);
    List<?> listResult = (List<?>) result;
    assertThat(listResult).hasSize(2);
  }

  @Test
  void testNodeRequestToNodeWithNonNullValue()
      throws NoSuchMethodException,
          IllegalAccessException,
          java.lang.reflect.InvocationTargetException {
    java.lang.reflect.Method method =
        SessionMapperImpl.class.getDeclaredMethod(
            "nodeRequestToNode", WorkflowDefinitionRequest.NodeRequest.class);
    method.setAccessible(true);

    NodeRequest input = new NodeRequest("node-1", "ProcessorPlugin", Map.of("key", "value"));

    Object result = method.invoke(mapper, input);

    assertThat(result).isNotNull().isInstanceOf(WorkflowDefinition.Node.class);
    WorkflowDefinition.Node nodeResult = (WorkflowDefinition.Node) result;
    assertThat(nodeResult.nodeId()).isEqualTo("node-1");
    assertThat(nodeResult.type()).isEqualTo("ProcessorPlugin");
  }

  @Test
  void testStringWorkflowDefinitionRequestMapToStringWorkflowDefinitionMapWithMultipleEntries()
      throws NoSuchMethodException,
          IllegalAccessException,
          java.lang.reflect.InvocationTargetException {
    java.lang.reflect.Method method =
        SessionMapperImpl.class.getDeclaredMethod(
            "stringWorkflowDefinitionRequestMapToStringWorkflowDefinitionMap", Map.class);
    method.setAccessible(true);

    Map<String, WorkflowDefinitionRequest> input = new java.util.LinkedHashMap<>();
    input.put(
        "w1",
        new WorkflowDefinitionRequest(
            "w1",
            "Workflow 1",
            List.of(new NodeRequest("n1", "ProcessorPlugin", Map.of())),
            List.of()));
    input.put(
        "w2",
        new WorkflowDefinitionRequest(
            "w2",
            "Workflow 2",
            List.of(new NodeRequest("n2", "ProcessorPlugin", Map.of())),
            List.of()));

    Object result = method.invoke(mapper, input);

    assertThat(result).isNotNull().isInstanceOf(Map.class);
    @SuppressWarnings("unchecked")
    Map<String, WorkflowDefinition> mapResult = (Map<String, WorkflowDefinition>) result;
    assertThat(mapResult).hasSize(2).containsKeys("w1", "w2");
  }

  @Test
  void testNodeRequestListToNodeListWithMultipleNodes()
      throws NoSuchMethodException,
          IllegalAccessException,
          java.lang.reflect.InvocationTargetException {
    java.lang.reflect.Method method =
        SessionMapperImpl.class.getDeclaredMethod("nodeRequestListToNodeList", List.class);
    method.setAccessible(true);

    List<NodeRequest> input =
        List.of(
            new NodeRequest("node-1", "TriggerPlugin", Map.of()),
            new NodeRequest("node-2", "ProcessorPlugin", Map.of()),
            new NodeRequest("node-3", "TerminalPlugin", Map.of()));

    Object result = method.invoke(mapper, input);

    assertThat(result).isNotNull().isInstanceOf(List.class);
    List<?> listResult = (List<?>) result;
    assertThat(listResult).hasSize(3);
  }

  @Test
  void testEdgeRequestListToEdgeListWithMultipleEdges()
      throws NoSuchMethodException,
          IllegalAccessException,
          java.lang.reflect.InvocationTargetException {
    java.lang.reflect.Method method =
        SessionMapperImpl.class.getDeclaredMethod("edgeRequestListToEdgeList", List.class);
    method.setAccessible(true);

    List<WorkflowDefinitionRequest.EdgeRequest> input =
        List.of(
            new EdgeRequest("n1", "n2", "port1"),
            new EdgeRequest("n2", "n3", null),
            new EdgeRequest("n3", "n4", "port2"));

    Object result = method.invoke(mapper, input);

    assertThat(result).isNotNull().isInstanceOf(List.class);
    List<?> listResult = (List<?>) result;
    assertThat(listResult).hasSize(3);
  }

  @Test
  void testStringWorkflowDefinitionRequestMapToStringWorkflowDefinitionMapWithEmptyMap()
      throws NoSuchMethodException,
          IllegalAccessException,
          java.lang.reflect.InvocationTargetException {
    java.lang.reflect.Method method =
        SessionMapperImpl.class.getDeclaredMethod(
            "stringWorkflowDefinitionRequestMapToStringWorkflowDefinitionMap", Map.class);
    method.setAccessible(true);

    Map<String, WorkflowDefinitionRequest> input = new java.util.LinkedHashMap<>();

    Object result = method.invoke(mapper, input);

    assertThat(result).isNotNull().isInstanceOf(Map.class);
    @SuppressWarnings("unchecked")
    Map<String, WorkflowDefinition> mapResult = (Map<String, WorkflowDefinition>) result;
    assertThat(mapResult).isEmpty();
  }

  @Test
  void testNodeRequestListToNodeListWithEmptyList()
      throws NoSuchMethodException,
          IllegalAccessException,
          java.lang.reflect.InvocationTargetException {
    java.lang.reflect.Method method =
        SessionMapperImpl.class.getDeclaredMethod("nodeRequestListToNodeList", List.class);
    method.setAccessible(true);

    List<NodeRequest> input = new java.util.ArrayList<>();

    Object result = method.invoke(mapper, input);

    assertThat(result).isNotNull().isInstanceOf(List.class);
    List<?> listResult = (List<?>) result;
    assertThat(listResult).isEmpty();
  }

  @Test
  void testEdgeRequestListToEdgeListWithEmptyList()
      throws NoSuchMethodException,
          IllegalAccessException,
          java.lang.reflect.InvocationTargetException {
    java.lang.reflect.Method method =
        SessionMapperImpl.class.getDeclaredMethod("edgeRequestListToEdgeList", List.class);
    method.setAccessible(true);

    List<WorkflowDefinitionRequest.EdgeRequest> input = new java.util.ArrayList<>();

    Object result = method.invoke(mapper, input);

    assertThat(result).isNotNull().isInstanceOf(List.class);
    List<?> listResult = (List<?>) result;
    assertThat(listResult).isEmpty();
  }

  @Test
  void testNodeRequestToNodeWithNullConfigMap()
      throws NoSuchMethodException,
          IllegalAccessException,
          java.lang.reflect.InvocationTargetException {
    java.lang.reflect.Method method =
        SessionMapperImpl.class.getDeclaredMethod(
            "nodeRequestToNode", WorkflowDefinitionRequest.NodeRequest.class);
    method.setAccessible(true);

    NodeRequest input = new NodeRequest("node-1", "ProcessorPlugin", null);

    Object result = method.invoke(mapper, input);

    assertThat(result).isNotNull().isInstanceOf(WorkflowDefinition.Node.class);
    WorkflowDefinition.Node nodeResult = (WorkflowDefinition.Node) result;
    assertThat(nodeResult.nodeId()).isEqualTo("node-1");
    assertThat(nodeResult.type()).isEqualTo("ProcessorPlugin");
    assertThat(nodeResult.config()).isNotNull();
  }

  @Test
  void testStringWorkflowDefinitionRequestMapToStringWorkflowDefinitionMapWithSingleEntry()
      throws NoSuchMethodException,
          IllegalAccessException,
          java.lang.reflect.InvocationTargetException {
    java.lang.reflect.Method method =
        SessionMapperImpl.class.getDeclaredMethod(
            "stringWorkflowDefinitionRequestMapToStringWorkflowDefinitionMap", Map.class);
    method.setAccessible(true);

    Map<String, WorkflowDefinitionRequest> input = new java.util.HashMap<>();
    input.put(
        "only-one",
        new WorkflowDefinitionRequest(
            "only-one",
            "Only Workflow",
            List.of(new NodeRequest("only-node", "ProcessorPlugin", Map.of())),
            List.of()));

    Object result = method.invoke(mapper, input);

    assertThat(result).isNotNull().isInstanceOf(Map.class);
    @SuppressWarnings("unchecked")
    Map<String, WorkflowDefinition> mapResult = (Map<String, WorkflowDefinition>) result;
    assertThat(mapResult).hasSize(1).containsKey("only-one");
  }

  @Test
  void testNodeRequestToNodeWithNonNullConfigMapValue()
      throws NoSuchMethodException,
          IllegalAccessException,
          java.lang.reflect.InvocationTargetException {
    java.lang.reflect.Method method =
        SessionMapperImpl.class.getDeclaredMethod(
            "nodeRequestToNode", WorkflowDefinitionRequest.NodeRequest.class);
    method.setAccessible(true);

    Map<String, Object> configMap = new java.util.LinkedHashMap<>();
    configMap.put("key1", "value1");
    configMap.put("key2", 123);
    NodeRequest input = new NodeRequest("complex-node", "ProcessorPlugin", configMap);

    Object result = method.invoke(mapper, input);

    assertThat(result).isNotNull().isInstanceOf(WorkflowDefinition.Node.class);
    WorkflowDefinition.Node nodeResult = (WorkflowDefinition.Node) result;
    assertThat(nodeResult.nodeId()).isEqualTo("complex-node");
    assertThat(nodeResult.type()).isEqualTo("ProcessorPlugin");
    assertThat(nodeResult.config()).isNotNull().containsKeys("key1", "key2");
  }

  @Test
  void testEdgeRequestToEdgeWithNonNullSourcePort()
      throws NoSuchMethodException,
          IllegalAccessException,
          java.lang.reflect.InvocationTargetException {
    java.lang.reflect.Method method =
        SessionMapperImpl.class.getDeclaredMethod(
            "edgeRequestToEdge", WorkflowDefinitionRequest.EdgeRequest.class);
    method.setAccessible(true);

    EdgeRequest input = new EdgeRequest("source-node", "target-node", "custom-port");

    Object result = method.invoke(mapper, input);

    assertThat(result).isNotNull().isInstanceOf(WorkflowDefinition.Edge.class);
    WorkflowDefinition.Edge edgeResult = (WorkflowDefinition.Edge) result;
    assertThat(edgeResult.source()).isEqualTo("source-node");
    assertThat(edgeResult.target()).isEqualTo("target-node");
    assertThat(edgeResult.sourcePort()).isEqualTo("custom-port");
  }

  @Test
  void testEdgeRequestToEdgeWithEmptyStringSourcePort()
      throws NoSuchMethodException,
          IllegalAccessException,
          java.lang.reflect.InvocationTargetException {
    java.lang.reflect.Method method =
        SessionMapperImpl.class.getDeclaredMethod(
            "edgeRequestToEdge", WorkflowDefinitionRequest.EdgeRequest.class);
    method.setAccessible(true);

    EdgeRequest input = new EdgeRequest("source-node", "target-node", "");

    Object result = method.invoke(mapper, input);

    assertThat(result).isNotNull().isInstanceOf(WorkflowDefinition.Edge.class);
    WorkflowDefinition.Edge edgeResult = (WorkflowDefinition.Edge) result;
    assertThat(edgeResult.source()).isEqualTo("source-node");
    assertThat(edgeResult.target()).isEqualTo("target-node");
    assertThat(edgeResult.sourcePort()).isEqualTo("");
  }
}
