// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import com.infenia.yukta.model.api.ApiResponse;
import com.infenia.yukta.service.NotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Serves the example sessions bundled at build time ({@code examples/NN-name/session.json}) as
 * session templates, for a client that wants to start from a working definition.
 */
@RestController
@RequestMapping("/api/templates")
@Tag(name = "Templates API", description = "Example sessions to start from")
public class TemplateController {

  /** Where the build copies the examples. */
  private static final String ROOT = "templates/";

  /** The first heading of a README: {@code # 01 — Hello world}. */
  private static final Pattern TITLE = Pattern.compile("^#\\s*(?:[\\d]+\\s*[—–-]\\s*)?(.+)$");

  /** The use-case line of a README. */
  private static final Pattern USE_CASE = Pattern.compile("^\\*\\*Use case:\\*\\*\\s*(.+)$");

  /** A template id: a folder name of the examples. */
  private static final Pattern ID = Pattern.compile("[\\w.-]+");

  /** Reads the sessions. */
  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** One template in the list. */
  public record TemplateSummary(
      String id, String title, String summary, List<String> pluginTypes) {}

  /** One template with its README and its session definition. */
  public record TemplateDetail(
      String id,
      String title,
      String summary,
      List<String> pluginTypes,
      String readme,
      JsonNode session) {}

  /**
   * List the templates.
   *
   * @return the templates, by id
   */
  @GetMapping
  @Operation(
      summary = "List the session templates",
      description = "The bundled examples: id, title, a one-line summary and the plugin types used")
  public Mono<ApiResponse<List<TemplateSummary>>> list() {
    return Mono.fromCallable(
            () -> {
              final var resolver = new PathMatchingResourcePatternResolver();
              final var ids = new TreeSet<String>();
              final Pattern folder = Pattern.compile("/" + ROOT + "([^/]+)/session\\.json$");
              for (final Resource resource :
                  resolver.getResources("classpath*:" + ROOT + "*/session.json")) {
                final Matcher match = folder.matcher(resource.getURL().getPath());
                if (match.find()) {
                  ids.add(match.group(1));
                }
              }
              final List<TemplateSummary> out = new ArrayList<>();
              for (final String id : ids) {
                out.add(summary(detail(id)));
              }
              out.sort(Comparator.comparing(TemplateSummary::id));
              return ApiResponse.success(200, "Templates retrieved", out);
            })
        .onErrorMap(IOException.class, UncheckedIOException::new);
  }

  /**
   * Get one template.
   *
   * @param id the template id, as {@code list} shows it
   * @return the template with its README and session definition
   */
  @GetMapping("/{id}")
  @Operation(
      summary = "Get a session template",
      description = "The README (markdown) and the session definition to apply. 404 if unknown")
  public Mono<ApiResponse<TemplateDetail>> get(
      @Parameter(description = "Template id") @PathVariable final String id) {
    return Mono.fromCallable(() -> ApiResponse.success(200, "Template retrieved", detail(id)));
  }

  private static TemplateSummary summary(final TemplateDetail detail) {
    return new TemplateSummary(detail.id(), detail.title(), detail.summary(), detail.pluginTypes());
  }

  private static TemplateDetail detail(final String id) throws IOException {
    if (!ID.matcher(id).matches() || id.startsWith(".")) {
      throw new NotFoundException("Template not found: " + id);
    }
    final var resolver = new PathMatchingResourcePatternResolver();
    final Resource session = resolver.getResource("classpath:" + ROOT + id + "/session.json");
    if (!session.exists()) {
      throw new NotFoundException("Template not found: " + id);
    }
    final JsonNode config = JSON.readTree(session.getContentAsByteArray());
    final Resource readmeFile = resolver.getResource("classpath:" + ROOT + id + "/README.md");
    final String readme =
        readmeFile.exists()
            ? new String(readmeFile.getContentAsByteArray(), StandardCharsets.UTF_8)
            : "";
    String title = config.path("name").asString(id);
    String summary = config.path("description").asString("");
    for (final String line : readme.lines().toList()) {
      final Matcher heading = TITLE.matcher(line);
      final Matcher useCase = USE_CASE.matcher(line);
      if (heading.matches() && line.startsWith("# ")) {
        title = heading.group(1).strip();
      } else if (useCase.matches()) {
        summary = useCase.group(1).strip();
        break;
      }
    }
    final var types = new TreeSet<String>();
    config
        .path("workflows")
        .forEach(
            workflow ->
                workflow.path("nodes").forEach(node -> types.add(node.path("type").asString(""))));
    types.remove("");
    return new TemplateDetail(id, title, summary, List.copyOf(types), readme, config);
  }
}
