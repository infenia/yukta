// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.persistence.postgres;

import com.infenia.yukta.plugin.notify.Notification;
import com.infenia.yukta.plugin.notify.NotificationKind;
import com.infenia.yukta.plugin.notify.NotificationSeverity;
import com.infenia.yukta.service.notify.NotificationDelivery;
import com.infenia.yukta.service.notify.NotificationPreferences;
import com.infenia.yukta.service.notify.NotificationQuery;
import com.infenia.yukta.service.notify.NotificationStore;
import com.infenia.yukta.service.notify.Receipt;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.r2dbc.spi.Readable;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Notifications, receipts, preferences and the outbox in PostgreSQL (tables {@code notifications},
 * {@code notification_receipts}, {@code notification_preferences} and {@code
 * notification_deliveries}, migration V3), so the inbox, what each person read or dismissed and the
 * queued deliveries survive a restart and a failover.
 *
 * <p>The change sequence is the database sequence {@code notification_seq}. A change of one
 * notification is one {@code UPDATE ... WHERE seq = :expected}, so two changes at once produce one
 * winner, on one node or on two; the loser reads again and merges again. The epoch is written once
 * in {@code notification_epoch}, so event ids stay valid across a restart.
 */
@SuppressWarnings({
  "PMD.TooManyMethods",
  "PMD.AvoidDuplicateLiterals",
  "PMD.CouplingBetweenObjects",
  "PMD.CompareObjectsWithEquals"
})
public class R2dbcNotificationStore implements NotificationStore {

  /** The most due deliveries one call returns. */
  private static final int DUE_BATCH = 500;

  /** How often a change that loses to another is read and merged again before it gives up. */
  private static final int MAX_TRIES = 8;

  /** The columns of a notification, read by name. */
  private static final String COLUMNS =
      "id, notif_key, kind, severity, category, title, body, session_id, workflow_id, execution_id,"
          + " node_id, input_request_id, CAST(audience AS text) AS audience, state, resolution,"
          + " count, seq, created_at, updated_at";

  private static final String DELIVERY_COLUMNS =
      "id, notification_id, channel, tier, state, attempts, next_attempt, last_error";

  private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {};
  private static final TypeReference<Map<String, NotificationPreferences.Level>> LEVELS =
      new TypeReference<>() {};

  private final DatabaseClient client;
  private final ObjectMapper mapper;

  /** The epoch of this database, read once on first use. */
  private final Mono<String> epochOnce;

  /**
   * Creates the store.
   *
   * @param client runs the statements
   * @param mapper reads and writes the JSON columns
   */
  @SuppressFBWarnings("EI_EXPOSE_REP2") // both are shared Spring beans
  public R2dbcNotificationStore(final DatabaseClient client, final ObjectMapper mapper) {
    this.client = client;
    this.mapper = mapper;
    this.epochOnce =
        Mono.defer(() ->
                client
                    .sql("SELECT epoch FROM notification_epoch WHERE id = 1")
                    .map(row -> Objects.requireNonNull(row.get("epoch", String.class)))
                    .one())
            .cache();
  }

  @Override
  public Mono<Notification> upsert(
      final String key, final Function<Optional<Notification>, Notification> merge) {
    return attempt(key, merge, MAX_TRIES);
  }

  private Mono<Notification> attempt(
      final String key,
      final Function<Optional<Notification>, Notification> merge,
      final int triesLeft) {
    return findByKey(key)
        .map(Optional::of)
        .defaultIfEmpty(Optional.empty())
        .flatMap(current -> {
          final Notification merged = merge.apply(current);
          if (current.isPresent() && merged == current.get()) {
            return Mono.just(merged);
          }
          final Mono<Notification> written =
              current.isPresent() ? update(current.get(), merged) : insert(merged);
          return written.switchIfEmpty(
              triesLeft <= 1
                  ? Mono.error(new IllegalStateException("Notification " + key + " kept changing"))
                  : Mono.defer(() -> attempt(key, merge, triesLeft - 1)));
        });
  }

  /** Inserts; empty when another writer created the key first. */
  private Mono<Notification> insert(final Notification n) {
    return Mono.fromCallable(() -> mapper.writeValueAsString(n.audience()))
        .flatMap(audience ->
            bind(
                    client.sql(
                        """
                        INSERT INTO notifications (id, notif_key, kind, severity, category, title,
                          body, session_id, workflow_id, execution_id, node_id, input_request_id,
                          audience, state, resolution, count, seq, created_at, updated_at)
                        VALUES (:id, :key, :kind, :severity, :category, :title, :body, :session,
                          :workflow, :execution, :node, :request, CAST(:audience AS jsonb), :state,
                          :resolution, :count, nextval('notification_seq'), :created, :updated)
                        ON CONFLICT DO NOTHING
                        """
                            + " RETURNING "
                            + COLUMNS),
                    UUID.randomUUID().toString(),
                    n,
                    audience)
                .map(this::notification)
                .one());
  }

  /** Updates if nobody changed it since it was read; empty when somebody did. */
  private Mono<Notification> update(final Notification current, final Notification n) {
    return Mono.fromCallable(() -> mapper.writeValueAsString(n.audience()))
        .flatMap(audience ->
            client
                .sql(
                    """
                    UPDATE notifications SET kind = :kind, severity = :severity,
                      category = :category, title = :title, body = :body, session_id = :session,
                      workflow_id = :workflow, execution_id = :execution, node_id = :node,
                      input_request_id = :request, audience = CAST(:audience AS jsonb),
                      state = :state, resolution = :resolution, count = :count,
                      seq = nextval('notification_seq'), updated_at = :updated
                    WHERE id = :id AND seq = :expected
                    """
                        + " RETURNING "
                        + COLUMNS)
                .bind("id", current.id())
                .bind("expected", current.seq())
                .bind("kind", n.kind().name())
                .bind("severity", n.severity().name())
                .bind("category", n.category())
                .bind("title", n.title())
                .bind("body", nullable(n.body(), String.class))
                .bind("session", nullable(n.sessionId(), String.class))
                .bind("workflow", nullable(n.workflowId(), String.class))
                .bind("execution", nullable(n.executionId(), String.class))
                .bind("node", nullable(n.nodeId(), String.class))
                .bind("request", nullable(n.inputRequestId(), String.class))
                .bind("audience", audience)
                .bind("state", n.state().name())
                .bind("resolution", nullable(n.resolution(), String.class))
                .bind("count", n.count())
                .bind("updated", n.updatedAt())
                .map(this::notification)
                .one());
  }

  private DatabaseClient.GenericExecuteSpec bind(
      final DatabaseClient.GenericExecuteSpec spec,
      final String id,
      final Notification n,
      final String audience) {
    return spec.bind("id", id)
        .bind("key", n.key())
        .bind("kind", n.kind().name())
        .bind("severity", n.severity().name())
        .bind("category", n.category())
        .bind("title", n.title())
        .bind("body", nullable(n.body(), String.class))
        .bind("session", nullable(n.sessionId(), String.class))
        .bind("workflow", nullable(n.workflowId(), String.class))
        .bind("execution", nullable(n.executionId(), String.class))
        .bind("node", nullable(n.nodeId(), String.class))
        .bind("request", nullable(n.inputRequestId(), String.class))
        .bind("audience", audience)
        .bind("state", n.state().name())
        .bind("resolution", nullable(n.resolution(), String.class))
        .bind("count", n.count())
        .bind("created", n.createdAt())
        .bind("updated", n.updatedAt());
  }

  @Override
  public Mono<Notification> resolve(final String key, final String resolution, final Instant at) {
    return client
        .sql(
            """
            UPDATE notifications SET state = 'RESOLVED', resolution = :resolution,
              seq = nextval('notification_seq'), updated_at = :at
            WHERE notif_key = :key AND state = 'ACTIVE'
            """
                + " RETURNING "
                + COLUMNS)
        .bind("key", key)
        .bind("resolution", resolution)
        .bind("at", at)
        .map(this::notification)
        .one();
  }

  @Override
  public Mono<Notification> findByKey(final String key) {
    return client
        .sql("SELECT " + COLUMNS + " FROM notifications WHERE notif_key = :key")
        .bind("key", key)
        .map(this::notification)
        .one();
  }

  @Override
  public Mono<Notification> findById(final String id) {
    return client
        .sql("SELECT " + COLUMNS + " FROM notifications WHERE id = :id")
        .bind("id", id)
        .map(this::notification)
        .one();
  }

  @Override
  public Flux<Notification> find(final NotificationQuery query) {
    final Notification.State state = query.state();
    final NotificationKind kind = query.kind();
    final String session = query.sessionId();
    // only the filters that are set become predicates, so a generic plan keeps its indexes
    DatabaseClient.GenericExecuteSpec spec =
        client.sql(
            "SELECT "
                + COLUMNS
                + " FROM notifications WHERE seq > :since"
                + (state == null ? "" : " AND state = :state")
                + (kind == null ? "" : " AND kind = :kind")
                + (session == null ? "" : " AND session_id = :session")
                + " ORDER BY seq DESC");
    spec = spec.bind("since", query.sinceSeq());
    if (state != null) {
      spec = spec.bind("state", state.name());
    }
    if (kind != null) {
      spec = spec.bind("kind", kind.name());
    }
    if (session != null) {
      spec = spec.bind("session", session);
    }
    return spec.map(this::notification).all();
  }

  @Override
  public Mono<Void> saveReceipt(final Receipt receipt) {
    return client
        .sql(
            """
            INSERT INTO notification_receipts (notification_id, recipient, read_at, dismissed_at)
            SELECT :id, :recipient, :read, :dismissed
            WHERE EXISTS (SELECT 1 FROM notifications WHERE id = :id)
            ON CONFLICT (recipient, notification_id)
            DO UPDATE SET read_at = EXCLUDED.read_at, dismissed_at = EXCLUDED.dismissed_at
            """)
        .bind("id", receipt.notificationId())
        .bind("recipient", receipt.recipient())
        .bind("read", nullable(receipt.readAt(), Instant.class))
        .bind("dismissed", nullable(receipt.dismissedAt(), Instant.class))
        .fetch()
        .rowsUpdated()
        .then();
  }

  @Override
  public Flux<Receipt> receipts(final String recipient, final Collection<String> ids) {
    if (ids.isEmpty()) {
      return Flux.empty();
    }
    return client
        .sql(
            "SELECT notification_id, recipient, read_at, dismissed_at FROM notification_receipts"
                + " WHERE recipient = :recipient AND notification_id = ANY(:ids)")
        .bind("recipient", recipient)
        .bind("ids", ids.toArray(String[]::new))
        .map(row ->
            new Receipt(
                row.get("notification_id", String.class),
                row.get("recipient", String.class),
                row.get("read_at", Instant.class),
                row.get("dismissed_at", Instant.class)))
        .all();
  }

  @Override
  public Mono<NotificationPreferences> preferences(final String recipient) {
    return client
        .sql(
            "SELECT CAST(levels AS text) AS levels, CAST(muted_sessions AS text) AS muted"
                + " FROM notification_preferences WHERE recipient = :recipient")
        .bind("recipient", recipient)
        .map(row ->
            new NotificationPreferences(
                recipient,
                mapper.readValue(row.get("levels", String.class), LEVELS),
                Set.copyOf(mapper.readValue(row.get("muted", String.class), STRINGS))))
        .one()
        .defaultIfEmpty(NotificationPreferences.defaults(recipient));
  }

  @Override
  public Mono<Void> savePreferences(final NotificationPreferences preferences) {
    return Mono.fromCallable(() ->
            List.of(
                mapper.writeValueAsString(preferences.levels()),
                mapper.writeValueAsString(preferences.mutedSessions())))
        .flatMap(json ->
            client
                .sql(
                    """
                    INSERT INTO notification_preferences (recipient, levels, muted_sessions)
                    VALUES (:recipient, CAST(:levels AS jsonb), CAST(:muted AS jsonb))
                    ON CONFLICT (recipient) DO UPDATE SET levels = EXCLUDED.levels,
                      muted_sessions = EXCLUDED.muted_sessions
                    """)
                .bind("recipient", preferences.recipient())
                .bind("levels", json.get(0))
                .bind("muted", json.get(1))
                .fetch()
                .rowsUpdated()
                .then());
  }

  @Override
  public Mono<NotificationDelivery> enqueueDelivery(final NotificationDelivery delivery) {
    // one round trip: the row inserted, or the one already there under that id
    return client
        .sql(
            """
            WITH ins AS (
              INSERT INTO notification_deliveries (id, notification_id, channel, tier, state,
                attempts, next_attempt, last_error)
              VALUES (:id, :notification, :channel, :tier, :state, :attempts, :next, :error)
              ON CONFLICT (id) DO NOTHING
              RETURNING *)
            """
                + "SELECT "
                + DELIVERY_COLUMNS
                + " FROM ins UNION ALL SELECT "
                + DELIVERY_COLUMNS
                + " FROM notification_deliveries WHERE id = :id AND NOT EXISTS (SELECT 1 FROM ins)")
        .bind("id", delivery.id())
        .bind("notification", delivery.notificationId())
        .bind("channel", delivery.channel())
        .bind("tier", nullable(delivery.tier(), String.class))
        .bind("state", delivery.state().name())
        .bind("attempts", delivery.attempts())
        .bind("next", delivery.nextAttempt())
        .bind("error", nullable(delivery.lastError(), String.class))
        .map(R2dbcNotificationStore::delivery)
        .one();
  }

  /**
   * At most {@value #DUE_BATCH} deliveries, the longest due first, so one tick of the delivery
   * worker has bounded work; each attempt moves the delivery on, so the next tick takes the rest.
   */
  @Override
  public Flux<NotificationDelivery> dueDeliveries(final Instant now) {
    return client
        .sql(
            "SELECT "
                + DELIVERY_COLUMNS
                + " FROM notification_deliveries WHERE state = 'PENDING' AND next_attempt <= :now"
                + " ORDER BY next_attempt, id LIMIT "
                + DUE_BATCH)
        .bind("now", now)
        .map(R2dbcNotificationStore::delivery)
        .all();
  }

  @Override
  public Mono<Void> saveDelivery(final NotificationDelivery delivery) {
    // a delivery of a notification that is gone is not kept
    return client
        .sql(
            """
            INSERT INTO notification_deliveries (id, notification_id, channel, tier, state,
              attempts, next_attempt, last_error)
            SELECT :id, :notification, :channel, :tier, :state, :attempts, :next, :error
            WHERE EXISTS (SELECT 1 FROM notifications WHERE id = :notification)
            ON CONFLICT (id) DO UPDATE SET state = EXCLUDED.state, attempts = EXCLUDED.attempts,
              next_attempt = EXCLUDED.next_attempt, last_error = EXCLUDED.last_error
            """)
        .bind("id", delivery.id())
        .bind("notification", delivery.notificationId())
        .bind("channel", delivery.channel())
        .bind("tier", nullable(delivery.tier(), String.class))
        .bind("state", delivery.state().name())
        .bind("attempts", delivery.attempts())
        .bind("next", delivery.nextAttempt())
        .bind("error", nullable(delivery.lastError(), String.class))
        .fetch()
        .rowsUpdated()
        .then();
  }

  /** In batches ({@link Batches}); receipts and deliveries go with each notification. */
  @Override
  public Mono<Long> purge(final Instant before) {
    return Batches.repeat(size ->
        client
            .sql(
                """
                WITH gone AS (
                  DELETE FROM notifications WHERE id IN (
                    SELECT id FROM notifications
                    WHERE updated_at < :before AND (kind = 'INFO' OR state = 'RESOLVED')
                    LIMIT :size)
                  RETURNING id)
                SELECT count(*) AS removed FROM gone
                """)
            .bind("before", before)
            .bind("size", size)
            .map(row -> Objects.requireNonNull(row.get("removed", Long.class)))
            .one());
  }

  @Override
  public Mono<String> epoch() {
    return epochOnce;
  }

  private Notification notification(final Readable row) {
    return new Notification(
        Objects.requireNonNull(row.get("id", String.class)),
        Objects.requireNonNull(row.get("notif_key", String.class)),
        NotificationKind.valueOf(row.get("kind", String.class)),
        NotificationSeverity.valueOf(row.get("severity", String.class)),
        Objects.requireNonNull(row.get("category", String.class)),
        Objects.requireNonNull(row.get("title", String.class)),
        row.get("body", String.class),
        row.get("session_id", String.class),
        row.get("workflow_id", String.class),
        row.get("execution_id", String.class),
        row.get("node_id", String.class),
        row.get("input_request_id", String.class),
        mapper.readValue(row.get("audience", String.class), STRINGS),
        Notification.State.valueOf(row.get("state", String.class)),
        row.get("resolution", String.class),
        Objects.requireNonNull(row.get("count", Integer.class)),
        Objects.requireNonNull(row.get("seq", Long.class)),
        Objects.requireNonNull(row.get("created_at", Instant.class)),
        Objects.requireNonNull(row.get("updated_at", Instant.class)));
  }

  private static NotificationDelivery delivery(final Readable row) {
    return new NotificationDelivery(
        Objects.requireNonNull(row.get("id", String.class)),
        Objects.requireNonNull(row.get("notification_id", String.class)),
        Objects.requireNonNull(row.get("channel", String.class)),
        row.get("tier", String.class),
        NotificationDelivery.State.valueOf(row.get("state", String.class)),
        Objects.requireNonNull(row.get("attempts", Integer.class)),
        Objects.requireNonNull(row.get("next_attempt", Instant.class)),
        row.get("last_error", String.class));
  }

  /** A bindable value: a null needs its type for the driver. */
  private static Object nullable(final @Nullable Object value, final Class<?> type) {
    return value == null ? io.r2dbc.spi.Parameters.in(type) : value;
  }
}
