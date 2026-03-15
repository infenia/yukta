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
package com.infenia.yukta.ui;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.service.ControlBusService;
import com.infenia.yukta.service.LogRetrievalService;
import com.infenia.yukta.service.SessionService;
import com.infenia.yukta.service.TaskTrackerService;
import com.infenia.yukta.service.WorkflowRegistry;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.AriaRole;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"gg.jte.development-mode=true", "gg.jte.template-location=src/main/jte"})
class UiE2ETest {

  @LocalServerPort private int port;

  @MockitoBean private SessionService sessionService;
  @MockitoBean private LogRetrievalService logRetrievalService;
  @MockitoBean private TaskTrackerService taskTrackerService;
  @MockitoBean private WorkflowRegistry workflowRegistry;
  @MockitoBean private ControlBusService controlBusService;
  @MockitoBean private com.infenia.yukta.plugin.gateway.BuildGateway buildGateway;

  private static Playwright playwright;
  private static Browser browser;
  private BrowserContext context;
  private Page page;

  @BeforeAll
  static void beforeAll() {
    playwright = Playwright.create();
    browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
  }

  @AfterAll
  static void afterAll() {
    if (browser != null) {
      browser.close();
    }
    if (playwright != null) {
      playwright.close();
    }
  }

  @BeforeEach
  void setUp() {
    when(sessionService.getSessionIds()).thenReturn(Flux.just("test-session"));
    when(sessionService.getSessionConfig(anyString())).thenReturn(Mono.just(Map.of()));
    when(sessionService.getSessionWorkflow(anyString(), anyString()))
        .thenReturn(Mono.just(new WorkflowDefinition("test", List.of(), List.of())));
    when(logRetrievalService.listLogs(anyString())).thenReturn(Mono.just(List.of()));
    when(taskTrackerService.getLatestExecutionId(anyString(), anyString())).thenReturn("exec-1");

    context = browser.newContext();
    page = context.newPage();
  }

  @AfterEach
  void tearDown() {
    if (context != null) {
      context.close();
    }
  }

  @Test
  void testSessionsPageShouldLoadAndMatchTitle() {
    page.navigate("http://localhost:" + port + "/ui");
    assertThat(page).hasTitle(Pattern.compile("Dashboard - Yukta"));
    assertThat(
            page.getByRole(
                AriaRole.HEADING, new Page.GetByRoleOptions().setName("Active Sessions")))
        .isVisible();
  }

  @Test
  void testSessionDetailsPageShouldLoad() {
    page.navigate("http://localhost:" + port + "/ui/sessions/test-session");
    assertThat(page.locator("h2")).isVisible();
  }

  @Test
  void testWorkflowPageShouldDisplayDagDiagram() {
    page.navigate("http://localhost:" + port + "/ui/sessions/test-session/workflow/quality-check");
    assertThat(page.locator("#dag-container")).isVisible();
    assertThat(page.locator("svg[x-ref=\"dagSvg\"]")).isVisible();
  }
}
