@import com.infenia.jagratha.model.WorkflowProgress
@import com.infenia.jagratha.model.TaskProgress
@import com.infenia.jagratha.model.WorkflowDefinition
@import com.infenia.jagratha.model.PluginDetails
@import java.util.Map
@import java.util.List
@param String sessionId
@param String actualWorkflowId
@param Map<String, Object> config
@param WorkflowDefinition workflow
@param Map<String, PluginDetails> pluginDetails
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

    <div id="plugin-designs" class="hidden">
        @for(var entry : pluginDetails.entrySet())
            @if(entry.getValue().uiDesign() != null)
                <div id="design-${entry.getKey()}"
                     data-width="${entry.getValue().uiDesign().width()}"
                     data-height="${entry.getValue().uiDesign().height()}">
                    $unsafe{entry.getValue().uiDesign().html()}
                </div>
            @endif
        @endfor
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
                    { id: "${node.nodeId()}", type: "${node.type()}", label: "${node.nodeId()}" },
                @endfor
            ];
            const edgesData = [
                @for(WorkflowDefinition.Edge edge : workflow.edges())
                    { source: "${edge.source()}", target: "${edge.target()}", sourcePort: "${edge.sourcePort()}" },
                @endfor
            ];

            const pluginDetails = {
                @for(var entry : pluginDetails.entrySet())
                    "${entry.getKey()}": {
                        type: "${entry.getValue().type()}",
                        uiDesign: (() => {
                            const el = document.getElementById("design-${entry.getKey()}");
                            if (!el) return null;
                            return {
                                html: el.innerHTML,
                                width: parseInt(el.dataset.width),
                                height: parseInt(el.dataset.height)
                            };
                        })(),
                        outputPorts: [
                            @for(String port : entry.getValue().outputPorts())
                                "${port}",
                            @endfor
                        ]
                    },
                @endfor
            };

            if (nodesData.length === 0) return;

            const g = new dagre.graphlib.Graph();
            g.setGraph({ rankdir: 'LR', nodesep: 60, ranksep: 120 });
            g.setDefaultEdgeLabel(() => ({}));

            nodesData.forEach(n => {
                const details = pluginDetails[n.type];
                const width = details?.uiDesign?.width || 120;
                const height = details?.uiDesign?.height || 40;
                g.setNode(n.id, { width, height });
            });
            edgesData.forEach(e => g.setEdge(e.source, e.target));
            dagre.layout(g);

            const container = svg.append("g");
            const line = d3.line().x(d => d.x).y(d => d.y).curve(d3.curveBasis);

            // Nodes
            nodesData.forEach(n => {
                const node = g.node(n.id);
                const details = pluginDetails[n.type];
                const design = details?.uiDesign;

                const grp = container.append("g")
                    .attr("transform", "translate(" + (node.x - node.width/2) + "," + (node.y - node.height/2) + ")")
                    .attr("data-node-id", n.id);

                if (design) {
                    // Base shell
                    grp.append("rect")
                        .attr("width", node.width)
                        .attr("height", node.height)
                        .attr("rx", 16)
                        .attr("fill", "white")
                        .attr("stroke", "#e2e8f0")
                        .attr("stroke-width", 1)
                        .attr("class", "shadow-sm");

                    // Inject HTML with extra space for ports
                    const foPadding = 40;
                    const fo = grp.append("foreignObject")
                        .attr("x", -foPadding/2)
                        .attr("y", -foPadding/2)
                        .attr("width", node.width + foPadding)
                        .attr("height", node.height + foPadding);

                    let html = design.html;
                    html = html.replace(/{{nodeId}}/g, n.id);
                    html = html.replace(/{{type}}/g, n.type);

                    fo.append("xhtml:div")
                        .attr("style", "width:100%; height:100%; pointer-events:none; display: flex; align-items: center; justify-content: center;")
                        .html('<div style="width:' + node.width + 'px; height:' + node.height + 'px; position: relative; overflow: visible;">' + html + '</div>');
                } else {
                    grp.append("rect")
                        .attr("width", node.width)
                        .attr("height", node.height)
                        .attr("rx", 12)
                        .attr("fill", "#3c83f6")
                        .attr("stroke", "white")
                        .attr("stroke-width", 2)
                        .attr("class", "shadow-lg shadow-blue-500/20");

                    grp.append("text")
                        .attr("x", node.width/2)
                        .attr("y", node.height/2 + 4)
                        .attr("text-anchor", "middle")
                        .attr("fill", "white")
                        .attr("font-family", "Space Grotesk")
                        .attr("font-size", "10px")
                        .attr("font-weight", "700")
                        .attr("class", "uppercase tracking-widest")
                        .text(n.label);
                }
            });

            const bbox = container.node().getBBox();
            const scale = Math.min(width / (bbox.width + 80), height / (bbox.height + 80), 1);
            const x = (width - bbox.width * scale) / 2 - bbox.x * scale;
            const y = (height - bbox.height * scale) / 2 - bbox.y * scale;
            container.attr("transform", "translate(" + x + "," + y + ") scale(" + scale + ")");

            // Edges (Rendered after nodes to allow port anchoring)
            edgesData.forEach(e => {
                const edge = g.edge(e.source, e.target);
                const sourceNode = g.node(e.source);
                const targetNode = g.node(e.target);

                let points = [...edge.points];

                if (e.sourcePort && e.sourcePort !== "null") {
                    const nodeGrp = document.querySelector('[data-node-id="' + e.source + '"]');
                    const portEl = nodeGrp?.querySelector('.jagratha-port[data-port-name="' + e.sourcePort + '"]');

                    if (portEl && nodeGrp) {
                        const nodeRect = nodeGrp.getBoundingClientRect();
                        const portRect = portEl.getBoundingClientRect();

                        const relX = (portRect.left + portRect.width/2 - nodeRect.left) / scale;
                        const relY = (portRect.top + portRect.height/2 - nodeRect.top) / scale;

                        points[0] = {
                            x: (sourceNode.x - sourceNode.width/2) + relX,
                            y: (sourceNode.y - sourceNode.height/2) + relY
                        };
                    }
                }

                container.append("path")
                    .attr("d", line(points))
                    .attr("fill", "none")
                    .attr("stroke", "#3c83f6")
                    .attr("stroke-width", 2)
                    .attr("opacity", 0.6)
                    .attr("marker-end", "url(#arrowhead)");
            });

            // Arrowhead marker
            svg.append("defs").append("marker")
                .attr("id", "arrowhead")
                .attr("viewBox", "0 0 10 10")
                .attr("refX", 9)
                .attr("refY", 5)
                .attr("markerWidth", 6)
                .attr("markerHeight", 6)
                .attr("orient", "auto")
                .append("path")
                .attr("d", "M 0 0 L 10 5 L 0 10 z")
                .attr("fill", "#3c83f6")
                .attr("opacity", 0.2);
        }
        window.addEventListener('load', initDag);
        window.addEventListener('resize', initDag);

        const sessionId = "${sessionId}";
        const workflowId = "${actualWorkflowId}";
        new EventSource("/ui/api/sessions/" + sessionId + "/" + workflowId + "/logs/stream").onmessage = (e) => {
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
