/*
 * Copyright 2026 Infenia Private Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.infenia.yukta.plugin;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
 * @param <T> the type of the payload
 */
public record DefaultMessage<T>(
    @SuppressWarnings("PMD.ShortVariable") String id,
    String traceId,
    String correlationId,
    String replyTo,
    long expiration,
    String formatIndicator,
    Map<String, Object> metadata,
    List<String> messageHistory,
    String sequenceId,
    int sequenceNumber,
    int sequenceSize,
    int priority,
    boolean controlMessage,
    String origDest,
    String failureReason,
    String exceptionDetail,
    int retryCount,
    T payload,
    Instant timestamp,
    String sourcePort,
    String sourceNodeId)
    implements Message<T> {

  /** Compact constructor to ensure metadata and history are immutable. */
  public DefaultMessage {
    metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    messageHistory = messageHistory != null ? List.copyOf(messageHistory) : List.of();
    if (timestamp == null) {
      timestamp = Instant.now();
    }
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
  public long getTimestamp() {
    return timestamp.toEpochMilli();
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
  public boolean isLastInSequence() {
    return sequenceSize > 0 && sequenceNumber == sequenceSize;
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
  public Message<T> withTraceId(final String traceId) {
    return new DefaultMessage<>(
        id,
        traceId,
        correlationId,
        replyTo,
        expiration,
        formatIndicator,
        metadata,
        messageHistory,
        sequenceId,
        sequenceNumber,
        sequenceSize,
        priority,
        controlMessage,
        origDest,
        failureReason,
        exceptionDetail,
        retryCount,
        payload,
        timestamp,
        sourcePort,
        sourceNodeId);
  }

  @Override
  public Message<T> withTimestamp(final Instant timestamp) {
    return new DefaultMessage<>(
        id,
        traceId,
        correlationId,
        replyTo,
        expiration,
        formatIndicator,
        metadata,
        messageHistory,
        sequenceId,
        sequenceNumber,
        sequenceSize,
        priority,
        controlMessage,
        origDest,
        failureReason,
        exceptionDetail,
        retryCount,
        payload,
        timestamp,
        sourcePort,
        sourceNodeId);
  }

  @Override
  public Message<T> withCorrelationId(final String correlationId) {
    return new DefaultMessage<>(
        id,
        traceId,
        correlationId,
        replyTo,
        expiration,
        formatIndicator,
        metadata,
        messageHistory,
        sequenceId,
        sequenceNumber,
        sequenceSize,
        priority,
        controlMessage,
        origDest,
        failureReason,
        exceptionDetail,
        retryCount,
        payload,
        timestamp,
        sourcePort,
        sourceNodeId);
  }

  @Override
  public Message<T> withReplyTo(final String replyTo) {
    return new DefaultMessage<>(
        id,
        traceId,
        correlationId,
        replyTo,
        expiration,
        formatIndicator,
        metadata,
        messageHistory,
        sequenceId,
        sequenceNumber,
        sequenceSize,
        priority,
        controlMessage,
        origDest,
        failureReason,
        exceptionDetail,
        retryCount,
        payload,
        timestamp,
        sourcePort,
        sourceNodeId);
  }

  @Override
  public Message<T> withSourcePort(final String sourcePort) {
    return new DefaultMessage<>(
        id,
        traceId,
        correlationId,
        replyTo,
        expiration,
        formatIndicator,
        metadata,
        messageHistory,
        sequenceId,
        sequenceNumber,
        sequenceSize,
        priority,
        controlMessage,
        origDest,
        failureReason,
        exceptionDetail,
        retryCount,
        payload,
        timestamp,
        sourcePort,
        sourceNodeId);
  }

  @Override
  public Message<T> withSourceNodeId(final String sourceNodeId) {
    return new DefaultMessage<>(
        id,
        traceId,
        correlationId,
        replyTo,
        expiration,
        formatIndicator,
        metadata,
        messageHistory,
        sequenceId,
        sequenceNumber,
        sequenceSize,
        priority,
        controlMessage,
        origDest,
        failureReason,
        exceptionDetail,
        retryCount,
        payload,
        timestamp,
        sourcePort,
        sourceNodeId);
  }

  @Override
  public Message<T> withExpiration(final long expiration) {
    return new DefaultMessage<>(
        id,
        traceId,
        correlationId,
        replyTo,
        expiration,
        formatIndicator,
        metadata,
        messageHistory,
        sequenceId,
        sequenceNumber,
        sequenceSize,
        priority,
        controlMessage,
        origDest,
        failureReason,
        exceptionDetail,
        retryCount,
        payload,
        timestamp,
        sourcePort,
        sourceNodeId);
  }

  @Override
  public Message<T> withFormatIndicator(final String format) {
    return new DefaultMessage<>(
        id,
        traceId,
        correlationId,
        replyTo,
        expiration,
        format,
        metadata,
        messageHistory,
        sequenceId,
        sequenceNumber,
        sequenceSize,
        priority,
        controlMessage,
        origDest,
        failureReason,
        exceptionDetail,
        retryCount,
        payload,
        timestamp,
        sourcePort,
        sourceNodeId);
  }

  @Override
  public Message<T> withAddedHistory(final String nodeId) {
    final List<String> newHistory = new ArrayList<>(messageHistory);
    newHistory.add(nodeId);
    return new DefaultMessage<>(
        id,
        traceId,
        correlationId,
        replyTo,
        expiration,
        formatIndicator,
        metadata,
        newHistory,
        sequenceId,
        sequenceNumber,
        sequenceSize,
        priority,
        controlMessage,
        origDest,
        failureReason,
        exceptionDetail,
        retryCount,
        payload,
        timestamp,
        sourcePort,
        sourceNodeId);
  }

  @Override
  public Message<T> withSequence(final String sid, final int pos, final int total) {
    return new DefaultMessage<>(
        id,
        traceId,
        correlationId,
        replyTo,
        expiration,
        formatIndicator,
        metadata,
        messageHistory,
        sid,
        pos,
        total,
        priority,
        controlMessage,
        origDest,
        failureReason,
        exceptionDetail,
        retryCount,
        payload,
        timestamp,
        sourcePort,
        sourceNodeId);
  }

  @Override
  public Message<T> withPriority(final int newPriority) {
    return new DefaultMessage<>(
        id,
        traceId,
        correlationId,
        replyTo,
        expiration,
        formatIndicator,
        metadata,
        messageHistory,
        sequenceId,
        sequenceNumber,
        sequenceSize,
        newPriority,
        controlMessage,
        origDest,
        failureReason,
        exceptionDetail,
        retryCount,
        payload,
        timestamp,
        sourcePort,
        sourceNodeId);
  }

  @Override
  public Message<T> withControl(final boolean control) {
    return new DefaultMessage<>(
        id,
        traceId,
        correlationId,
        replyTo,
        expiration,
        formatIndicator,
        metadata,
        messageHistory,
        sequenceId,
        sequenceNumber,
        sequenceSize,
        priority,
        control,
        origDest,
        failureReason,
        exceptionDetail,
        retryCount,
        payload,
        timestamp,
        sourcePort,
        sourceNodeId);
  }

  @Override
  public Message<T> withFailure(final String origDest, final String reason, final String detail) {
    return new DefaultMessage<>(
        id,
        traceId,
        correlationId,
        replyTo,
        expiration,
        formatIndicator,
        metadata,
        messageHistory,
        sequenceId,
        sequenceNumber,
        sequenceSize,
        priority,
        controlMessage,
        origDest,
        reason,
        detail,
        retryCount,
        payload,
        timestamp,
        sourcePort,
        sourceNodeId);
  }

  @Override
  public Message<T> withRetryCount(final int count) {
    return new DefaultMessage<>(
        id,
        traceId,
        correlationId,
        replyTo,
        expiration,
        formatIndicator,
        metadata,
        messageHistory,
        sequenceId,
        sequenceNumber,
        sequenceSize,
        priority,
        controlMessage,
        origDest,
        failureReason,
        exceptionDetail,
        count,
        payload,
        timestamp,
        sourcePort,
        sourceNodeId);
  }

  @Override
  public Message<T> withIncrementedRetry() {
    return withRetryCount(retryCount + 1);
  }

  @Override
  public <R> Message<R> withPayload(final R newPayload) {
    return new DefaultMessage<>(
        id,
        traceId,
        correlationId,
        replyTo,
        expiration,
        formatIndicator,
        metadata,
        messageHistory,
        sequenceId,
        sequenceNumber,
        sequenceSize,
        priority,
        controlMessage,
        origDest,
        failureReason,
        exceptionDetail,
        retryCount,
        newPayload,
        timestamp,
        sourcePort,
        sourceNodeId);
  }

  @Override
  public Message<T> withMetadata(final Map<String, Object> newMetadata) {
    return new DefaultMessage<>(
        id,
        traceId,
        correlationId,
        replyTo,
        expiration,
        formatIndicator,
        newMetadata,
        messageHistory,
        sequenceId,
        sequenceNumber,
        sequenceSize,
        priority,
        controlMessage,
        origDest,
        failureReason,
        exceptionDetail,
        retryCount,
        payload,
        timestamp,
        sourcePort,
        sourceNodeId);
  }

  @Override
  public Message<T> withHeader(final String key, final Object value) {
    final Map<String, Object> newMetadata = new ConcurrentHashMap<>(metadata);
    newMetadata.put(key, value);
    return withMetadata(newMetadata);
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
    return new DefaultMessage<>(
        original.getMessageId(),
        original.getTraceId(),
        original.getCorrelationId(),
        original.getReplyTo(),
        original.getExpiration(),
        original.getFormatIndicator(),
        original.getMetadata(),
        original.getMessageHistory(),
        original.getSequenceId(),
        original.getSequenceNumber(),
        original.getSequenceSize(),
        original.getPriority(),
        original.isControlMessage(),
        original.getOrigDest(),
        original.getFailureReason(),
        original.getExceptionDetail(),
        original.getRetryCount(),
        newPayload,
        Instant.ofEpochMilli(original.getTimestamp()),
        original.getSourcePort(),
        original.getSourceNodeId());
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
        null);
  }
}
