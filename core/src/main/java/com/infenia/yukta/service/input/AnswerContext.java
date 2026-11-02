// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.input;

import com.infenia.yukta.plugin.input.InputRequest;

/**
 * What an {@link AnswerAuthorizer} decides on.
 *
 * @param request the request, as it is now
 * @param actor who sends the command
 * @param escalation true for handing the request up, false for answering it
 */
public record AnswerContext(InputRequest request, ActorInfo actor, boolean escalation) {}
