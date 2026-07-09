// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.message.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.message.store.MessageStore;
import java.util.UUID;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** Tests for {@link PersistingNodeMessageChannel}. */
@MockitoSettings(strictness = Strictness.LENIENT)
@NoArgsConstructor
class PersistingNodeMessageChannelTest {

  /** Test message payload. */
  private static final String DATA = "data";

  /** Test node ID. */
  private static final String NODE_ID = "node-1";

  /** Test execution ID. */
  private static final String EXEC_ID = "exec-1";

  /** Mock message store for testing. */
  @Mock private MessageStore messageStore;

  /** Channel instance under test. */
  private PersistingNodeMessageChannel channel;

  @BeforeEach
  void setUp() {
    channel = new PersistingNodeMessageChannel(messageStore);
    when(messageStore.store(any())).thenReturn(Mono.empty());
  }

  @Test
  void inboundPassesThroughUnchanged() {
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), DATA);
    final Flux<Message<?>> upstream = Flux.just(msg);

    StepVerifier.create(channel.inbound(NODE_ID, EXEC_ID, upstream))
        .assertNext(m -> assertThat(m.getPayload()).isEqualTo(DATA))
        .verifyComplete();
  }

  @Test
  void outboundPersistsMessageBeforeEmitting() {
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), DATA);
    final Flux<Message<?>> pluginOutput = Flux.just(msg);

    StepVerifier.create(channel.outbound(NODE_ID, EXEC_ID, pluginOutput))
        .assertNext(m -> assertThat(m.getPayload()).isEqualTo(DATA))
        .verifyComplete();

    verify(messageStore).store(msg);
  }

  @Test
  void outboundHandlesMultipleMessages() {
    final Message<?> msg1 = DefaultMessage.create(UUID.randomUUID(), "data1");
    final Message<?> msg2 = DefaultMessage.create(UUID.randomUUID(), "data2");
    final Flux<Message<?>> pluginOutput = Flux.just(msg1, msg2);

    StepVerifier.create(channel.outbound(NODE_ID, EXEC_ID, pluginOutput))
        .expectNextCount(2)
        .verifyComplete();

    verify(messageStore).store(msg1);
    verify(messageStore).store(msg2);
  }

  @Test
  void outboundPropagatesStoreError() {
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), DATA);
    final RuntimeException storeError = new RuntimeException("Store failed");
    when(messageStore.store(any())).thenReturn(Mono.error(storeError));
    final Flux<Message<?>> pluginOutput = Flux.just(msg);

    StepVerifier.create(channel.outbound(NODE_ID, EXEC_ID, pluginOutput))
        .expectError(RuntimeException.class)
        .verify();
  }
}
