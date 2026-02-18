@import java.util.List
@param List<String> sessions

@template.layout.main(title = "Dashboard", content = @`
    <div class="flex justify-between items-center mb-8">
        <h1 class="text-3xl font-extrabold text-slate-800">Active Sessions</h1>
        <div class="bg-indigo-100 text-indigo-700 px-4 py-1 rounded-full text-sm font-medium">
            ${sessions.size()} active
        </div>
    </div>

    @if(sessions.isEmpty())
        <div class="bg-white rounded-xl shadow-sm border border-slate-200 p-12 text-center">
            <div class="text-slate-400 mb-4">
                <svg class="w-16 h-16 mx-auto" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M9.75 17L9 20l-1 1h8l-1-1-.75-3M3 13h18M5 17h14a2 2 0 002-2V5a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z"></path>
                </svg>
            </div>
            <h2 class="text-xl font-semibold text-slate-700">No active sessions found</h2>
            <p class="text-slate-500 mt-2">Start a new session using the Jagratha CLI or API.</p>
        </div>
    @else
        <div class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
            @for(String session : sessions)
                <a href="/ui/sessions/${session}" class="block group">
                    <div class="bg-white rounded-xl shadow-sm border border-slate-200 p-6 hover:border-indigo-500 hover:shadow-md transition-all duration-200 h-full">
                        <div class="flex items-center justify-between mb-4">
                            <span class="bg-slate-100 text-slate-600 px-3 py-1 rounded-md text-xs font-mono">${session}</span>
                            <span class="text-indigo-500 group-hover:translate-x-1 transition-transform">
                                <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 5l7 7-7 7"></path></svg>
                            </span>
                        </div>
                        <h3 class="text-lg font-bold text-slate-800 mb-2 truncate">Session View</h3>
                        <p class="text-slate-500 text-sm">Monitor logs, configurations, and real-time task progress.</p>
                    </div>
                </a>
            @endfor
        </div>
    @endif
`)
