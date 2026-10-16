// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.model.execution;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;

/**
 * Writes a server local time with the server's offset, for example {@code
 * 2026-10-16T04:26:14.183+05:30}.
 *
 * <p>The tracker keeps run and node times as {@link LocalDateTime} in the server's time zone.
 * Without an offset a client cannot tell which moment that is unless it happens to be in the same
 * zone; with it, any client reads the right instant. Reading in the model is unchanged.
 */
public final class OffsetTimestampSerializer extends ValueSerializer<LocalDateTime> {

  /** Default constructor, used by Jackson. */
  public OffsetTimestampSerializer() {
    super();
  }

  @Override
  public void serialize(
      final LocalDateTime value,
      final JsonGenerator generator,
      final SerializationContext context) {
    generator.writeString(
        DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
            value.atZone(ZoneId.systemDefault()).toOffsetDateTime()));
  }
}
