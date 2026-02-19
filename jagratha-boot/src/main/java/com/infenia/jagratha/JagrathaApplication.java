package com.infenia.jagratha;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Main application class for Jagratha. Jagratha is a Spring Boot application that manages external
 * projects and runs quality checks.
 */
@SpringBootApplication
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
