// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { envName, type ConfigOption } from './catalog';

export interface Snippet {
  label: string;
  code: string;
}

const IMAGE = 'ghcr.io/infenia/yukta';

/** The ways to set one option: each is a line the user can copy. */
export function howToSet(o: ConfigOption): Snippet[] {
  if (o.sysprop) {
    return [
      {
        label: 'JVM jar',
        code: `java -D${o.key}=<value> -jar yukta.jar`,
      },
      {
        label:
          'Native binary (an argument; the environment and yaml are ignored)',
        code: `yukta -D${o.key}=<value>`,
      },
      { label: 'Docker', code: `docker run ${IMAGE} -D${o.key}=<value>` },
    ];
  }
  const env = envName(o);
  return [
    { label: 'Environment variable', code: `${env}=<value>` },
    { label: 'application.properties', code: `${o.key}=<value>` },
    { label: 'Command-line argument', code: `--${o.key}=<value>` },
    { label: 'Docker', code: `docker run -e ${env}=<value> ${IMAGE}` },
  ];
}
