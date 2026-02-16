package io.jagratha.jagratha.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for Jagratha.
 *
 * @param externalProject configuration for the external project
 */
@ConfigurationProperties(prefix = "jagratha")
public record JagrathaConfig(ExternalProject externalProject) {

  /**
   * Configuration for the external project.
   *
   * @param path path to the external project
   * @param gradlePath path to the Gradle executable
   */
  public record ExternalProject(String path, String gradlePath) {}
}
