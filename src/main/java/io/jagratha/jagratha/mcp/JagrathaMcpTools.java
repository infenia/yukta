package io.jagratha.jagratha.mcp;

import io.jagratha.jagratha.service.JagrathaService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * MCP (Model Context Protocol) tools for Jagratha. Provides tools for interacting with the external
 * project managed by Jagratha.
 */
@Component
@RequiredArgsConstructor
public class JagrathaMcpTools {

  private final JagrathaService jagrathaService;

  private static final String MCP_SESSION_ID = "mcp-session";

  /**
   * Get current status of the external project managed by Jagratha.
   *
   * @return status message indicating Jagratha is ready
   */
  @Tool(description = "Get current status of the external project managed by Jagratha")
  public String getProjectStatus() {
    return "Jagratha is managing the project and ready to run quality checks.";
  }

  /**
   * Trigger quality checks on the external project. Runs spotless, checkstyle, and tests.
   *
   * @return Mono containing the status and output of the quality checks
   */
  @Tool(
      description = "Trigger quality checks (spotless, checkstyle, tests) on the external project")
  public Mono<String> triggerQualityChecks() {
    return jagrathaService
        .runQualityChecks(MCP_SESSION_ID)
        .map(response -> "Status: " + response.status() + "\n\nOutput:\n" + response.output());
  }
}
