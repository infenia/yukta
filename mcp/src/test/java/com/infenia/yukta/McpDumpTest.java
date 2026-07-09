// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.web.reactive.function.server.RouterFunction;

/** Dump test. */
@SpringBootTest
public class McpDumpTest {
  @Autowired ApplicationContext ctx;

  /** Dumps router beans. */
  @Test
  public void dump() {
    System.out.println("----- DUMP START -----");
    try {
      Map<String, RouterFunction> routerBeans = ctx.getBeansOfType(RouterFunction.class);
      System.out.println("Router beans: " + routerBeans.keySet());
      for (RouterFunction r : routerBeans.values()) {
        System.out.println("Router: " + r.toString());
      }
    } catch (Exception e) {
      System.err.println("Dump failed: " + e.getMessage());
    }
    System.out.println("----- DUMP END -----");
  }
}
