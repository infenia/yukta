package io.jagratha.jagratha.mcp;

import io.jagratha.jagratha.service.JagrathaService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
public class JagrathaMcpTools {

  private final JagrathaService jagrathaService;

  @Tool(description = "Get current status of the external project managed by Jagratha")
  public String getProjectStatus() {
    return "Jagratha is managing the project and ready to run quality checks.";
  }

  @Tool(
      description = "Trigger quality checks (spotless, checkstyle, tests) on the external project")
  public Mono<String> triggerQualityChecks() {
    return jagrathaService
        .runQualityChecks()
        .map(response -> "Status: " + response.status() + "\n\nOutput:\n" + response.output());
  }
}
