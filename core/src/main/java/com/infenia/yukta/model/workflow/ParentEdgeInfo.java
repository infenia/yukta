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

import jakarta.annotation.Nullable;

/**
 * Metadata for an inbound parent edge to a node.
 *
 * <p>Encodes the source node, optional output port, and index in the Flux array for routing and
 * filtering incoming messages.
 *
 * @param parentIndex the index of the parent node's stream in the Flux array
 * @param sourceNodeId the ID of the source node
 * @param sourcePort optional output port filter; null means no port filtering
 */
public record ParentEdgeInfo(int parentIndex, String sourceNodeId, @Nullable String sourcePort) {}
