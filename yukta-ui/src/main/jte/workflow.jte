@import java.util.Map
@import java.util.List
@import com.infenia.yukta.model.WorkflowDefinition
@import com.infenia.yukta.model.PluginDetails
@import com.infenia.yukta.model.WorkflowProgress

@param String sessionId
@param String workflowId
@param WorkflowDefinition workflow
@param Map<String, PluginDetails> pluginDetails
@param String pluginDetailsJson
@param WorkflowProgress progress
@param List<String> logs

@template.layout.main(title = "Workflow " + workflowId, content = @`
    <!-- Breadcrumbs -->
    <div class="flex flex-wrap gap-2 py-4">
        <a href="/ui" class="text-slate-500 dark:text-slate-400 text-sm font-medium hover:text-primary transition-colors">Workflows</a>
        <span class="text-slate-300 dark:text-slate-700 text-sm">/</span>
        <span class="text-slate-900 dark:text-white font-semibold text-sm">${workflowId}</span>
    </div>

    <!-- Workflow Title -->
    <div class="flex flex-wrap justify-between items-end gap-3 pb-6">
        <div class="flex flex-col gap-1">
            <h1 class="text-3xl font-bold leading-tight text-slate-900 dark:text-white">Workflow Details</h1>
            <p class="text-sm font-mono uppercase tracking-wider text-slate-500 dark:text-slate-400">ID: ${workflowId}</p>
        </div>
    </div>

    <!-- DAG Visualization Area with Alpine Component -->
    !{
        // Convert nodes and edges to JSON format for Alpine.js
        java.util.List<String> nodesJson = workflow.nodes().stream()
            .map(n -> "{\"id\":\"" + n.nodeId() + "\",\"type\":\"" + n.type() + "\"}")
            .collect(java.util.stream.Collectors.toList());
        java.util.List<String> edgesJson = workflow.edges().stream()
            .map(e -> "{\"source\":\"" + e.source() + "\",\"target\":\"" + e.target() + "\",\"sourcePort\":\"" + (e.sourcePort() != null ? e.sourcePort() : "null") + "\"}")
            .collect(java.util.stream.Collectors.toList());
        String nodesStr = "[" + String.join(",", nodesJson) + "]";
        String edgesStr = "[" + String.join(",", edgesJson) + "]";
    }
    <div class="relative group w-full aspect-[21/9] bg-white dark:bg-slate-900/40 rounded-xl border border-slate-200 dark:border-slate-800/60 backdrop-blur-sm overflow-hidden shadow-xl mb-6"
         id="dag-container"
         x-data="dagComponent(JSON.parse($el.dataset.nodes), JSON.parse($el.dataset.edges), JSON.parse($el.dataset.plugins))"
         data-nodes='${nodesStr}'
         data-edges='${edgesStr}'
         data-plugins='${pluginDetailsJson}'>

        <!-- DAG Canvas -->
        <svg x-ref="dagSvg" class="w-full h-full"></svg>

        <!-- Controls Overlay -->
        <div class="absolute bottom-4 right-4 flex gap-2 opacity-0 group-hover:opacity-100 transition-opacity">
            <button @click="zoomIn()"
                    class="bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 p-2 rounded-lg text-slate-400 hover:text-slate-600 dark:hover:text-white shadow-sm transition-colors"
                    title="Zoom in">
                <span class="material-symbols-outlined">zoom_in</span>
            </button>
            <button @click="zoomOut()"
                    class="bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 p-2 rounded-lg text-slate-400 hover:text-slate-600 dark:hover:text-white shadow-sm transition-colors"
                    title="Zoom out">
                <span class="material-symbols-outlined">zoom_out</span>
            </button>
            <button @click="resetZoom()"
                    class="bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 p-2 rounded-lg text-slate-400 hover:text-slate-600 dark:hover:text-white shadow-sm transition-colors"
                    title="Center focus">
                <span class="material-symbols-outlined">center_focus_strong</span>
            </button>
            <button @click="toggleFullscreen()"
                    class="bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 p-2 rounded-lg text-slate-400 hover:text-slate-600 dark:hover:text-white shadow-sm transition-colors"
                    title="Toggle fullscreen">
                <span class="material-symbols-outlined" x-text="isFullscreen ? 'fullscreen_exit' : 'fullscreen'"></span>
            </button>
        </div>

        <!-- Fullscreen Container -->
        <div x-show="isFullscreen"
             class="fixed inset-0 z-50 bg-white dark:bg-slate-900 rounded-none"
             x-transition>
            <div class="absolute inset-0 overflow-hidden">
                <svg x-ref="dagSvgFullscreen" class="w-full h-full"></svg>
            </div>
            <div class="absolute top-4 right-4 flex gap-2">
                <button @click="toggleFullscreen()"
                        class="bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 p-2 rounded-lg text-slate-400 hover:text-slate-600 dark:hover:text-white shadow-sm transition-colors">
                    <span class="material-symbols-outlined">fullscreen_exit</span>
                </button>
            </div>
        </div>
    </div>

    <!-- Terminal Log Viewer -->
    <div class="log-container rounded-xl overflow-hidden mb-8 shadow-2xl flex flex-col min-h-[320px] border border-slate-800/60">
        <div class="terminal-header px-6 py-3 flex items-center justify-between border-b border-slate-800">
            <div class="flex items-center gap-2">
                <span class="material-symbols-outlined text-slate-400 text-sm">terminal</span>
                <span class="text-xs font-mono font-bold uppercase tracking-wider text-slate-300">Live Logs</span>
            </div>
            <div class="flex gap-4">
                <button class="text-xs font-mono text-slate-400 hover:text-white flex items-center gap-1 transition-colors">
                    <span class="material-symbols-outlined text-xs">download</span> Download
                </button>
                <button class="text-xs font-mono text-slate-400 hover:text-white flex items-center gap-1 transition-colors" onclick="document.getElementById('log-content').innerHTML = ''">
                    <span class="material-symbols-outlined text-xs">close</span> Clear
                </button>
            </div>
        </div>

        <div id="log-content" class="p-4 font-mono text-xs leading-relaxed overflow-y-auto max-h-[300px] text-slate-300">
            @if(logs != null && !logs.isEmpty())
                @for(String log : logs)
                    <p class="text-slate-300">${log}</p>
                @endfor
            @else
                <p class="text-slate-600 animate-pulse">➜ Listening for workflow logs...</p>
            @endif
        </div>
    </div>

    <style>
        .dag-node {
            cursor: pointer;
            transition: all 0.2s ease-in-out;
        }

        .dag-node rect {
            fill: #ffffff;
            stroke: #e2e8f0;
            stroke-width: 1px;
            transition: all 0.2s ease-in-out;
        }

        .dark .dag-node rect {
            fill: #1e293b;
            stroke: #334155;
        }

        .dag-node:hover rect {
            stroke: #3c83f6;
            filter: drop-shadow(0 4px 6px rgb(0 0 0 / 0.05));
        }

        .dag-line {
            stroke: #94a3b8;
            stroke-width: 1.5px;
            fill: none;
            opacity: 0.3;
            transition: all 0.2s ease-in-out;
        }

        .dag-line.highlight {
            stroke: #3c83f6;
            stroke-width: 2px;
            opacity: 1;
        }

        #arrowhead path {
            fill: #94a3b8;
            opacity: 0.5;
            transition: all 0.2s ease-in-out;
        }

        #arrowhead-highlight path {
            fill: #3c83f6;
            opacity: 1;
        }

        .terminal-header {
            background: #111827;
        }

        .log-container {
            background: #0a0f18;
            border-color: #1e2937;
        }
    </style>
`)
