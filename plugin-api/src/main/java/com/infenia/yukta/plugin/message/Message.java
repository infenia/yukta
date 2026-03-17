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
package com.infenia.yukta.plugin.message;

import java.util.List;
import java.util.Map;

/**
 * An atomic packet of data that can be transmitted across a Message Channel.
 *
 * <p>A message consists of two parts: a Header for meta-information used by the messaging system,
 * and a Body (Payload) for the actual data being transmitted.
 *
 * @param <T> the type of the payload
 */
@SuppressWarnings("PMD.TooManyMethods")
public interface Message<T> {

  // --- Header / Metadata Accessors ---

  /**
   * Unique identifier for this message instance.
   *
   * @return the message ID
   */
  String getMessageId();

  /**
   * Used to match replies to requests in Request-Reply scenarios.
   *
   * @return the correlation ID
   */
  String getCorrelationId();

  /**
   * The Return Address specifying where to send responses.
   *
   * @return the reply channel/address
   */
  String getReplyTo();

  /**
   * The identifier for tracing the workflow execution.
   *
   * @return the trace ID
   */
  String getTraceId();

  /**
   * The time when the message was created (epoch milliseconds).
   *
   * @return the timestamp
   */
  long getTimestamp();

  /**
   * The "use-by" timestamp after which the message is discarded (epoch milliseconds).
   *
   * @return the expiration timestamp, or 0 if none
   */
  long getExpiration();

  /**
   * Indicates the data format or schema version.
   *
   * @return the format indicator
   */
  String getFormatIndicator();

  /**
   * The port from which the message was emitted.
   *
   * @return the source port
   */
  String getSourcePort();

  /**
   * The ID of the node that emitted the message.
   *
   * @return the source node ID
   */
  String getSourceNodeId();

  /**
   * Extensible metadata associated with the message.
   *
   * @return the metadata map
   */
  Map<String, Object> getMetadata();

  // --- Message History ---

  /**
   * The list of component IDs this message has traversed.
   *
   * @return the message history list
   */
  List<String> getMessageHistory();

  // --- Message Sequence ---

  /**
   * Unique ID for a group of related messages.
   *
   * @return the sequence ID
   */
  String getSequenceId();

  /**
   * The index of this message in a sequence (starts at 1).
   *
   * @return the sequence number
   */
  int getSequenceNumber();

  /**
   * Total number of messages in the sequence, or 0 if unknown.
   *
   * @return the sequence size
   */
  int getSequenceSize();

  /**
   * Flag indicating if this is the final message in a stream.
   *
   * @return true if last in sequence
   */
  boolean isLastInSequence();

  // --- Control Bus & Quality of Service ---

  /**
   * Priority of the message (higher value = higher priority).
   *
   * @return the priority level
   */
  int getPriority();

  /**
   * Indicates if this is a management/control message.
   *
   * @return true if control message
   */
  boolean isControlMessage();

  // --- Dead Letter Channel & Retry Metadata ---

  /**
   * The node or port where the message was headed before failure.
   *
   * @return the original destination
   */
  String getOrigDest();

  /**
   * Describes the reason for failure.
   *
   * @return the failure reason
   */
  String getFailureReason();

  /**
   * Detailed exception message or stack trace.
   *
   * @return the exception detail
   */
  String getExceptionDetail();

  /**
   * Number of times this message has been retried.
   *
   * @return the retry count
   */
  int getRetryCount();

  // --- Body / Payload Accessors ---

  /**
   * The actual data structure being transferred.
   *
   * @return the payload
   */
  T getPayload();

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
   * Add a component to the history list.
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
   * Create a copy of this message with an incremented retry count.
   *
   * @return a new message instance
   */
  Message<T> withIncrementedRetry();

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
}
