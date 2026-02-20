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
package com.infenia.jagratha.model;

import com.infenia.jagratha.validation.ProjectPath;
import com.infenia.jagratha.validation.SessionId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * Data record for application configuration.
 *
 * @param sessionId the session identifier
 * @param projectPath the project path
 * @param workflow the workflow definition (DAG)
 */
public record AppConfigData(
    @SessionId String sessionId,
    @ProjectPath String projectPath,
    @NotNull(message = "Workflow definition cannot be null") @Valid WorkflowDefinition workflow) {}
