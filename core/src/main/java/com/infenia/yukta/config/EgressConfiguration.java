// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.config;

import com.infenia.yukta.plugin.util.EgressPolicy;
import com.infenia.yukta.plugin.util.HostAllowList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.NoArgsConstructor;
import org.springframework.boot.context.properties.bind.BindResult;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.ResolvableType;
import org.springframework.core.env.Environment;

/**
 * Reads the outbound host lists: {@code yukta.egress.allowed-hosts} for the deployment, and {@code
 * yukta.egress.by-workflow.<id>} and {@code yukta.egress.by-session.<id>} to narrow one workflow or
 * session further.
 */
@Configuration(proxyBeanMethods = false)
@NoArgsConstructor
public class EgressConfiguration {

  /** A list of host entries. */
  private static final Bindable<List<String>> HOSTS = Bindable.listOf(String.class);

  /** Host lists by workflow or session id. */
  private static final Bindable<Map<String, List<String>>> BY_ID =
      Bindable.of(
          ResolvableType.forClassWithGenerics(
              Map.class, ResolvableType.forClass(String.class), HOSTS.getType()));

  /**
   * Creates the policy the HTTP, MCP and notify plugins check before they connect.
   *
   * @param environment holds the {@code yukta.egress.*} properties
   * @return the policy
   */
  @Bean
  public EgressPolicy egressPolicy(final Environment environment) {
    final Binder binder = Binder.get(environment);
    return new EgressPolicy(
        HostAllowList.from(binder.bind("yukta.egress.allowed-hosts", HOSTS).orElse(List.of())),
        lists(binder, "yukta.egress.by-workflow"),
        lists(binder, "yukta.egress.by-session"));
  }

  private static Map<String, HostAllowList> lists(final Binder binder, final String prefix) {
    final BindResult<Map<String, List<String>>> bound = binder.bind(prefix, BY_ID);
    return (bound.isBound() ? bound.get() : Map.<String, List<String>>of())
        .entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, e -> HostAllowList.from(e.getValue())));
  }
}
