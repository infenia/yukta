// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.core;

/**
 * Metadata for rendering a plugin node in the UI canvas.
 *
 * @param html the HTML template for the node, can contain placeholders like {{nodeId}}
 * @param width the preferred width of the node in pixels
 * @param height the preferred height of the node in pixels
 */
public record UiDesign(String html, int width, int height) {}
