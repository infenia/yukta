@import java.util.List
@param List<String> nodes

@template.layout.main(title = "Control Bus", content = @`
    <div class="mb-6 flex justify-between items-end">
        <div>
            <h1 class="text-3xl font-black mb-2 text-slate-800 dark:text-slate-50">Control Bus</h1>
            <p class="text-slate-500 dark:text-slate-300 text-lg max-w-2xl leading-relaxed">
                System-wide administrative monitoring and management.
            </p>
        </div>
        <div class="flex gap-2">
            <span class="inline-flex items-center px-3 py-1 rounded-full text-xs font-medium bg-emerald-100 text-emerald-700 border border-emerald-200">
                <span class="w-2 h-2 rounded-full bg-emerald-500 mr-2 animate-pulse"></span>
                Live Monitoring Active
            </span>
        </div>
    </div>

    <div class="grid grid-cols-1 lg:grid-cols-3 gap-8">
        <!-- Active Nodes Panel -->
        <div class="lg:col-span-1 space-y-6">
            <div class="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-700 rounded-2xl overflow-hidden shadow-sm">
                <div class="p-4 border-b border-slate-100 dark:border-slate-800 bg-slate-50/50 dark:bg-slate-800/50 flex justify-between items-center">
                    <h2 class="font-bold text-slate-700 dark:text-slate-200 flex items-center gap-2">
                        <span class="material-symbols-outlined text-sm">hub</span>
                        Active Nodes
                    </h2>
                    <span class="text-xs font-mono text-slate-400">${nodes.size()} registered</span>
                </div>
                <div class="divide-y divide-slate-100 dark:divide-slate-800 max-h-[500px] overflow-y-auto">
                    @if(nodes.isEmpty())
                        <div class="p-8 text-center text-slate-400 italic text-sm">
                            No nodes registered yet
                        </div>
                    @else
                        @for(String nodeId : nodes)
                            <div class="p-4 hover:bg-slate-50 dark:hover:bg-slate-800/50 transition-colors group"
                                 id="node-${nodeId}">
                                <div class="flex items-center justify-between mb-2">
                                    <span class="font-mono text-sm font-bold text-slate-700 dark:text-slate-300 group-hover:text-primary transition-colors">
                                        ${nodeId}
                                    </span>
                                    <span class="w-2 h-2 rounded-full bg-emerald-400 shadow-[0_0_8px_rgba(52,211,153,0.5)]"></span>
                                </div>
                                <div class="flex gap-2">
                                    <button onclick="sendCommand('${nodeId}', {action: 'PING'})"
                                            class="text-[10px] bg-slate-200 dark:bg-slate-700 hover:bg-primary hover:text-white px-2 py-0.5 rounded transition-all font-bold">
                                        PING
                                    </button>
                                    <button onclick="sendCommand('${nodeId}', {action: 'RESET'})"
                                            class="text-[10px] bg-slate-200 dark:bg-slate-700 hover:bg-rose-500 hover:text-white px-2 py-0.5 rounded transition-all font-bold">
                                        RESET
                                    </button>
                                </div>
                            </div>
                        @endfor
                    @endif
                </div>
            </div>
        </div>

        <!-- Live Feed Panel -->
        <div class="lg:col-span-2 space-y-6">
            <div class="bg-slate-950 rounded-2xl overflow-hidden shadow-2xl border border-slate-800 h-[600px] flex flex-col">
                <div class="p-4 border-b border-slate-800 bg-slate-900/50 flex justify-between items-center">
                    <h2 class="font-bold text-slate-300 flex items-center gap-2 text-sm uppercase tracking-widest">
                        <span class="material-symbols-outlined text-sm text-primary">terminal</span>
                        Control Bus Live Feed
                    </h2>
                    <div class="flex gap-4 items-center">
                        <div class="flex items-center gap-2 text-[10px] font-mono text-slate-500 uppercase">
                             <span class="w-1.5 h-1.5 rounded-full bg-primary"></span> Heartbeat
                             <span class="w-1.5 h-1.5 rounded-full bg-emerald-500"></span> Stats
                             <span class="w-1.5 h-1.5 rounded-full bg-rose-500"></span> Error
                        </div>
                        <button onclick="clearLogs()" class="text-slate-500 hover:text-slate-300 transition-colors">
                            <span class="material-symbols-outlined text-sm">delete_sweep</span>
                        </button>
                    </div>
                </div>
                <div id="control-feed" class="flex-grow p-4 font-mono text-xs overflow-y-auto space-y-2 scroll-smooth"
                     hx-ext="sse" sse-connect="/api/control/stream" sse-swap="message">
                </div>
            </div>
        </div>
    </div>

    <template id="feed-item-template">
        <div class="animate-in slide-in-from-left-2 duration-300 p-2 rounded border border-white/5 bg-white/5 flex gap-3">
            <span class="text-slate-600 shrink-0">[{timestamp}]</span>
            <span class="font-bold shrink-0 w-24 truncate">{nodeId}</span>
            <span class="text-primary font-bold shrink-0">{type}</span>
            <span class="text-slate-400 italic overflow-hidden text-ellipsis whitespace-nowrap">{payload}</span>
        </div>
    </template>

    <script>
        const feed = document.getElementById('control-feed');
        const template = document.getElementById('feed-item-template');

        feed.addEventListener('htmx:sseMessage', (e) => {
            const msg = JSON.parse(e.detail.data);
            const item = template.content.cloneNode(true).firstElementChild;

            const type = msg.payload['@type'] || msg.payload.constructor.name || 'Signal';
            const shortType = type.replace('Control', '').replace('Record', '');

            const ts = new Date(msg.timestamp).toLocaleTimeString();

            item.innerHTML = item.innerHTML
                .replace('{timestamp}', ts)
                .replace('{nodeId}', msg.sourceNodeId || 'SYSTEM')
                .replace('{type}', shortType)
                .replace('{payload}', JSON.stringify(msg.payload));

            if (shortType === 'Error') {
                item.classList.add('border-rose-500/30', 'bg-rose-500/5');
                item.querySelector('.text-primary').classList.replace('text-primary', 'text-rose-500');
            } else if (shortType === 'Statistics') {
                item.classList.add('border-emerald-500/30', 'bg-emerald-500/5');
                item.querySelector('.text-primary').classList.replace('text-primary', 'text-emerald-500');
            }

            feed.appendChild(item);
            feed.scrollTop = feed.scrollHeight;

            // Keep only last 100 items
            while (feed.children.length > 100) {
                feed.removeChild(feed.firstChild);
            }
        });

        function clearLogs() {
            feed.innerHTML = '';
        }

        async function sendCommand(nodeId, payload) {
            try {
                const resp = await fetch('/api/control/nodes/' + nodeId + '/command', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(payload)
                });
                const result = await resp.json();
                console.log('Command response:', result);

                const item = template.content.cloneNode(true).firstElementChild;
                const ts = new Date().toLocaleTimeString();
                item.innerHTML = item.innerHTML
                    .replace('{timestamp}', ts)
                    .replace('{nodeId}', 'UI_CONSOLE')
                    .replace('{type}', 'Command')
                    .replace('{payload}', 'Sent ' + JSON.stringify(payload) + ' to ' + nodeId + '. Result: ' + JSON.stringify(result.data));

                item.classList.add('border-primary/30', 'bg-primary/5');
                feed.appendChild(item);
                feed.scrollTop = feed.scrollHeight;
            } catch (err) {
                console.error('Failed to send command:', err);
            }
        }
    </script>
`)
