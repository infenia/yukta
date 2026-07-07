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
package com.infenia.yukta.logging.api;

import java.time.LocalDateTime;

/**
 * Metadata summary for a plugin execution.
 *
 * @param executionId unique execution identifier
 * @param sessionId session identifier
 * @param startTime when execution started
 * @param endTime when execution ended (null if still running)
 * @param entryCount number of log entries
 */
public record ExecutionSummary(
    String executionId,
    String sessionId,
    LocalDateTime startTime,
    LocalDateTime endTime,
    long entryCount) {}
