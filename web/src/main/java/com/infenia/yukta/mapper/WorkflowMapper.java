// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mapper;

import org.mapstruct.Mapper;

/**
 * MapStruct mapper for converting between core domain workflow models.
 *
 * <p>Note: WorkflowExecutionSummary and WorkflowProgress are core monitoring models used for
 * tracking workflow execution state. Controllers return these directly to clients via REST API.
 */
@Mapper(componentModel = "spring")
public interface WorkflowMapper {}
