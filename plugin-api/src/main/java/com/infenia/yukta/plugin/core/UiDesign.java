// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.core;

/**
 * Metadata for rendering a plugin node in the UI canvas. The UI draws the node from these fields,
 * so plugins ship no markup.
 *
 * @param color the accent colour, a Tailwind palette name such as {@code blue} or {@code slate}
 * @param icon the icon, a Material Symbols name such as {@code filter_alt}
 * @param label the short title shown on the node
 * @param width the preferred width of the node in pixels
 * @param height the preferred height of the node in pixels
 */
public record UiDesign(String color, String icon, String label, int width, int height) {}
