// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.workers;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

/** A worker in a JVM of its own, started by the chaos test; the arguments are properties. */
@SuppressWarnings("PMD.PublicMemberInNonPublicType")
final class WorkerMain {

  private WorkerMain() {
    // a main class
  }

  /**
   * Starts the worker and keeps the JVM alive until it is killed.
   *
   * @param args {@code key=value} properties
   * @throws InterruptedException if the JVM is interrupted
   */
  public static void main(final String... args) throws InterruptedException {
    new SpringApplicationBuilder(RemoteNodeWorkerConfig.class)
        .web(WebApplicationType.NONE)
        .properties(args)
        .run();
    Thread.currentThread().join();
  }
}
