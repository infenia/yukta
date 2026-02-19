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

import com.infenia.jagratha.model.PluginRegistration;
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
    StepVerifier.create(configService.getPluginName(sessionId)).expectNext("").verifyComplete();
    StepVerifier.create(configService.getPluginConfig(sessionId))
        .expectNext(Map.of())
        .verifyComplete();
    StepVerifier.create(configService.getTasks(sessionId)).expectNextCount(4).verifyComplete();
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
    StepVerifier.create(configService.getWorkflows(sessionId)).expectNextCount(0).verifyComplete();
  }

  @Test
  void testApiOverrides() {
    String sessionId = "sess-1";
    StepVerifier.create(configService.setProjectPath(sessionId, "/api/path")).verifyComplete();
    List<PluginRegistration> plugins =
        List.of(new PluginRegistration("gradle", Map.of("gradlePath", "/api/gradle")));
    StepVerifier.create(configService.setPlugins(sessionId, plugins)).verifyComplete();
    StepVerifier.create(configService.setWorkflows(sessionId, List.of())).verifyComplete();

    StepVerifier.create(configService.getProjectPath(sessionId))
        .expectNext("/api/path")
        .verifyComplete();
    StepVerifier.create(configService.getPluginName(sessionId))
        .expectNext("gradle")
        .verifyComplete();
    StepVerifier.create(configService.getPluginConfig(sessionId))
        .expectNext(Map.of("gradlePath", "/api/gradle"))
        .verifyComplete();
    StepVerifier.create(configService.getWorkflows(sessionId)).expectNextCount(0).verifyComplete();

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

    StepVerifier.create(
            configService.setPlugins(
                sess2, List.of(new PluginRegistration("maven", Map.of("mavenPath", "/api/maven")))))
        .verifyComplete();

    StepVerifier.create(configService.getActiveSessionIds()).expectNextCount(2).verifyComplete();
  }
}
