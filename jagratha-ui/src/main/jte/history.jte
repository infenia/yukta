@import java.util.List
@param List<String> sessions

@template.layout.main(title = "History", content = @`
    <div class="flex justify-between items-center mb-8">
        <h1 class="text-3xl font-extrabold text-slate-800">History Sessions</h1>
        <div class="bg-slate-100 text-slate-700 px-4 py-1 rounded-full text-sm font-medium">
            ${sessions.size()} archived
        </div>
    </div>

    @if(sessions.isEmpty())
        <div class="bg-white rounded-xl shadow-sm border border-slate-200 p-12 text-center">
            <div class="text-slate-400 mb-4">
                <svg class="w-16 h-16 mx-auto" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z"></path>
                </svg>
            </div>
            <h2 class="text-xl font-semibold text-slate-700">No history found</h2>
            <p class="text-slate-500 mt-2">Completed or past sessions will appear here.</p>
        </div>
    @else
        <div class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
            @for(String session : sessions)
                <a href="/ui/sessions/${session}" class="block group">
                    <div class="bg-white rounded-xl shadow-sm border border-slate-200 p-6 hover:border-slate-400 hover:shadow-md transition-all duration-200 h-full">
                        <div class="flex items-center justify-between mb-4">
                            <span class="bg-slate-100 text-slate-600 px-3 py-1 rounded-md text-xs font-mono">${session}</span>
                            <span class="text-slate-400 group-hover:translate-x-1 transition-transform">
                                <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 5l7 7-7 7"></path></svg>
                            </span>
                        </div>
                        <h3 class="text-lg font-bold text-slate-800 mb-2 truncate">Archived Session</h3>
                        <p class="text-slate-500 text-sm">Review historical logs and configurations from this session.</p>
                    </div>
                </a>
            @endfor
        </div>
    @endif
`)
