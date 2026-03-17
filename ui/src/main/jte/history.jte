@import java.util.List
@param List<String> sessions

@template.layout.main(title = "History", content = @`
    <div class="flex items-center justify-between mb-6 px-1">
        <h2 class="text-sm font-display font-bold uppercase tracking-wider text-text-slate-500">Archived Sessions</h2>
        <div class="px-3 py-1 rounded-full bg-white/40 border border-white/60 text-[10px] font-bold text-text-slate-500">
            ${sessions.size()} ARCHIVED
        </div>
    </div>

    @if(sessions.isEmpty())
        <div class="glass-panel rounded-3xl p-16 text-center max-w-2xl mx-auto mt-12">
            <div class="text-text-slate-500/30 mb-6">
                <span class="material-symbols-outlined text-[64px]">history</span>
            </div>
            <h3 class="text-xl font-display font-bold text-text-slate-800">No history found</h3>
            <p class="text-text-slate-500 mt-2">Completed or past sessions will appear here automatically.</p>
        </div>
    @else
        <div class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6 auto-rows-min">
            @for(String session : sessions)
                <a href="/ui/sessions/${session}" class="glass-panel rounded-2xl p-5 flex flex-col justify-between h-[180px] hover:translate-y-[-4px] transition-all duration-300 group">
                    <div class="flex justify-between items-start">
                        <div>
                            <h3 class="font-display font-bold text-lg leading-tight text-primary">#${session}</h3>
                            <h4 class="mt-2 text-sm font-bold text-text-slate-800 group-hover:text-primary transition-colors">Archived Audit</h4>
                            <p class="mt-1 text-[12px] leading-relaxed text-text-slate-500/70 line-clamp-2">
                                Review historical logs and configurations from this past execution.
                            </p>
                        </div>
                        <span class="material-symbols-outlined text-text-slate-500/30 group-hover:text-primary transition-colors">arrow_forward_ios</span>
                    </div>
                    <div class="mt-4 flex items-center gap-2 text-text-slate-500 text-xs font-medium">
                        <span class="material-symbols-outlined text-[16px]">inventory_2</span>
                        <span>Archived</span>
                    </div>
                </a>
            @endfor
        </div>
    @endif
`)
