// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.message;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.Builder;
import lombok.With;
import lombok.extern.slf4j.Slf4j;

/**
 * Default implementation of the {@link Message} interface using a Java record.
 *
 * @param id logical identifier: kept across {@code from}/{@code with*} copies, so not unique per
 *     node output (key stores by execution, node and id)
 * @param traceId identifier for tracing the workflow execution
 * @param correlationId unique ID used to match replies to requests
 * @param replyTo pointer to the channel where a reply should be sent
 * @param expiration time-to-live for the message
 * @param formatIndicator indicates the data format or schema version
 * @param metadata metadata associated with the message
 * @param messageHistory list of component IDs this message has traversed
 * @param sequenceId unique ID for a group of related messages
 * @param sequenceNumber index of this message in a sequence
 * @param sequenceSize total number of messages in the sequence
 * @param priority message priority
 * @param controlMessage indicates if this is a control message
 * @param origDest destination before failure
 * @param failureReason reason for failure
 * @param exceptionDetail detailed exception info
 * @param retryCount number of retries
 * @param payload the actual data being passed
 * @param timestamp time when the message was created
 * @param sourcePort the port from which the message was emitted
 * @param sourceNodeId the ID of the node that emitted the message
 * @param workflowId the ID of the workflow context
 * @param <T> the type of the payload
 */
@Slf4j
@Builder(toBuilder = true)
public record DefaultMessage<T>(
    @With String id,
    @With String traceId,
    @With String correlationId,
    @With String replyTo,
    @With long expiration,
    @With String formatIndicator,
    @With Map<String, Object> metadata,
    @With List<String> messageHistory,
    @With String sequenceId,
    @With int sequenceNumber,
    @With int sequenceSize,
    @With int priority,
    @With boolean controlMessage,
    @With String origDest,
    @With String failureReason,
    @With String exceptionDetail,
    @With int retryCount,
    T payload,
    @With Instant timestamp,
    @With String sourcePort,
    @With String sourceNodeId,
    @With String workflowId)
    implements Message<T> {

  /**
   * Maximum number of entries kept in {@code messageHistory}. Past it the first node stays, the
   * oldest after it are dropped, and a {@code "…(+N)"} marker after the first says how many.
   */
  public static final int MAX_HISTORY = 64;

  /** Prefix of the history entry that counts the dropped node IDs. */
  private static final String DROPPED_MARKER = "…(+";

  /**
   * Compact constructor to ensure metadata and history are immutable. Metadata entries with a null
   * value are dropped (logged at debug); a null key is rejected.
   */
  public DefaultMessage {
    metadata = copyWithoutNulls(metadata);
    messageHistory = capHistory(messageHistory);
    timestamp = Objects.requireNonNullElseGet(timestamp, Instant::now);
  }

  @Override
  public Message<T> withAddedHistory(final String nodeId) {
    Objects.requireNonNull(nodeId, "nodeId must not be null when stamping message history");
    final List<String> history = new ArrayList<>(messageHistory.size() + 1);
    history.addAll(messageHistory);
    history.add(nodeId);
    return withMessageHistory(history); // the constructor applies the cap
  }

  @Override
  public Message<T> withSequence(final String sid, final int pos, final int total) {
    return toBuilder().sequenceId(sid).sequenceNumber(pos).sequenceSize(total).build();
  }

  @Override
  public Message<T> withControl(final boolean control) {
    return withControlMessage(control);
  }

  @Override
  public Message<T> withFailure(final String origDest, final String reason, final String detail) {
    return toBuilder().origDest(origDest).failureReason(reason).exceptionDetail(detail).build();
  }

  @Override
  public <R> Message<R> withPayload(final R newPayload) {
    return from(this, newPayload);
  }

  @Override
  public Message<T> withHeader(final String key, final Object value) {
    Objects.requireNonNull(key, "header key must not be null");
    return withHeaders(Collections.singletonMap(key, value));
  }

  @Override
  @SuppressWarnings("PMD.UseConcurrentHashMap") // local, never shared
  public Message<T> withHeaders(final Map<String, ?> headers) {
    final Map<String, Object> newMetadata = new HashMap<>(metadata);
    headers.forEach(
        (key, value) -> {
          Objects.requireNonNull(key, "header key must not be null");
          if (value == null) {
            newMetadata.remove(key);
          } else {
            newMetadata.put(key, value);
          }
        });
    return withMetadata(newMetadata);
  }

  /** The history cut to {@link #MAX_HISTORY}; an immutable list within the cap is not copied. */
  private static List<String> capHistory(final List<String> history) {
    List<String> result = List.of();
    if (history != null && history.size() <= MAX_HISTORY) {
      result = List.copyOf(history);
    } else if (history != null) {
      final boolean marked = history.get(1).startsWith(DROPPED_MARKER);
      final List<String> rest = history.subList(marked ? 2 : 1, history.size());
      final int keep = MAX_HISTORY - 2;
      final int dropped = (marked ? droppedIn(history.get(1)) : 0) + rest.size() - keep;
      final List<String> capped = new ArrayList<>(MAX_HISTORY);
      capped.add(history.get(0));
      capped.add(DROPPED_MARKER + dropped + ")");
      capped.addAll(rest.subList(rest.size() - keep, rest.size()));
      result = List.copyOf(capped);
    }
    return result;
  }

  private static int droppedIn(final String marker) {
    return Integer.parseInt(marker.substring(DROPPED_MARKER.length(), marker.length() - 1));
  }

  private static Map<String, Object> copyWithoutNulls(final Map<String, Object> source) {
    Map<String, Object> result = Map.of();
    if (source != null && !source.isEmpty()) {
      result =
          source.values().stream().noneMatch(Objects::isNull)
              ? Map.copyOf(source)
              : dropNullValues(source);
    }
    return result;
  }

  @SuppressWarnings({"PMD.UseConcurrentHashMap", "PMD.AvoidThrowingNullPointerException"})
  private static Map<String, Object> dropNullValues(final Map<String, Object> source) {
    final Map<String, Object> copy = new HashMap<>(source);
    if (copy.containsKey(null)) {
      throw new NullPointerException("metadata key must not be null");
    }
    copy.values().removeIf(Objects::isNull);
    return Map.copyOf(copy);
  }

  /**
   * Create a new message with metadata from another message but a new payload.
   *
   * @param original the original message to copy headers from
   * @param newPayload the new payload
   * @param <T> new payload type
   * @return a new message
   */
  public static <T> DefaultMessage<T> from(final Message<?> original, final T newPayload) {
    return DefaultMessage.<T>builder()
        .id(original.id())
        .traceId(original.traceId())
        .correlationId(original.correlationId())
        .replyTo(original.replyTo())
        .expiration(original.expiration())
        .formatIndicator(original.formatIndicator())
        .metadata(original.metadata())
        .messageHistory(original.messageHistory())
        .sequenceId(original.sequenceId())
        .sequenceNumber(original.sequenceNumber())
        .sequenceSize(original.sequenceSize())
        .priority(original.priority())
        .controlMessage(original.controlMessage())
        .origDest(original.origDest())
        .failureReason(original.failureReason())
        .exceptionDetail(original.exceptionDetail())
        .retryCount(original.retryCount())
        .payload(newPayload)
        .timestamp(original.timestamp())
        .sourcePort(original.sourcePort())
        .sourceNodeId(original.sourceNodeId())
        .workflowId(original.workflowId())
        .build();
  }

  /**
   * Create a new message with default values.
   *
   * @param traceId the trace identifier
   * @param payload the payload
   * @param <T> payload type
   * @return a new message
   */
  public static <T> DefaultMessage<T> create(final UUID traceId, final T payload) {
    return new DefaultMessage<>(
        UUID.randomUUID().toString(),
        traceId != null ? traceId.toString() : null,
        null,
        null,
        0L,
        null,
        Map.of(),
        List.of(),
        null,
        0,
        0,
        0,
        false,
        null,
        null,
        null,
        0,
        payload,
        Instant.now(),
        null,
        null,
        null);
  }
}
