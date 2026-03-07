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
package com.infenia.yukta;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.scheduling.annotation.EnableAsync;
import reactor.core.publisher.Hooks;

/**
 * Main application class for Yukta. Yukta is a Spring Boot application that manages external
 * projects and runs quality checks.
 */
@SpringBootApplication
@EnableAutoConfiguration
@EnableAsync
@Slf4j
@SuppressWarnings("PMD.UseUtilityClass")
public class YuktaApplication {

  /**
   * Main method to start the application.
   *
   * @param args command line arguments
   */
  public static void main(final String[] args) {
    log.info("Starting User Application");
    Hooks.enableAutomaticContextPropagation();
    new SpringApplicationBuilder().sources(YuktaApplication.class).run(args);
  }
}
