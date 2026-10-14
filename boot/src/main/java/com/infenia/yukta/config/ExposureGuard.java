// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.config;

import com.infenia.yukta.security.ApiKeys;
import jakarta.annotation.PostConstruct;
import java.net.InetAddress;
import java.net.UnknownHostException;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Refuses to start when the server listens beyond loopback with no API key: the API, the MCP tools
 * and (unless {@code yukta.process.allowed-commands} is set) process execution would be open to the
 * network. Set {@code yukta.api-key} or {@code yukta.api-keys}, or bind {@code
 * YUKTA_SERVER_ADDRESS} to a loopback address.
 */
@Component
public class ExposureGuard {

  /** The configuration to check. */
  private final Environment environment;

  /**
   * Create the guard.
   *
   * @param environment holds {@code server.address} and the API keys
   */
  public ExposureGuard(final Environment environment) {
    this.environment = environment;
  }

  /**
   * Check the configuration at startup.
   *
   * @throws IllegalStateException if the server is reachable from the network without a key
   */
  @PostConstruct
  public void check() {
    final String address = environment.getProperty("server.address", "");
    if (!loopback(address) && ApiKeys.read(environment, "yukta.mcp.api-key").isEmpty()) {
      throw new IllegalStateException(
          "Refusing to listen on '"
              + (address.isBlank() ? "all interfaces" : address)
              + "' without an API key. Set yukta.api-key (YUKTA_API_KEY) or yukta.api-keys, or set"
              + " YUKTA_SERVER_ADDRESS=127.0.0.1.");
    }
  }

  private static boolean loopback(final String address) {
    boolean result = false;
    if (!address.isBlank()) {
      try {
        result = InetAddress.getByName(address).isLoopbackAddress();
      } catch (UnknownHostException e) {
        result = false;
      }
    }
    return result;
  }
}
