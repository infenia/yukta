@import java.util.Map
@import java.util.List
@import com.infenia.yukta.model.WorkflowProgress
@import com.infenia.yukta.model.PluginDetails
@import com.infenia.yukta.model.WorkflowDefinition

@param String sessionId
@param String actualWorkflowId
@param Map<String, Object> config
@param List<String> logs
@param Map<String, Object> workflows
@param Map<String, PluginDetails> pluginDetails
@param WorkflowProgress progress

@template.layout.main(title = "Session " + sessionId, content = @`
    <!-- Breadcrumbs & Title Area -->
    <div class="mb-4 flex flex-col md:flex-row md:items-end justify-between gap-4">
        <div>
            <nav class="flex items-center gap-2 text-sm text-slate-500 mb-2">
                <a class="hover:text-primary transition-colors" href="/ui">Sessions</a>
                <span class="material-symbols-outlined text-xs">chevron_right</span>
                <span class="text-slate-900 dark:text-slate-100 font-bold">${sessionId}</span>
            </nav>
            <h2 class="text-3xl font-black tracking-tight text-slate-900 dark:text-white">
                ${(String) config.getOrDefault("description", "Session " + sessionId)}
            </h2>
            <p class="text-slate-500 dark:text-slate-400 mt-1">
                Session initiated by ${(String) config.getOrDefault("initiator", "System")}
            </p>
        </div>
    </div>

    <!-- Search -->
    <div class="mb-8 max-w-md">
        <div class="glass flex items-center gap-3 px-4 py-3 rounded-xl border border-white/10 group focus-within:border-primary/50 transition-all">
            <span class="material-symbols-outlined text-slate-500 group-focus-within:text-primary transition-colors">search</span>
            <input id="workflow-search"
                   class="bg-transparent border-none outline-none focus:ring-0 p-0 text-sm text-slate-900 dark:text-slate-100 placeholder:text-slate-400 dark:placeholder:text-slate-500 w-full"
                   placeholder="Search workflows..."
                   type="text"/>
        </div>
    </div>

    <div class="flex flex-col lg:flex-row gap-8">
        <!-- Left Area: Workflow Cards -->
        <div class="flex-1">
            <div class="flex items-center justify-between mb-6">
                <h3 class="text-xl font-bold flex items-center gap-2">
                    <span class="material-symbols-outlined text-primary">account_tree</span>
                    Active Workflows
                </h3>
            </div>

            @if(workflows != null && !workflows.isEmpty())
                <div class="grid grid-cols-1 md:grid-cols-2 gap-6" id="workflow-grid">
                    @for(String workflowId : workflows.keySet())
                        !{
                            String workflowStatus = "IDLE";
                            String statusColor = "bg-slate-400";
                            String pulseColor = "bg-slate-400";
                            if (progress != null && progress.workflowId().equals(workflowId)) {
                                workflowStatus = progress.status();
                                if ("RUNNING".equalsIgnoreCase(workflowStatus)) {
                                    statusColor = "bg-blue-400";
                                    pulseColor = "bg-blue-500";
                                } else if ("SUCCESS".equalsIgnoreCase(workflowStatus)) {
                                    statusColor = "bg-green-400";
                                    pulseColor = "bg-green-500";
                                } else if ("FAILURE".equalsIgnoreCase(workflowStatus) || "ERROR".equalsIgnoreCase(workflowStatus)) {
                                    statusColor = "bg-red-400";
                                    pulseColor = "bg-red-500";
                                }
                            }
                        }
                        <div class="workflow-card glass p-6 rounded-xl flex flex-col gap-4 cursor-pointer hover:border-primary/50 transition-all"
                             onclick="window.location='/ui/sessions/${sessionId}/workflow/${workflowId}'">
                            <div class="flex items-start justify-between">
                                <div class="flex items-center gap-3">
                                    <div>
                                        <h4 class="font-bold text-lg text-slate-900 dark:text-white">${workflowId}</h4>
                                        <span class="text-xs text-slate-500 dark:text-slate-400">${workflowStatus}</span>
                                    </div>
                                </div>
                                <span class="flex h-2 w-2 rounded-full ${pulseColor} animate-pulse"></span>
                            </div>
                            <p class="text-sm text-slate-500 dark:text-slate-400 leading-relaxed">
                                !{
                                    Object workflowObj = workflows.get(workflowId);
                                    String description = "Workflow execution and monitoring";
                                    if (workflowObj instanceof Map workflowMap) {
                                        Object desc = workflowMap.get("description");
                                        if (desc != null) {
                                            description = String.valueOf(desc);
                                        }
                                    }
                                }
                                ${description}
                            </p>
                            <div class="flex flex-wrap gap-2 mt-2">
                                @if(pluginDetails != null && !pluginDetails.isEmpty())
                                    @for(var entry : pluginDetails.entrySet())
                                        <div class="px-4 py-1.5 rounded-full bg-blue-50 dark:bg-blue-900/10 border border-blue-100 dark:border-blue-800/50 text-[11px] font-semibold text-blue-600 dark:text-blue-400">
                                            ${entry.getValue().type()}
                                        </div>
                                    @endfor
                                @endif
                            </div>
                        </div>
                    @endfor
                </div>
            @else
                <div class="text-center py-12 bg-slate-50 dark:bg-slate-900/20 rounded-3xl border border-dashed border-slate-200 dark:border-slate-700">
                    <span class="material-symbols-outlined text-4xl text-slate-400 mx-auto block mb-3">account_tree</span>
                    <p class="text-slate-500 dark:text-slate-400">No workflows found for this session</p>
                </div>
            @endif
        </div>

        <!-- Right Sidebar: Session Context -->
        <aside class="w-full lg:w-[320px] shrink-0">
            <div class="glass p-6 rounded-xl sticky top-28 shadow-xl shadow-slate-200/50 dark:shadow-blue-500/5 bg-white dark:bg-transparent">
                <h3 class="text-lg font-bold mb-6 flex items-center gap-2 text-slate-900 dark:text-white">
                    <span class="material-symbols-outlined text-primary">info</span>
                    Session Context
                </h3>

                <div class="space-y-6">
                    <!-- Session ID -->
                    <div class="flex flex-col gap-1">
                        <label class="text-xs font-bold uppercase tracking-widest text-slate-500 dark:text-slate-400">SESSION ID</label>
                        <div class="flex items-center justify-between bg-blue-50 dark:bg-primary/5 p-3 rounded-lg border border-blue-100 dark:border-primary/10">
                            <code class="text-sm font-bold text-primary">#${sessionId}</code>
                            <button onclick="navigator.clipboard.writeText('#${sessionId}')"
                                    class="material-symbols-outlined text-lg text-slate-500 hover:text-primary transition-colors">content_copy</button>
                        </div>
                    </div>

                    <!-- Description -->
                    <div class="flex flex-col gap-1">
                        <label class="text-xs font-bold uppercase tracking-widest text-slate-500 dark:text-slate-400">DESCRIPTION</label>
                        <div class="bg-blue-50 dark:bg-white/5 p-3 rounded-lg border border-blue-100 dark:border-white/10">
                            <p class="text-sm text-slate-600 dark:text-slate-300 leading-relaxed font-medium">
                                ${(String) config.getOrDefault("description", "Session monitoring and workflow tracking")}
                            </p>
                        </div>
                    </div>

                    <!-- Initiator -->
                    <div class="flex flex-col gap-2">
                        <label class="text-xs font-bold uppercase tracking-widest text-slate-500 dark:text-slate-400">INITIATED BY</label>
                        <div class="flex items-center">
                            <p class="text-sm font-bold text-slate-900 dark:text-white">
                                ${(String) config.getOrDefault("initiator", "System")}
                            </p>
                        </div>
                    </div>

                    <!-- Timestamp -->
                    <div class="flex flex-col gap-2">
                        <label class="text-xs font-bold uppercase tracking-widest text-slate-500 dark:text-slate-400">STARTED</label>
                        <div class="space-y-3">
                            !{
                                String sessionInitTime = (String) config.getOrDefault("initiatedTime", "");
                                String dateStr = "Unknown";
                                String timeStr = "Unknown";
                                if (sessionInitTime != null && !sessionInitTime.isEmpty()) {
                                    try {
                                        java.time.Instant instant = java.time.Instant.parse(sessionInitTime);
                                        java.time.ZonedDateTime zdt = instant.atZone(java.time.ZoneId.systemDefault());
                                        java.time.format.DateTimeFormatter dateFormatter = java.time.format.DateTimeFormatter.ofPattern("MMM dd, yyyy");
                                        dateStr = zdt.format(dateFormatter);
                                        java.time.format.DateTimeFormatter timeFormatter = java.time.format.DateTimeFormatter.ofPattern("h:mm a");
                                        timeStr = zdt.format(timeFormatter);
                                    } catch (Exception e) {
                                        dateStr = "Invalid date";
                                        timeStr = "Invalid time";
                                    }
                                }
                            }
                            <div class="flex items-center gap-3 text-sm text-slate-600 dark:text-slate-400">
                                <span class="material-symbols-outlined text-slate-400 text-lg">calendar_today</span>
                                <span>${dateStr}</span>
                            </div>
                            <div class="flex items-center gap-3 text-sm text-slate-600 dark:text-slate-400">
                                <span class="material-symbols-outlined text-slate-400 text-lg">schedule</span>
                                <span>${timeStr}</span>
                            </div>
                        </div>
                    </div>
                </div>
            </div>
        </aside>
    </div>

    <style>
        .glass {
            background: rgba(255, 255, 255, 0.8);
            backdrop-filter: blur(12px);
            -webkit-backdrop-filter: blur(12px);
            border: 1px solid rgba(15, 23, 42, 0.1);
        }
        .dark .glass {
            background: rgba(15, 23, 42, 0.4);
            backdrop-filter: blur(12px);
            -webkit-backdrop-filter: blur(12px);
            border: 1px solid rgba(255, 255, 255, 0.08);
        }

        .workflow-card {
            background: #FFFFFF;
            border: 1px solid rgba(15, 23, 42, 0.08);
            transition: all 0.3s ease;
        }
        .dark .workflow-card {
            background: rgba(15, 23, 42, 0.6);
            border: 1px solid rgba(255, 255, 255, 0.05);
        }
        .workflow-card:hover {
            border-color: rgba(59, 130, 246, 0.4);
            box-shadow: 0 10px 15px -3px rgba(0, 0, 0, 0.05);
        }
        .dark .workflow-card:hover {
            border-color: rgba(59, 130, 246, 0.5);
            background: rgba(15, 23, 42, 0.8);
        }
    </style>

    <script>
        // Workflow search functionality
        document.getElementById('workflow-search').addEventListener('input', e => {
            const term = e.target.value.toLowerCase();
            document.querySelectorAll('#workflow-grid > div').forEach(card => {
                card.style.display = card.textContent.toLowerCase().includes(term) ? '' : 'none';
            });
        });
    </script>
`)
