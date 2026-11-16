// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import com.infenia.yukta.dto.response.ConversationEventResponse;
import com.infenia.yukta.dto.response.ConversationResponse;
import com.infenia.yukta.dto.response.TerminalScreenResponse;
import com.infenia.yukta.mapper.ConversationMapper;
import com.infenia.yukta.model.api.ApiResponse;
import com.infenia.yukta.model.execution.WorkflowProgress;
import com.infenia.yukta.security.ApiKeys;
import com.infenia.yukta.security.CallerIdentity;
import com.infenia.yukta.service.NotFoundException;
import com.infenia.yukta.service.agent.AgentChange;
import com.infenia.yukta.service.agent.AgentConversationQueries;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Reads the conversations with AI agents: lists, one conversation, its events, the terminal screen
 * and one live stream for a run and the runs below it. Everything here needs only VIEWER; a
 * conversation of a session the caller does not reach is 404, as one that does not exist.
 *
 * <p>The text of a conversation is written by an AI agent: show it as untrusted plain text.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@ConditionalOnProperty(
    prefix = "yukta.agents",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
@SuppressWarnings({"PMD.ExcessiveImports"})
@Tag(name = "Agent Conversations API", description = "Read the conversations with AI agents")
public class ConversationsController {

  /** The default of the options that are on unless a client turns them off. */
  private static final String ON_BY_DEFAULT = "true";

  /** Most streams of the conversation changes one key may hold open. */
  private static final int MAX_STREAMS_PER_KEY = 50;

  /** Conversation ID parameter description. */
  private static final String CONVERSATION_ID = "Conversation ID";

  /** The states parameter description. */
  private static final String STATES =
      "Comma-separated states: STARTING, RUNNING, WAITING, IDLE, ENDED; none means all";

  /** The kinds parameter description. */
  private static final String KINDS =
      "Comma-separated event kinds: STATE, MESSAGE, TOOL, PLAN, REQUEST, TERMINAL, RESULT, NOTICE;"
          + " none means all";

  /** Reads the conversations. */
  private final AgentConversationQueries queries;

  /** Maps them to responses. */
  private final ConversationMapper mapper;

  /** Resolves a run for a request. */
  private final ExecutionAccess access;

  /** Lists the conversations the caller reaches. */
  @GetMapping("/conversations")
  @Operation(
      summary = "List agent conversations",
      description =
          "The conversations of all sessions the caller reaches, newest first. Narrow them with"
              + " state, sessionId and executionId; with executionId, tree=true (the default) also"
              + " lists those of the runs below it. limit defaults to 100, at most 500. An unknown"
              + " state is a 400.")
  public Mono<ApiResponse<List<ConversationResponse>>> listConversations(
      @Parameter(description = STATES) @RequestParam(required = false) final String state,
      @Parameter(description = "Only this session") @RequestParam(required = false)
          final String sessionId,
      @Parameter(description = "Only this run") @RequestParam(required = false)
          final String executionId,
      @Parameter(description = "With executionId: also the runs below it (default true)")
          @RequestParam(defaultValue = ON_BY_DEFAULT)
          final boolean tree,
      @Parameter(description = "Most conversations returned (default 100, at most 500)")
          @RequestParam(required = false)
          final Integer limit) {
    return reach()
        .flatMapMany(reaches ->
            queries.list(
                new AgentConversationQueries.Scope(
                    sessionId, executionId, tree, AgentConversationQueries.states(state), reaches),
                limit == null ? 0 : limit))
        .map(mapper::toResponse)
        .collectList()
        .map(items -> ApiResponse.success(200, "Conversations retrieved", items));
  }

  /** Lists the conversations of a run and, by default, of the runs below it. */
  @GetMapping("/executions/{executionId}/conversations")
  @Operation(
      summary = "List the agent conversations of a run",
      description =
          "The conversations of the run and, with tree=true (the default), of its child runs,"
              + " newest first. 404 for an unknown run or one of a session the caller does not"
              + " reach.")
  public Mono<ApiResponse<List<ConversationResponse>>> listRunConversations(
      @Parameter(description = "Execution ID") @PathVariable final String executionId,
      @Parameter(description = "Also the runs below it (default true)")
          @RequestParam(defaultValue = ON_BY_DEFAULT)
          final boolean tree,
      @Parameter(description = STATES) @RequestParam(required = false) final String state,
      @Parameter(description = "Most conversations returned (default 100, at most 500)")
          @RequestParam(required = false)
          final Integer limit) {
    return reach()
        .flatMap(reaches -> run(executionId, reaches).thenReturn(reaches))
        .flatMapMany(reaches ->
            queries.list(
                new AgentConversationQueries.Scope(
                    null, executionId, tree, AgentConversationQueries.states(state), reaches),
                limit == null ? 0 : limit))
        .map(mapper::toResponse)
        .collectList()
        .map(items -> ApiResponse.success(200, "Conversations retrieved", items));
  }

  /** Reads one conversation. */
  @GetMapping("/conversations/{id}")
  @Operation(
      summary = "Read an agent conversation",
      description = "The conversation record. 404 when it is unknown or of an unreachable session.")
  public Mono<ApiResponse<ConversationResponse>> getConversation(
      @Parameter(description = CONVERSATION_ID) @PathVariable final String id) {
    return reach()
        .flatMap(reaches -> queries.require(id, reaches))
        .map(mapper::toResponse)
        .map(conversation -> ApiResponse.success(200, "Conversation retrieved", conversation));
  }

  /** Reads a page of the events of a conversation. */
  @GetMapping("/conversations/{id}/events")
  @Operation(
      summary = "Read the events of an agent conversation",
      description =
          "A page of events in the order of their sequence numbers, after the event whose number"
              + " is given in after (default: from the start). limit defaults to 100, at most"
              + " 500. When more events follow, the X-Next-Cursor header holds the number to pass"
              + " as after. Terminal output is kept in a ring of bytes, so an old chunk may be"
              + " gone.")
  public Mono<ResponseEntity<ApiResponse<List<ConversationEventResponse>>>> getEvents(
      @Parameter(description = CONVERSATION_ID) @PathVariable final String id,
      @Parameter(description = "Sequence number of the last event the caller has")
          @RequestParam(defaultValue = "0")
          final long after,
      @Parameter(description = "Most events returned (default 100, at most 500)")
          @RequestParam(required = false)
          final Integer limit,
      @Parameter(description = KINDS) @RequestParam(required = false) final String kinds) {
    final Set<com.infenia.yukta.plugin.agent.AgentEventKind> wanted =
        AgentConversationQueries.kinds(kinds);
    return reach()
        .flatMap(reaches -> queries.events(id, after, limit == null ? 0 : limit, wanted, reaches))
        .map(page ->
            Pagination.respond(
                page.events().stream().map(mapper::toResponse).toList(),
                page.next() == null ? null : String.valueOf(page.next()),
                "Conversation events retrieved"));
  }

  /** Reads the latest screen of a terminal agent. */
  @GetMapping("/conversations/{id}/terminal/screen")
  @Operation(
      summary = "Read the screen of a terminal agent",
      description =
          "The latest settled screen as text with the size and the cursor. 404 when the"
              + " conversation is unknown or has not drawn a screen yet, 409 when it has no"
              + " terminal.")
  public Mono<ApiResponse<TerminalScreenResponse>> getScreen(
      @Parameter(description = CONVERSATION_ID) @PathVariable final String id) {
    return reach()
        .flatMap(reaches -> queries.screen(id, reaches))
        .map(mapper::toScreen)
        .map(screen -> ApiResponse.success(200, "Screen retrieved", screen));
  }

  /** Streams the conversations of a run tree. */
  @GetMapping(
      value = "/executions/{executionId}/conversations/stream",
      produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  @Operation(
      summary = "Stream the agent conversations of a run",
      description =
          "Server-sent events for the run and, with tree=true (the default), the runs below it."
              + " The event named conversation carries a conversation record (on connect one for"
              + " each conversation, then on every change); the event named event carries a"
              + " conversation event. The SSE id is the change sequence number, and Last-Event-ID"
              + " resumes after it: a client gets exactly what it missed, no gap and no"
              + " duplicate. For a terminal agent a new client gets the latest screen before the"
              + " live output; terminal=false leaves out the output and sends screens only. The"
              + " stream ends when the run does, and ends early for a client that falls too far"
              + " behind, which resumes with Last-Event-ID.")
  @SuppressWarnings("PMD.UseObjectForClearerAPI")
  public Flux<ServerSentEvent<Object>> streamConversations(
      @Parameter(description = "Execution ID") @PathVariable final String executionId,
      @Parameter(description = "Also the runs below it (default true)")
          @RequestParam(defaultValue = ON_BY_DEFAULT)
          final boolean tree,
      @Parameter(description = "Only this conversation") @RequestParam(required = false)
          final String conversationId,
      @Parameter(description = KINDS) @RequestParam(required = false) final String kinds,
      @Parameter(description = "false: no terminal output, screens only")
          @RequestParam(defaultValue = ON_BY_DEFAULT)
          final boolean terminal,
      @RequestHeader(value = "Last-Event-ID", required = false) final String lastEventId) {
    final Set<com.infenia.yukta.plugin.agent.AgentEventKind> wanted =
        AgentConversationQueries.kinds(kinds);
    return reach()
        .flatMap(reaches -> run(executionId, reaches).thenReturn(reaches))
        .flatMapMany(reaches ->
            SseStreams.named(
                queries
                    .follow(
                        new AgentConversationQueries.Follow(
                            executionId,
                            tree,
                            conversationId,
                            wanted,
                            terminal,
                            resumeAfter(lastEventId),
                            reaches))
                    .map(this::toEvent),
                SseStreams.HEARTBEAT,
                MAX_STREAMS_PER_KEY));
  }

  private ServerSentEvent<Object> toEvent(final AgentChange change) {
    final ServerSentEvent.Builder<Object> event =
        ServerSentEvent.<Object>builder().id(String.valueOf(change.seq()));
    return switch (change) {
      case AgentChange.ConversationChanged changed ->
          event.event("conversation").data(mapper.toResponse(changed.conversation())).build();
      case AgentChange.EventAppended appended ->
          event.event("event").data(mapper.toResponse(appended.event())).build();
    };
  }

  /** The number a reconnecting client holds; 0 when the header is missing or not a number. */
  private static long resumeAfter(final String lastEventId) {
    if (lastEventId == null) {
      return 0;
    }
    try {
      return Math.max(0, Long.parseLong(lastEventId.trim()));
    } catch (final NumberFormatException e) {
      return 0;
    }
  }

  /** The run of the request; 404 when it is unknown or of a session the caller does not reach. */
  private Mono<WorkflowProgress> run(final String executionId, final Predicate<String> reaches) {
    return access
        .require(null, executionId)
        .filter(progress -> reaches.test(progress.sessionId()))
        .switchIfEmpty(
            Mono.error(() -> new NotFoundException("Execution not found: " + executionId)));
  }

  /** Which sessions the caller reaches: all, unless the key is limited to some. */
  private static Mono<Predicate<String>> reach() {
    return Mono.deferContextual(context -> {
      final ApiKeys.Match key = context.getOrDefault(CallerIdentity.CONTEXT_KEY, null);
      final Predicate<String> reaches = key == null ? session -> true : key::reaches;
      return Mono.just(reaches);
    });
  }
}
