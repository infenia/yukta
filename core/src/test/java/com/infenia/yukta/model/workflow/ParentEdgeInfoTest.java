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

/** Unit tests for {@link ParentEdgeInfo}. */
@NoArgsConstructor
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class ParentEdgeInfoTest {

  @Test
  void testConstructorAndGetters() {
    // Test with null sourcePort
    final ParentEdgeInfo info1 = new ParentEdgeInfo(0, "source1", null);
    assertThat(info1.parentIndex()).isEqualTo(0);
    assertThat(info1.sourceNodeId()).isEqualTo("source1");
    assertThat(info1.sourcePort()).isNull();

    // Test with non-null sourcePort
    final ParentEdgeInfo info2 = new ParentEdgeInfo(1, "source2", "port1");
    assertThat(info2.parentIndex()).isEqualTo(1);
    assertThat(info2.sourceNodeId()).isEqualTo("source2");
    assertThat(info2.sourcePort()).isEqualTo("port1");
  }

  @Test
  void testEqualsAndHashCode() {
    final ParentEdgeInfo info1 = new ParentEdgeInfo(0, "source", null);
    final ParentEdgeInfo info2 = new ParentEdgeInfo(0, "source", null);
    final ParentEdgeInfo info3 = new ParentEdgeInfo(0, "source", "port");
    final ParentEdgeInfo info4 = new ParentEdgeInfo(1, "source", null);
    final ParentEdgeInfo info5 = new ParentEdgeInfo(0, "different", null);

    // Same values should be equal
    assertThat(info1).isEqualTo(info2);
    assertThat(info1.hashCode()).isEqualTo(info2.hashCode());

    // Different sourcePort should not be equal
    assertThat(info1).isNotEqualTo(info3);
    assertThat(info1.hashCode()).isNotEqualTo(info3.hashCode());

    // Different parentIndex should not be equal
    assertThat(info1).isNotEqualTo(info4);
    assertThat(info1.hashCode()).isNotEqualTo(info4.hashCode());

    // Different sourceNodeId should not be equal
    assertThat(info1).isNotEqualTo(info5);
    assertThat(info1.hashCode()).isNotEqualTo(info5.hashCode());
  }

  @Test
  void testToString() {
    final ParentEdgeInfo infoWithNullPort = new ParentEdgeInfo(0, "source", null);
    final ParentEdgeInfo infoWithPort = new ParentEdgeInfo(1, "source", "port");

    final String toStringWithNull = infoWithNullPort.toString();
    final String toStringWithPort = infoWithPort.toString();

    // toString should contain all field values
    assertThat(toStringWithNull)
        .isEqualTo("ParentEdgeInfo[parentIndex=0, sourceNodeId=source, sourcePort=null]");
    assertThat(toStringWithPort)
        .isEqualTo("ParentEdgeInfo[parentIndex=1, sourceNodeId=source, sourcePort=port]");
  }
}
