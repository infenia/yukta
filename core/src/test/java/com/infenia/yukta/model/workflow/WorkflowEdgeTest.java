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
package com.infenia.yukta.model.workflow;

import static org.assertj.core.api.Assertions.*;

import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link WorkflowEdge}. */
@NoArgsConstructor
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class WorkflowEdgeTest {

  @Test
  void testConstructorAndGetters() {
    // Test with null sourcePort
    final WorkflowEdge edge1 = new WorkflowEdge("source1", "target1", null);
    assertThat(edge1.source()).isEqualTo("source1");
    assertThat(edge1.target()).isEqualTo("target1");
    assertThat(edge1.sourcePort()).isNull();

    // Test with non-null sourcePort
    final WorkflowEdge edge2 = new WorkflowEdge("source2", "target2", "port1");
    assertThat(edge2.source()).isEqualTo("source2");
    assertThat(edge2.target()).isEqualTo("target2");
    assertThat(edge2.sourcePort()).isEqualTo("port1");
  }

  @Test
  void testEqualsAndHashCode() {
    final WorkflowEdge edge1 = new WorkflowEdge("source", "target", null);
    final WorkflowEdge edge2 = new WorkflowEdge("source", "target", null);
    final WorkflowEdge edge3 = new WorkflowEdge("source", "target", "port");
    final WorkflowEdge edge4 = new WorkflowEdge("different", "target", null);

    // Same values should be equal
    assertThat(edge1).isEqualTo(edge2);
    assertThat(edge1.hashCode()).isEqualTo(edge2.hashCode());

    // Different sourcePort should not be equal
    assertThat(edge1).isNotEqualTo(edge3);
    assertThat(edge1.hashCode()).isNotEqualTo(edge3.hashCode());

    // Different source should not be equal
    assertThat(edge1).isNotEqualTo(edge4);
    assertThat(edge1.hashCode()).isNotEqualTo(edge4.hashCode());
  }

  @Test
  void testToString() {
    final WorkflowEdge edgeWithNullPort = new WorkflowEdge("source", "target", null);
    final WorkflowEdge edgeWithPort = new WorkflowEdge("source", "target", "port");

    final String toStringWithNull = edgeWithNullPort.toString();
    final String toStringWithPort = edgeWithPort.toString();

    // toString should contain all field values
    assertThat(toStringWithNull)
        .isEqualTo("WorkflowEdge[source=source, target=target, sourcePort=null]");
    assertThat(toStringWithPort)
        .isEqualTo("WorkflowEdge[source=source, target=target, sourcePort=port]");
  }
}
