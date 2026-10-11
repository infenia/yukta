// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.core.transformer;

import com.github.jknack.handlebars.Handlebars;
import com.github.jknack.handlebars.Template;
import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.PluginDescriptor;
import com.infenia.yukta.plugin.core.UiDesign;
import com.infenia.yukta.plugin.core.WorkflowContext;
import com.infenia.yukta.plugin.type.ProcessorPlugin;
import com.infenia.yukta.plugin.util.SpelUtils;
import com.infenia.yukta.util.MapUtils;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Mapper processor transforms message payloads using PROJECTION, TEMPLATE, or SCRIPT modes. */
@Slf4j
@Component
@SuppressWarnings({
  "PMD.OnlyOneReturn",
  "PMD.AvoidThrowingRawExceptionTypes",
  "PMD.TooManyMethods",
  "PMD.AvoidCatchingGenericException",
  "PMD.ExceptionAsFlowControl",
  "PMD.CyclomaticComplexity",
  "PMD.GodClass",
  "PMD.LawOfDemeter",
  "PMD.CollapsibleIfStatements"
})
public class MapperProcessor implements ProcessorPlugin {

  /** Type. */
  private static final String TYPE = "MAPPER";

  /** Unchecked. */
  private static final String UNCHECKED = "unchecked";

  /** Mode projection. */
  private static final String MODE_PROJECTION = "PROJECTION";

  /** Mode template. */
  private static final String MODE_TEMPLATE = "TEMPLATE";

  /** Mode script. */
  private static final String MODE_SCRIPT = "SCRIPT";

  /** Config mode. */
  private static final String CONFIG_MODE = "mode";

  /** Config mapping. */
  private static final String CONFIG_MAPPING = "mapping";

  /** Drop orig. */
  private static final String DROP_ORIG = "dropOriginal";

  /** Strict. */
  private static final String STRICT = "strictMode";

  /** Err prefix. */
  private static final String ERR_PREFIX = "WorkflowExecutionException: ";

  /** Script limit. */
  private static final int SCRIPT_LIMIT = 50;

  /** Err no script engine. */
  private static final String ERR_NO_SCRIPT_ENGINE =
      "SCRIPT mode needs the optional script-js plugin module (add it to yukta.plugins)";

  /** Handlebars. */
  private Handlebars handlebars;

  /** Map mapper. */
  private final MapMessageMapper mapMapper = new MapMessageMapper();

  /** Template cache. */
  private final Map<String, Template> templateCache = new ConcurrentHashMap<>();

  /** Script engines. */
  private final ObjectProvider<ScriptEngine> scriptEngines;

  /** Constructor without a script engine: SCRIPT mode is rejected. */
  public MapperProcessor() {
    this.scriptEngines = null;
  }

  /**
   * Spring constructor.
   *
   * @param scriptEngines the optional script engine
   */
  @Autowired
  public MapperProcessor(final ObjectProvider<ScriptEngine> scriptEngines) {
    this.scriptEngines = scriptEngines;
  }

  private Handlebars getHandlebars() {
    if (handlebars == null) {
      // Lazy initialization: Handlebars static initializer may fail in native images
      // if classpath resources aren't available. Deferring initialization ensures it
      // happens only when actually needed, and in the context where resources are available.
      handlebars = new Handlebars();
    }
    return handlebars;
  }

  @Override
  public PluginDescriptor describe() {
    return new PluginDescriptor(
        "Transforms message payloads using PROJECTION (SpEL), TEMPLATE (Handlebars), or SCRIPT"
            + " (GraalVM JS) modes.",
        "Configure with:\n"
            + "- mode: 'PROJECTION', 'TEMPLATE', or 'SCRIPT'.\n"
            + "- mapping:\n"
            + "    - For PROJECTION: Map of target field (supports dot notation) to SpEL"
            + " expression.\n"
            + "    - For TEMPLATE: Map of target field to Handlebars template, or a single string"
            + " template.\n"
            + "    - For SCRIPT: A JavaScript string returning the new payload.\n"
            + "- dropOriginal: Boolean. If true (default), original payload is discarded.\n"
            + "- strictMode: Boolean. If true (default), throws exception on transformation"
            + " errors.",
        Optional.of(
            new UiDesign(
                """
                <div class="flex items-center w-full h-full bg-slate-50 border-2 border-slate-200 rounded-xl px-4 gap-3">
                    <div class="flex-shrink-0 w-8 h-8 bg-slate-100 rounded-lg flex items-center justify-center text-slate-500">
                        <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8 7h12m0 0l-4-4m4 4l-4 4m0 6H4m0 0l4 4m-4-4l4-4" />
                        </svg>
                    </div>
                    <div class="flex flex-col min-w-0">
                        <div class="text-[10px] text-slate-400 font-bold uppercase tracking-wider leading-none mb-1">Mapper</div>
                        <div class="text-xs font-bold text-slate-700 truncate w-full">{{nodeId}}</div>
                    </div>
                </div>
                """,
                140,
                80)));
  }

  @Override
  public String getType() {
    return TYPE;
  }

  @Override
  @SuppressWarnings(UNCHECKED)
  public Mono<Void> initialize(final Map<String, Object> config) {
    final String mode = (String) config.get(CONFIG_MODE);
    final Object mapping = config.get(CONFIG_MAPPING);

    if (MODE_PROJECTION.equals(mode) && mapping instanceof Map) {
      ((Map<String, String>) mapping).values().forEach(SpelUtils::preParse);
    } else if (MODE_TEMPLATE.equals(mode)) {
      initializeTemplates(mapping);
    } else if (MODE_SCRIPT.equals(mode) && mapping instanceof String script) {
      requireScriptEngine().precompile(script);
    }
    return Mono.empty();
  }

  @SuppressWarnings(UNCHECKED)
  private void initializeTemplates(final Object mapping) {
    if (mapping instanceof Map) {
      ((Map<String, String>) mapping).values().forEach(this::compileTemplate);
    } else if (mapping instanceof String template) {
      compileTemplate(template);
    }
  }

  private void compileTemplate(final String templateStr) {
    templateCache.computeIfAbsent(
        templateStr,
        t -> {
          try {
            return getHandlebars().compileInline(t);
          } catch (Exception e) {
            throw new RuntimeException("Failed to compile Handlebars template", e);
          }
        });
  }

  @Override
  @SuppressWarnings(UNCHECKED)
  public Flux<Message<?>> process(final Flux<Message<?>> input, final Map<String, Object> config) {
    final String mode = (String) config.get(CONFIG_MODE);
    final Object mapping = config.get(CONFIG_MAPPING);
    final boolean dropOriginal = (Boolean) config.getOrDefault(DROP_ORIG, true);
    final boolean strictMode = (Boolean) config.getOrDefault(STRICT, true);

    return input.flatMap(
        message -> {
          try {
            final Object resultPayload =
                executeInternal(mode, mapping, message, dropOriginal, strictMode);

            return Flux.just(DefaultMessage.from(message, resultPayload));
          } catch (RuntimeException e) {
            log.error("Mapping failed for message {}: {}", message.id(), e.getMessage());
            return Flux.error(
                new RuntimeException(ERR_PREFIX + "Mapping failed: " + e.getMessage(), e));
          }
        });
  }

  @SuppressWarnings(UNCHECKED)
  private Object executeInternal(
      final String mode,
      final Object mapping,
      final Message<?> message,
      final boolean dropOriginal,
      final boolean strictMode) {
    return switch (mode) {
      case MODE_PROJECTION ->
          executeProjection(message, (Map<String, String>) mapping, dropOriginal, strictMode);
      case MODE_TEMPLATE -> executeTemplate(message, mapping, dropOriginal, strictMode);
      case MODE_SCRIPT -> executeScript(message, (String) mapping, dropOriginal, strictMode);
      default -> throw new IllegalArgumentException("Unsupported Mapper mode: " + mode);
    };
  }

  private Object executeProjection(
      final Message<?> message,
      final Map<String, String> mapping,
      final boolean dropOriginal,
      final boolean strictMode) {
    @SuppressWarnings("unchecked")
    final Message<Object> castMsg = (Message<Object>) message;
    final Map<String, Object> domain = mapMapper.toDomain(castMsg);

    final Map<String, Object> result =
        dropOriginal ? new HashMap<>() : MapUtils.asMutableMap(domain);
    for (final Map.Entry<String, String> entry : mapping.entrySet()) {
      try {
        final Object value = SpelUtils.evaluateSync(entry.getValue(), message);
        if (value != null || strictMode) {
          MapUtils.setNestedValue(result, entry.getKey(), value);
        }
      } catch (RuntimeException e) {
        if (strictMode) {
          throw e;
        }
        log.warn("Projection failed for {}: {}", entry.getKey(), e.getMessage());
      }
    }
    return result;
  }

  @SuppressWarnings(UNCHECKED)
  private Object executeTemplate(
      final Message<?> message,
      final Object mapping,
      final boolean dropOriginal,
      final boolean strictMode) {
    final Object result;
    if (mapping instanceof Map) {
      result = executeTemplateMap(message, (Map<String, String>) mapping, dropOriginal, strictMode);
    } else if (mapping instanceof String template) {
      result = executeTemplateString(message, template, strictMode);
    } else {
      throw new IllegalArgumentException("Invalid mapping for TEMPLATE mode");
    }
    return result;
  }

  private Map<String, Object> executeTemplateMap(
      final Message<?> message,
      final Map<String, String> mapping,
      final boolean dropOriginal,
      final boolean strictMode) {
    final Map<String, Object> mapResult =
        dropOriginal ? new HashMap<>() : MapUtils.asMutableMap(message.payload());
    for (final Map.Entry<String, String> entry : mapping.entrySet()) {
      try {
        final Template template = templateCache.get(entry.getValue());
        if (template == null) {
          throw new IllegalArgumentException(
              "Template not found in cache for key: " + entry.getValue());
        }
        final String value = template.apply(message);
        MapUtils.setNestedValue(mapResult, entry.getKey(), value);
      } catch (IOException e) {
        if (strictMode) {
          throw new RuntimeException("Template application failed", e);
        }
      } catch (RuntimeException e) {
        if (strictMode) {
          throw e;
        }
      }
    }
    return mapResult;
  }

  private String executeTemplateString(
      final Message<?> message, final String mapping, final boolean strictMode) {
    try {
      final Template template = templateCache.get(mapping);
      if (template == null) {
        throw new IllegalArgumentException("Template not found in cache: " + mapping);
      }
      return template.apply(message);
    } catch (IOException e) {
      if (strictMode) {
        throw new RuntimeException("Template application failed", e);
      }
    } catch (RuntimeException e) {
      if (strictMode) {
        throw e;
      }
    }
    return "";
  }

  @SuppressWarnings(UNCHECKED)
  private Object executeScript(
      final Message<?> message,
      final String script,
      final boolean dropOriginal,
      final boolean strictMode) {
    Object finalResult;
    try {
      final Object resultObj = requireScriptEngine().eval(script, message);

      if (dropOriginal) {
        finalResult = resultObj;
      } else {
        final Map<String, Object> original = MapUtils.asMutableMap(message.payload());
        if (resultObj instanceof Map) {
          original.putAll((Map<String, Object>) resultObj);
        }
        finalResult = original;
      }
    } catch (RuntimeException e) {
      if (strictMode) {
        throw e;
      }
      log.warn("Script execution failed: {}", e.getMessage());
      finalResult = dropOriginal ? Map.of() : message.payload();
    }
    return finalResult;
  }

  private ScriptEngine scriptEngine() {
    return scriptEngines == null ? null : scriptEngines.getIfAvailable();
  }

  private ScriptEngine requireScriptEngine() {
    final ScriptEngine scriptEngine = scriptEngine();
    if (scriptEngine == null) {
      throw new IllegalStateException(ERR_NO_SCRIPT_ENGINE);
    }
    return scriptEngine;
  }

  @Override
  public Mono<Void> validateConfig(final Map<String, Object> config) {
    final String mode = (String) config.get(CONFIG_MODE);
    if (mode == null
        || (!MODE_PROJECTION.equals(mode)
            && !MODE_TEMPLATE.equals(mode)
            && !MODE_SCRIPT.equals(mode))) {
      return Mono.error(
          new IllegalArgumentException(
              "Invalid or missing mode. Must be PROJECTION, TEMPLATE, or SCRIPT"));
    }
    if (MODE_SCRIPT.equals(mode) && scriptEngine() == null) {
      return Mono.error(new IllegalArgumentException(ERR_NO_SCRIPT_ENGINE));
    }
    if (config.get(CONFIG_MAPPING) == null) {
      return Mono.error(new IllegalArgumentException("mapping is mandatory"));
    }
    return Mono.empty();
  }

  @Override
  public boolean isComputationallyHeavy(final Map<String, Object> config) {
    final String mode = (String) config.get(CONFIG_MODE);
    return MODE_SCRIPT.equals(mode);
  }

  @Override
  public Mono<Void> validateInContext(
      final WorkflowContext context, final Map<String, Object> config) {
    final String mode = (String) config.get(CONFIG_MODE);
    final Object mapping = config.get(CONFIG_MAPPING);

    if (MODE_SCRIPT.equals(mode) && mapping instanceof String script) {
      if (isSimpleScript(script)) {
        log.atWarn()
            .log(
                "Mapper node {} uses SCRIPT mode for a simple transformation. "
                    + "Consider using PROJECTION mode for better performance.",
                context.nodeId());
      }
    }
    return Mono.empty();
  }

  private static boolean isSimpleScript(final String script) {
    boolean simple = false;
    if (script.length() < SCRIPT_LIMIT) {
      final String low = script.toLowerCase(java.util.Locale.ROOT);
      simple =
          !low.contains("if")
              && !low.contains("for")
              && !low.contains("function")
              && !low.contains("while");
    }
    return simple;
  }
}
