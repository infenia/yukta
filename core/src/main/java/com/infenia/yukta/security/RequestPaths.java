// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.security;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import lombok.experimental.UtilityClass;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriUtils;

/** Reads request paths the way routing does, so a key check cannot be passed by spelling. */
@UtilityClass
public class RequestPaths {

  /** Encoded slash, backslash, dot and percent sign: they change what a path means. */
  private static final Pattern ENCODED_SEPARATOR =
      Pattern.compile("%(2f|5c|2e|25)", Pattern.CASE_INSENSITIVE);

  /**
   * Whether the raw request path is written in a form the key filters refuse: path parameters
   * ({@code ;}), backslashes, empty or dot segments, encoded separators or dots, or bad
   * percent-encoding. Routing decodes and normalizes these, so such a path could skip a filter's
   * prefix check and still reach a controller; the filters answer 400. Plain escapes such as {@code
   * %20} in an id stay allowed.
   *
   * @param rawPath the path as sent
   * @return true if the path is not in plain form
   */
  public boolean ambiguous(final String rawPath) {
    if (rawPath.indexOf(';') >= 0
        || rawPath.indexOf('\\') >= 0
        || rawPath.contains("//")
        || rawPath.contains("/./")
        || rawPath.contains("/../")
        || rawPath.endsWith("/..")
        || rawPath.endsWith("/.")
        || ENCODED_SEPARATOR.matcher(rawPath).find()) {
      return true;
    }
    try {
      decoded(rawPath);
      return false;
    } catch (IllegalArgumentException e) {
      return true;
    }
  }

  /**
   * The path with percent-escapes decoded, which is what a filter must match on.
   *
   * @param rawPath the path as sent
   * @return the decoded path
   */
  public String decoded(final String rawPath) {
    return UriUtils.decode(rawPath, StandardCharsets.UTF_8);
  }

  /**
   * Best-effort path as routing would see it: no path parameters, backslashes as slashes, decoded
   * and cleaned of dot segments. Used to find out whether a request is meant for a guarded path.
   *
   * @param rawPath the path as sent
   * @return the normalized path (the raw path if it cannot be decoded)
   */
  public String normalized(final String rawPath) {
    final String plain = rawPath.replaceAll(";[^/]*", "").replace('\\', '/');
    try {
      final String clean = StringUtils.cleanPath(decoded(plain));
      return clean.length() > 1 && clean.endsWith("/")
          ? clean.substring(0, clean.length() - 1)
          : clean;
    } catch (IllegalArgumentException e) {
      return rawPath;
    }
  }
}
