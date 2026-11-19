// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import com.infenia.yukta.dto.response.ConfigValue;
import com.infenia.yukta.model.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Shows the value the running server uses for the settings a client names. Never dumps the whole
 * environment: only keys under the known prefixes, secrets masked.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Tag(name = "Configuration API", description = "Effective values of the server's settings")
public class ConfigController {

  /** What a masked value shows. */
  private static final String MASKED = "******";

  /** Where nothing sets the key, so the built-in default applies. */
  private static final String DEFAULT = "default";

  /** Settings Yukta documents live under these prefixes; nothing else is readable. */
  private static final List<String> PREFIXES =
      List.of("yukta.", "server.", "spring.", "management.", "logging.", "springdoc.");

  /** The most keys one request may ask for. */
  private static final int MAX_KEYS = 500;

  /** The longest key accepted. */
  private static final int MAX_KEY_LENGTH = 200;

  /** Keys whose value is a secret: tokens, passwords, credentials and API keys. */
  private static final Pattern SECRET_KEY =
      Pattern.compile("secret|token|password|credential|api-keys|(^|\\.)key$");

  /** The password in a {@code scheme://user:password@host} URL. */
  private static final Pattern URL_PASSWORD = Pattern.compile("(://[^/:@\\s]+:)[^@\\s]+@");

  /** The server's settings, as Spring resolved them. */
  private final ConfigurableEnvironment environment;

  /**
   * Show the values in use.
   *
   * @param keys the property keys to look up, in the order to return them
   * @return one entry per distinct key: the value (masked for a secret) and where it comes from
   */
  @GetMapping("/config")
  @Operation(
      summary = "Show the effective values of settings",
      description =
          "Returns, for each `key` asked for, the value the server uses and where it comes from"
              + " (argument, system property, environment variable, configuration file). A key"
              + " nothing sets has a null value and the source `default`: the built-in default"
              + " applies. Keys must start with yukta., server., spring., management., logging."
              + " or springdoc.; at most 500. Secrets (keys, tokens, passwords, credentials, and"
              + " passwords in URLs) are masked as ******. Needs an ADMIN key.")
  public Mono<ApiResponse<List<ConfigValue>>> show(@RequestParam("key") final List<String> keys) {
    return Mono.fromSupplier(
        () -> ApiResponse.success(200, "Configuration retrieved", lookup(keys)));
  }

  private List<ConfigValue> lookup(final List<String> keys) {
    final Set<String> distinct = new LinkedHashSet<>(keys);
    if (distinct.size() > MAX_KEYS) {
      throw new IllegalArgumentException("At most " + MAX_KEYS + " keys per request");
    }
    return distinct.stream().map(this::valueOf).toList();
  }

  private ConfigValue valueOf(final String key) {
    if (key.length() > MAX_KEY_LENGTH || PREFIXES.stream().noneMatch(key::startsWith)) {
      throw new IllegalArgumentException(
          "Key " + key + " is not a setting: it must start with " + String.join(" ", PREFIXES));
    }
    final String value = environment.getProperty(key);
    if (value == null) {
      return new ConfigValue(key, null, DEFAULT);
    }
    return new ConfigValue(key, mask(key, value), sourceOf(key));
  }

  /** The value to show: hidden for a secret key, with a URL's password hidden otherwise. */
  private static String mask(final String key, final String value) {
    return SECRET_KEY.matcher(key.toLowerCase(Locale.ROOT)).find()
        ? MASKED
        : URL_PASSWORD.matcher(value).replaceAll("$1" + MASKED + "@");
  }

  private String sourceOf(final String key) {
    for (final PropertySource<?> source : environment.getPropertySources()) {
      if (!"configurationProperties".equals(source.getName()) && source.containsProperty(key)) {
        return label(source.getName());
      }
    }
    return "other";
  }

  private static String label(final String name) {
    return switch (name) {
      case "commandLineArgs" -> "argument";
      case "systemProperties" -> "system property";
      case "systemEnvironment" -> "environment variable";
      default ->
          name.startsWith("Config resource") || name.startsWith("applicationConfig")
              ? "configuration file"
              : "other";
    };
  }
}
