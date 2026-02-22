@import com.infenia.jagratha.model.WorkflowProgress
@import com.infenia.jagratha.model.TaskProgress
@import com.infenia.jagratha.model.WorkflowDefinition
@import java.util.Map
@import java.util.List
@param String sessionId
@param String actualWorkflowId
@param Map<String, Object> config
@param WorkflowDefinition workflow
@param List<String> logs
@param WorkflowProgress progress

@template.layout.main(title = "Session " + sessionId, content = @`
    <div class="mb-10 flex items-center justify-between">
        <div class="flex items-center gap-6">
            <a href="/ui" class="p-3 rounded-2xl bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 text-slate-400 hover:text-indigo-600 dark:hover:text-indigo-400 transition shadow-sm group">
                <svg class="w-6 h-6 group-hover:-translate-x-1 transition-transform" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M10 19l-7-7m0 0l7-7m-7 7h18"></path></svg>
            </a>
            <h1 class="text-4xl font-black text-slate-900 dark:text-white tracking-tight font-brand">#${sessionId}</h1>
        </div>
        <div class="flex items-center gap-4">
            @if(config.get("workflows") instanceof Map)
                <select id="workflow-selector"
                        onchange="window.location.href='/ui/sessions/${sessionId}/' + this.value"
                        class="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl px-4 py-2 text-sm font-bold text-slate-700 dark:text-slate-300 outline-none focus:ring-2 focus:ring-indigo-500 shadow-sm">
                    @for(String wId : ((Map<String, Object>)config.get("workflows")).keySet())
                        @if(wId.equals(actualWorkflowId))
                            <option value="${wId}" selected>${wId}</option>
                        @else
                            <option value="${wId}">${wId}</option>
                        @endif
                    @endfor
                </select>
            @endif
            <div id="workflow-status-badge">
                @if(progress != null)
                    <span class="px-5 py-2 rounded-2xl text-xs font-black uppercase tracking-widest bg-indigo-50 dark:bg-indigo-900/30 text-indigo-700 dark:text-indigo-300 border border-indigo-100 dark:border-indigo-800 shadow-sm">${progress.status()}</span>
                @else
                    <span class="px-5 py-2 rounded-2xl text-xs font-black uppercase tracking-widest bg-slate-50 dark:bg-slate-900 text-slate-400 border border-slate-100 dark:border-slate-800 shadow-sm">IDLE</span>
                @endif
            </div>
        </div>
    </div>

    <div class="grid grid-cols-1 lg:grid-cols-12 gap-10">
        <!-- Sidebar -->
        <div class="lg:col-span-4 space-y-10">
            <section class="bg-white dark:bg-slate-900 rounded-3xl shadow-sm border border-slate-200 dark:border-slate-800 p-8">
                <h2 class="font-black text-slate-800 dark:text-white uppercase tracking-wider text-sm mb-6">System Config</h2>
                <div class="space-y-4">
                    @for(Map.Entry<String, Object> entry : config.entrySet())
                        @if(!entry.getKey().equals("workflows"))
                            <div>
                                <div class="text-[10px] font-black uppercase text-slate-400 dark:text-slate-500 mb-1 tracking-widest">${entry.getKey()}</div>
                                <div class="font-bold text-slate-700 dark:text-slate-300 text-sm break-all">${String.valueOf(entry.getValue())}</div>
                            </div>
                        @endif
                    @endfor
                </div>
            </section>
        </div>

        <!-- Main Content -->
        <div class="lg:col-span-8 space-y-10">
            <section class="bg-white dark:bg-slate-900 rounded-3xl shadow-sm border border-slate-200 dark:border-slate-800 overflow-hidden">
                <div class="px-8 py-6 border-b border-slate-50 dark:border-slate-800/50 flex justify-between items-center">
                    <h2 class="font-black text-slate-800 dark:text-white uppercase tracking-wider text-sm">Workflow Topology</h2>
                </div>
                <div class="p-8 bg-slate-50 dark:bg-slate-950 flex justify-center min-h-[300px]">
                    <svg id="workflow-dag" width="600" height="300" class="max-w-full"></svg>
                </div>
            </section>

            <section class="bg-slate-950 rounded-3xl shadow-2xl border border-slate-800 overflow-hidden flex flex-col h-[500px]">
                <div class="bg-slate-900 px-8 py-4 border-b border-white/5 flex items-center gap-4">
                    <div class="flex gap-2">
                        <div class="w-3 h-3 rounded-full bg-rose-500/80"></div>
                        <div class="w-3 h-3 rounded-full bg-amber-500/80"></div>
                        <div class="w-3 h-3 rounded-full bg-emerald-500/80"></div>
                    </div>
                    <span class="text-white/40 text-[10px] font-black uppercase tracking-widest ml-4 font-mono">Reactive Terminal</span>
                </div>
                <div id="console-output" class="p-8 font-mono text-sm text-slate-300 overflow-y-auto flex-1 leading-relaxed custom-scrollbar">
                    <div class="text-slate-600 animate-pulse">➜ Listening for session emission...</div>
                </div>
            </section>
        </div>
    </div>

    <style>
        .custom-scrollbar::-webkit-scrollbar { width: 4px; }
        .custom-scrollbar::-webkit-scrollbar-track { background: transparent; }
        .custom-scrollbar::-webkit-scrollbar-thumb { background: #334155; border-radius: 10px; }
    </style>

    <script src="https://cdn.jsdelivr.net/npm/dagre@0.8.5/dist/dagre.min.js"></script>
    <script>
        function initDag() {
            const svg = d3.select("#workflow-dag");
            const width = 600, height = 300;
            svg.selectAll("*").remove();

            const nodesData = [
                @for(WorkflowDefinition.Node node : workflow.nodes())
                    { id: "${node.nodeId()}", label: "${node.nodeId()}" },
                @endfor
            ];
            const edgesData = [
                @for(WorkflowDefinition.Edge edge : workflow.edges())
                    { source: "${edge.source()}", target: "${edge.target()}" },
                @endfor
            ];

            if (nodesData.length === 0) return;

            const g = new dagre.graphlib.Graph();
            g.setGraph({ rankdir: 'LR', nodesep: 50, ranksep: 100 });
            g.setDefaultEdgeLabel(() => ({}));

            nodesData.forEach(n => g.setNode(n.id, { width: 120, height: 40 }));
            edgesData.forEach(e => g.setEdge(e.source, e.target));
            dagre.layout(g);

            const container = svg.append("g");

            // Edges
            const line = d3.line().x(d => d.x).y(d => d.y).curve(d3.curveBasis);
            edgesData.forEach(e => {
                const edge = g.edge(e.source, e.target);
                container.append("path")
                    .attr("d", line(edge.points))
                    .attr("fill", "none")
                    .attr("stroke", "#6366f1")
                    .attr("stroke-width", 2)
                    .attr("opacity", 0.3);
            });

            // Nodes
            nodesData.forEach(n => {
                const node = g.node(n.id);
                const grp = container.append("g").attr("transform", "translate(" + (node.x - 60) + "," + (node.y - 20) + ")");

                grp.append("rect")
                    .attr("width", 120)
                    .attr("height", 40)
                    .attr("rx", 10)
                    .attr("fill", "#6366f1")
                    .attr("stroke", "white")
                    .attr("stroke-width", 2)
                    .attr("class", "dark:stroke-slate-900");

                grp.append("text")
                    .attr("x", 60)
                    .attr("y", 25)
                    .attr("text-anchor", "middle")
                    .attr("fill", "white")
                    .attr("font-size", "10px")
                    .attr("font-weight", "black")
                    .attr("class", "uppercase tracking-tighter")
                    .text(n.label);
            });

            const bbox = container.node().getBBox();
            const scale = Math.min(width / (bbox.width + 40), height / (bbox.height + 40), 1);
            const x = (width - bbox.width * scale) / 2 - bbox.x * scale;
            const y = (height - bbox.height * scale) / 2 - bbox.y * scale;
            container.attr("transform", "translate(" + x + "," + y + ") scale(" + scale + ")");
        }
        window.addEventListener('load', initDag);
        window.addEventListener('resize', initDag);

        const sessionId = "${sessionId}";
        new EventSource("/ui/api/sessions/" + sessionId + "/logs/stream").onmessage = (e) => {
            const out = document.getElementById('console-output');
            if (out.innerText.includes('Listening')) out.innerHTML = '';
            const line = document.createElement('div');
            line.className = 'mb-1 text-slate-300 flex gap-4';
            line.innerHTML = '<span class="opacity-20 w-16 shrink-0">' + new Date().toLocaleTimeString() + '</span>' +
                             '<span class="text-indigo-400">➜</span>' +
                             '<span>' + e.data + '</span>';
            out.appendChild(line);
            out.scrollTop = out.scrollHeight;
        };
    </script>
`)
