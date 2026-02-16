package io.jagratha.jagratha.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "jagratha")
public record JagrathaConfig(ExternalProject externalProject) {
  public record ExternalProject(String path, String gradlePath) {}
}
