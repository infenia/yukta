@import java.util.List
@import java.util.Map
@param List<Map<String, Object>> sessions

@template.layout.main(title = "Dashboard", content = @`
    <div class="flex items-center justify-between mb-6 px-1">
        <h2 class="text-sm font-display font-bold uppercase tracking-wider text-text-slate-500 dark:text-slate-400">Active Sessions</h2>
    </div>

    @if(sessions == null || sessions.isEmpty())
        <div class="glass-panel rounded-3xl p-16 text-center max-w-4xl mx-auto mt-12">
            <div class="mb-8 relative inline-block">
                <div class="absolute -inset-4 bg-primary/20 rounded-full blur-2xl animate-pulse"></div>
                <div class="w-32 h-32 mx-auto relative rounded-3xl bg-gradient-to-br from-primary to-blue-600 flex items-center justify-center text-white shadow-2xl">
                    <span class="material-symbols-outlined text-[64px]">motion_sensor_active</span>
                </div>
            </div>
            <h2 class="text-3xl font-display font-black text-text-slate-800 dark:text-white mb-4 uppercase tracking-widest">Ready for Vigilance</h2>
            <p class="text-text-slate-500 dark:text-slate-400 text-lg max-w-md mx-auto leading-relaxed">
                Jagratha is standing by. Start a session to begin monitoring your automated quality gates.
            </p>
            <div class="mt-10 flex flex-wrap justify-center gap-4">
                <code class="px-4 py-2 bg-white/50 dark:bg-slate-800/50 rounded-lg text-sm font-mono text-text-slate-500 dark:text-slate-300 border border-white/60 dark:border-slate-700">
                    ./gradlew bootRun
                </code>
            </div>
        </div>
    @else
        <div id="session-grid" class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6 auto-rows-min">
            @for(Map<String, Object> session : sessions)
                <article class="session-card glass-panel rounded-2xl p-5 flex flex-col justify-between h-[250px] hover:translate-y-[-4px] transition-all duration-300 cursor-pointer group flex-shrink-0" onclick="window.location='/ui/sessions/${(String)session.get("sessionId")}'">
                    <div class="flex justify-between items-start">
                        <div class="flex items-center gap-3 w-full">
                            <div class="w-full">
                                <h3 class="font-display font-bold text-lg leading-tight text-primary">#${(String)session.get("sessionId")}</h3>
                                <p class="mt-2 text-[12px] leading-relaxed text-text-slate-500/70 dark:text-slate-400 font-dm line-clamp-2">
                                    ${(String)session.getOrDefault("description", "No description provided.")}
                                </p>
                                <div class="mt-3 flex flex-wrap gap-2">
                                    @if(session.get("tags") instanceof Map tags)
                                        @for(Object tagValue : tags.values())
                                            <span class="px-2 py-0.5 rounded text-[10px] font-mono font-bold tracking-wider uppercase bg-primary/10 border border-primary/20 text-primary">
                                                ${String.valueOf(tagValue)}
                                            </span>
                                        @endfor
                                    @endif
                                </div>
                            </div>
                        </div>
                    </div>
                    <div class="mt-4">
                        <div class="flex justify-between mb-2 items-center">
                            <div class="flex items-center gap-2 text-text-slate-500 dark:text-slate-400 text-xs font-medium">
                                <span class="material-symbols-outlined text-[16px]">schedule</span>
                                <span>
                                    !{
                                        String initiatedTime = (String) session.get("initiatedTime");
                                        String timeAgo = "Unknown";
                                        if (initiatedTime != null && !initiatedTime.isEmpty()) {
                                            try {
                                                java.time.Instant initiatedAt = java.time.Instant.parse(initiatedTime);
                                                java.time.Duration duration = java.time.Duration.between(initiatedAt, java.time.Instant.now());
                                                if (duration.toMinutes() < 1) timeAgo = "Just now";
                                                else if (duration.toMinutes() < 60) timeAgo = duration.toMinutes() + "m ago";
                                                else if (duration.toHours() < 24) timeAgo = duration.toHours() + "h ago";
                                                else timeAgo = duration.toDays() + "d ago";
                                            } catch (Exception e) {
                                                timeAgo = "Invalid date";
                                            }
                                        }
                                    }
                                    ${timeAgo}
                                </span>
                            </div>
                            <span class="text-xs font-medium text-text-slate-500 dark:text-slate-400 truncate max-w-[120px]">
                                Initiator: ${(String)session.getOrDefault("initiator", "System")}
                            </span>
                        </div>
                    </div>
                </article>
            @endfor
        </div>
    @endif

`)
