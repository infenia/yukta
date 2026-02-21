@import com.infenia.jagratha.model.WorkflowProgress
@import com.infenia.jagratha.model.TaskProgress
@import com.infenia.jagratha.model.WorkflowDefinition
@import java.util.Map
@import java.util.List
@param String sessionId
@param Map<String, Object> config
@param WorkflowDefinition workflow
@param Map<String, String> modifiedFiles
@param List<String> logs
@param WorkflowProgress progress

@template.layout.main(title = "Session " + sessionId, content = @`
    <div class="mb-8 flex items-center gap-4">
        <a href="/ui" class="text-slate-400 hover:text-indigo-600 transition">
            <svg class="w-6 h-6" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M10 19l-7-7m0 0l7-7m-7 7h18"></path></svg>
        </a>
        <h1 class="text-3xl font-extrabold text-slate-800">Session <span class="text-indigo-600 font-mono text-2xl">${sessionId}</span></h1>
    </div>

    <div class="grid grid-cols-1 lg:grid-cols-3 gap-8">
        <!-- Sidebar: Info & Stats -->
        <div class="space-y-8">
            <!-- Configuration -->
            <section class="bg-white rounded-xl shadow-sm border border-slate-200 overflow-hidden">
                <div class="bg-slate-50 px-6 py-4 border-b border-slate-200 flex justify-between items-center">
                    <h2 class="font-bold text-slate-700">Configuration</h2>
                    <svg class="w-5 h-5 text-slate-400" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.065 2.572c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.572 1.065c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.065-2.572c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z"></path><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z"></path></svg>
                </div>
                <div class="p-6 space-y-4">
                    @for(Map.Entry<String, Object> entry : config.entrySet())
                        @if(!entry.getKey().equals("workflow"))
                            <div>
                                <label class="block text-xs font-semibold text-slate-400 uppercase tracking-wider">${entry.getKey()}</label>
                                <p class="text-slate-700 font-medium break-all">${String.valueOf(entry.getValue())}</p>
                            </div>
                        @endif
                    @endfor
                </div>
            </section>

            <!-- Modified Files -->
            <section class="bg-white rounded-xl shadow-sm border border-slate-200 overflow-hidden">
                <div class="bg-slate-50 px-6 py-4 border-b border-slate-200 flex justify-between items-center">
                    <h2 class="font-bold text-slate-700">Modified Files</h2>
                    <span class="text-xs bg-indigo-100 text-indigo-700 px-2 py-0.5 rounded-md font-bold">${modifiedFiles.size()}</span>
                </div>
                <div class="max-h-64 overflow-y-auto">
                    <ul class="divide-y divide-slate-100">
                        @for(Map.Entry<String, String> entry : modifiedFiles.entrySet())
                            <li class="px-6 py-3 flex justify-between items-center text-sm">
                                <span class="text-slate-600 truncate mr-2" title="${entry.getKey()}">${entry.getKey()}</span>
                                <span class="px-2 py-0.5 rounded text-[10px] font-bold uppercase ${entry.getValue().equals("SUCCESS") ? "bg-emerald-100 text-emerald-700" : "bg-amber-100 text-amber-700"}">
                                    ${entry.getValue()}
                                </span>
                            </li>
                        @endfor
                        @if(modifiedFiles.isEmpty())
                            <li class="px-6 py-8 text-center text-slate-400 italic text-sm">No files modified</li>
                        @endif
                    </ul>
                </div>
            </section>
        </div>

        <!-- Main Content: Workflow & Logs -->
        <div class="lg:col-span-2 space-y-8">
            <!-- Workflow Visualization -->
            <section class="bg-white rounded-xl shadow-sm border border-slate-200 overflow-hidden">
                <div class="bg-slate-50 px-6 py-4 border-b border-slate-200 flex justify-between items-center">
                    <h2 class="font-bold text-slate-700">Workflow DAG</h2>
                    <div id="workflow-status-badge">
                        @if(progress != null)
                            <span class="px-3 py-1 rounded-full text-xs font-bold uppercase ${progress.status().equals("RUNNING") ? "bg-blue-100 text-blue-700 animate-pulse" : (progress.status().equals("COMPLETED") || progress.status().equals("SUCCESS") ? "bg-emerald-100 text-emerald-700" : "bg-rose-100 text-rose-700")}">
                                ${progress.status()}
                            </span>
                        @else
                            <span class="px-3 py-1 rounded-full text-xs font-bold uppercase bg-slate-100 text-slate-400">Idle</span>
                        @endif
                    </div>
                </div>
                <div class="p-6">
                    <div class="mermaid flex justify-center">
                        graph TD
                        @for(WorkflowDefinition.Node node : workflow.nodes())
                            !{
                                String status = "PENDING";
                                if (progress != null) {
                                    status = progress.tasks().stream()
                                        .filter(t -> t.nodeId().equals(node.nodeId()))
                                        .map(com.infenia.jagratha.model.TaskProgress::status)
                                        .findFirst()
                                        .orElse("PENDING");
                                }
                                String color = status.equals("SUCCESS") ? "#10b981" : (status.equals("FAILURE") ? "#f43f5e" : (status.equals("RUNNING") ? "#3b82f6" : "#94a3b8"));
                                String textColor = "#ffffff";
                            }
                            ${node.nodeId()}["${node.nodeId()}<br/>(${node.type()})"]
                            style ${node.nodeId()} fill:${color},stroke:#fff,color:${textColor},stroke-width:2px
                        @endfor
                        @for(WorkflowDefinition.Edge edge : workflow.edges())
                            ${edge.source()} --> ${edge.target()}
                        @endfor
                    </div>
                </div>
            </section>

            <!-- Node Status Table -->
            <section class="bg-white rounded-xl shadow-sm border border-slate-200 overflow-hidden">
                <div class="bg-slate-50 px-6 py-4 border-b border-slate-200 flex justify-between items-center">
                    <h2 class="font-bold text-slate-700">Node Progress</h2>
                </div>
                <div class="p-0">
                    <table class="w-full text-left border-collapse">
                        <thead>
                            <tr class="bg-slate-50/50 text-slate-400 text-[10px] uppercase tracking-wider">
                                <th class="px-6 py-3 font-semibold">Node ID</th>
                                <th class="px-6 py-3 font-semibold">Type</th>
                                <th class="px-6 py-3 font-semibold">Status</th>
                                <th class="px-6 py-3 font-semibold text-right">Timing</th>
                            </tr>
                        </thead>
                        <tbody class="divide-y divide-slate-100" id="task-list">
                            @if(progress != null)
                                @for(TaskProgress task : progress.tasks())
                                    !{
                                        String type = workflow.nodes().stream()
                                            .filter(n -> n.nodeId().equals(task.nodeId()))
                                            .map(com.infenia.jagratha.model.WorkflowDefinition.Node::type)
                                            .findFirst()
                                            .orElse("unknown");
                                    }
                                    <tr class="hover:bg-slate-50 transition">
                                        <td class="px-6 py-4 text-sm font-bold text-slate-700">${task.nodeId()}</td>
                                        <td class="px-6 py-4 text-xs font-mono text-slate-500">${type}</td>
                                        <td class="px-6 py-4">
                                            <span class="px-2 py-0.5 rounded text-[10px] font-bold uppercase
                                                ${task.status().equals("SUCCESS") ? "bg-emerald-100 text-emerald-700" : (task.status().equals("FAILURE") ? "bg-rose-100 text-rose-700" : (task.status().equals("RUNNING") ? "bg-blue-100 text-blue-700 animate-pulse" : "bg-slate-100 text-slate-400"))}">
                                                ${task.status()}
                                            </span>
                                        </td>
                                        <td class="px-6 py-4 text-right text-[10px] text-slate-400">
                                            @if(task.startTime() != null)
                                                ${task.startTime().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))}
                                                @if(task.endTime() != null)
                                                    → ${task.endTime().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))}
                                                @endif
                                            @else
                                                -
                                            @endif
                                        </td>
                                    </tr>
                                @endfor
                            @else
                                <tr>
                                    <td colspan="4" class="px-6 py-8 text-center text-slate-400 italic text-sm">No active workflow tracking available.</td>
                                </tr>
                            @endif
                        </tbody>
                    </table>
                </div>
            </section>

            <!-- Live Logs / Console -->
            <section class="bg-slate-900 rounded-xl shadow-2xl border border-slate-800 overflow-hidden flex flex-col h-[500px]">
                <div class="bg-slate-800 px-6 py-3 border-b border-slate-700 flex justify-between items-center">
                    <div class="flex items-center gap-2">
                        <div class="flex gap-1.5">
                            <div class="w-3 h-3 rounded-full bg-rose-500"></div>
                            <div class="w-3 h-3 rounded-full bg-amber-500"></div>
                            <div class="w-3 h-3 rounded-full bg-emerald-500"></div>
                        </div>
                        <span class="ml-2 text-slate-400 text-xs font-mono uppercase tracking-widest">Live Console</span>
                    </div>
                    <button class="text-slate-500 hover:text-white transition" onclick="document.getElementById('console-output').innerHTML = ''">
                        <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16"></path></svg>
                    </button>
                </div>
                <div id="console-output" class="p-6 font-mono text-sm text-slate-300 overflow-y-auto flex-1 leading-relaxed whitespace-pre-wrap">
                    <div class="text-slate-600">Waiting for task execution logs...</div>
                </div>
            </section>

            <!-- Historical Logs -->
            <section class="bg-white rounded-xl shadow-sm border border-slate-200 overflow-hidden">
                <div class="bg-slate-50 px-6 py-4 border-b border-slate-200">
                    <h2 class="font-bold text-slate-700">Archived Result Logs</h2>
                </div>
                <div class="p-0">
                    <table class="w-full text-left border-collapse">
                        <thead>
                            <tr class="bg-slate-50/50 text-slate-400 text-[10px] uppercase tracking-wider">
                                <th class="px-6 py-3 font-semibold">Log Name</th>
                                <th class="px-6 py-3 font-semibold text-right">Action</th>
                            </tr>
                        </thead>
                        <tbody class="divide-y divide-slate-100">
                            @for(String logName : logs)
                                <tr class="hover:bg-slate-50 transition">
                                    <td class="px-6 py-4 text-sm font-medium text-slate-700">${logName}</td>
                                    <td class="px-6 py-4 text-right">
                                        <a href="/api/logs/${sessionId}/${logName}/raw" target="_blank" class="text-indigo-600 hover:text-indigo-800 text-xs font-bold uppercase tracking-tighter">View Raw</a>
                                    </td>
                                </tr>
                            @endfor
                            @if(logs.isEmpty())
                                <tr>
                                    <td colspan="2" class="px-6 py-12 text-center text-slate-400 italic text-sm">No log files available yet.</td>
                                </tr>
                            @endif
                        </tbody>
                    </table>
                </div>
            </section>
        </div>
    </div>

    <!-- SSE Script for Real-time Updates -->
    <script>
        const sessionId = "${sessionId}";
        const consoleOutput = document.getElementById('console-output');

        // Listen for log updates
        const logSource = new EventSource("/ui/api/sessions/" + sessionId + "/logs/stream");
        logSource.onmessage = function(event) {
            if (consoleOutput.innerText.includes('Waiting for task execution logs...')) {
                consoleOutput.innerHTML = '';
            }
            const line = document.createElement('div');
            line.className = 'mb-1';
            line.textContent = event.data;
            consoleOutput.appendChild(line);
            consoleOutput.scrollTop = consoleOutput.scrollHeight;
        };

        // Listen for workflow status updates
        const statusSource = new EventSource("/ui/api/sessions/" + sessionId + "/status/stream");
        statusSource.onmessage = function(event) {
            if (event.data === "update") {
                location.reload();
            }
        };
    </script>
`)
