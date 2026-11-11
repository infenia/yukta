// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
// `yk new engine <name>`: a supervisor engine module under plugins/supervisors/<name>-engine, added to settings.gradle.kts and boot/build.gradle.kts.
// The engine decides nothing yet (it returns no directives); it compiles, is tested and shows where to write the decision. See docs/reference/supervisors/engines.mdx.
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'

const HEADER = '// SPDX-License-Identifier: Apache-2.0\n// SPDX-FileCopyrightText: 2026 Infenia Private Limited\n'

export const NAME = /^[a-z][a-z0-9]*(-[a-z0-9]+)*$/

export type Names = { type: string; module: string; pkg: string; cls: string; dir: string }

export function names(name: string): Names {
  if (!NAME.test(name)) throw new Error(`the engine name must be lower case words joined by -, like "my-agent": ${name}`)
  const cls = name
    .split('-')
    .map((w) => w[0]!.toUpperCase() + w.slice(1))
    .join('')
  return {
    type: name,
    module: `${name}-engine`,
    pkg: `com.infenia.yukta.supervisor.${name.replace(/-/g, '')}`,
    cls,
    dir: `plugins/supervisors/${name}-engine`,
  }
}

/** The files of the new module, by path from the repository root. */
export function files(name: string): Record<string, string> {
  const n = names(name)
  const main = `${n.dir}/src/main/java/${n.pkg.replace(/\./g, '/')}`
  const test = `${n.dir}/src/test/java/${n.pkg.replace(/\./g, '/')}`
  return {
    [`${n.dir}/build.gradle.kts`]: `${HEADER}plugins {
    id("com.infenia.yukta.library-conventions")
}

dependencies {
    api(project(":plugin-api"))
    implementation(libs.spring.boot.starter.webflux)
}
`,
    [`${main}/${n.cls}EngineFactory.java`]: `${HEADER}package ${n.pkg};

import com.infenia.yukta.plugin.control.engine.Directive;
import com.infenia.yukta.plugin.control.engine.EngineConfig;
import com.infenia.yukta.plugin.control.engine.EngineContext;
import com.infenia.yukta.plugin.control.engine.EngineDescriptor;
import com.infenia.yukta.plugin.control.engine.Signal;
import com.infenia.yukta.plugin.control.engine.SupervisorEngine;
import com.infenia.yukta.plugin.control.engine.SupervisorEngineFactory;
import com.infenia.yukta.plugin.core.ConfigField;
import java.util.List;
import java.util.Set;
import reactor.core.publisher.Flux;

/**
 * The ${n.type} engine. It reads signals and returns directives; the supervisor checks every
 * directive (ownership, allowed commands, initiative, rate) before it has any effect, so an engine
 * only decides.
 */
public final class ${n.cls}EngineFactory implements SupervisorEngineFactory {

  /** The engine type, as supervisors name it. */
  public static final String ENGINE_TYPE = "${n.type}";

  @Override
  public String type() {
    return ENGINE_TYPE;
  }

  @Override
  public EngineDescriptor describe() {
    return new EngineDescriptor(
        "${n.cls}",
        "One sentence for the catalog: who decides and when to use it.",
        Set.of(Signal.Kind.EVENT, Signal.Kind.STARTED),
        false);
  }

  @Override
  public List<ConfigField> getConfigSchema() {
    // one ConfigField per setting create() reads; an unlisted key is refused when a supervisor is saved
    return List.of();
  }

  @Override
  public void validate(final EngineConfig config) {
    // throw IllegalArgumentException for a bad setting, with a message that never repeats a secret
  }

  @Override
  public SupervisorEngine create(final EngineConfig config, final EngineContext context) {
    validate(config);
    // TODO: decide here: turn signals into Directive.Command, ReviewVerdict, ... (see the contract)
    return signals -> signals.thenMany(Flux.<Directive>empty());
  }
}
`,
    [`${main}/${n.cls}EngineConfiguration.java`]: `${HEADER}package ${n.pkg};

import com.infenia.yukta.plugin.control.SupervisorFactory;
import com.infenia.yukta.plugin.control.engine.EngineSupervisorFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Offers the ${n.type} engine to the server. */
@Configuration(proxyBeanMethods = false)
public class ${n.cls}EngineConfiguration {

  /**
   * The supervisor type ${n.type}.
   *
   * @return the factory the server lists in the engine catalog
   */
  @Bean
  public SupervisorFactory ${n.cls[0]!.toLowerCase()}${n.cls.slice(1)}SupervisorFactory() {
    return new EngineSupervisorFactory(new ${n.cls}EngineFactory());
  }
}
`,
    [`${test}/${n.cls}EngineFactoryTest.java`]: `${HEADER}package ${n.pkg};

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.control.engine.EngineConfig;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class ${n.cls}EngineFactoryTest {

  private final ${n.cls}EngineFactory factory = new ${n.cls}EngineFactory();

  @Test
  void theEngineIsInTheCatalog() {
    assertThat(factory.type()).isEqualTo("${n.type}");
    assertThat(factory.describe().name()).isNotBlank();
  }

  @Test
  void anEngineThatDecidesNothingReturnsNoDirectives() {
    StepVerifier.create(factory.create(new EngineConfig(Map.of()), null).run(Flux.empty()))
        .verifyComplete();
  }
}
`,
  }
}

function addAfterLast(text: string, pattern: RegExp, line: string): string {
  if (text.includes(line)) return text
  const all = [...text.matchAll(pattern)]
  if (!all.length) throw new Error(`no line like ${pattern} to put ${line.trim()} after`)
  const last = all[all.length - 1]!
  const at = last.index! + last[0].length
  return text.slice(0, at) + '\n' + line + text.slice(at)
}

/** Writes the module and registers it. Refuses an existing module. */
export function scaffold(root: string, name: string): string[] {
  const n = names(name)
  if (existsSync(join(root, n.dir))) throw new Error(`${n.dir} exists already`)
  const written: string[] = []
  for (const [path, text] of Object.entries(files(name))) {
    mkdirSync(dirname(join(root, path)), { recursive: true })
    writeFileSync(join(root, path), text)
    written.push(path)
  }
  const settings = join(root, 'settings.gradle.kts')
  writeFileSync(
    settings,
    addAfterLast(
      readFileSync(settings, 'utf8'),
      /^include\("plugins:supervisors:[^"]+"\)$/gm,
      `include("plugins:supervisors:${n.module}")`,
    ),
  )
  const boot = join(root, 'boot/build.gradle.kts')
  writeFileSync(
    boot,
    addAfterLast(
      readFileSync(boot, 'utf8'),
      /^ *implementation\(project\(":plugins:supervisors:[^"]+"\)\)$/gm,
      `    implementation(project(":plugins:supervisors:${n.module}"))`,
    ),
  )
  return [...written, 'settings.gradle.kts', 'boot/build.gradle.kts']
}

export function cmdNew(args: string[], root: string): number {
  if (args[0] !== 'engine' || !args[1]) {
    console.error('usage: yk new engine <name>   (lower case words joined by -, like "my-agent")')
    return 2
  }
  try {
    const out = scaffold(root, args[1])
    console.log(out.map((p) => `wrote ${p}`).join('\n'))
    console.log(
      `\nNext: write the decision in ${names(args[1]).cls}EngineFactory.create, run\n  yk gradle :plugins:supervisors:${names(args[1]).module}:test\nand add the bean (${names(args[1]).cls[0]!.toLowerCase()}${names(args[1]).cls.slice(1)}SupervisorFactory) to boot/src/test/resources/expected-beans.txt.`,
    )
    return 0
  } catch (e) {
    console.error(String((e as Error).message))
    return 1
  }
}
