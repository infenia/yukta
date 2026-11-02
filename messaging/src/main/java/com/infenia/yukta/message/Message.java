// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.message;

import java.util.List;
import java.util.Map;

/**
 * An atomic packet of data that can be transmitted across a Message Channel.
 *
 * <p>A message consists of two parts: a Header for meta-information used by the messaging system,
 * and a Body (Payload) for the actual data being transmitted.
 *
 * <p>{@link DefaultMessage} is the only implementation. The interface is kept for a possible lazy
 * or claim-check implementation; a new field must be added here, to the record and to {@code
 * DefaultMessage.from}, and the copy-path test in {@code DefaultMessageTest} fails if one is
 * missed.
 *
 * <p>A payload is immutable once a node has emitted it: it is shared, not copied, between the nodes
 * that read it. Where a node has several children the engine freezes its maps, lists and sets (they
 * throw {@link UnsupportedOperationException} on a write); a plugin that wants to change its input
 * builds a new value. Other payload types (records, POJOs, {@code byte[]}) are not frozen and must
 * not be modified.
 *
 * <p>Headers (the {@link #metadata()} values) should be JSON values (string, number, boolean, list
 * or map): a message sent to a remote worker is rebuilt from JSON, so other types arrive as plain
 * JSON values.
 *
 * @param <T> the type of the payload
 */
@SuppressWarnings("PMD.TooManyMethods")
public interface Message<T> {

  /**
   * Metadata key under which a node hands values (a {@code Map}) to its own task record: they are
   * merged into the metadata of the node's final status, so clients show them next to the status.
   */
  String NODE_METADATA_KEY = "yukta.node-metadata";

  // --- Header / Metadata Accessors ---

  /**
   * Logical identifier of this message (not a unique per-output id). It is kept across {@link
   * #withPayload} and the other {@code with*} copies, so it is not unique per node output. A store
   * must key entries by (executionId, nodeId, messageId).
   *
   * @return the message ID
   */
  @SuppressWarnings("PMD.ShortMethodName")
  String id();

  /**
   * Used to match replies to requests in Request-Reply scenarios.
   *
   * @return the correlation ID
   */
  String correlationId();

  /**
   * The Return Address specifying where to send responses.
   *
   * @return the reply channel/address
   */
  String replyTo();

  /**
   * The identifier for tracing the workflow execution.
   *
   * @return the trace ID
   */
  String traceId();

  /**
   * The time when the message was created.
   *
   * @return the timestamp
   */
  java.time.Instant timestamp();

  /**
   * The "use-by" timestamp (epoch milliseconds). The node inbound hook of {@code
   * DirectNodeMessageChannel} drops a message once it has passed.
   *
   * @return the expiration timestamp, or 0 if none
   */
  long expiration();

  /**
   * Indicates the data format or schema version.
   *
   * <p>Reserved: carried and copied, but nothing in the engine acts on it yet.
   *
   * @return the format indicator
   */
  String formatIndicator();

  /**
   * The port from which the message was emitted.
   *
   * @return the source port
   */
  String sourcePort();

  /**
   * The ID of the node that emitted the message.
   *
   * @return the source node ID
   */
  String sourceNodeId();

  /**
   * The ID of the workflow context for this message.
   *
   * @return the workflow ID
   */
  String workflowId();

  /**
   * Extensible metadata associated with the message.
   *
   * @return the metadata map
   */
  Map<String, Object> metadata();

  // --- Message History ---

  /**
   * The list of node IDs this message has traversed.
   *
   * @return the message history list
   */
  List<String> messageHistory();

  // --- Message Sequence ---

  /**
   * Unique ID for a group of related messages.
   *
   * @return the sequence ID
   */
  String sequenceId();

  /**
   * The index of this message in a sequence (starts at 1).
   *
   * @return the sequence number
   */
  int sequenceNumber();

  /**
   * Total number of messages in the sequence, or 0 if unknown.
   *
   * @return the sequence size
   */
  int sequenceSize();

  // --- Control Bus & Quality of Service ---

  /**
   * Priority of the message (higher value = higher priority).
   *
   * @return the priority level
   */
  int priority();

  /**
   * Indicates if this is a management/control message.
   *
   * <p>Reserved: carried and copied, but nothing in the engine acts on it yet.
   *
   * @return true if control message
   */
  boolean controlMessage();

  // --- Dead Letter Channel & Retry Metadata ---

  /**
   * The node or port where the message was headed before failure.
   *
   * @return the original destination
   */
  String origDest();

  /**
   * Describes the reason for failure.
   *
   * @return the failure reason
   */
  String failureReason();

  /**
   * Detailed exception message or stack trace.
   *
   * @return the exception detail
   */
  String exceptionDetail();

  /**
   * Number of times this message has been retried.
   *
   * <p>Reserved: carried and copied, but nothing in the engine acts on it yet.
   *
   * @return the retry count
   */
  int retryCount();

  // --- Body / Payload Accessors ---

  /**
   * The actual data structure being transferred. After a resume from a checkpoint the payload has
   * the type it had when it was saved if that type is one of Yukta's own, a date/time, number or
   * UUID type, or bytes; any other type comes back as plain JSON values (a map for an object).
   *
   * @return the payload
   */
  T payload();

  // --- Functional Mutation (Wither style) ---

  /**
   * Create a copy of this message with a new trace ID.
   *
   * @param traceId the new trace ID
   * @return a new message instance
   */
  Message<T> withTraceId(String traceId);

  /**
   * Create a copy of this message with a new correlation ID.
   *
   * @param correlationId the new correlation ID
   * @return a new message instance
   */
  Message<T> withCorrelationId(String correlationId);

  /**
   * Create a copy of this message with a new reply address.
   *
   * @param replyTo the new reply address
   * @return a new message instance
   */
  Message<T> withReplyTo(String replyTo);

  /**
   * Create a copy of this message with a new source port.
   *
   * @param sourcePort the new source port
   * @return a new message instance
   */
  Message<T> withSourcePort(String sourcePort);

  /**
   * Create a copy of this message with a new source node ID.
   *
   * @param sourceNodeId the new source node ID
   * @return a new message instance
   */
  Message<T> withSourceNodeId(String sourceNodeId);

  /**
   * Create a copy of this message with a new workflow ID.
   *
   * @param workflowId the new workflow ID
   * @return a new message instance
   */
  Message<T> withWorkflowId(String workflowId);

  /**
   * Create a copy of this message with a new expiration timestamp.
   *
   * @param expiration the new expiration timestamp
   * @return a new message instance
   */
  Message<T> withExpiration(long expiration);

  /**
   * Create a copy of this message with a new format indicator.
   *
   * @param formatIndicator the new format indicator
   * @return a new message instance
   */
  Message<T> withFormatIndicator(String formatIndicator);

  /**
   * Create a copy of this message with a new timestamp.
   *
   * @param timestamp the new timestamp
   * @return a new message instance
   */
  Message<T> withTimestamp(java.time.Instant timestamp);

  /**
   * Add a component to the history list, keeping at most {@link DefaultMessage#MAX_HISTORY} entries
   * (the oldest are dropped). Core's stream topology stamps every node itself, so plugins must not
   * call this.
   *
   * @param nodeId the ID of the node currently processing the message
   * @return a new message instance with the updated history
   */
  Message<T> withAddedHistory(String nodeId);

  /**
   * Create a copy of this message with sequence information.
   *
   * @param sequenceId the sequence ID
   * @param position the index in sequence
   * @param total the total size of sequence
   * @return a new message instance
   */
  Message<T> withSequence(String sequenceId, int position, int total);

  /**
   * Create a copy of this message with a new priority.
   *
   * @param priority the priority level
   * @return a new message instance
   */
  Message<T> withPriority(int priority);

  /**
   * Create a copy of this message with a control flag.
   *
   * @param control true if this is a control message
   * @return a new message instance
   */
  Message<T> withControl(boolean control);

  /**
   * Create a copy of this message augmented with failure metadata.
   *
   * @param origDest the original destination
   * @param reason the failure reason
   * @param detail the exception detail
   * @return a new message instance
   */
  Message<T> withFailure(String origDest, String reason, String detail);

  /**
   * Create a copy of this message with a specific retry count.
   *
   * @param count the retry count
   * @return a new message instance
   */
  Message<T> withRetryCount(int count);

  /**
   * Create a copy of this message with a new payload, preserving all headers.
   *
   * @param <R> the new payload type
   * @param payload the new payload
   * @return a new message instance
   */
  <R> Message<R> withPayload(R payload);

  /**
   * Create a copy of this message with updated metadata.
   *
   * @param metadata the new metadata map
   * @return a new message instance
   */
  Message<T> withMetadata(Map<String, Object> metadata);

  /**
   * Create a copy of this message with an additional or updated header in metadata.
   *
   * @param key the header key
   * @param value the header value
   * @return a new message instance
   */
  Message<T> withHeader(String key, Object value);

  /**
   * Create a copy of this message with several headers set in one copy of the metadata. A null
   * value removes the header, as in {@link #withHeader}.
   *
   * @param headers the headers to add or update
   * @return a new message instance
   */
  Message<T> withHeaders(Map<String, ?> headers);
}
