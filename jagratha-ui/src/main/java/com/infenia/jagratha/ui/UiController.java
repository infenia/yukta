package com.infenia.jagratha.ui;

import com.infenia.jagratha.config.AppConfigService;
import com.infenia.jagratha.service.AppService;
import com.infenia.jagratha.service.TaskTrackerService;
import gg.jte.TemplateEngine;
import gg.jte.output.StringOutput;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Controller for the Jagratha UI. */
@Controller
@RequestMapping("/ui")
@RequiredArgsConstructor
public class UiController {

  private final AppService appService;
  private final AppConfigService configService;
  private final TaskTrackerService tracker;
  private final TemplateEngine templateEngine;

  /**
   * Render the index page.
   *
   * @param model the UI model
   * @return the rendered HTML
   */
  @GetMapping(produces = MediaType.TEXT_HTML_VALUE)
  @ResponseBody
  public Mono<String> index(final Model model) {
    model.addAttribute("sessions", appService.getActiveSessions());
    return Mono.fromCallable(
            () -> {
              final StringOutput output = new StringOutput();
              templateEngine.render("index.jte", model.asMap(), output);
              return output.toString();
            })
        .subscribeOn(Schedulers.boundedElastic());
  }

  /**
   * Render the history page.
   *
   * @param model the UI model
   * @return the rendered HTML
   */
  @GetMapping(value = "/history", produces = MediaType.TEXT_HTML_VALUE)
  @ResponseBody
  public Mono<String> history(final Model model) {
    model.addAttribute("sessions", appService.getHistorySessions());
    return Mono.fromCallable(
            () -> {
              final StringOutput output = new StringOutput();
              templateEngine.render("history.jte", model.asMap(), output);
              return output.toString();
            })
        .subscribeOn(Schedulers.boundedElastic());
  }

  /**
   * Render the session detail page.
   *
   * @param sessionId the session identifier
   * @param model the UI model
   * @return the rendered HTML
   */
  @GetMapping(value = "/sessions/{sessionId}", produces = MediaType.TEXT_HTML_VALUE)
  @ResponseBody
  public Mono<String> session(@PathVariable final String sessionId, final Model model) {
    model.addAttribute("sessionId", sessionId);
    model.addAttribute("config", appService.getSessionConfig(sessionId));
    model.addAttribute("workflows", appService.getSessionWorkflows(sessionId));
    model.addAttribute("modifiedFiles", appService.getModifiedFiles(sessionId));
    model.addAttribute("progress", tracker.getProgress(sessionId));

    return appService
        .listLogs(sessionId)
        .map(
            logs -> {
              model.addAttribute("logs", logs);
              final StringOutput output = new StringOutput();
              templateEngine.render("session.jte", model.asMap(), output);
              return output.toString();
            })
        .subscribeOn(Schedulers.boundedElastic());
  }

  /**
   * Stream logs for a session via SSE.
   *
   * @param sessionId the session identifier
   * @return a flux of log lines
   */
  @GetMapping(
      value = "/api/sessions/{sessionId}/logs/stream",
      produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  @ResponseBody
  public Flux<String> streamLogs(@PathVariable final String sessionId) {
    return tracker.getLogStream(sessionId);
  }

  /**
   * Stream status updates for a session via SSE.
   *
   * @param sessionId the session identifier
   * @return a flux of status update events
   */
  @GetMapping(
      value = "/api/sessions/{sessionId}/status/stream",
      produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  @ResponseBody
  public Flux<String> streamStatus(@PathVariable final String sessionId) {
    return tracker.getStatusStream(sessionId);
  }
}
