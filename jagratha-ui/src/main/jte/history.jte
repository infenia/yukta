@import java.util.List
@param List<String> sessions

@template.layout.main(title = "History", content = @`
    <div class="flex justify-between items-center mb-10">
        <div>
            <h1 class="text-4xl font-black text-slate-900 dark:text-white tracking-tight">Archived Sessions</h1>
            <p class="text-slate-500 dark:text-slate-400 mt-1">Review past quality gate results.</p>
        </div>
        <div class="bg-slate-100 dark:bg-slate-800 text-slate-700 dark:text-slate-300 px-4 py-1.5 rounded-full text-sm font-bold border border-slate-200 dark:border-slate-700">
            ${sessions.size()} ARCHIVED
        </div>
    </div>

    @if(sessions.isEmpty())
        <div class="bg-white dark:bg-slate-900 rounded-3xl shadow-sm border border-slate-200 dark:border-slate-800 p-16 text-center max-w-4xl mx-auto mt-12">
            <div class="text-slate-300 dark:text-slate-700 mb-6">
                <svg class="w-16 h-16 mx-auto" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z"></path>
                </svg>
            </div>
            <h2 class="text-2xl font-bold text-slate-700 dark:text-slate-300">No history found</h2>
            <p class="text-slate-500 dark:text-slate-400 mt-2">Completed or past sessions will appear here automatically.</p>
        </div>
    @else
        <div class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-8">
            @for(String session : sessions)
                <a href="/ui/sessions/${session}" class="block group">
                    <div class="bg-white dark:bg-slate-900 rounded-2xl shadow-sm border border-slate-200 dark:border-slate-800 p-8 hover:border-slate-400 dark:hover:border-slate-600 hover:shadow-lg transition-all duration-300 h-full">
                        <div class="flex items-center justify-between mb-6">
                            <span class="bg-slate-50 dark:bg-slate-800 text-slate-500 dark:text-slate-400 px-3 py-1 rounded-lg text-[10px] font-bold font-mono tracking-widest uppercase border border-slate-100 dark:border-slate-700">${session}</span>
                            <span class="text-slate-300 dark:text-slate-600 group-hover:translate-x-1 transition-transform">
                                <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 5l7 7-7 7"></path></svg>
                            </span>
                        </div>
                        <h3 class="text-xl font-bold text-slate-800 dark:text-white mb-3 group-hover:text-indigo-600 dark:group-hover:text-indigo-400 transition-colors">Archived Audit</h3>
                        <p class="text-slate-500 dark:text-slate-400 text-sm leading-relaxed">
                            Review historical logs and configurations from this past execution.
                        </p>
                    </div>
                </a>
            @endfor
        </div>
    @endif
`)
