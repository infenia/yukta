// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import {
  describe,
  it,
  expect,
  vi,
  beforeAll,
  afterAll,
  afterEach,
} from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { renderWithProviders } from '@/test/utils/testUtils';
import { WorkflowVersionsPanel } from '../WorkflowVersionsPanel';
import type { WorkflowDiff, WorkflowVersion } from '../../api/versions';

const ok = (data: unknown) =>
  HttpResponse.json({
    timestamp: 't',
    status: 200,
    message: 'ok',
    data,
    path: '/',
  });

const BASE = '/api/sessions/s1/workflows/wf';
const noChange: WorkflowDiff = {
  descriptionChanged: false,
  addedNodes: [],
  removedNodes: [],
  changedNodes: [],
  addedEdges: [],
  removedEdges: [],
};

let versions: WorkflowVersion[] = [];
let diff: WorkflowDiff = noChange;
let listFails = false;
let diffFails = false;
let rollbackFails = false;
let noteFails = false;
const rolledBack: string[] = [];
const noted: string[] = [];

const server = setupServer(
  http.get(`${BASE}/versions`, () =>
    listFails
      ? HttpResponse.json({ message: 'no store' }, { status: 500 })
      : ok(versions)
  ),
  http.get(`${BASE}/versions/diff`, () =>
    diffFails
      ? HttpResponse.json({ message: 'No such version' }, { status: 404 })
      : ok(diff)
  ),
  http.post(`${BASE}/versions/note`, ({ request }) => {
    const query = new URL(request.url).searchParams;
    noted.push(`${query.get('version')}=${query.get('note')}`);
    return noteFails
      ? HttpResponse.json({ message: 'No such version' }, { status: 404 })
      : ok({});
  }),
  http.post(`${BASE}/rollback`, ({ request }) => {
    rolledBack.push(new URL(request.url).searchParams.get('version') ?? '');
    return rollbackFails
      ? HttpResponse.json({ message: 'Version gone' }, { status: 404 })
      : ok({ workflowId: 'wf', version: 'x' });
  })
);

beforeAll(() => server.listen());
afterEach(() => {
  versions = [];
  diff = noChange;
  listFails = false;
  diffFails = false;
  vi.restoreAllMocks();
  rollbackFails = false;
  noteFails = false;
  rolledBack.length = 0;
  noted.length = 0;
});
afterAll(() => server.close());

const two: WorkflowVersion[] = [
  { id: '0002-b', savedAt: '2026-10-14T10:00:00Z' },
  { id: '0001-a', savedAt: '2026-10-13T10:00:00Z' },
];

describe('WorkflowVersionsPanel', () => {
  it('says so when no version is saved', async () => {
    renderWithProviders(
      <WorkflowVersionsPanel sessionId="s1" workflowId="wf" />
    );
    expect(
      await screen.findByText('No saved versions yet.')
    ).toBeInTheDocument();
  });

  it('shows why the versions cannot be read', async () => {
    listFails = true;
    renderWithProviders(
      <WorkflowVersionsPanel sessionId="s1" workflowId="wf" />
    );
    expect(await screen.findByText('no store')).toBeInTheDocument();
  });

  it('lists the versions and shows the changes since one, then hides them', async () => {
    versions = two;
    diff = {
      descriptionChanged: true,
      addedNodes: ['n-new'],
      removedNodes: ['n-old'],
      changedNodes: ['n-mid'],
      addedEdges: ['a -> n-new'],
      removedEdges: ['a -> n-old:error'],
    };
    renderWithProviders(
      <WorkflowVersionsPanel sessionId="s1" workflowId="wf" />
    );
    expect(await screen.findByText('0002-b')).toBeInTheDocument();
    expect(screen.getByText('0001-a')).toBeInTheDocument();

    await userEvent.click(
      screen.getAllByRole('button', { name: 'Changes since' })[0]
    );

    expect(await screen.findByText('description changed')).toBeInTheDocument();
    expect(screen.getByText('+ node n-new')).toBeInTheDocument();
    expect(screen.getByText('- node n-old')).toBeInTheDocument();
    expect(screen.getByText('~ node n-mid')).toBeInTheDocument();
    expect(screen.getByText('+ edge a -> n-new')).toBeInTheDocument();
    expect(screen.getByText('- edge a -> n-old:error')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Hide changes' }));
    expect(screen.queryByText('+ node n-new')).not.toBeInTheDocument();
  });

  it('says when a version equals the current definition', async () => {
    versions = two;
    renderWithProviders(
      <WorkflowVersionsPanel sessionId="s1" workflowId="wf" />
    );

    await userEvent.click(
      (await screen.findAllByRole('button', { name: 'Changes since' }))[1]
    );

    expect(
      await screen.findByText('Same as the current definition')
    ).toBeInTheDocument();
  });

  it('shows why the changes could not be read', async () => {
    versions = two;
    diffFails = true;
    renderWithProviders(
      <WorkflowVersionsPanel sessionId="s1" workflowId="wf" />
    );

    await userEvent.click(
      (await screen.findAllByRole('button', { name: 'Changes since' }))[0]
    );

    expect(await screen.findByText('No such version')).toBeInTheDocument();
  });

  it('asks first, with what the rollback changes, and rolls back only on confirm', async () => {
    versions = two;
    diff = { ...noChange, addedNodes: ['audit'] };
    renderWithProviders(
      <WorkflowVersionsPanel sessionId="s1" workflowId="wf" />
    );

    await userEvent.click(
      (await screen.findAllByRole('button', { name: 'Roll back' }))[1]
    );
    const dialog = await screen.findByRole('dialog', {
      name: 'Roll back to 0001-a?',
    });
    expect(await within(dialog).findByText('+ node audit')).toBeInTheDocument();
    await userEvent.click(
      within(dialog).getByRole('button', { name: 'Cancel' })
    );
    expect(rolledBack).toEqual([]);

    await userEvent.click(
      screen.getAllByRole('button', { name: 'Roll back' })[1]
    );
    await userEvent.click(
      within(await screen.findByRole('dialog')).getByRole('button', {
        name: 'Roll back',
      })
    );
    await waitFor(() => expect(rolledBack).toEqual(['0001-a']));
  });

  it('says when the version equals the current one, and why the diff failed', async () => {
    versions = two;
    renderWithProviders(
      <WorkflowVersionsPanel sessionId="s1" workflowId="wf" />
    );
    await userEvent.click(
      (await screen.findAllByRole('button', { name: 'Roll back' }))[0]
    );
    expect(
      await screen.findByText('Same as the current definition')
    ).toBeInTheDocument();
    await userEvent.keyboard('{Escape}');

    diffFails = true;
    await userEvent.click(
      screen.getAllByRole('button', { name: 'Roll back' })[1]
    );
    expect(await screen.findByText('No such version')).toBeInTheDocument();
  });

  it('shows why a rollback failed', async () => {
    versions = two;
    rollbackFails = true;
    renderWithProviders(
      <WorkflowVersionsPanel sessionId="s1" workflowId="wf" />
    );
    await userEvent.click(
      (await screen.findAllByRole('button', { name: 'Roll back' }))[0]
    );
    await userEvent.click(
      within(await screen.findByRole('dialog')).getByRole('button', {
        name: 'Roll back',
      })
    );
    expect(await screen.findByText('Version gone')).toBeInTheDocument();
  });

  it('blocks rollback while a run is going', async () => {
    versions = two;
    renderWithProviders(
      <WorkflowVersionsPanel sessionId="s1" workflowId="wf" liveRun />
    );
    for (const button of await screen.findAllByRole('button', {
      name: 'Roll back',
    })) {
      expect(button).toBeDisabled();
    }
  });

  it('shows the note of a version, saves an edited one, and shows why it failed', async () => {
    versions = [
      { id: '0002-b', savedAt: '2026-10-14T10:00:00Z', note: 'added audit' },
      { id: '0001-a', savedAt: '2026-10-13T10:00:00Z' },
    ];
    renderWithProviders(
      <WorkflowVersionsPanel sessionId="s1" workflowId="wf" />
    );
    expect(await screen.findByLabelText('Note for 0002-b')).toHaveValue(
      'added audit'
    );
    expect(screen.getByLabelText('Note for 0001-a')).toHaveValue('');

    await userEvent.type(screen.getByLabelText('Note for 0001-a'), 'first cut');
    await userEvent.click(
      screen.getAllByRole('button', { name: 'Save note' })[1]
    );
    await waitFor(() => expect(noted).toEqual(['0001-a=first cut']));

    await userEvent.click(
      screen.getAllByRole('button', { name: 'Save note' })[0]
    );
    await waitFor(() => expect(noted).toHaveLength(2));
    expect(noted[1]).toBe('0002-b=added audit');

    noteFails = true;
    await userEvent.click(
      screen.getAllByRole('button', { name: 'Save note' })[0]
    );
    expect(await screen.findByText('No such version')).toBeInTheDocument();
  });

  it('clears the note when an empty one is saved', async () => {
    versions = [{ id: '0001-a', savedAt: '2026-10-13T10:00:00Z' }];
    renderWithProviders(
      <WorkflowVersionsPanel sessionId="s1" workflowId="wf" />
    );

    await userEvent.click(
      await screen.findByRole('button', { name: 'Save note' })
    );

    await waitFor(() => expect(noted).toEqual(['0001-a=']));
  });
});
