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
    <link href="https://fonts.googleapis.com/css2?family=DM+Sans:ital,opsz,wght@0,9..40,100..1000;1,9..40,100..1000&family=Space+Grotesk:wght@300..700&display=swap" rel="stylesheet"/>
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
<body class="bg-background-light dark:bg-background-dark font-body text-slate-800 dark:text-slate-50 min-h-screen antialiased selection:bg-primary/20 transition-colors duration-300" x-data="themeManager" x-cloak>
    <!-- Background Decor -->
    <div class="fixed inset-0 z-[-1] overflow-hidden pointer-events-none">
        <div class="absolute inset-0 bg-background-light dark:bg-background-dark"></div>
        <!-- Light Mode Mesh -->
        <div class="dark:hidden">
            <div class="absolute -top-[10%] -left-[10%] w-[40%] h-[40%] rounded-full bg-primary/5 blur-[120px]"></div>
            <div class="absolute top-[20%] -right-[5%] w-[35%] h-[35%] rounded-full bg-blue-200/20 blur-[100px]"></div>
        </div>
    </div>

    <div class="max-w-[1200px] mx-auto px-6 flex flex-col min-h-screen">
        <header class="flex items-center justify-between border-b sticky top-0 z-10 backdrop-blur-md bg-background-light/80 dark:bg-background-dark/80 border-slate-200 dark:border-slate-800 py-4">
            <a href="/ui" class="flex items-center gap-3 group transition-transform hover:scale-[1.02]">
                <div class="text-primary flex items-center justify-center">
                    <span class="material-symbols-outlined text-4xl">settings_input_antenna</span>
                </div>
                <h2 class="text-xl font-heading font-bold tracking-tight text-slate-800 dark:text-white">Jagratha</h2>
            </a>

            <div class="flex items-center pl-4 border-l border-slate-200 dark:border-slate-800">
                <div class="flex items-center gap-1 p-1 bg-slate-100 dark:bg-white/5 backdrop-blur-sm rounded-full border border-slate-200 dark:border-white/10">
                    <button @click="setTheme('light')"
                            class="p-1.5 rounded-full transition-all flex items-center justify-center"
                            :class="theme === 'light' ? 'bg-white dark:bg-slate-700 text-primary shadow-sm' : 'text-slate-400 hover:text-slate-600 dark:hover:text-slate-300'">
                        <span class="material-symbols-outlined text-[18px]">light_mode</span>
                    </button>
                    <button @click="setTheme('dark')"
                            class="p-1.5 rounded-full transition-all flex items-center justify-center"
                            :class="theme === 'dark' ? 'bg-white dark:bg-slate-700 text-primary shadow-sm' : 'text-slate-400 hover:text-slate-600 dark:hover:text-slate-300'">
                        <span class="material-symbols-outlined text-[18px]">dark_mode</span>
                    </button>
                    <button @click="setTheme('system')"
                            class="p-1.5 rounded-full transition-all flex items-center justify-center border-l border-slate-200 dark:border-white/10 ml-1 pl-2"
                            :class="theme === 'system' ? 'bg-white dark:bg-slate-700 text-primary shadow-sm' : 'text-slate-400 hover:text-slate-600 dark:hover:text-slate-300'">
                        <span class="material-symbols-outlined text-[18px]">desktop_windows</span>
                    </button>
                </div>
            </div>
        </header>

        <main class="py-12 flex-grow">
            ${content}
        </main>

        <footer class="border-t border-slate-200 dark:border-white/10 flex justify-between items-center py-8">
            <p class="text-[10px] font-bold text-slate-400 uppercase tracking-widest">© 2024 Jagratha Engineering. All rights reserved.</p>
            <div class="flex gap-4 text-slate-400">
                <span class="material-symbols-outlined text-lg hover:text-primary transition-colors cursor-pointer">terminal</span>
                <span class="material-symbols-outlined text-lg hover:text-primary transition-colors cursor-pointer">lan</span>
                <span class="material-symbols-outlined text-lg hover:text-primary transition-colors cursor-pointer">shield</span>
            </div>
        </footer>
    </div>
</body>
</html>
