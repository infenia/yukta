// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

/** Tests for WorkflowMapper. */
@NoArgsConstructor
class WorkflowMapperTest {

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
}
