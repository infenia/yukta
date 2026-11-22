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
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
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
 * Serves the example sessions bundled at build time ({@code
 * examples/<category>/NN-name/session.json}) as session templates, for a client that wants to start
 * from a working definition.
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

  /** Reads the sessions. */
  private static final JsonMapper JSON = JsonMapper.builder().build();

  /**
   * One template in the list.
   *
   * @param category the examples folder it sits in, such as {@code 1-standalone}
   * @param categoryLabel what the category is called, such as {@code Standalone}; the folder name
   *     when {@code examples/index.json} does not name it
   * @param categoryDescription what the category means, for a tooltip; empty when not given
   */
  public record TemplateSummary(
      String id,
      String title,
      String summary,
      String category,
      String categoryLabel,
      String categoryDescription,
      List<String> pluginTypes) {

    /** Copies the list so the record cannot be changed through it. */
    public TemplateSummary {
      pluginTypes = List.copyOf(pluginTypes);
    }
  }

  /** What {@code examples/index.json} says about a category. */
  /* package */ record Category(String label, String description) {}

  /**
   * The order and the categories of the templates, from {@code examples/index.json}.
   *
   * @param order template ids, first to last; a template not named comes after them, by id
   * @param categories the folder name to its label and description
   */
  /* package */ record Index(List<String> order, Map<String, Category> categories) {

    /** Where a template stands; the ones not named share the last place. */
    /* default */ int rank(final String id) {
      final int at = order.indexOf(id);
      return at < 0 ? order.size() : at;
    }

    /* default */ Category category(final String folder) {
      return categories.getOrDefault(folder, new Category(folder, ""));
    }
  }

  /** One template with its README and its session definition. */
  public record TemplateDetail(
      String id,
      String title,
      String summary,
      List<String> pluginTypes,
      String readme,
      JsonNode session) {

    /** Copies the list so the record cannot be changed through it. */
    public TemplateDetail {
      pluginTypes = List.copyOf(pluginTypes);
    }
  }

  /**
   * List the templates.
   *
   * @return the templates, in the order of {@code examples/index.json}, the ones it does not name
   *     last by id
   */
  @GetMapping
  @Operation(
      summary = "List the session templates",
      description =
          "The bundled examples in display order: id, title, a one-line summary, the category with"
              + " its label and meaning, and the plugin types used")
  public Mono<ApiResponse<List<TemplateSummary>>> list() {
    return Mono.fromCallable(
            () -> {
              final Index index = index();
              final List<TemplateSummary> out = new ArrayList<>();
              for (final var dir : knownDirs().entrySet()) {
                final String folder = dir.getValue().substring(0, dir.getValue().indexOf('/'));
                out.add(summary(detail(dir.getKey()), folder, index.category(folder)));
              }
              out.sort(
                  Comparator.comparingInt((TemplateSummary t) -> index.rank(t.id()))
                      .thenComparing(TemplateSummary::id));
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

  private static TemplateSummary summary(
      final TemplateDetail detail, final String folder, final Category category) {
    return new TemplateSummary(
        detail.id(),
        detail.title(),
        detail.summary(),
        folder,
        category.label(),
        category.description(),
        detail.pluginTypes());
  }

  /** Reads {@code templates/index.json}; without it the order is by id and categories bare. */
  private static Index index() throws IOException {
    final Resource file = new ClassPathResource(ROOT + "index.json");
    if (!file.exists()) {
      return new Index(List.of(), Map.of());
    }
    final JsonNode config = JSON.readTree(file.getContentAsByteArray());
    final List<String> order = new ArrayList<>();
    config.path("order").forEach(id -> order.add(id.asString("")));
    final Map<String, Category> categories = new ConcurrentHashMap<>();
    config
        .path("categories")
        .properties()
        .forEach(entry ->
            categories.put(
                entry.getKey(),
                new Category(
                    entry.getValue().path("label").asString(entry.getKey()),
                    entry.getValue().path("description").asString(""))));
    return new Index(List.copyOf(order), Map.copyOf(categories));
  }

  /**
   * The bundled templates: id to folder under {@code ROOT}. The examples are grouped by category
   * ({@code templates/<category>/<id>/session.json}); the id is the example's own folder name.
   */
  private static Map<String, String> knownDirs() throws IOException {
    final var dirs = new TreeMap<String, String>();
    final Pattern folder = Pattern.compile("/" + ROOT + "([^/]+/[^/]+)/session\\.json$");
    for (final Resource resource :
        new PathMatchingResourcePatternResolver()
            .getResources("classpath*:" + ROOT + "*/*/session.json")) {
      folder
          .matcher(resource.getURL().getPath())
          .results()
          .forEach(m -> dirs.put(m.group(1).substring(m.group(1).indexOf('/') + 1), m.group(1)));
    }
    return dirs;
  }

  private static TemplateDetail detail(final String requested) throws IOException {
    // the id is looked up among the bundled folders, never put into a path as sent
    final Map<String, String> dirs = knownDirs();
    final String id =
        dirs.keySet().stream()
            .filter(requested::equals)
            .findFirst()
            .orElseThrow(() -> new NotFoundException("Template not found: " + requested));
    final String dir = ROOT + dirs.get(id) + "/";
    final var resolver = new PathMatchingResourcePatternResolver();
    final Resource session = resolver.getResource("classpath:" + dir + "session.json");
    final JsonNode config = JSON.readTree(session.getContentAsByteArray());
    final Resource readmeFile = resolver.getResource("classpath:" + dir + "README.md");
    final String readme = new String(readmeFile.getContentAsByteArray(), StandardCharsets.UTF_8);
    final Heading heading =
        heading(readme, config.path("name").asString(id), config.path("description").asString(""));
    final var types = new TreeSet<String>();
    config
        .path("workflows")
        .forEach(workflow ->
            workflow.path("nodes").forEach(node -> types.add(node.path("type").asString(""))));
    types.remove("");
    return new TemplateDetail(
        id, heading.title(), heading.summary(), List.copyOf(types), readme, config);
  }

  /** The title and the one-line summary of a template. */
  /* package */ record Heading(String title, String summary) {}

  /**
   * Reads the title (the first {@code # } heading) and the summary (the use-case line) out of a
   * README; what it does not have stays as given.
   */
  /* package */ static Heading heading(
      final String readme, final String fallbackTitle, final String fallbackSummary) {
    String title = fallbackTitle;
    String summary = fallbackSummary;
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
    return new Heading(title, summary);
  }
}
