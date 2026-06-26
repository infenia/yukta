/*
 * Copyright 2026 Infenia Private Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.infenia.yukta.cli;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import reactor.core.publisher.Hooks;

/**
 * Main application class for Yukta CLI. This is a standalone Spring Boot application that provides
 * command-line interface capabilities for interacting with Yukta.
 */
@SpringBootApplication(scanBasePackages = {"com.infenia.yukta.cli"})
@SuppressWarnings("PMD.UseUtilityClass")
public class YuktaCliApplication {

  /**
   * Main method to start the CLI application.
   *
   * @param args command line arguments
   */
  public static void main(final String[] args) {
    initializeAndRun(args);
  }

  @SuppressWarnings("PMD.UseVarargs")
  static void initializeAndRun(final String[] args) {
    Hooks.enableAutomaticContextPropagation();

    if (isNativeImage() && !hasProfileArgument(args)) {
      System.setProperty("spring.profiles.active", "prod");
    }

    final SpringApplication app = new SpringApplication(YuktaCliApplication.class);
    app.setWebApplicationType(org.springframework.boot.WebApplicationType.NONE);
    app.run(args);
  }

  @SuppressWarnings("PMD.LocalVariableCouldBeFinal")
  private static boolean isNativeImage() {
    String nativeImageProp = System.getProperty("org.graalvm.nativeimage.imagecode");
    return "runtime".equals(nativeImageProp);
  }

  @SuppressWarnings({"PMD.UseVarargs", "PMD.LocalVariableCouldBeFinal", "PMD.OnlyOneReturn"})
  private static boolean hasProfileArgument(final String[] args) {
    for (String arg : args) {
      if (arg.startsWith("--spring.profiles.active=")
          || arg.startsWith("-Dspring.profiles.active=")) {
        return true;
      }
    }
    return false;
  }
}
