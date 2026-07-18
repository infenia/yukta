// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.config.ResourceHandlerRegistry;
import org.springframework.web.reactive.config.WebFluxConfigurer;
import org.springframework.web.reactive.function.server.RequestPredicates;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

/** Serves the React SPA static assets from classpath:/static/. */
@Configuration
public class SpaWebFluxConfig implements WebFluxConfigurer {

  /** Default constructor for Spring. */
  public SpaWebFluxConfig() {
    // Intentionally empty
  }

  @Override
  public void addResourceHandlers(final ResourceHandlerRegistry registry) {
    // Serve all static assets (JS, CSS, fonts, images) with cache
    registry
        .addResourceHandler(
            "/**/*.js",
            "/**/*.css",
            "/**/*.woff",
            "/**/*.woff2",
            "/**/*.ttf",
            "/**/*.svg",
            "/**/*.png",
            "/**/*.ico",
            "/**/*.webmanifest")
        .addResourceLocations("classpath:/static/")
        .setCacheControl(CacheControl.maxAge(365, java.util.concurrent.TimeUnit.DAYS));
  }

  /**
   * SPA fallback router: serves index.html for all unmatched routes to enable client-side routing.
   */
  @Bean
  public RouterFunction<ServerResponse> spaRouter() {
    final Resource indexHtml = new ClassPathResource("static/index.html");
    return RouterFunctions.route(
        RequestPredicates.GET("/**"),
        request ->
            ServerResponse.ok()
                .contentType(MediaType.TEXT_HTML)
                .cacheControl(CacheControl.noCache().mustRevalidate())
                .body(Mono.just(indexHtml), Resource.class));
  }
}
