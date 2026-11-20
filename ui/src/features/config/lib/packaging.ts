// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

/** What changes with how Yukta is built and run. Checked against the Dockerfiles, `boot/build.gradle.kts` and `cli/`. */

export interface Table {
  title: string;
  intro: string;
  columns: string[];
  rows: string[][];
}

export const MATRIX: Table = {
  title: 'JVM jar, native binary and Docker image',
  intro:
    'The Docker image runs the native binary, never a JVM, so its column follows the native one except where noted.',
  columns: ['', 'JVM jar', 'Native binary', 'Docker image'],
  rows: [
    [
      '-Dkey=value',
      'After `java`, before `-jar`.',
      'An argument to the binary.',
      'An argument after the image name.',
    ],
    [
      'JAVA_TOOL_OPTIONS',
      'Read by the JVM.',
      'Ignored.',
      'Ignored. Pass flags as container arguments.',
    ],
    [
      'Memory flags (-Xmx)',
      'Standard JVM flags.',
      '`-Xmx` as an argument; the garbage collector is SerialGC. Other -XX flags are not verified.',
      'As native, as container arguments.',
    ],
    [
      'Profile',
      '`prod` by default (`spring.profiles.default`).',
      '`prod` unless a profile is given.',
      'As native. To add Postgres name both: `prod,postgres`.',
    ],
    [
      'Options that switch a bean on or off',
      'Read at start.',
      'Fixed when the image is built (marked in the Options tab).',
      'Fixed when the image is built.',
    ],
    [
      'Plugins, edition, persistence, workers',
      'Chosen at build time.',
      'Chosen at build time.',
      'Chosen at build time. The release image is the Community edition.',
    ],
    [
      'Bind address',
      '127.0.0.1.',
      '127.0.0.1.',
      '0.0.0.0, so the server refuses to start without an API key.',
    ],
    [
      'Data folder',
      '`~/.yukta` for the file store (Plus edition).',
      'Same.',
      'In memory. No `VOLUME` is declared; the user is UID 65532, so a mounted folder must be writable by it.',
    ],
  ],
};

export const BUILD_FLAGS: Table = {
  title: 'Build-time flags',
  intro:
    'Gradle properties for `yk gradle :boot:bootJar` or `nativeCompile`. They decide what is in the binary and cannot be changed at start.',
  columns: ['Flag', 'Default', 'Effect'],
  rows: [
    [
      '-Pyukta.edition=plus',
      'Community',
      'Adds the Plus modules: file stores, execution log, checkpoints.',
    ],
    [
      '-Pyukta.persistence=postgres',
      'off',
      'Adds the Postgres stores and high-availability mode.',
    ],
    ['-Pyukta.workers=true', 'off', 'Adds remote workers (Plus).'],
    [
      '-Pyukta.plugins=<list>',
      'default',
      '`default` (all but `script-js`), `all`, `none`, or a comma list. An unknown name fails the build. `YUKTA_PLUGINS_ENABLED` can only switch on plugins that are in.',
    ],
    [
      '-Pnative.heap=<size>',
      '16g',
      'Heap of the native-image builder (use 24g locally if it runs out).',
    ],
    [
      '-Pnative.static',
      'off',
      'A statically linked binary (`--static-nolibc`), as the Docker image uses.',
    ],
    [
      '-Pnative.quick',
      'off',
      'A faster build and a slower binary; for smoke tests.',
    ],
  ],
};

export const TUNING: Table = {
  title: 'Netty, Reactor and JVM tuning',
  intro:
    'Yukta sets none of these itself, so every one is a standard default. Set them as `-D` or JVM arguments (an argument to the binary in native and Docker). Whether each one takes effect in a native image is not verified.',
  columns: ['Setting', 'Default', 'What it does'],
  rows: [
    [
      '-Dreactor.schedulers.defaultPoolSize',
      'CPU count',
      'Threads of the shared `parallel` scheduler.',
    ],
    [
      '-Dreactor.schedulers.defaultBoundedElasticSize',
      '10 x CPU count',
      'Threads of the shared `boundedElastic` scheduler.',
    ],
    [
      '-Dio.netty.leakDetection.level',
      'simple',
      'Buffer leak detection: `disabled`, `simple`, `advanced` or `paranoid`.',
    ],
    [
      '-Xmx / -XX:MaxRAMPercentage',
      '25% of RAM (JVM)',
      'Maximum heap. In a container the JVM reads the container limit.',
    ],
    [
      'spring.codec.max-in-memory-size',
      '1MB',
      'Largest request body (`YUKTA_LIMITS_MAX_BODY`); the one network limit Yukta does set.',
    ],
  ],
};

export const CLI: Table = {
  title: 'The command-line client',
  intro:
    '`yukta-cli` is a separate Go binary with no config file and no stored credentials. It is in the packages and archives, not in the Docker image.',
  columns: ['Flag or variable', 'Default', 'Effect'],
  rows: [
    [
      '--url / YUKTA_SERVER_URL',
      'http://localhost:9858',
      'The server to talk to.',
    ],
    [
      '--token, --token-file / YUKTA_TOKEN',
      'none',
      'The API key. `--token` and `--token-file` exclude each other.',
    ],
    [
      '--insecure-token / YUKTA_INSECURE_TOKEN',
      'off',
      'A boolean switch for the token flag.',
    ],
    ['--timeout', 'client default', 'Request timeout; 0 turns it off.'],
    ['--retries, --retry-wait', '0, 1s', 'Retry failed requests.'],
    ['-o, --output', 'table', '`table` or `json`.'],
    [
      'YUKTA_STREAM_IDLE, YUKTA_STREAM_RECONNECTS',
      'none',
      'Idle limit (0 disables it) and reconnect count of the live streams. Environment only.',
    ],
    [
      'YUKTA_HOME',
      '~/.local/state/yukta',
      'Folder of the daemon pid and log files, shared with the server.',
    ],
  ],
};

export const DOCKER_RUN = `docker run --rm -p 127.0.0.1:9858:9858 \\
  -e YUKTA_API_KEYS_0_NAME=admin -e YUKTA_API_KEYS_0_KEY=change-me -e YUKTA_API_KEYS_0_ROLE=ADMIN \\
  -e YUKTA_PLUGINS_ENABLED=all \\
  ghcr.io/infenia/yukta -Dyukta.rate-limit-per-minute=600`;

export const DOCKER_FACTS = [
  'Port 9858. The container listens on all interfaces, so an API key is required.',
  'Runs as the unprivileged user `nonroot` (UID 65532) on a distroless image that still has a BusyBox `sh`.',
  'No `HEALTHCHECK`: probe `GET /actuator/health` from outside.',
  'No `VOLUME`. Data is in memory; the file store needs a Plus build and a mounted folder the user can write.',
  'Stop with SIGTERM; the server waits up to `yukta.execution.shutdown-timeout` (30s) for running work.',
];
