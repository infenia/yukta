@import java.util.List
@param List<String> sessions

@template.layout.main(title = "Dashboard", content = @`
    <div class="flex justify-between items-center mb-10">
        <div>
            <h1 class="text-4xl font-black text-slate-900 dark:text-white tracking-tight">Active Sessions</h1>
            <p class="text-slate-500 dark:text-slate-400 mt-1">Real-time monitoring of quality gates.</p>
        </div>
        <div class="bg-indigo-50 dark:bg-indigo-900/30 text-indigo-700 dark:text-indigo-300 px-4 py-1.5 rounded-full text-sm font-bold border border-indigo-100 dark:border-indigo-800">
            ${sessions.size()} ACTIVE
        </div>
    </div>

    @if(sessions.isEmpty())
        <div class="bg-white dark:bg-slate-900 rounded-3xl shadow-xl shadow-slate-200/50 dark:shadow-none border border-slate-200 dark:border-slate-800 p-16 text-center max-w-4xl mx-auto mt-12">
            <div class="mb-8 relative inline-block">
                <div class="absolute -inset-4 bg-indigo-500/10 dark:bg-indigo-500/20 rounded-full blur-2xl animate-pulse"></div>
                <img src="/images/icon-512x512.png" alt="Jagratha" class="w-32 h-32 mx-auto relative drop-shadow-2xl">
            </div>
            <h2 class="text-3xl font-black text-slate-800 dark:text-white mb-4 uppercase font-brand tracking-widest">Ready for Vigilance</h2>
            <p class="text-slate-500 dark:text-slate-400 text-lg max-w-md mx-auto leading-relaxed">
                Jagratha is standing by. Start a session to begin monitoring your automated quality gates.
            </p>
            <div class="mt-10 flex flex-wrap justify-center gap-4">
                <code class="px-4 py-2 bg-slate-100 dark:bg-slate-800 rounded-lg text-sm font-mono text-slate-600 dark:text-slate-300 border border-slate-200 dark:border-slate-700">
                    ./gradlew bootRun
                </code>
            </div>
        </div>
    @else
        <div class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-8">
            @for(String session : sessions)
                <a href="/ui/sessions/${session}" class="block group">
                    <div class="bg-white dark:bg-slate-900 rounded-2xl shadow-sm border border-slate-200 dark:border-slate-800 p-8 hover:border-indigo-500 dark:hover:border-indigo-400 hover:shadow-xl hover:shadow-indigo-500/10 transition-all duration-300 h-full relative overflow-hidden">
                        <div class="absolute top-0 right-0 p-4 opacity-0 group-hover:opacity-100 transition-opacity">
                             <svg class="w-6 h-6 text-indigo-500" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 7l5 5m0 0l-5 5m5-5H6"></path></svg>
                        </div>
                        <div class="flex items-center justify-between mb-6">
                            <span class="bg-slate-50 dark:bg-slate-800 text-slate-500 dark:text-slate-400 px-3 py-1 rounded-lg text-[10px] font-bold font-mono tracking-widest uppercase border border-slate-100 dark:border-slate-700">${session}</span>
                            <div class="w-2 h-2 rounded-full bg-emerald-500 animate-pulse"></div>
                        </div>
                        <h3 class="text-xl font-bold text-slate-800 dark:text-white mb-3 group-hover:text-indigo-600 dark:group-hover:text-indigo-400 transition-colors">Session Overview</h3>
                        <p class="text-slate-500 dark:text-slate-400 text-sm leading-relaxed">
                            Full audit trail of quality checks, modified files, and reactive workflow execution.
                        </p>
                        <div class="mt-8 pt-6 border-t border-slate-50 dark:border-slate-800/50 flex items-center text-xs font-bold text-indigo-600 dark:text-indigo-400 uppercase tracking-widest">
                            Monitor Session
                        </div>
                    </div>
                </a>
            @endfor
        </div>
    @endif
`)
