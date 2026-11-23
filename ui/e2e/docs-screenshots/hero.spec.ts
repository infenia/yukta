// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

/*
 * The README's hero animation: the stubbed release run playing from the tag to the deploy, as an
 * animated WebP per theme (docs/images/readme-run.<theme>.webp). Run it with
 * `pnpm docs:screenshots -- --hero`; it needs ffmpeg built with libwebp.
 *
 * The run's state lives in `step` below. The status stream answers with `retry: 250`, so the
 * browser's EventSource reconnects four times a second and picks up the next state on its own:
 * no reload, and the canvas animates the change the way it does for a real run.
 */
import { test, expect, type Page } from '@playwright/test';
import { spawnSync } from 'node:child_process';
import { mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { fitCanvas } from './shots';
import { base, ok, releaseRun } from './stubs/routes';
import {
  EXECUTION_ID,
  NOW,
  PROGRESS,
  WORKFLOW_ID,
  WORKFLOW_URL,
} from './stubs/data';

const HERE = dirname(fileURLToPath(import.meta.url));
const OUT_DIR = resolve(HERE, '../../../docs/images');
const FRAMES_DIR = resolve(HERE, '../../build/docs-hero');
const THEMES = ['light', 'dark'] as const;
/** Frames per second of the capture and of the animation. */
const FPS = 10;
/** Widest the published animation may be; a wider capture is scaled down to it. */
const WIDTH = 1400;
const VIEWPORT = { width: 1440, height: 520 };

type NodeState = 'PENDING' | 'RUNNING' | 'SUCCESS';
interface Step {
  /** node id -> state; nodes left out are PENDING */
  nodes: Record<string, NodeState>;
  /** seconds to stay on this step */
  hold: number;
  run?: 'RUNNING' | 'SUCCESS';
}

const done = (...ids: string[]) =>
  Object.fromEntries(ids.map((id) => [id, 'SUCCESS' as const]));
const running = (...ids: string[]) =>
  Object.fromEntries(ids.map((id) => [id, 'RUNNING' as const]));

/** The run, step by step: rules check it, the AI drafts the notes, a person approves, it ships. */
const STEPS: Step[] = [
  { nodes: running('on_tag'), hold: 0.8 },
  { nodes: { ...done('on_tag'), ...running('validate') }, hold: 1.0 },
  {
    nodes: {
      ...done('on_tag', 'validate'),
      ...running('risk_gate', 'draft_notes'),
    },
    hold: 1.0,
  },
  {
    nodes: {
      ...done('on_tag', 'validate', 'risk_gate'),
      ...running('draft_notes', 'approve'),
    },
    hold: 1.0,
  },
  {
    nodes: {
      ...done('on_tag', 'validate', 'risk_gate', 'draft_notes'),
      ...running('approve'),
    },
    hold: 1.8,
  },
  {
    nodes: {
      ...done('on_tag', 'validate', 'risk_gate', 'draft_notes', 'approve'),
      ...running('deploy'),
    },
    hold: 1.0,
  },
  {
    nodes: {
      ...done(
        'on_tag',
        'validate',
        'risk_gate',
        'draft_notes',
        'approve',
        'deploy'
      ),
      ...running('notify'),
    },
    hold: 0.8,
  },
  {
    nodes: done(...PROGRESS.tasks.map((t) => t.nodeId)),
    hold: 2.2,
    run: 'SUCCESS',
  },
];

/** WorkflowProgress for a step, built on the stub run so ids and modules stay in one place. */
function progress(step: Step) {
  const minute = (n: number) =>
    new Date(NOW.getTime() - n * 60_000).toISOString();
  return {
    ...PROGRESS,
    status: step.run ?? 'RUNNING',
    endTime: step.run === 'SUCCESS' ? minute(0) : null,
    tasks: PROGRESS.tasks.map((task) => {
      const state = step.nodes[task.nodeId] ?? 'PENDING';
      return {
        ...task,
        status: state,
        startTime: state === 'PENDING' ? null : minute(1),
        endTime: state === 'SUCCESS' ? minute(0) : null,
      };
    }),
  };
}

/** Answers the run's status, details and list from whatever step `current()` returns. */
async function liveRun(page: Page, current: () => Step) {
  await page.route('**/api/executions/*/status/stream*', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'text/event-stream',
      body: `retry: 250\ndata: ${JSON.stringify(progress(current()))}\n\n`,
    })
  );
  await page.route(/\/api\/executions\/[^/]+$/, (route) =>
    route.fulfill(ok(progress(current())))
  );
  await page.route(
    /\/api\/sessions\/[^/]+\/workflows\/[^/]+\/executions$/,
    (route) =>
      route.fulfill(
        ok([
          {
            executionId: EXECUTION_ID,
            workflowId: WORKFLOW_ID,
            status: current().run ?? 'RUNNING',
            startTime: PROGRESS.startTime,
            endTime: null,
          },
        ])
      )
  );
}

/** The UI status a node shows for a server state (statusMapper). */
const UI_STATUS: Record<NodeState, string> = {
  PENDING: 'pending',
  RUNNING: 'running',
  SUCCESS: 'completed',
};

test.describe.configure({ mode: 'parallel' });
test.use({ locale: 'en-US', timezoneId: 'UTC', deviceScaleFactor: 1 });

for (const theme of THEMES) {
  test(`readme-run (${theme})`, async ({ page }) => {
    test.setTimeout(120_000);
    const which = spawnSync('ffmpeg', ['-hide_banner', '-encoders'], {
      encoding: 'utf8',
    });
    expect(
      which.stdout ?? '',
      'ffmpeg with the libwebp_anim encoder is needed for the hero animation'
    ).toContain('libwebp_anim');

    let index = 0;
    await page.setViewportSize(VIEWPORT);
    // Motion on: the running pulse and the edge flow are the point of the animation.
    await page.emulateMedia({
      colorScheme: theme,
      reducedMotion: 'no-preference',
    });
    await page.addInitScript((t) => localStorage.setItem('theme', t), theme);
    await page.clock.setFixedTime(NOW);
    const thrown: string[] = [];
    page.on('pageerror', (error) => thrown.push(error.message));
    await base(page);
    await releaseRun(page);
    await liveRun(page, () => STEPS[index]);

    await page.goto(WORKFLOW_URL);
    await page.getByTestId('workflow-node-risk_gate').waitFor();
    await page
      .getByRole('button', { name: 'Close workflow progress panel' })
      .click();
    await page.getByRole('button', { name: 'Collapse execution logs' }).click();
    // Following pans to each running node; the animation shows the whole graph instead.
    await page.getByRole('button', { name: 'Follow running nodes' }).click();
    await fitCanvas(page);
    // The canvas tools, zoom buttons and minimap are React Flow panels: noise in a hero picture.
    await page.addStyleTag({
      content: '.react-flow__panel{visibility:hidden!important}',
    });
    await page.evaluate(() => document.fonts.ready);

    // The run header and the canvas, without the app's rail and top bar.
    const canvas = await page.getByTestId('rf__wrapper').boundingBox();
    const title = await page
      .getByRole('heading', { name: WORKFLOW_ID, level: 1 })
      .boundingBox();
    if (!canvas || !title) throw new Error('canvas or run header not found');
    const clip = {
      x: canvas.x,
      y: Math.max(0, title.y - 16),
      width: canvas.width,
      height: canvas.y + canvas.height - Math.max(0, title.y - 16),
    };

    const frames = resolve(FRAMES_DIR, theme);
    rmSync(frames, { recursive: true, force: true });
    mkdirSync(frames, { recursive: true });
    // Chromium's screencast sends a frame, with its time, whenever the page repaints, so the CSS
    // animations keep their real speed (a screenshot per frame takes ~400 ms and would not).
    const cdp = await page.context().newCDPSession(page);
    const shots: { file: string; time: number }[] = [];
    cdp.on('Page.screencastFrame', (event) => {
      const file = resolve(
        frames,
        `${String(shots.length).padStart(5, '0')}.png`
      );
      writeFileSync(file, Buffer.from(event.data, 'base64'));
      shots.push({ file, time: event.metadata.timestamp ?? Date.now() / 1000 });
      void cdp
        .send('Page.screencastFrameAck', { sessionId: event.sessionId })
        .catch(() => undefined);
    });
    await cdp.send('Page.startScreencast', {
      format: 'png',
      maxWidth: VIEWPORT.width,
      maxHeight: VIEWPORT.height,
      everyNthFrame: 1,
    });
    for (index = 0; index < STEPS.length; index++) {
      const step = STEPS[index];
      for (const [id, state] of Object.entries(step.nodes)) {
        await expect(page.getByTestId(`workflow-node-${id}`)).toHaveAttribute(
          'data-status',
          UI_STATUS[state],
          { timeout: 5_000 }
        );
      }
      await page.waitForTimeout(step.hold * 1000);
    }
    await cdp.send('Page.stopScreencast');
    if (thrown.length > 0) throw new Error(`the page threw: ${thrown[0]}`);
    if (shots.length < 2) throw new Error('the screencast sent no frames');

    // Each frame lasts until the next one; ffmpeg's concat demuxer turns that into a steady FPS.
    const list = shots
      .map((shot, i) => {
        const next = shots[i + 1]?.time ?? shot.time + 0.5;
        return `file '${shot.file}'\nduration ${(next - shot.time).toFixed(3)}`;
      })
      .join('\n');
    const listFile = resolve(frames, 'frames.txt');
    // the last file is repeated, or concat drops its duration
    writeFileSync(
      listFile,
      `${list}\nfile '${shots[shots.length - 1].file}'\n`
    );
    const frame = shots.length;

    const out = resolve(OUT_DIR, `readme-run.${theme}.webp`);
    const encode = spawnSync(
      'ffmpeg',
      [
        '-y',
        '-loglevel',
        'error',
        '-f',
        'concat',
        '-safe',
        '0',
        '-i',
        listFile,
        '-vf',
        `fps=${FPS},crop=${Math.round(clip.width)}:${Math.round(clip.height)}:${Math.round(clip.x)}:${Math.round(clip.y)},scale='min(${WIDTH},iw)':-2:flags=lanczos`,
        '-c:v',
        'libwebp_anim',
        '-lossless',
        '0',
        '-quality',
        '70',
        '-compression_level',
        '6',
        '-loop',
        '0',
        out,
      ],
      { encoding: 'utf8' }
    );
    if (encode.status !== 0) throw new Error(`ffmpeg failed: ${encode.stderr}`);
    console.log(`wrote ${out} (${frame} frames)`);
  });
}
