// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';
import path from 'path';
export default defineConfig({
    plugins: [react(), tailwindcss()],
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
            '/sse': {
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
