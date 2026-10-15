// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.workers;

import com.infenia.yukta.service.plugin.PluginRegistry;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** A worker that runs the fake plugins. */
@Configuration
@EnableAutoConfiguration
@SuppressWarnings("PMD.AtLeastOneConstructor")
class RemoteNodeWorkerConfig {

  /** Counts attempts that were cancelled on the worker. */
  /* default */ static final AtomicInteger CANCELLED = new AtomicInteger();

  @Bean
  /* default */ PluginRegistry plugins() {
    return new PluginRegistry(
        List.of(
            new FakePlugin("UPPER", CANCELLED),
            new FakePlugin("BOOM", CANCELLED),
            new FakePlugin("BAD", CANCELLED),
            new FakePlugin("HEART", CANCELLED),
            new FakePlugin("CTX", CANCELLED),
            new FakePlugin("HOLD", CANCELLED),
            new FakePlugin("SLOW", CANCELLED),
            new FakePlugin("NOT_ALLOWED", CANCELLED)));
  }
}
