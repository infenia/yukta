// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';
import path from 'path';
import type { Plugin } from 'vite';
import { buildDocsIndex } from './scripts/docsIndex.mjs';

/**
 * The documentation as searchable passages (`/assistant-docs.json`) for the UI assistant: emitted
 * with the build, and built on request by the dev server. Without a docs folder it is empty.
 */
function assistantDocs(): Plugin {
  const docs = path.resolve(__dirname, '../docs');
  return {
    name: 'assistant-docs',
    configureServer(server) {
      server.middlewares.use('/assistant-docs.json', (_request, response) => {
        response.setHeader('Content-Type', 'application/json');
        response.end(JSON.stringify(buildDocsIndex(docs)));
      });
    },
    generateBundle() {
      this.emitFile({
        type: 'asset',
        fileName: 'assistant-docs.json',
        source: JSON.stringify(buildDocsIndex(docs)),
      });
    },
  };
}

export default defineConfig({
  plugins: [react(), tailwindcss(), assistantDocs()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  build: {
    rolldownOptions: {
      output: {
        // Stable vendor chunks cache across releases and keep every chunk under the size budget
        codeSplitting: {
          groups: [
            {
              name: 'react',
              test: /node_modules[\\/](react|react-dom|scheduler)[\\/]/,
              priority: 30,
            },
            {
              name: 'tanstack',
              test: /node_modules[\\/]@tanstack[\\/]/,
              priority: 20,
            },
            {
              name: 'router',
              test: /node_modules[\\/]react-router[\\/]/,
              priority: 20,
            },
            {
              // Only the lazy charts import these, so the main chunks never load them
              name: 'charts',
              test: /node_modules[\\/](recharts|d3-[^\\/]+|victory-vendor|internmap)[\\/]/,
              priority: 25,
            },
            {
              // Only the lazy agent terminal imports xterm, so the main chunks never load it
              name: 'xterm',
              test: /node_modules[\\/]@xterm[\\/]/,
              priority: 25,
            },
            {
              name: 'ui-kit',
              test: /node_modules[\\/]@base-ui[\\/]/,
              priority: 20,
            },
          ],
        },
      },
    },
  },
  server: {
    proxy: {
      '/api': {
        target: 'http://localhost:9858',
        changeOrigin: true,
      },
      '/mcp': {
        target: 'http://localhost:9858',
        changeOrigin: true,
      },
      '/actuator': {
        target: 'http://localhost:9858',
        changeOrigin: true,
      },
    },
  },
});
