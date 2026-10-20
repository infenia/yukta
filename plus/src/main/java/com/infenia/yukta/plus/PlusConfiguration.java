// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plus;

import com.infenia.yukta.plugin.store.NodeCheckpointStore;
import com.infenia.yukta.plugin.store.SessionHistoryStore;
import com.infenia.yukta.service.store.FileNodeCheckpointStore;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/**
 * The Plus edition (AGPL-3.0-only): single-node durable storage on disk. The session, workflow and
 * execution-log stores are components of their own; this adds the checkpoint and chat-history files
 * and tells users where the source is, as section 13 of the AGPL asks.
 */
@NoArgsConstructor
@Configuration(proxyBeanMethods = false)
public class PlusConfiguration {

  /**
   * Keeps what each node produced on disk when {@code yukta.restart.dir} is set.
   *
   * @param dir root directory
   * @param mapper reads and writes messages
   * @return the store
   */
  @Bean
  @ConditionalOnExpression(
      "!'${yukta.restart.dir:}'.isEmpty() && !'postgres'.equals('${yukta.restart.store-type:}')")
  public NodeCheckpointStore fileNodeCheckpointStore(
      @Value("${yukta.restart.dir}") final String dir, final ObjectMapper mapper) {
    return new FileNodeCheckpointStore(dir, mapper);
  }

  /**
   * Keeps chat history on disk when {@code yukta.session-memory.dir} is set.
   *
   * @param dir directory for the session files
   * @return the store
   */
  @Bean
  @ConditionalOnExpression(
      "!'${yukta.session-memory.dir:}'.isEmpty()"
          + " && !'postgres'.equals('${yukta.session-memory.store-type:}')")
  public SessionHistoryStore fileSessionHistoryStore(
      @Value("${yukta.session-memory.dir}") final String dir) {
    return new FileSessionHistoryStore(dir);
  }

  /**
   * Shows the edition, its licence and where its source is.
   *
   * @param source where the corresponding source is offered
   * @return the contributor for {@code /actuator/info}
   */
  @Bean
  @ConditionalOnProperty(name = "yukta.plus.info", havingValue = "true", matchIfMissing = true)
  public InfoContributor plusInfo(
      @Value("${yukta.plus.source-url:https://github.com/infenia/yukta}") final String source) {
    return builder ->
        builder.withDetail(
            "edition", Map.of("name", "plus", "license", "AGPL-3.0-only", "source", source));
  }
}
