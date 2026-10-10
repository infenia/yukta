// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.message;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.Builder;
import lombok.With;

/**
 * Default implementation of the {@link Message} interface using a Java record.
 *
 * @param id unique identifier for the message
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
@Builder(toBuilder = true)
public record DefaultMessage<T>(
    @SuppressWarnings("PMD.ShortVariable") @With String id,
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

  /** Maximum number of node IDs kept in {@code messageHistory}; the oldest are dropped first. */
  public static final int MAX_HISTORY = 64;

  /**
   * Compact constructor to ensure metadata and history are immutable. Metadata entries with a null
   * value are dropped; a null key is rejected.
   */
  public DefaultMessage {
    metadata = copyWithoutNulls(metadata);
    messageHistory = messageHistory != null ? List.copyOf(messageHistory) : List.of();
    timestamp = Objects.requireNonNullElseGet(timestamp, Instant::now);
  }

  @Override
  public String getMessageId() {
    return id;
  }

  @Override
  public String getCorrelationId() {
    return correlationId;
  }

  @Override
  public String getReplyTo() {
    return replyTo;
  }

  @Override
  public String getTraceId() {
    return traceId;
  }

  @Override
  public Instant getTimestamp() {
    return timestamp;
  }

  @Override
  public long getExpiration() {
    return expiration;
  }

  @Override
  public String getFormatIndicator() {
    return formatIndicator;
  }

  @Override
  public String getSourcePort() {
    return sourcePort;
  }

  @Override
  public String getSourceNodeId() {
    return sourceNodeId;
  }

  @Override
  public String getWorkflowId() {
    return workflowId;
  }

  @Override
  public Map<String, Object> getMetadata() {
    return metadata;
  }

  @Override
  public List<String> getMessageHistory() {
    return messageHistory;
  }

  @Override
  public String getSequenceId() {
    return sequenceId;
  }

  @Override
  public int getSequenceNumber() {
    return sequenceNumber;
  }

  @Override
  public int getSequenceSize() {
    return sequenceSize;
  }

  @Override
  public int getPriority() {
    return priority;
  }

  @Override
  public boolean isControlMessage() {
    return controlMessage;
  }

  @Override
  public String getOrigDest() {
    return origDest;
  }

  @Override
  public String getFailureReason() {
    return failureReason;
  }

  @Override
  public String getExceptionDetail() {
    return exceptionDetail;
  }

  @Override
  public int getRetryCount() {
    return retryCount;
  }

  @Override
  public T getPayload() {
    return payload;
  }

  @Override
  public Message<T> withAddedHistory(final String nodeId) {
    final int size = messageHistory.size();
    final List<String> tail = messageHistory.subList(Math.max(0, size - (MAX_HISTORY - 1)), size);
    final String[] history = tail.toArray(new String[tail.size() + 1]);
    history[tail.size()] = nodeId;
    return withMessageHistory(List.of(history));
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
    return DefaultMessage.<R>builder()
        .id(id)
        .traceId(traceId)
        .correlationId(correlationId)
        .replyTo(replyTo)
        .expiration(expiration)
        .formatIndicator(formatIndicator)
        .metadata(metadata)
        .messageHistory(messageHistory)
        .sequenceId(sequenceId)
        .sequenceNumber(sequenceNumber)
        .sequenceSize(sequenceSize)
        .priority(priority)
        .controlMessage(controlMessage)
        .origDest(origDest)
        .failureReason(failureReason)
        .exceptionDetail(exceptionDetail)
        .retryCount(retryCount)
        .payload(newPayload)
        .timestamp(timestamp)
        .sourcePort(sourcePort)
        .sourceNodeId(sourceNodeId)
        .workflowId(workflowId)
        .build();
  }

  @Override
  public Message<T> withHeader(final String key, final Object value) {
    Objects.requireNonNull(key, "header key must not be null");
    final Map<String, Object> newMetadata = new HashMap<>(metadata);
    if (value == null) {
      newMetadata.remove(key);
    } else {
      newMetadata.put(key, value);
    }
    return withMetadata(newMetadata);
  }

  private static Map<String, Object> copyWithoutNulls(final Map<String, Object> source) {
    if (source == null || source.isEmpty()) {
      return Map.of();
    }
    if (source.values().stream().noneMatch(Objects::isNull)) {
      return Map.copyOf(source);
    }
    final Map<String, Object> copy = new HashMap<>(source);
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
        .id(original.getMessageId())
        .traceId(original.getTraceId())
        .correlationId(original.getCorrelationId())
        .replyTo(original.getReplyTo())
        .expiration(original.getExpiration())
        .formatIndicator(original.getFormatIndicator())
        .metadata(original.getMetadata())
        .messageHistory(original.getMessageHistory())
        .sequenceId(original.getSequenceId())
        .sequenceNumber(original.getSequenceNumber())
        .sequenceSize(original.getSequenceSize())
        .priority(original.getPriority())
        .controlMessage(original.isControlMessage())
        .origDest(original.getOrigDest())
        .failureReason(original.getFailureReason())
        .exceptionDetail(original.getExceptionDetail())
        .retryCount(original.getRetryCount())
        .payload(newPayload)
        .timestamp(original.getTimestamp())
        .sourcePort(original.getSourcePort())
        .sourceNodeId(original.getSourceNodeId())
        .workflowId(original.getWorkflowId())
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
