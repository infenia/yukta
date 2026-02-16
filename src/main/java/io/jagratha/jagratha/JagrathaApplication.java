package io.jagratha.jagratha;

import io.jagratha.jagratha.config.JagrathaConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Main application class for Jagratha. Jagratha is a Spring Boot application that manages external
 * projects and runs quality checks.
 */
@SpringBootApplication
@EnableConfigurationProperties(JagrathaConfig.class)
@SuppressWarnings("PMD.UseUtilityClass")
public class JagrathaApplication {

  /**
   * Main method to start the application.
   *
   * @param args command line arguments
   */
  public static void main(final String[] args) {
    SpringApplication.run(JagrathaApplication.class, args);
  }
}
