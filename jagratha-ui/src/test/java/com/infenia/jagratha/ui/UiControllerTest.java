package com.infenia.jagratha.ui;

import com.infenia.jagratha.config.AppConfigService;
import com.infenia.jagratha.service.AppService;
import com.infenia.jagratha.service.TaskTrackerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

@WebFluxTest(UiController.class)
class UiControllerTest {

  @Autowired private WebTestClient webTestClient;

  @MockitoBean private AppService appService;

  @MockitoBean private AppConfigService configService;

  @MockitoBean private TaskTrackerService taskTrackerService;

  @MockitoBean private gg.jte.TemplateEngine templateEngine;

  @Test
  void testStreamLogs() {
    webTestClient
        .get()
        .uri("/ui/api/sessions/session1/logs/stream")
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .contentTypeCompatibleWith("text/event-stream");
  }

  @Test
  void testStreamStatus() {
    webTestClient
        .get()
        .uri("/ui/api/sessions/session1/status/stream")
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .contentTypeCompatibleWith("text/event-stream");
  }
}
