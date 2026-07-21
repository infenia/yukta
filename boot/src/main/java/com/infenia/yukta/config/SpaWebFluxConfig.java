// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.config;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.config.ResourceHandlerRegistry;
import org.springframework.web.reactive.config.WebFluxConfigurer;
import org.springframework.web.reactive.function.server.RequestPredicate;
import org.springframework.web.reactive.function.server.RequestPredicates;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

/** Serves the React SPA static assets from classpath:/static/. */
@Configuration
public class SpaWebFluxConfig implements WebFluxConfigurer {

  /** Patterns for flat, top-level static asset routes served from classpath:/static/. */
  private static final List<String> TOP_LEVEL_ASSET_PATTERNS =
      List.of(
          "/*.js",
          "/*.css",
          "/*.woff",
          "/*.woff2",
          "/*.ttf",
          "/*.svg",
          "/*.png",
          "/*.jpg",
          "/*.webp",
          "/*.ico",
          "/*.json",
          "/*.webmanifest");

  /** All static asset patterns (top-level plus nested /assets/**) excluded from the SPA router. */
  private static final List<String> STATIC_ASSET_PATTERNS =
      Stream.concat(Stream.of("/assets/**"), TOP_LEVEL_ASSET_PATTERNS.stream()).toList();

  /** Default constructor for Spring. */
  public SpaWebFluxConfig() {
    // Intentionally empty
  }

  @Override
  public void addResourceHandlers(final ResourceHandlerRegistry registry) {
    // Vite emits hashed bundles under dist/assets/, which is copied to classpath:/static/assets/.
    // AntPathMatcher strips the "/assets/" prefix from "/assets/**" before resolving against the
    // location, so this pattern needs its own location that includes the "assets" segment —
    // otherwise it looks for the file directly under classpath:/static/ and 404s.
    registry
        .addResourceHandler("/assets/**")
        .addResourceLocations("classpath:/static/assets/")
        .setCacheControl(CacheControl.maxAge(365, TimeUnit.DAYS));

    // Flat, top-level static assets (favicon, manifest, fonts referenced outside /assets/, etc.)
    registry
        .addResourceHandler(TOP_LEVEL_ASSET_PATTERNS.toArray(new String[0]))
        .addResourceLocations("classpath:/static/")
        .setCacheControl(CacheControl.maxAge(365, TimeUnit.DAYS));
  }

  /**
   * SPA fallback router: serves index.html for all unmatched routes to enable client-side routing.
   * Excludes API routes (/api/**, /actuator/**, /sse/**) and static asset paths so that the
   * resource handler mapping (registered in {@link #addResourceHandlers}) can serve them — the
   * router mapping is otherwise consulted before the resource handler mapping and would shadow it.
   */
  @Bean
  public RouterFunction<ServerResponse> spaRouter() {
    final Resource indexHtml = new ClassPathResource("static/index.html");
    RequestPredicate predicate =
        RequestPredicates.GET("/**")
            .and(RequestPredicates.path("/api/**").negate())
            .and(RequestPredicates.path("/actuator/**").negate())
            .and(RequestPredicates.path("/sse/**").negate());
    for (final String pattern : STATIC_ASSET_PATTERNS) {
      predicate = predicate.and(RequestPredicates.path(pattern).negate());
    }
    return RouterFunctions.route(
        predicate,
        request ->
            ServerResponse.ok()
                .contentType(MediaType.TEXT_HTML)
                .cacheControl(CacheControl.noCache().mustRevalidate())
                .body(Mono.just(indexHtml), Resource.class));
  }
}
