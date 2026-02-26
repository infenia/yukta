@import java.util.List
@import java.util.Map
@param List<Map<String, Object>> sessions

@template.layout.main(title = "Dashboard", content = @`
    <div class="mb-10">
        <h1 class="text-4xl font-heading font-black tracking-tight mb-3 text-slate-800 dark:text-slate-50">Active Sessions</h1>
        <p class="text-slate-500 dark:text-slate-300 max-w-2xl text-lg leading-relaxed">
            Manage and monitor your current engineering sessions and AI agent workflows.
        </p>
    </div>

    <div class="mb-12 max-w-xl" x-data="sessionSearch">
        <div class="relative group">
            <div class="absolute inset-y-0 left-0 pl-4 flex items-center pointer-events-none">
                <span class="material-symbols-outlined text-slate-400 group-focus-within:text-primary transition-colors">search</span>
            </div>
            <input x-model="query" @input="filterSessions"
                   class="block w-full pl-12 pr-4 py-3 bg-white/50 dark:bg-slate-800/40 border border-slate-200 dark:border-slate-700 backdrop-blur-md rounded-xl text-slate-800 dark:text-white placeholder-slate-400 focus:outline-none focus:ring-2 focus:ring-primary/40 focus:border-primary/60 transition-all shadow-xl"
                   placeholder="Search sessions by ID, name, or initiator..." type="text"/>
        </div>
    </div>

    @if(sessions == null || sessions.isEmpty())
        <div class="text-center py-24 bg-white/30 dark:bg-slate-800/20 rounded-3xl border border-dashed border-slate-200 dark:border-slate-700 backdrop-blur-sm">
            <div class="w-20 h-20 mx-auto bg-slate-100 dark:bg-slate-800 rounded-full flex items-center justify-center text-slate-400 dark:text-slate-600 mb-6">
                <span class="material-symbols-outlined text-4xl">sensors_off</span>
            </div>
            <h3 class="text-xl font-heading font-bold text-slate-800 dark:text-white mb-2 uppercase tracking-widest">Ready for Vigilance</h3>
            <p class="text-slate-500 dark:text-slate-400 max-w-sm mx-auto">
                No active sessions found. Start an agent or a workflow to begin monitoring.
            </p>
        </div>
    @else
        <div id="session-grid" class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-8">
            @for(Map<String, Object> session : sessions)
                <div class="session-card bg-white dark:bg-slate-800/40 border border-slate-200 dark:border-slate-700 p-7 rounded-2xl shadow-sm hover:shadow-xl dark:shadow-2xl hover:border-primary/60 transition-all group backdrop-blur-sm cursor-pointer flex flex-col justify-between h-full"
                     onclick="window.location='/ui/sessions/${(String)session.get("sessionId")}'">
                    <div>
                        <div class="flex justify-between items-start mb-5">
                            <span class="text-[10px] font-mono font-bold text-primary px-3 py-1 bg-primary/10 rounded tracking-widest uppercase shadow-sm">
                                #${(String)session.get("sessionId")}
                            </span>
                        </div>
                        <h3 class="font-heading font-bold text-xl text-slate-800 dark:text-white line-clamp-2 h-[3.5rem] overflow-hidden mb-6 group-hover:text-primary transition-colors">
                            ${(String)session.getOrDefault("description", "No description provided.")}
                        </h3>
                        <div class="flex flex-wrap gap-2 mb-6">
                            @if(session.get("tags") instanceof Map tags)
                                @for(Object tagValue : tags.values())
                                    <span class="text-[10px] font-bold px-2.5 py-1 bg-slate-100 dark:bg-slate-700/50 border border-slate-200 dark:border-slate-600 rounded uppercase text-slate-600 dark:text-white">
                                        ${String.valueOf(tagValue)}
                                    </span>
                                @endfor
                            @endif
                        </div>
                    </div>
                    <div class="flex items-center justify-between mt-auto pt-6 border-t border-slate-100 dark:border-slate-700/50">
                        <div class="flex items-center gap-2">
                            <span class="text-[10px] font-bold text-slate-400 uppercase tracking-tight">Initiator:</span>
                            <span class="text-[11px] font-semibold text-slate-800 dark:text-slate-200">${(String)session.getOrDefault("initiator", "System")}</span>
                        </div>
                        <span class="text-[10px] font-bold text-slate-400 uppercase tracking-tighter">
                            !{
                                String initiatedTime = (String) session.get("initiatedTime");
                                String formattedTime = "Unknown";
                                if (initiatedTime != null && !initiatedTime.isEmpty()) {
                                    try {
                                        java.time.Instant initiatedAt = java.time.Instant.parse(initiatedTime);
                                        java.time.ZonedDateTime zdt = initiatedAt.atZone(java.time.ZoneId.of("UTC"));
                                        java.time.format.DateTimeFormatter formatter = java.time.format.DateTimeFormatter.ofPattern("h:mm a");
                                        formattedTime = zdt.format(formatter);
                                    } catch (Exception e) {
                                        formattedTime = "Invalid date";
                                    }
                                }
                            }
                            Initiated ${formattedTime}
                        </span>
                    </div>
                </div>
            @endfor
        </div>
    @endif
`)
