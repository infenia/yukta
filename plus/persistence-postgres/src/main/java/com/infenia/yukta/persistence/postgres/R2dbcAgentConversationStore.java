// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.persistence.postgres;

import com.infenia.yukta.plugin.agent.AgentConversation;
import com.infenia.yukta.plugin.agent.AgentEvent;
import com.infenia.yukta.plugin.agent.AgentEventKind;
import com.infenia.yukta.plugin.agent.AgentProtocol;
import com.infenia.yukta.plugin.agent.AgentState;
import com.infenia.yukta.plugin.input.Audience;
import com.infenia.yukta.service.agent.AgentConversationStore;
import com.infenia.yukta.service.agent.ConversationFilter;
import com.infenia.yukta.service.agent.EventFilter;
import io.r2dbc.spi.Parameters;
import io.r2dbc.spi.Readable;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * The conversations with AI agents in PostgreSQL, so they survive a restart. The change sequence is
 * a database sequence. Terminal output is not stored: a ring of it is kept in memory, per
 * conversation, and is gone after a restart; the latest settled screen is stored with the
 * conversation. At start {@link #endOpenConversations()} ends what a restart left open.
 */
@SuppressWarnings({
  "PMD.TooManyMethods",
  "PMD.AvoidDuplicateLiterals",
  "PMD.CouplingBetweenObjects",
  "PMD.UseConcurrentHashMap",
  "PMD.ExcessiveImports"
})
public final class R2dbcAgentConversationStore implements AgentConversationStore {

  private static final String COLUMNS =
      "id, execution_id, root_execution_id, session_id, workflow_id, node_id, message_id, harness,"
          + " protocol, model, CAST(capabilities AS text) AS capabilities, state, outcome, reason,"
          + " turn, queued, CAST(steer_audience AS text) AS steer_audience, started_at,"
          + " updated_at, ended_at, seq";

  private static final String EVENT_COLUMNS =
      "seq, conversation_id, occurred_at, kind, item_id, CAST(data AS text) AS data";

  private static final String ID = "id";
  private static final String SEQ = "seq";
  private static final String TERMINAL_KIND = AgentEventKind.TERMINAL.name();

  private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {};
  private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {};

  private final DatabaseClient client;
  private final ObjectMapper mapper;
  private final Clock clock;
  private final int maxConversations;
  private final int maxEvents;
  private final long ringBytes;
  private final Mono<String> epochOnce;

  /** Terminal output, per conversation: only in memory. */
  private final Map<String, OutputRing> rings = new ConcurrentHashMap<>();

  /**
   * Creates the store.
   *
   * @param client runs the statements
   * @param mapper reads and writes the JSON columns
   * @param maxConversations the most conversations kept before ended ones are dropped
   * @param maxEventsPerConversation the most events of one conversation kept
   * @param terminalRingBytes the terminal output kept for one conversation
   */
  public R2dbcAgentConversationStore(
      final DatabaseClient client,
      final ObjectMapper mapper,
      final int maxConversations,
      final int maxEventsPerConversation,
      final long terminalRingBytes) {
    this(
        client,
        mapper,
        maxConversations,
        maxEventsPerConversation,
        terminalRingBytes,
        Clock.systemUTC());
  }

  /* default */ R2dbcAgentConversationStore(
      final DatabaseClient client,
      final ObjectMapper mapper,
      final int maxConversations,
      final int maxEventsPerConversation,
      final long terminalRingBytes,
      final Clock clock) {
    if (maxConversations < 1 || maxEventsPerConversation < 1 || terminalRingBytes < 1) {
      throw new IllegalArgumentException("every bound of the store must be at least 1");
    }
    this.client = client;
    this.mapper = mapper;
    this.clock = clock;
    this.maxConversations = maxConversations;
    this.maxEvents = maxEventsPerConversation;
    this.ringBytes = terminalRingBytes;
    this.epochOnce =
        Mono.defer(() ->
                client
                    .sql("SELECT epoch FROM agent_epoch WHERE id = 1")
                    .map(row -> Objects.requireNonNull(row.get("epoch", String.class)))
                    .one())
            .cache();
  }

  /**
   * Ends every conversation a restart left open, with the outcome {@code failure} and the reason
   * "server restarted": the agent processes were children of the server that went down. Call once
   * at start.
   *
   * @return how many conversations were ended
   */
  public Mono<Long> endOpenConversations() {
    return client
        .sql(
            """
            UPDATE agent_conversation SET state = 'ENDED', outcome = 'failure',
              reason = 'server restarted', ended_at = :now, updated_at = :now,
              seq = nextval('agent_seq')
            WHERE state <> 'ENDED'
            RETURNING id
            """)
        .bind("now", clock.instant())
        .map(row -> Objects.requireNonNull(row.get(ID, String.class)))
        .all()
        .concatMap(id ->
            insertEvent(
                new AgentEvent(
                    0,
                    id,
                    clock.instant(),
                    AgentEventKind.STATE,
                    null,
                    Map.of("state", AgentState.ENDED.name(), "reason", "server restarted"))))
        .count();
  }

  /**
   * The same, blocking, for the start of the application.
   *
   * @return how many conversations were ended
   */
  public long endOpenConversationsNow() {
    return endOpenConversations().defaultIfEmpty(0L).toFuture().join();
  }

  @Override
  public String epoch() {
    // a future, not block(): this may be asked from a thread that must not block in Reactor
    return Objects.requireNonNull(epochOnce.toFuture().join());
  }

  @Override
  public Mono<AgentConversation> save(final AgentConversation c) {
    return Mono.fromCallable(
            () -> Map.entry(json(c.capabilities()), json(c.steerAudience().selectors())))
        .flatMap(json ->
            client
                .sql(
                    """
                    INSERT INTO agent_conversation (id, execution_id, root_execution_id,
                      session_id, workflow_id, node_id, message_id, harness, protocol, model,
                      capabilities, state, outcome, reason, turn, queued, steer_audience,
                      started_at, updated_at, ended_at, seq)
                    VALUES (:id, :execution, :root, :session, :workflow, :node, :message,
                      :harness, :protocol, :model, CAST(:capabilities AS jsonb), :state,
                      :outcome, :reason, :turn, :queued, CAST(:audience AS jsonb), :started,
                      :updated, :ended, nextval('agent_seq'))
                    ON CONFLICT (id) DO UPDATE SET execution_id = :execution,
                      root_execution_id = :root, session_id = :session, workflow_id = :workflow,
                      node_id = :node, message_id = :message, harness = :harness,
                      protocol = :protocol, model = :model,
                      capabilities = CAST(:capabilities AS jsonb), state = :state,
                      outcome = :outcome, reason = :reason, turn = :turn, queued = :queued,
                      steer_audience = CAST(:audience AS jsonb), started_at = :started,
                      updated_at = :updated, ended_at = :ended, seq = nextval('agent_seq')
                    """
                        + " RETURNING "
                        + COLUMNS)
                .bind(ID, c.id())
                .bind("execution", c.executionId())
                .bind("root", c.rootExecutionId())
                .bind("session", c.sessionId())
                .bind("workflow", c.workflowId())
                .bind("node", c.nodeId())
                .bind("message", c.messageId())
                .bind("harness", c.harness())
                .bind("protocol", c.protocol().name())
                .bind("model", nullable(c.model(), String.class))
                .bind("capabilities", json.getKey())
                .bind("state", c.state().name())
                .bind("outcome", nullable(c.outcome(), String.class))
                .bind("reason", nullable(c.reason(), String.class))
                .bind("turn", c.turn())
                .bind("queued", c.queued())
                .bind("audience", json.getValue())
                .bind("started", c.startedAt())
                .bind("updated", c.updatedAt())
                .bind("ended", nullable(c.endedAt(), Instant.class))
                .map(this::conversation)
                .one())
        .flatMap(saved -> evictEnded(saved.id()).thenReturn(saved));
  }

  /**
   * Drops the ended conversations that are over the bound, the one that ended longest ago first.
   */
  private Mono<Void> evictEnded(final String keep) {
    return client
        .sql(
            """
            DELETE FROM agent_conversation WHERE id IN (
              SELECT id FROM agent_conversation
              WHERE state = 'ENDED' AND id <> :keep
              ORDER BY ended_at ASC NULLS LAST
              LIMIT GREATEST(0, (SELECT count(*) FROM agent_conversation) - :max))
            RETURNING id
            """)
        .bind("keep", keep)
        .bind("max", maxConversations)
        .map(row -> Objects.requireNonNull(row.get(ID, String.class)))
        .all()
        .doOnNext(rings::remove)
        .then();
  }

  @Override
  public Mono<AppendResult> append(final AgentEvent event) {
    return exists(event.conversationId())
        .flatMap(found -> {
          if (!found) {
            return Mono.error(
                new IllegalArgumentException(
                    "Unknown conversation '" + event.conversationId() + "'"));
          }
          if (event.kind() == AgentEventKind.TERMINAL) {
            return terminal(event);
          }
          return insertEvent(event)
              .flatMap(stored ->
                  trim(stored.conversationId())
                      .map(notice -> new AppendResult(stored, notice))
                      .defaultIfEmpty(new AppendResult(stored, null)));
        });
  }

  private Mono<Boolean> exists(final String id) {
    return client
        .sql("SELECT 1 AS one FROM agent_conversation WHERE id = :id")
        .bind(ID, id)
        .map(row -> true)
        .one()
        .defaultIfEmpty(false);
  }

  private Mono<AppendResult> terminal(final AgentEvent event) {
    return nextSeq()
        .flatMap(seq -> {
          final AgentEvent stored = withSeq(event, seq);
          if (!stored.data().containsKey("screen")) {
            rings
                .computeIfAbsent(stored.conversationId(), id -> new OutputRing())
                .add(stored, ringBytes);
            return Mono.just(new AppendResult(stored, null));
          }
          return Mono.fromCallable(() -> eventJson(stored))
              .flatMap(text ->
                  client
                      .sql(
                          "UPDATE agent_conversation SET screen = CAST(:screen AS jsonb)"
                              + " WHERE id = :id")
                      .bind("screen", text)
                      .bind(ID, stored.conversationId())
                      .fetch()
                      .rowsUpdated())
              .thenReturn(new AppendResult(stored, null));
        });
  }

  private Mono<Long> nextSeq() {
    return client
        .sql("SELECT nextval('agent_seq') AS seq")
        .map(row -> Objects.requireNonNull(row.get(SEQ, Long.class)))
        .one();
  }

  private Mono<AgentEvent> insertEvent(final AgentEvent event) {
    return Mono.fromCallable(() -> json(event.data()))
        .flatMap(data ->
            client
                .sql(
                    """
                    INSERT INTO agent_event (seq, conversation_id, occurred_at, kind, item_id, data)
                    VALUES (nextval('agent_seq'), :conversation, :at, :kind, :item,
                      CAST(:data AS jsonb))
                    """
                        + " RETURNING "
                        + EVENT_COLUMNS)
                .bind("conversation", event.conversationId())
                .bind("at", event.timestamp())
                .bind("kind", event.kind().name())
                .bind("item", nullable(event.itemId(), String.class))
                .bind("data", data)
                .map(this::event)
                .one());
  }

  /** Drops the oldest events over the bound; the first drop of a conversation writes a notice. */
  private Mono<AgentEvent> trim(final String conversationId) {
    return client
        .sql(
            """
            DELETE FROM agent_event WHERE seq IN (
              SELECT seq FROM agent_event WHERE conversation_id = :id AND kind <> 'NOTICE'
              ORDER BY seq ASC
              LIMIT GREATEST(0, (SELECT count(*) FROM agent_event WHERE conversation_id = :id
                AND kind <> 'NOTICE') - :max))
            """)
        .bind(ID, conversationId)
        .bind("max", maxEvents)
        .fetch()
        .rowsUpdated()
        .filter(dropped -> dropped > 0)
        .flatMap(dropped ->
            client
                .sql(
                    "UPDATE agent_conversation SET dropped = TRUE"
                        + " WHERE id = :id AND NOT dropped RETURNING id")
                .bind(ID, conversationId)
                .map(row -> true)
                .one())
        .flatMap(first ->
            insertEvent(
                new AgentEvent(
                    0,
                    conversationId,
                    clock.instant(),
                    AgentEventKind.NOTICE,
                    null,
                    Map.of("level", "WARN", "text", DROPPED_NOTICE))));
  }

  @Override
  public Mono<AgentConversation> find(final String id) {
    return client
        .sql("SELECT " + COLUMNS + " FROM agent_conversation WHERE id = :id")
        .bind(ID, id)
        .map(this::conversation)
        .one();
  }

  @Override
  public Flux<AgentConversation> list(final ConversationFilter filter) {
    final StringBuilder where = new StringBuilder(64).append("TRUE");
    final Map<String, Object> binds = new LinkedHashMap<>();
    equal(where, binds, "session_id", "session", filter.sessionId());
    equal(where, binds, "execution_id", "execution", filter.executionId());
    equal(where, binds, "root_execution_id", "root", filter.rootExecutionId());
    final Set<AgentState> states = Objects.requireNonNullElse(filter.states(), Set.of());
    if (!states.isEmpty()) {
      // enum names, never user text
      where
          .append(" AND state IN (")
          .append(states.stream().map(s -> "'" + s.name() + "'").collect(Collectors.joining(",")))
          .append(')');
    }
    DatabaseClient.GenericExecuteSpec spec =
        client.sql(
            "SELECT "
                + COLUMNS
                + " FROM agent_conversation WHERE "
                + where
                + " ORDER BY started_at DESC, seq DESC LIMIT :limit");
    for (final Map.Entry<String, Object> bind : binds.entrySet()) {
      spec = spec.bind(bind.getKey(), bind.getValue());
    }
    return spec.bind("limit", filter.limit()).map(this::conversation).all();
  }

  @Override
  public Flux<AgentEvent> events(final EventFilter filter, final long afterSeq, final int limit) {
    final StringBuilder where = new StringBuilder(64).append("TRUE");
    final Map<String, Object> binds = new LinkedHashMap<>();
    equal(where, binds, "c.id", "conversation", filter.conversationId());
    equal(where, binds, "c.session_id", "session", filter.sessionId());
    equal(where, binds, "c.execution_id", "execution", filter.executionId());
    equal(where, binds, "c.root_execution_id", "root", filter.rootExecutionId());
    final long after = Math.max(0, afterSeq);
    final int size = Math.max(0, limit);
    final Set<AgentEventKind> kinds = Objects.requireNonNullElse(filter.kinds(), Set.of());
    final StringBuilder kindClause = new StringBuilder(128);
    if (!kinds.isEmpty()) {
      kindClause
          .append(" AND e.kind IN (")
          .append(kinds.stream().map(k -> "'" + k.name() + "'").collect(Collectors.joining(",")))
          .append(')');
    }
    DatabaseClient.GenericExecuteSpec rows =
        client.sql(
            "SELECT e.seq, e.conversation_id, e.occurred_at, e.kind, e.item_id,"
                + " CAST(e.data AS text) AS data FROM agent_event e JOIN agent_conversation c"
                + " ON c.id = e.conversation_id WHERE "
                + where
                + " AND e.seq > :after"
                + kindClause
                + " ORDER BY e.seq ASC LIMIT :size");
    DatabaseClient.GenericExecuteSpec screens =
        client.sql(
            "SELECT c.id, CAST(c.screen AS text) AS screen FROM agent_conversation c WHERE "
                + where);
    for (final Map.Entry<String, Object> bind : binds.entrySet()) {
      rows = rows.bind(bind.getKey(), bind.getValue());
      screens = screens.bind(bind.getKey(), bind.getValue());
    }
    if (size == 0) {
      return Flux.empty();
    }
    final DatabaseClient.GenericExecuteSpec rowsSpec = rows.bind("after", after).bind("size", size);
    return screens
        .map(row ->
            Map.entry(
                text(row, ID), Objects.requireNonNullElse(row.get("screen", String.class), "")))
        .all()
        .collectList()
        .flatMapMany(matching -> {
          final List<AgentEvent> found = new ArrayList<>();
          for (final Map.Entry<String, String> entry : matching) {
            if (!entry.getValue().isEmpty()) {
              found.add(screen(entry.getValue()));
            }
            final OutputRing ring = rings.get(entry.getKey());
            if (ring != null) {
              found.addAll(ring.snapshot());
            }
          }
          return rowsSpec
              .map(this::event)
              .all()
              .collectList()
              .flatMapMany(stored -> {
                found.addAll(stored);
                return Flux.fromStream(
                    found.stream()
                        .filter(piece -> piece.seq() > after && filter.matches(piece))
                        .sorted(Comparator.comparingLong(AgentEvent::seq))
                        .limit(size));
              });
        });
  }

  @Override
  public Mono<AgentEvent> latestScreen(final String conversationId) {
    return client
        .sql("SELECT CAST(screen AS text) AS screen FROM agent_conversation WHERE id = :id")
        .bind(ID, conversationId)
        .map(row -> Objects.requireNonNullElse(row.get("screen", String.class), ""))
        .one()
        .filter(text -> !text.isEmpty())
        .map(this::screen);
  }

  @Override
  public Mono<Long> purge(final Instant before) {
    return client
        .sql("DELETE FROM agent_conversation WHERE ended_at < :before RETURNING id")
        .bind("before", before)
        .map(row -> Objects.requireNonNull(row.get(ID, String.class)))
        .all()
        .doOnNext(rings::remove)
        .count();
  }

  // ---- mapping

  private static void equal(
      final StringBuilder where,
      final Map<String, Object> binds,
      final String column,
      final String name,
      final @Nullable String value) {
    if (value != null) {
      where.append(" AND ").append(column).append(" = :").append(name);
      binds.put(name, value);
    }
  }

  private AgentConversation conversation(final Readable row) {
    return new AgentConversation(
        text(row, ID),
        text(row, "execution_id"),
        text(row, "root_execution_id"),
        text(row, "session_id"),
        text(row, "workflow_id"),
        text(row, "node_id"),
        text(row, "message_id"),
        text(row, "harness"),
        AgentProtocol.valueOf(text(row, "protocol")),
        row.get("model", String.class),
        Set.copyOf(readList(text(row, "capabilities"))),
        AgentState.valueOf(text(row, "state")),
        row.get("outcome", String.class),
        row.get("reason", String.class),
        Objects.requireNonNull(row.get("turn", Integer.class)),
        Objects.requireNonNull(row.get("queued", Integer.class)),
        new Audience(readList(text(row, "steer_audience"))),
        Objects.requireNonNull(row.get("started_at", Instant.class)),
        Objects.requireNonNull(row.get("updated_at", Instant.class)),
        row.get("ended_at", Instant.class),
        Objects.requireNonNull(row.get(SEQ, Long.class)));
  }

  private AgentEvent event(final Readable row) {
    return new AgentEvent(
        Objects.requireNonNull(row.get(SEQ, Long.class)),
        text(row, "conversation_id"),
        Objects.requireNonNull(row.get("occurred_at", Instant.class)),
        AgentEventKind.valueOf(text(row, "kind")),
        row.get("item_id", String.class),
        readObject(text(row, "data")));
  }

  /** A screen is stored as the whole event that carried it. */
  private AgentEvent screen(final String stored) {
    final Map<String, Object> wrapper = readObject(stored);
    @SuppressWarnings("unchecked")
    final Map<String, Object> data = (Map<String, Object>) wrapper.get("data");
    return new AgentEvent(
        ((Number) wrapper.get(SEQ)).longValue(),
        String.valueOf(wrapper.get("conversationId")),
        Instant.parse(String.valueOf(wrapper.get("timestamp"))),
        AgentEventKind.valueOf(TERMINAL_KIND),
        (String) wrapper.get("itemId"),
        data);
  }

  private String eventJson(final AgentEvent event) {
    final Map<String, Object> wrapper = new LinkedHashMap<>();
    wrapper.put(SEQ, event.seq());
    wrapper.put("conversationId", event.conversationId());
    wrapper.put("timestamp", event.timestamp().toString());
    wrapper.put("itemId", event.itemId());
    wrapper.put("data", event.data());
    return mapper.writeValueAsString(wrapper);
  }

  private String json(final Object value) {
    return mapper.writeValueAsString(value);
  }

  private List<String> readList(final String text) {
    return mapper.readValue(text, STRINGS);
  }

  private Map<String, Object> readObject(final String text) {
    return mapper.readValue(text, OBJECT);
  }

  private static String text(final Readable row, final String column) {
    return Objects.requireNonNull(row.get(column, String.class), column);
  }

  private static Object nullable(final @Nullable Object value, final Class<?> type) {
    return value == null ? Parameters.in(type) : value;
  }

  private static AgentEvent withSeq(final AgentEvent event, final long seq) {
    return new AgentEvent(
        seq, event.conversationId(), event.timestamp(), event.kind(), event.itemId(), event.data());
  }

  /** The terminal output of one conversation, newest last, bounded in bytes. */
  private static final class OutputRing {

    private final ReentrantLock lock = new ReentrantLock();
    private final Deque<AgentEvent> pieces = new ArrayDeque<>();
    private long bytes;

    private void add(final AgentEvent piece, final long limit) {
      lock.lock();
      try {
        pieces.addLast(piece);
        bytes += decodedLength(piece.data().get("data"));
        while (bytes > limit && pieces.size() > 1) {
          bytes -= decodedLength(pieces.removeFirst().data().get("data"));
        }
      } finally {
        lock.unlock();
      }
    }

    private List<AgentEvent> snapshot() {
      lock.lock();
      try {
        return new ArrayList<>(pieces);
      } finally {
        lock.unlock();
      }
    }

    private static long decodedLength(final Object data) {
      if (!(data instanceof final String text) || text.isEmpty()) {
        return 0;
      }
      int padding = 0;
      for (int i = text.length() - 1; i >= 0 && text.charAt(i) == '='; i--) {
        padding++;
      }
      return Math.max(0, text.length() / 4L * 3 - padding);
    }
  }
}
