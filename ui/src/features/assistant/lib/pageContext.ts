// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

/** Where the person is, in words for the header chip, the model and the suggestions. */
export interface PageContext {
  /** Short, for the chip; empty when no particular object is in view. */
  label: string;
  /** For the model's system prompt. */
  hint: string;
  suggestions: string[];
}

const GENERAL: PageContext = {
  label: '',
  hint: 'The person is browsing Yukta; no particular session or run is in view.',
  suggestions: [
    'What is running right now?',
    'Which runs failed recently?',
    'How do I start a workflow?',
  ],
};

/** Reads the route (and `?exec=`) of the page the person is on. */
export function pageContext(pathname: string, search: string): PageContext {
  const workflow = /^\/sessions\/([^/]+)\/workflow\/([^/]+?)(\/edit)?\/?$/.exec(
    pathname
  );
  if (workflow) {
    const [, sessionId, workflowId, edit] = workflow;
    const exec = new URLSearchParams(search).get('exec');
    const where = `workflow ${workflowId} of session ${sessionId}`;
    return edit
      ? {
          label: `Editing ${workflowId}`,
          hint: `The person is editing ${where}.`,
          suggestions: [
            'Explain this workflow',
            'What does this workflow need to run?',
          ],
        }
      : {
          label: exec ? `Run ${exec.slice(0, 8)}` : `Workflow ${workflowId}`,
          hint: `The person is looking at ${where}${exec ? `, run ${exec}` : ''}.`,
          suggestions: [
            'Why did the latest run fail?',
            'Summarize this workflow',
            'Show the logs of the latest run',
          ],
        };
  }
  const session = /^\/sessions\/([^/]+?)(\/edit)?\/?$/.exec(pathname);
  if (session && !['new', 'import'].includes(session[1])) {
    return {
      label: `Session ${session[1]}`,
      hint: `The person is looking at session ${session[1]}.`,
      suggestions: ['List the workflows of this session', 'Start a workflow'],
    };
  }
  return GENERAL;
}

/** The system prompt: what Yukta is, how to use the tools, and where the person is. */
export function systemPrompt(context: PageContext): string {
  return [
    'You are the assistant built into Yukta, a workflow orchestration server: workflows are DAGs of trigger, processor and terminal nodes that run as executions inside sessions.',
    'Use the tools to look things up and to act; do not guess ids or states you can fetch. Answer briefly, in Markdown, and name the ids you used.',
    'For questions about how Yukta works or how to do something, use search_docs and give the page links it returns.',
    'A tool that changes something is shown to the person for approval before it runs; if they decline, say so and do not try to work around it.',
    context.hint,
  ].join('\n');
}
