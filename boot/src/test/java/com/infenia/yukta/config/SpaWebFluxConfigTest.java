// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.config;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.config.ResourceHandlerRegistration;
import org.springframework.web.reactive.config.ResourceHandlerRegistry;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;

@SuppressWarnings({"PMD.TooManyMethods", "PMD.UnitTestShouldIncludeAssert"})
@ExtendWith(MockitoExtension.class)
class SpaWebFluxConfigTest {

  private final SpaWebFluxConfig config = new SpaWebFluxConfig();

  @Mock private ResourceHandlerRegistry registry;

  @Captor private ArgumentCaptor<String[]> pathPatternsCaptor;

  @Test
  void fallbackServesHtmlToBrowsersButNotToJsonOnlyClients() {
    final var client = WebTestClient.bindToRouterFunction(config.spaRouter()).build();

    client
        .get()
        .uri("/workflows")
        .header("Accept", "text/html,*/*;q=0.8")
        .exchange()
        .expectStatus()
        .isOk();
    client
        .get()
        .uri("/apis/workflows")
        .header("Accept", "application/json")
        .exchange()
        .expectStatus()
        .isNotFound();
  }

  @Test
  void testAddResourceHandlersRegistersNestedAssetsPatternSeparately() {
    final ResourceHandlerRegistration registration = mock(ResourceHandlerRegistration.class);
    when(registry.addResourceHandler(any(String[].class))).thenReturn(registration);
    when(registration.addResourceLocations(anyString())).thenReturn(registration);
    when(registration.setCacheControl(any(CacheControl.class))).thenReturn(registration);

    config.addResourceHandlers(registry);

    verify(registry, times(2)).addResourceHandler(pathPatternsCaptor.capture());
    final String[] nestedAssetsPatterns = pathPatternsCaptor.getAllValues().get(0);

    assertThat(nestedAssetsPatterns).containsExactly("/assets/**");
  }

  @Test
  void testAddResourceHandlersRegistersTopLevelAssetPatterns() {
    final ResourceHandlerRegistration registration = mock(ResourceHandlerRegistration.class);
    when(registry.addResourceHandler(any(String[].class))).thenReturn(registration);
    when(registration.addResourceLocations(anyString())).thenReturn(registration);
    when(registration.setCacheControl(any(CacheControl.class))).thenReturn(registration);

    config.addResourceHandlers(registry);

    verify(registry, times(2)).addResourceHandler(pathPatternsCaptor.capture());
    final String[] topLevelPatterns = pathPatternsCaptor.getAllValues().get(1);

    assertThat(topLevelPatterns)
        .containsExactlyInAnyOrder(
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
  }

  @Test
  void testAddResourceHandlersUsesNestedAssetsLocationForAssetsPattern() {
    final ResourceHandlerRegistration registration = mock(ResourceHandlerRegistration.class);
    when(registry.addResourceHandler(any(String[].class))).thenReturn(registration);
    when(registration.addResourceLocations(anyString())).thenReturn(registration);
    when(registration.setCacheControl(any(CacheControl.class))).thenReturn(registration);

    config.addResourceHandlers(registry);

    verify(registration).addResourceLocations("classpath:/static/assets/");
    verify(registration).addResourceLocations("classpath:/static/");
  }

  @Test
  void testHashedAssetsCacheForOneYearAndTopLevelAssetsRevalidate() {
    final ResourceHandlerRegistration registration = mock(ResourceHandlerRegistration.class);
    when(registry.addResourceHandler(any(String[].class))).thenReturn(registration);
    when(registration.addResourceLocations(anyString())).thenReturn(registration);

    config.addResourceHandlers(registry);

    final ArgumentCaptor<CacheControl> cacheControlCaptor =
        ArgumentCaptor.forClass(CacheControl.class);
    verify(registration, times(2)).setCacheControl(cacheControlCaptor.capture());

    assertThat(cacheControlCaptor.getAllValues())
        .extracting(CacheControl::getHeaderValue)
        .containsExactly("max-age=31536000", "no-cache");
  }

  @Test
  void testSpaRouterReturnsBeanInstance() {
    final RouterFunction<ServerResponse> router = config.spaRouter();
    assertThat(router).isNotNull();
  }

  @Test
  void testSpaRouterServesIndexHtmlOnGetRequest() {
    final RouterFunction<ServerResponse> router = config.spaRouter();
    final WebTestClient client = WebTestClient.bindToRouterFunction(router).build();

    client
        .get()
        .uri("/")
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .contentTypeCompatibleWith(MediaType.TEXT_HTML)
        .expectHeader()
        .exists("Cache-Control");
  }

  @Test
  void testSpaRouterSetsContentSecurityPolicy() {
    final RouterFunction<ServerResponse> router = config.spaRouter();
    final WebTestClient client = WebTestClient.bindToRouterFunction(router).build();

    client
        .get()
        .uri("/sessions/1")
        .exchange()
        .expectHeader()
        .valueEquals("Content-Security-Policy", SpaWebFluxConfig.CONTENT_SECURITY_POLICY);
    assertThat(SpaWebFluxConfig.CONTENT_SECURITY_POLICY)
        .contains("default-src 'self'", "style-src 'self' 'unsafe-inline'")
        .doesNotContain("script-src 'unsafe");
  }

  @Test
  void testSpaRouterServesIndexHtmlOnUnmatchedRoute() {
    final RouterFunction<ServerResponse> router = config.spaRouter();
    final WebTestClient client = WebTestClient.bindToRouterFunction(router).build();

    client
        .get()
        .uri("/sessions/123/details")
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .contentTypeCompatibleWith(MediaType.TEXT_HTML);
  }

  @Test
  void testSpaRouterSetsCacheControlNoCacheOnResponses() {
    final RouterFunction<ServerResponse> router = config.spaRouter();
    final WebTestClient client = WebTestClient.bindToRouterFunction(router).build();

    client
        .get()
        .uri("/app")
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .exists("Cache-Control");
  }

  @Test
  void testSpaRouterReturnsMonoServerResponse() {
    final RouterFunction<ServerResponse> router = config.spaRouter();
    assertThat(router).isNotNull();

    final WebTestClient client = WebTestClient.bindToRouterFunction(router).build();

    client
        .get()
        .uri("/any-path")
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .contentTypeCompatibleWith(MediaType.TEXT_HTML);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"/", "/foo/bar/baz/deeply/nested/path", "/sessions?filter=active&sort=created"})
  void testSpaRouterHandlesVariousRoutes(final String uri) {
    final RouterFunction<ServerResponse> router = config.spaRouter();
    final WebTestClient client = WebTestClient.bindToRouterFunction(router).build();

    client
        .get()
        .uri(uri)
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .contentTypeCompatibleWith(MediaType.TEXT_HTML);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/assets/index-CeV2IbxW.js",
        "/assets/index-qFjbJNVv.css",
        "/assets/font-BEAKL7Jp.woff2",
        "/assets/font-BEAKL7Jp.woff",
        "/assets/font-BEAKL7Jp.ttf",
        "/favicon.svg",
        "/favicon.png",
        "/favicon.ico",
        "/site.webmanifest"
      })
  void testSpaRouterDoesNotMatchStaticAssetPaths(final String uri) {
    final RouterFunction<ServerResponse> router = config.spaRouter();
    final WebTestClient client = WebTestClient.bindToRouterFunction(router).build();

    // The router must NOT handle these — they must fall through so the resource handler
    // mapping (registered separately in addResourceHandlers) can serve the real asset. If the
    // router wrongly matches, WebTestClient's default handler returns 404 for the unmatched
    // route, which is what we assert here since no resource handler is wired in this test.
    client.get().uri(uri).exchange().expectStatus().isNotFound();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/api/sessions/summaries",
        "/actuator/health",
        "/mcp/status",
        "/sse/status",
        "/v3/api-docs",
        "/swagger-ui.html",
        "/swagger-ui/index.html"
      })
  void testSpaRouterDoesNotMatchBackendPaths(final String uri) {
    final RouterFunction<ServerResponse> router = config.spaRouter();
    final WebTestClient client = WebTestClient.bindToRouterFunction(router).build();

    client.get().uri(uri).exchange().expectStatus().isNotFound();
  }

  @Test
  void testConfigurationIsNotNull() {
    assertThat(config).isNotNull();
  }

  @Test
  void testAddResourceHandlersDoesNotThrowException() {
    final ResourceHandlerRegistry registration = mock(ResourceHandlerRegistry.class);
    final ResourceHandlerRegistration regHandler = mock(ResourceHandlerRegistration.class);
    when(registration.addResourceHandler(any(String[].class))).thenReturn(regHandler);
    when(regHandler.addResourceLocations(anyString())).thenReturn(regHandler);
    when(regHandler.setCacheControl(any(CacheControl.class))).thenReturn(regHandler);

    assertThatNoException().isThrownBy(() -> config.addResourceHandlers(registration));
  }
}
