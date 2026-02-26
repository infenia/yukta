@import gg.jte.Content
@param String title
@param Content content

<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>${title} - Jagratha</title>
    <link rel="icon" type="image/png" href="/images/favicon-32x32.png">
    <link rel="stylesheet" href="/css/style.css">
    <link rel="preconnect" href="https://fonts.googleapis.com">
    <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
    <link href="https://fonts.googleapis.com/css2?family=DM+Sans:opsz,wght@9..40,400;500;700&family=JetBrains+Mono:wght@400;500&family=Space+Grotesk:wght@300;400;500;600;700&display=swap" rel="stylesheet"/>
    <link href="https://fonts.googleapis.com/css2?family=Material+Symbols+Outlined:wght,FILL@100..700,0..1&display=swap" rel="stylesheet"/>

    <style>[x-cloak] { display: none !important; }</style>
    <script src="https://unpkg.com/htmx.org@2.0.4"></script>
    <script src="https://unpkg.com/htmx-ext-sse@2.2.2"></script>
    <script src="/js/app.js" defer></script>
    <script>
        (function() {
            const theme = localStorage.getItem('theme');
            if (theme === 'dark' || (!theme && window.matchMedia('(prefers-color-scheme: dark)').matches)) {
                document.documentElement.classList.add('dark');
            } else {
                document.documentElement.classList.remove('dark');
            }
        })();
    </script>
</head>
<body class="bg-background-light dark:bg-background-dark text-text-slate-800 dark:text-slate-200 font-body antialiased overflow-hidden h-screen w-screen selection:bg-primary/20 transition-colors duration-300" x-data="themeManager" x-cloak>
    <div class="fixed inset-0 w-full h-full pointer-events-none z-0 overflow-hidden">
        <div class="absolute inset-0 bg-background-light dark:bg-background-dark"></div>
        <div class="absolute inset-0 opacity-30" style="background-image: radial-gradient(at 0% 0%, rgba(59, 130, 246, 0.15) 0px, transparent 50%), radial-gradient(at 100% 0%, rgba(147, 51, 234, 0.1) 0px, transparent 50%), radial-gradient(at 100% 100%, rgba(59, 130, 246, 0.15) 0px, transparent 50%), radial-gradient(at 0% 100%, rgba(147, 51, 234, 0.1) 0px, transparent 50%);"></div>
    </div>

    <div class="relative z-10 flex flex-col h-full w-full max-w-[1440px] mx-auto p-4 md:p-8">
        <header class="flex items-center justify-between mb-8 md:mb-12 shrink-0 bg-transparent border-none shadow-none">
            <div class="flex items-center gap-3">
                <a href="/ui" class="flex items-center gap-3 group">
                    <div class="w-10 h-10 rounded-xl bg-gradient-to-br from-primary to-blue-600 flex items-center justify-center text-white shadow-lg shadow-blue-500/20" style="background: linear-gradient(135deg, #3c83f6 0%, #2563eb 100%);">
                        <span class="material-symbols-outlined text-[24px]">motion_sensor_active</span>
                    </div>
                    <h1 class="text-2xl md:text-3xl font-display font-bold tracking-tight text-text-slate-800 dark:text-white">Jagratha</h1>
                </a>
            </div>

            <div class="flex items-center gap-6">
                <nav class="hidden lg:flex items-center gap-6 mr-4 text-sm font-medium">
                    <a href="/ui" class="text-text-slate-500 dark:text-slate-400 hover:text-primary transition-colors">Dashboard</a>
                    <a href="/ui/history" class="text-text-slate-500 dark:text-slate-400 hover:text-primary transition-colors">History</a>
                    <a href="/swagger-ui.html" class="text-text-slate-500 dark:text-slate-400 hover:text-primary transition-colors">API</a>
                </nav>

                <div class="hidden md:flex items-center h-12 px-4 rounded-full glass-panel min-w-[320px] group transition-all duration-300 focus-within:ring-2 focus-within:ring-primary/50" x-data="sessionSearch">
                    <span class="material-symbols-outlined text-text-slate-500 text-[20px]">search</span>
                    <input x-ref="searchInput" x-model="query" @input="filterSessions" class="bg-transparent border-none outline-none focus:ring-0 w-full text-sm font-body text-text-slate-800 dark:text-slate-200 placeholder:text-text-slate-500/70 ml-2" placeholder="Search sessions..." type="text"/>
                    <div class="flex items-center gap-1 px-1.5 py-0.5 rounded-md bg-white/50 dark:bg-slate-800/50 text-[10px] font-mono font-medium text-text-slate-500 border border-white/60 dark:border-slate-700">
                        ⌘K
                    </div>
                </div>

                <div class="relative">
                    <button @click="themeDropdownOpen = !themeDropdownOpen" class="w-10 h-10 rounded-full glass-panel text-text-slate-500 hover:text-primary transition-colors flex items-center justify-center border border-white/50 dark:border-slate-700 hover:border-primary">
                        <span class="material-symbols-outlined text-[20px]" x-text="currentIcon">light_mode</span>
                    </button>

                    <div x-show="themeDropdownOpen" @click.away="themeDropdownOpen = false" x-transition class="absolute right-0 top-full mt-2 w-48 bg-white dark:bg-slate-800 rounded-lg shadow-lg border border-white/20 dark:border-slate-700 z-50">
                        <button @click="setTheme('light')" class="w-full text-left px-4 py-3 hover:bg-slate-100 dark:hover:bg-slate-700 rounded-t-lg flex items-center gap-3 transition-colors group/item">
                            <span class="material-symbols-outlined text-text-slate-500 group-hover/item:text-primary">light_mode</span>
                            <span class="text-sm font-medium text-text-slate-800 dark:text-slate-200">Light</span>
                            <span class="material-symbols-outlined ml-auto text-primary" x-show="theme === 'light'">check</span>
                        </button>
                        <button @click="setTheme('dark')" class="w-full text-left px-4 py-3 hover:bg-slate-100 dark:hover:bg-slate-700 flex items-center gap-3 transition-colors group/item">
                            <span class="material-symbols-outlined text-text-slate-500 group-hover/item:text-primary">dark_mode</span>
                            <span class="text-sm font-medium text-text-slate-800 dark:text-slate-200">Dark</span>
                            <span class="material-symbols-outlined ml-auto text-primary" x-show="theme === 'dark'">check</span>
                        </button>
                        <button @click="setTheme('system')" class="w-full text-left px-4 py-3 hover:bg-slate-100 dark:hover:bg-slate-700 rounded-b-lg flex items-center gap-3 transition-colors group/item">
                            <span class="material-symbols-outlined text-text-slate-500 group-hover/item:text-primary">smart_toy</span>
                            <span class="text-sm font-medium text-text-slate-800 dark:text-slate-200">System</span>
                            <span class="material-symbols-outlined ml-auto text-primary" x-show="theme === 'system'">check</span>
                        </button>
                    </div>
                </div>
            </div>
        </header>

        <main class="flex-1 overflow-y-auto no-scrollbar pb-24 pr-2 -mr-2">
            ${content}
        </main>
    </div>

</body>
</html>
