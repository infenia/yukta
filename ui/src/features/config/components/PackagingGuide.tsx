// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { CodeBlock } from '@/components/common/CodeBlock';
import { Bullets, DataTable, Prose, Section, StepCard } from './GuideParts';
import {
  CLI,
  DOCKER_FACTS,
  DOCKER_RUN,
  MATRIX,
  TUNING,
} from '../lib/packaging';
import {
  PORT_EXAMPLES,
  SETTING_INTRO,
  SETTING_NOTES,
  WHERE,
} from '../lib/setting';

/** How to run Yukta as a JVM jar, a native binary or a Docker image, and where to set a value in each. */
export function PackagingGuide() {
  return (
    <div className="space-y-10">
      <Prose>{SETTING_INTRO}</Prose>
      <DataTable table={WHERE} mono={false} />
      <Section title="Example: start on another port">
        <Prose>
          `server.port` is 9858 by default. To start on 9000 instead:
        </Prose>
        <div className="space-y-4">
          {PORT_EXAMPLES.map((example) => (
            <StepCard key={example.title} {...example} />
          ))}
        </div>
      </Section>
      <Section title="Good to know">
        <Bullets items={SETTING_NOTES} />
      </Section>
      <DataTable table={MATRIX} />
      <Section title="Running the Docker image">
        <Bullets items={DOCKER_FACTS} />
        <CodeBlock code={DOCKER_RUN} label="docker run example" />
      </Section>
      <DataTable table={TUNING} />
      <DataTable table={CLI} />
    </div>
  );
}
