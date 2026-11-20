// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { Table } from './packaging';

/** Where a value is set, for each way of running Yukta. The worked example is `server.port`. */

export const SETTING_INTRO =
  'Every option on the Options tab can be set in the same few places. Which one you use depends on how you run Yukta. Use the option name as written (`server.port`); the environment variable is the name in upper case with every `.` and `-` turned into `_` (`SERVER_PORT`). Click an option on the Options tab to see its own commands.';

export const WHERE: Table = {
  title: 'Where to set a value',
  intro:
    'Highest priority first: when the same option is set twice, the first row that sets it wins.',
  columns: ['Where', 'JVM jar', 'Native binary', 'Docker image'],
  rows: [
    [
      'Command-line argument',
      '`java -jar yukta.jar --server.port=9000`',
      '`./yukta --server.port=9000`',
      '`docker run ghcr.io/infenia/yukta --server.port=9000`',
    ],
    [
      'Environment variable',
      '`SERVER_PORT=9000 java -jar yukta.jar`',
      '`SERVER_PORT=9000 ./yukta`',
      '`docker run -e SERVER_PORT=9000 ghcr.io/infenia/yukta`',
    ],
    [
      'Config file',
      '`application.yaml` or `config/application.yaml` in the folder you start from, or `--spring.config.additional-location=file:<path>`.',
      'The same file names, in the folder you start from.',
      'Mount a file and point to it: `-v $PWD/yukta.yaml:/config/yukta.yaml --spring.config.additional-location=file:/config/yukta.yaml`.',
    ],
    [
      'Built-in default',
      'The default on the Options tab.',
      'The same, except the choices fixed when the binary was built.',
      'The same, plus `YUKTA_SERVER_ADDRESS=0.0.0.0` set by the image.',
    ],
  ],
};

export interface Example {
  title: string;
  text: string;
  code: string;
  label: string;
}

/** Starting the server on port 9000 instead of 9858. */
export const PORT_EXAMPLES: Example[] = [
  {
    title: 'JVM jar',
    text: 'Any one of these.',
    code: `java -jar yukta.jar --server.port=9000
SERVER_PORT=9000 java -jar yukta.jar

# or in application.yaml next to the jar
server:
  port: 9000`,
    label: 'server.port with the JVM jar',
  },
  {
    title: 'Native binary',
    text: 'The same argument and variable. The binary reads no `JAVA_TOOL_OPTIONS`.',
    code: `./yukta --server.port=9000
SERVER_PORT=9000 ./yukta`,
    label: 'server.port with the native binary',
  },
  {
    title: 'Docker image',
    text: 'Set the variable and publish the same port. `-p 127.0.0.1:9000:9000` keeps it off other machines. The image listens on all interfaces, so it also needs an API key (see the Security tab).',
    code: `docker run --rm -p 127.0.0.1:9000:9000 \\
  -e SERVER_PORT=9000 \\
  -e YUKTA_API_KEYS_0_NAME=admin -e YUKTA_API_KEYS_0_KEY=<key> -e YUKTA_API_KEYS_0_ROLE=ADMIN \\
  ghcr.io/infenia/yukta`,
    label: 'server.port with Docker',
  },
];

export const SETTING_NOTES = [
  'Most options are read once at start: change them, then restart. Options marked Live change without a restart.',
  'A few options are read only as `-D` JVM system properties (the Options tab says which). Pass them as `java -D<name>=<value> -jar yukta.jar`, or as `-D<name>=<value>` to the native binary or after the Docker image name; the environment and the config file are ignored for them.',
  'A list is set with an index in the environment: `YUKTA_API_KEYS_0_NAME`, `YUKTA_API_KEYS_1_NAME` and so on.',
  'In the native binary and the Docker image, some choices are fixed when the image is built. The Options tab marks them.',
];
