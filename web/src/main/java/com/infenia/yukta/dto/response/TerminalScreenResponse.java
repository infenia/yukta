// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The latest settled screen of a terminal agent. The text is what the program drew: show it as
 * untrusted plain text.
 *
 * @param cols the width in characters
 * @param rows the height in lines
 * @param text the screen as text, one line per row
 * @param cursorRow the row of the cursor, from 0
 * @param cursorCol the column of the cursor, from 0
 * @param seq the sequence number of the screen
 */
@Schema(description = "The latest settled screen of a terminal agent")
public record TerminalScreenResponse(
    @Schema(description = "The width in characters") int cols,
    @Schema(description = "The height in lines") int rows,
    @Schema(description = "The screen as text, one line per row") String text,
    @Schema(description = "The row of the cursor, from 0") int cursorRow,
    @Schema(description = "The column of the cursor, from 0") int cursorCol,
    @Schema(description = "The sequence number of the screen") long seq) {}
