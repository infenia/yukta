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

    <script src="https://unpkg.com/htmx.org@2.0.0"></script>
    <script src="https://d3js.org/d3.v7.min.js"></script>
    <script>
        if (localStorage.theme === 'dark' || (!('theme' in localStorage) && window.matchMedia('(prefers-color-scheme: dark)').matches)) {
            document.documentElement.classList.add('dark')
        } else {
            document.documentElement.classList.remove('dark')
        }
    </script>
</head>
<body class="bg-background-light dark:bg-background-dark text-text-slate-800 dark:text-slate-200 font-body antialiased overflow-hidden h-screen w-screen selection:bg-primary/20 transition-colors duration-300">
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

                <div class="hidden md:flex items-center h-12 px-4 rounded-full glass-panel min-w-[320px] group transition-all duration-300 focus-within:ring-2 focus-within:ring-primary/50">
                    <span class="material-symbols-outlined text-text-slate-500 text-[20px]">search</span>
                    <input id="session-search-input" class="bg-transparent border-none outline-none focus:ring-0 w-full text-sm font-body text-text-slate-800 dark:text-slate-200 placeholder:text-text-slate-500/70 ml-2" placeholder="Search sessions..." type="text"/>
                    <div class="flex items-center gap-1 px-1.5 py-0.5 rounded-md bg-white/50 dark:bg-slate-800/50 text-[10px] font-mono font-medium text-text-slate-500 border border-white/60 dark:border-slate-700">
                        ⌘K
                    </div>
                </div>

                <div class="relative">
                    <button id="theme-toggle-btn" onclick="toggleThemeDropdown(event)" class="w-10 h-10 rounded-full glass-panel text-text-slate-500 hover:text-primary transition-colors flex items-center justify-center border border-white/50 dark:border-slate-700 hover:border-primary">
                        <span id="theme-icon" class="material-symbols-outlined text-[20px]">light_mode</span>
                    </button>

                    <div id="theme-dropdown" class="absolute right-0 top-full mt-2 w-48 bg-white dark:bg-slate-800 rounded-lg shadow-lg border border-white/20 dark:border-slate-700 opacity-0 invisible transition-all duration-200 z-50" style="display: none; pointer-events: none;">
                        <button onclick="setTheme('light'); event.stopPropagation();" class="w-full text-left px-4 py-3 hover:bg-slate-100 dark:hover:bg-slate-700 rounded-t-lg flex items-center gap-3 transition-colors group/item">
                            <span class="material-symbols-outlined text-text-slate-500 group-hover/item:text-primary">light_mode</span>
                            <span class="text-sm font-medium text-text-slate-800 dark:text-slate-200">Light</span>
                            <span id="light-check" class="material-symbols-outlined ml-auto text-primary hidden">check</span>
                        </button>
                        <button onclick="setTheme('dark'); event.stopPropagation();" class="w-full text-left px-4 py-3 hover:bg-slate-100 dark:hover:bg-slate-700 flex items-center gap-3 transition-colors group/item">
                            <span class="material-symbols-outlined text-text-slate-500 group-hover/item:text-primary">dark_mode</span>
                            <span class="text-sm font-medium text-text-slate-800 dark:text-slate-200">Dark</span>
                            <span id="dark-check" class="material-symbols-outlined ml-auto text-primary hidden">check</span>
                        </button>
                        <button onclick="setTheme('system'); event.stopPropagation();" class="w-full text-left px-4 py-3 hover:bg-slate-100 dark:hover:bg-slate-700 rounded-b-lg flex items-center gap-3 transition-colors group/item">
                            <span class="material-symbols-outlined text-text-slate-500 group-hover/item:text-primary">smart_toy</span>
                            <span class="text-sm font-medium text-text-slate-800 dark:text-slate-200">System</span>
                            <span id="system-check" class="material-symbols-outlined ml-auto text-primary hidden">check</span>
                        </button>
                    </div>
                </div>
            </div>
        </header>

        <main class="flex-1 overflow-y-auto no-scrollbar pb-24 pr-2 -mr-2">
            ${content}
        </main>
    </div>

    <script>
        let themeDropdownOpen = false;

        function closeThemeDropdown() {
            const dropdown = document.getElementById('theme-dropdown');
            themeDropdownOpen = false;
            dropdown?.classList.add('opacity-0', 'invisible');
            dropdown.style.pointerEvents = 'none';
            // Fallback if CSS utilities aren't available
            if (dropdown) dropdown.style.display = 'none';
        }

        function openThemeDropdown() {
            const dropdown = document.getElementById('theme-dropdown');
            themeDropdownOpen = true;
            dropdown?.classList.remove('opacity-0', 'invisible');
            dropdown.style.pointerEvents = 'auto';
            // Fallback if CSS utilities aren't available
            if (dropdown) dropdown.style.display = 'block';
        }

        function toggleThemeDropdown(e) {
            e.stopPropagation();
            if (themeDropdownOpen) {
                closeThemeDropdown();
            } else {
                openThemeDropdown();
            }
        }

        function getSystemTheme() {
            return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
        }

        function applyTheme(isDark) {
            if (isDark) {
                document.documentElement.classList.add('dark');
            } else {
                document.documentElement.classList.remove('dark');
            }
        }

        function setTheme(theme) {
            if (theme === 'dark') {
                localStorage.theme = 'dark';
                applyTheme(true);
            } else if (theme === 'light') {
                localStorage.theme = 'light';
                applyTheme(false);
            } else if (theme === 'system') {
                localStorage.removeItem('theme');
                // Apply system preference
                const systemTheme = getSystemTheme();
                applyTheme(systemTheme === 'dark');
            }
            updateThemeIcon();
            updateThemeCheckmarks();
            closeThemeDropdown();
        }

        function updateThemeIcon() {
            const themeIcon = document.getElementById('theme-icon');
            const isDark = document.documentElement.classList.contains('dark');
            
            if (isDark) {
                themeIcon.textContent = 'light_mode';
            } else {
                themeIcon.textContent = 'dark_mode';
            }
        }

        function updateThemeCheckmarks() {
            const userTheme = localStorage.theme;
            const lightCheck = document.getElementById('light-check');
            const darkCheck = document.getElementById('dark-check');
            const systemCheck = document.getElementById('system-check');

            if (!lightCheck || !darkCheck || !systemCheck) return;

            // Hide all checks first (both utility class and inline fallback)
            [lightCheck, darkCheck, systemCheck].forEach(el => {
                el.classList.add('hidden');
                el.style.display = 'none';
            });

            // Show the appropriate check (use inline display for reliability)
            if (userTheme === 'dark') {
                darkCheck.classList.remove('hidden');
                darkCheck.style.display = '';
            } else if (userTheme === 'light') {
                lightCheck.classList.remove('hidden');
                lightCheck.style.display = '';
            } else {
                // System theme is selected (or not set yet)
                systemCheck.classList.remove('hidden');
                systemCheck.style.display = '';
            }
        }

        // Initialize on DOM ready
        document.addEventListener('DOMContentLoaded', () => {
            // Close dropdown when clicking outside
            document.addEventListener('click', (e) => {
                const dropdown = document.getElementById('theme-dropdown');
                const btn = document.getElementById('theme-toggle-btn');
                if (themeDropdownOpen && dropdown && btn && !dropdown.contains(e.target) && !btn.contains(e.target)) {
                    closeThemeDropdown();
                }
            });

            // Listen to system theme changes when user selected 'system'
            const mq = window.matchMedia('(prefers-color-scheme: dark)');
            const mqHandler = (ev) => {
                if (!localStorage.theme) {
                    applyTheme(ev.matches);
                    updateThemeIcon();
                    updateThemeCheckmarks();
                }
            };
            if (mq.addEventListener) {
                mq.addEventListener('change', mqHandler);
            } else if (mq.addListener) {
                mq.addListener(mqHandler);
            }

            // Ensure dropdown is hidden on load (safety fallback)
            closeThemeDropdown();
            updateThemeCheckmarks();
            updateThemeIcon();
        });

        // Global search shortcut
        document.addEventListener('keydown', (e) => {
            if ((e.metaKey || e.ctrlKey) && e.key === 'k') {
                e.preventDefault();
                document.getElementById('session-search-input')?.focus();
            }
        });
    </script>
</body>
</html>
