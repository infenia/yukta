// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.dto.response.LogEntryResponse;
import com.infenia.yukta.dto.response.WorkflowGraphEdge;
import com.infenia.yukta.logging.api.LogLevel;
import com.infenia.yukta.logging.api.LogStream;
import com.infenia.yukta.logging.api.PluginLogEntry;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

/** Tests for WorkflowMapper. */
@SuppressWarnings({"PMD.TooManyMethods", "PMD.LinguisticNaming"})
@NoArgsConstructor
class WorkflowMapperTest {

  /** Execution identifier constant. */
  private static final String EXECUTION_ID = "exec-091-qp-55";

  /** Session identifier constant. */
  private static final String SESSION_ID = "session-a92";

  /** Plugin identifier constant. */
  private static final String PLUGIN_ID = "vectorize-batch";

  /** Plugin name constant. */
  private static final String PLUGIN_NAME = "vector-engine-v2";

  /** Log message constant. */
  private static final String MESSAGE = "Processing batch 1/45...";

  /** Source node identifier constant. */
  private static final String SOURCE = "data-ingress";

  /** Target node identifier constant. */
  private static final String TARGET = "sink-storage";

  /** Sample timestamp constant. */
  private static final Instant TIMESTAMP = Instant.parse("2026-07-26T14:22:15Z");

  /** Mapper for workflow data transformation. */
  private WorkflowMapper mapper;

  @BeforeEach
  void setUp() {
    mapper = Mappers.getMapper(WorkflowMapper.class);
  }

  @Test
  void testMapperIsNotNull() {
    assertThat(mapper).isNotNull();
  }

  @Test
  void testMapperInstanceExists() {
    assertThat(mapper).isInstanceOf(WorkflowMapper.class);
  }

  @Test
  void toGraphEdge_mapsAllFields() {
    // Given
    final WorkflowDefinition.Edge edge = new WorkflowDefinition.Edge(SOURCE, TARGET, "default");

    // When
    final WorkflowGraphEdge actual = mapper.toGraphEdge(edge);

    // Then
    assertThat(actual.source()).isEqualTo(SOURCE);
    assertThat(actual.target()).isEqualTo(TARGET);
    assertThat(actual.sourcePort()).isEqualTo("default");
  }

  @Test
  void toGraphEdge_nullEdge_returnsNull() {
    // Given-When-Then
    assertThat(mapper.toGraphEdge(null)).isNull();
  }

  @Test
  void toGraphEdges_mapsListPreservingOrderAndNullPorts() {
    // Given
    final List<WorkflowDefinition.Edge> edges =
        List.of(
            new WorkflowDefinition.Edge(SOURCE, TARGET, null),
            new WorkflowDefinition.Edge(TARGET, SOURCE, "error"));

    // When
    final List<WorkflowGraphEdge> actual = mapper.toGraphEdges(edges);

    // Then
    assertThat(actual)
        .containsExactly(
            new WorkflowGraphEdge(SOURCE, TARGET, null),
            new WorkflowGraphEdge(TARGET, SOURCE, "error"));
  }

  @Test
  void toGraphEdges_nullList_returnsNull() {
    // Given-When-Then
    assertThat(mapper.toGraphEdges(null)).isNull();
  }

  @Test
  void toLogEntry_mapsAllFieldsWithStandardStream() {
    // Given
    final PluginLogEntry entry =
        new PluginLogEntry(
            EXECUTION_ID,
            SESSION_ID,
            PLUGIN_ID,
            PLUGIN_NAME,
            LogStream.STDOUT,
            MESSAGE,
            LogLevel.INFO,
            TIMESTAMP,
            null,
            Map.of());

    // When
    final LogEntryResponse actual = mapper.toLogEntry(entry);

    // Then
    assertThat(actual.executionId()).isEqualTo(EXECUTION_ID);
    assertThat(actual.pluginId()).isEqualTo(PLUGIN_ID);
    assertThat(actual.pluginName()).isEqualTo(PLUGIN_NAME);
    assertThat(actual.stream()).isEqualTo("STDOUT");
    assertThat(actual.message()).isEqualTo(MESSAGE);
    assertThat(actual.level()).isEqualTo("INFO");
    assertThat(actual.timestamp()).isEqualTo(TIMESTAMP);
  }

  @Test
  void toLogEntry_customStreamWithName_foldsCustomStreamName() {
    // Given
    final PluginLogEntry entry =
        new PluginLogEntry(
            EXECUTION_ID,
            SESSION_ID,
            PLUGIN_ID,
            PLUGIN_NAME,
            LogStream.CUSTOM,
            MESSAGE,
            LogLevel.WARN,
            TIMESTAMP,
            "metrics",
            Map.of());

    // When
    final LogEntryResponse actual = mapper.toLogEntry(entry);

    // Then
    assertThat(actual.stream()).isEqualTo("metrics");
    assertThat(actual.level()).isEqualTo("WARN");
  }

  @Test
  void toLogEntry_customStreamWithoutName_fallsBackToStreamName() {
    // Given
    final PluginLogEntry entry =
        new PluginLogEntry(
            EXECUTION_ID,
            SESSION_ID,
            PLUGIN_ID,
            PLUGIN_NAME,
            LogStream.CUSTOM,
            MESSAGE,
            LogLevel.ERROR,
            TIMESTAMP,
            null,
            Map.of());

    // When
    final LogEntryResponse actual = mapper.toLogEntry(entry);

    // Then
    assertThat(actual.stream()).isEqualTo("CUSTOM");
    assertThat(actual.level()).isEqualTo("ERROR");
  }

  @Test
  void toLogEntry_nullStreamAndLevel_mapsToNulls() {
    // Given
    final PluginLogEntry entry =
        new PluginLogEntry(
            EXECUTION_ID,
            SESSION_ID,
            PLUGIN_ID,
            PLUGIN_NAME,
            null,
            MESSAGE,
            null,
            TIMESTAMP,
            null,
            Map.of());

    // When
    final LogEntryResponse actual = mapper.toLogEntry(entry);

    // Then
    assertThat(actual.stream()).isNull();
    assertThat(actual.level()).isNull();
  }

  @Test
  void toLogEntry_nullEntry_returnsNull() {
    // Given-When-Then
    assertThat(mapper.toLogEntry(null)).isNull();
  }
}
