// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import reactor.core.publisher.Hooks;

/**
 * Main application class for Yukta. Yukta is a Spring Boot application that manages external
 * projects and runs quality checks.
 */
@SpringBootApplication
@SuppressWarnings("PMD.UseUtilityClass")
public class YuktaApplication {

  /**
   * Main method to start the application.
   *
   * @param args command line arguments
   */
  public static void main(final String[] args) {
    Hooks.enableAutomaticContextPropagation();

    final SpringApplication app = new SpringApplication(YuktaApplication.class);
    app.run(args);
  }
}
