// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { Callout } from '@/components/common/Callout';
import { Bullets, DataTable, Section, StepCard, inline } from './GuideParts';
import {
  KEY_STEPS,
  LIMIT_STEPS,
  ROLES,
  SECURITY_INTRO,
  SECURITY_NOTES,
} from '../lib/security';

/** How to set API keys and limit what workflows may do. */
export function SecurityGuide() {
  return (
    <div className="space-y-10">
      <Callout kind="warning" title="Open by default, closed to the network">
        {inline(SECURITY_INTRO)}
      </Callout>
      <DataTable table={ROLES} />
      <Section title="Set up API keys">
        <div className="space-y-4">
          {KEY_STEPS.map((step) => (
            <StepCard key={step.title} {...step} />
          ))}
        </div>
      </Section>
      <Section title="Limit what workflows may do">
        <div className="space-y-4">
          {LIMIT_STEPS.map((step) => (
            <StepCard key={step.title} {...step} />
          ))}
        </div>
      </Section>
      <Section title="Good to know">
        <Bullets items={SECURITY_NOTES} />
      </Section>
    </div>
  );
}
