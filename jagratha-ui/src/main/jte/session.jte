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
            <div id="workflow-status-badge"
                 hx-ext="sse"
                 sse-connect="/ui/api/sessions/${sessionId}/${actualWorkflowId}/status/stream"
                 sse-swap="message">
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
            <section class="glass-panel rounded-3xl overflow-hidden"
                     x-data="dagComponent(
                        [
                            @for(WorkflowDefinition.Node node : workflow.nodes())
                                { id: '${node.nodeId()}', type: '${node.type()}', label: '${node.nodeId()}' },
                            @endfor
                        ],
                        [
                            @for(WorkflowDefinition.Edge edge : workflow.edges())
                                { source: '${edge.source()}', target: '${edge.target()}', sourcePort: '${edge.sourcePort()}' },
                            @endfor
                        ],
                        {
                            @for(var entry : pluginDetails.entrySet())
                                '${entry.getKey()}': {
                                    type: '${entry.getValue().type()}',
                                    uiDesign: (() => {
                                        const el = document.getElementById('design-${entry.getKey()}');
                                        if (!el) return null;
                                        return {
                                            html: el.innerHTML,
                                            width: parseInt(el.dataset.width),
                                            height: parseInt(el.dataset.height)
                                        };
                                    })(),
                                    outputPorts: [
                                        @for(String port : entry.getValue().outputPorts())
                                            '${port}',
                                        @endfor
                                    ]
                                },
                            @endfor
                        }
                     )">
                <div class="px-8 py-5 border-b border-white/40 flex justify-between items-center">
                    <h2 class="font-display font-bold text-text-slate-500 uppercase tracking-wider text-xs">Workflow Topology</h2>
                </div>
                <div class="p-8 flex justify-center min-h-[400px]">
                    <svg x-ref="dagSvg" width="600" height="400" class="max-w-full"></svg>
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
                <div id="console-output"
                     hx-ext="sse"
                     sse-connect="/ui/api/sessions/${sessionId}/${actualWorkflowId}/logs/stream"
                     sse-swap="message"
                     hx-swap="beforeend"
                     @htmx:after-swap.camel="$el.scrollTop = $el.scrollHeight"
                     @htmx:sse-message.camel="$el.innerHTML.includes('Listening') && ($el.innerHTML = '')"
                     class="p-8 font-mono text-sm text-slate-300 overflow-y-auto flex-1 leading-relaxed no-scrollbar">
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

`)
