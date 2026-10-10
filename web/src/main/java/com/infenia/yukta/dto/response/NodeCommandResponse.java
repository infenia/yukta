// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Result of an administrative node command.
 *
 * @param payload what the node answered with
 */
@Schema(description = "A node's answer to an administrative command")
public record NodeCommandResponse(@Schema(description = "The node's reply") Object payload) {}
