// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.mcp.dto.AgentConversationList;
import com.infenia.yukta.mcp.dto.AgentConversationView;
import com.infenia.yukta.mcp.dto.AgentScreen;
import com.infenia.yukta.mcp.dto.ControlActionResult;
import com.infenia.yukta.mcp.provider.ConversationProvider;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** Each tool hands its arguments to the provider and returns what it answers. */
@NoArgsConstructor
@SuppressWarnings({"PMD.TooManyMethods", "PMD.AvoidDuplicateLiterals"})
class ConversationToolsTest {

  private final ConversationProvider provider = mock(ConversationProvider.class);
  private final ConversationTools tools = new ConversationTools(provider);

  @Test
  void listAndGetAreReadFromTheProvider() {
    final AgentConversationList list = new AgentConversationList(null, false);
    final AgentConversationView view = new AgentConversationView(null, null, null);
    when(provider.list("s", "e", "RUNNING")).thenReturn(Mono.just(list));
    when(provider.get("s", "c", 3L, 10)).thenReturn(Mono.just(view));

    StepVerifier.create(tools.listAgentConversations("s", "e", "RUNNING"))
        .expectNext(list)
        .verifyComplete();
    StepVerifier.create(tools.getAgentConversation("s", "c", 3L, 10))
        .expectNext(view)
        .verifyComplete();
  }

  @Test
  void steeringToolsPassTheirArgumentsOn() {
    final ControlActionResult done = mock(ControlActionResult.class);
    when(provider.message("s", "c", "hi", true)).thenReturn(Mono.just(done));
    when(provider.message("s", "c", "hi", false)).thenReturn(Mono.just(done));
    when(provider.interrupt("s", "c")).thenReturn(Mono.just(done));
    when(provider.end("s", "c", "success", "ok")).thenReturn(Mono.just(done));
    when(provider.keys("s", "c", "ls", "enter")).thenReturn(Mono.just(done));

    StepVerifier.create(tools.sendAgentMessage("s", "c", "hi", true))
        .expectNext(done)
        .verifyComplete();
    StepVerifier.create(tools.sendAgentMessage("s", "c", "hi", null))
        .expectNext(done)
        .verifyComplete();
    StepVerifier.create(tools.interruptAgent("s", "c")).expectNext(done).verifyComplete();
    StepVerifier.create(tools.endAgentConversation("s", "c", "success", "ok"))
        .expectNext(done)
        .verifyComplete();
    StepVerifier.create(tools.sendAgentKeys("s", "c", "ls", "enter"))
        .expectNext(done)
        .verifyComplete();
    verify(provider).message("s", "c", "hi", false);
  }

  @Test
  void screenIsReadFromTheProvider() {
    final AgentScreen screen = new AgentScreen("c", 80, 24, "text");
    when(provider.screen("s", "c")).thenReturn(Mono.just(screen));

    StepVerifier.create(tools.readAgentScreen("s", "c", null)).expectNext(screen).verifyComplete();
    StepVerifier.create(tools.readAgentScreen("s", "c", 0)).expectNext(screen).verifyComplete();
  }

  @Test
  void screenTailKeepsTheLastRows() {
    when(provider.screen("s", "c"))
        .thenReturn(Mono.just(new AgentScreen("c", 80, 24, "one\ntwo\nthree")));

    StepVerifier.create(tools.readAgentScreen("s", "c", 2))
        .expectNext(new AgentScreen("c", 80, 24, "two\nthree"))
        .verifyComplete();
    StepVerifier.create(tools.readAgentScreen("s", "c", 9))
        .expectNext(new AgentScreen("c", 80, 24, "one\ntwo\nthree"))
        .verifyComplete();
  }
}
