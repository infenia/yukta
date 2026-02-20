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
package com.infenia.jagratha.config;

import com.infenia.jagratha.model.WorkflowDefinition;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

class AppConfigServiceTest {

  private AppConfigService configService;

  @BeforeEach
  void setUp() {
    configService = new AppConfigService();
  }

  @Test
  void testDefaultValues() {
    String sessionId = "sess-1";
    StepVerifier.create(configService.getProjectPath(sessionId)).expectNext("").verifyComplete();
    StepVerifier.create(configService.getWorkflow(sessionId)).verifyComplete(); // Empty initially
    StepVerifier.create(configService.getExecutionTimeout(sessionId))
        .expectNext(300L)
        .verifyComplete();
    String home = System.getProperty("user.home");
    StepVerifier.create(configService.getFileLogDir(sessionId))
        .expectNext(home + "/.jagratha/modified-files")
        .verifyComplete();
    StepVerifier.create(configService.getResultLogDir(sessionId))
        .expectNext(home + "/.jagratha/results")
        .verifyComplete();
  }

  @Test
  void testApiOverrides() {
    String sessionId = "sess-1";
    StepVerifier.create(configService.setProjectPath(sessionId, "/api/path")).verifyComplete();
    WorkflowDefinition workflow =
        new WorkflowDefinition(
            List.of(new WorkflowDefinition.Node("n1", "gradle", Map.of())), List.of());
    StepVerifier.create(configService.setWorkflow(sessionId, workflow)).verifyComplete();

    StepVerifier.create(configService.getProjectPath(sessionId))
        .expectNext("/api/path")
        .verifyComplete();
    StepVerifier.create(configService.getWorkflow(sessionId)).expectNext(workflow).verifyComplete();

    // Another session should still have defaults
    String otherSession = "sess-2";
    StepVerifier.create(configService.getProjectPath(otherSession)).expectNext("").verifyComplete();
  }

  @Test
  void testActiveSessionTracking() {
    String sess1 = "sess-1";
    String sess2 = "sess-2";

    StepVerifier.create(configService.getActiveSessionIds()).expectNextCount(0).verifyComplete();
    StepVerifier.create(configService.isActive(sess1)).expectNext(false).verifyComplete();

    StepVerifier.create(configService.setProjectPath(sess1, "/path/1")).verifyComplete();
    StepVerifier.create(configService.getActiveSessionIds()).expectNext(sess1).verifyComplete();
    StepVerifier.create(configService.isActive(sess1)).expectNext(true).verifyComplete();
    StepVerifier.create(configService.isActive(sess2)).expectNext(false).verifyComplete();

    WorkflowDefinition workflow = new WorkflowDefinition(List.of(), List.of());
    StepVerifier.create(configService.setWorkflow(sess2, workflow)).verifyComplete();

    StepVerifier.create(configService.getActiveSessionIds()).expectNextCount(2).verifyComplete();
  }
}
