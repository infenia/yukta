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
            <a href="/ui" class="w-12 h-12 flex items-center justify-center rounded-2xl glass-panel text-text-slate-500 hover:text-primary transition-all duration-300 shadow-sm group">
                <span class="material-symbols-outlined group-hover:-translate-x-1 transition-transform">arrow_back</span>
            </a>
            <h1 class="text-4xl font-display font-bold text-text-slate-800 tracking-tight">#${sessionId}</h1>
        </div>
        <div class="flex items-center gap-4">
            @if(config.get("workflows") instanceof Map)
                <div class="glass-panel px-1">
                    <select id="workflow-selector"
                            onchange="window.location.href='/ui/sessions/${sessionId}/' + this.value"
                            class="bg-transparent border-none outline-none focus:ring-0 px-4 py-2 text-sm font-display font-bold text-text-slate-800 cursor-pointer">
                        @for(String wId : ((Map<String, Object>)config.get("workflows")).keySet())
                            @if(wId.equals(actualWorkflowId))
                                <option value="${wId}" selected class="bg-white text-text-slate-800">${wId}</option>
                            @else
                                <option value="${wId}" class="bg-white text-text-slate-800">${wId}</option>
                            @endif
                        @endfor
                    </select>
                </div>
            @endif
            <div id="workflow-status-badge">
                @if(progress != null)
                    <span class="px-5 py-2 rounded-xl text-xs font-display font-bold uppercase tracking-widest glass-panel text-primary border-primary/20">${progress.status()}</span>
                @else
                    <span class="px-5 py-2 rounded-xl text-xs font-display font-bold uppercase tracking-widest glass-panel text-text-slate-500 opacity-60">IDLE</span>
                @endif
            </div>
        </div>
    </div>

    <div class="grid grid-cols-1 lg:grid-cols-12 gap-8">
        <!-- Sidebar -->
        <div class="lg:col-span-4 space-y-8">
            <section class="glass-panel rounded-3xl p-8">
                <h2 class="font-display font-bold text-text-slate-500 uppercase tracking-wider text-xs mb-6">System Config</h2>
                <div class="space-y-6">
                    @for(Map.Entry<String, Object> entry : config.entrySet())
                        @if(!entry.getKey().equals("workflows"))
                            <div>
                                <div class="text-[10px] font-mono font-bold uppercase text-text-slate-500/60 mb-1 tracking-widest">${entry.getKey()}</div>
                                <div class="font-display font-medium text-text-slate-800 text-sm break-all">${String.valueOf(entry.getValue())}</div>
                            </div>
                        @endif
                    @endfor
                </div>
            </section>
        </div>

        <!-- Main Content -->
        <div class="lg:col-span-8 space-y-8">
            <section class="glass-panel rounded-3xl overflow-hidden">
                <div class="px-8 py-5 border-b border-white/40 flex justify-between items-center">
                    <h2 class="font-display font-bold text-text-slate-500 uppercase tracking-wider text-xs">Workflow Topology</h2>
                </div>
                <div class="p-8 flex justify-center min-h-[400px]">
                    <svg id="workflow-dag" width="600" height="400" class="max-w-full"></svg>
                </div>
            </section>

            <section class="bg-[#0f172a] rounded-3xl shadow-2xl border border-white/5 overflow-hidden flex flex-col h-[500px]">
                <div class="bg-white/5 px-8 py-4 border-b border-white/5 flex items-center gap-4">
                    <div class="flex gap-2">
                        <div class="w-2.5 h-2.5 rounded-full bg-rose-500/80 shadow-[0_0_8px_rgba(244,63,94,0.4)]"></div>
                        <div class="w-2.5 h-2.5 rounded-full bg-amber-500/80 shadow-[0_0_8px_rgba(245,158,11,0.4)]"></div>
                        <div class="w-2.5 h-2.5 rounded-full bg-emerald-500/80 shadow-[0_0_8px_rgba(16,185,129,0.4)]"></div>
                    </div>
                    <span class="text-white/40 text-[10px] font-mono font-bold uppercase tracking-widest ml-4">Reactive Terminal</span>
                </div>
                <div id="console-output" class="p-8 font-mono text-sm text-slate-300 overflow-y-auto flex-1 leading-relaxed no-scrollbar">
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
                    .attr("stroke", "#3c83f6")
                    .attr("stroke-width", 2)
                    .attr("opacity", 0.2);
            });

            // Nodes
            nodesData.forEach(n => {
                const node = g.node(n.id);
                const grp = container.append("g").attr("transform", "translate(" + (node.x - 60) + "," + (node.y - 20) + ")");

                grp.append("rect")
                    .attr("width", 120)
                    .attr("height", 40)
                    .attr("rx", 12)
                    .attr("fill", "#3c83f6")
                    .attr("stroke", "white")
                    .attr("stroke-width", 2)
                    .attr("class", "shadow-lg shadow-blue-500/20");

                grp.append("text")
                    .attr("x", 60)
                    .attr("y", 24)
                    .attr("text-anchor", "middle")
                    .attr("fill", "white")
                    .attr("font-family", "Space Grotesk")
                    .attr("font-size", "10px")
                    .attr("font-weight", "700")
                    .attr("class", "uppercase tracking-widest")
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
        const workflowId = "${actualWorkflowId}";
        new EventSource("/ui/api/session/" + sessionId + "/" + workflowId + "/logs/stream").onmessage = (e) => {
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
