<%-- SPDX-License-Identifier: Apache-2.0 --%>
<%-- SPDX-FileCopyrightText: 2026 Infenia Private Limited --%>

@import gg.jte.Content
@param String title
@param Content content

<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>${title} - Yukta</title>
    <link rel="icon" type="image/png" href="/images/favicon-96x96.png">
    <script src="https://cdn.tailwindcss.com?plugins=forms,container-queries"></script>
    <link href="https://fonts.googleapis.com/css2?family=DM+Sans:ital,opsz,wght@0,9..40,100..1000;1,9..40,100..1000&family=Space+Grotesk:wght@400;500;600;700&display=swap" rel="stylesheet">
    <link href="https://fonts.googleapis.com/css2?family=Material+Symbols+Outlined:wght,FILL@100..700,0..1&display=swap" rel="stylesheet">
    <script src="https://unpkg.com/htmx.org@2.0.4"></script>
    <script src="https://unpkg.com/htmx-ext-sse@2.2.2"></script>
    <script>
        tailwind.config = {
            darkMode: "class",
            theme: {
                extend: {
                    colors: {
                        primary: "#3c83f6",
                        "background-light": "#f5f7f8",
                        "background-dark": "#0F172A",
                    },
                    fontFamily: {
                        body: ["DM Sans", "sans-serif"],
                        heading: ["Space Grotesk", "sans-serif"],
                        display: ["DM Sans", "sans-serif"]
                    }
                }
            }
        }
    </script>
    <style>
        body { font-family: 'DM Sans', sans-serif; }
        .material-symbols-outlined { font-variation-settings: 'FILL' 0, 'wght' 400, 'GRAD' 0, 'opsz' 24; }
    </style>
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
    <script src="/js/app.js" defer></script>
</head>
<body class="font-body bg-background-light dark:bg-background-dark text-slate-800 dark:text-slate-50 antialiased min-h-screen">
    <div class="mx-auto max-w-[1200px] flex flex-col px-6 lg:px-10 min-h-screen">
        <!-- Header -->
        <header class="flex items-center justify-between border-b sticky top-0 z-10 backdrop-blur-md bg-background-light/80 dark:bg-background-dark/80 border-slate-200 dark:border-slate-800 p-4">
            <a href="/ui" class="flex items-center gap-4 group transition-transform hover:scale-[1.02]">
                <div class="text-primary">
                    <img
                            class="relative inline-block h-8 w-8 rounded-xl object-center"
                            src="/images/favicon.svg"
                            alt="Logo"
                    />
                </div>
                <h1 class="text-xl font-bold tracking-tight">Yukta</h1>
            </a>

            <!-- Theme Toggle -->
            <div class="flex bg-white dark:bg-slate-900 p-1 rounded-3xl border border-slate-200 dark:border-slate-700 shadow-sm">
                <button class="p-1.5 rounded-full hover:bg-white/10 transition-all flex items-center justify-center text-slate-400"
                        id="theme-light" data-theme="light" onclick="setTheme('light')"
                        style="">
                    <span class="material-symbols-outlined text-[18px]">light_mode</span>
                </button>
                <button class="p-1.5 rounded-full hover:bg-white/10 transition-all flex items-center justify-center text-slate-400"
                        id="theme-dark" data-theme="dark" onclick="setTheme('dark')"
                        style="">
                    <span class="material-symbols-outlined text-[18px]">dark_mode</span>
                </button>
                <button class="p-1.5 rounded-full hover:bg-white/10 transition-all flex items-center justify-center text-slate-400"
                        id="theme-system" data-theme="system" onclick="setTheme('system')"
                        style="">
                    <span class="material-symbols-outlined text-[18px]">desktop_windows</span>
                </button>
            </div>
        </header>

        <main class="flex-grow py-6">
            ${content}
        </main>

        <footer class="mt-auto py-4 border-t border-slate-200 dark:border-slate-800 text-sm text-slate-500 flex justify-between items-center">
            <p>© 2026 Infenia Private Limited. Licensed under <a href="https://www.apache.org/licenses/LICENSE-2.0">Apache 2.0</a>.</p>
        </footer>
    </div>

    <script>
        function setTheme(theme) {
            const html = document.documentElement;
            if (theme === 'system') {
                localStorage.removeItem('theme');
                html.classList.toggle('dark', window.matchMedia('(prefers-color-scheme: dark)').matches);
            } else {
                localStorage.setItem('theme', theme);
                html.classList.toggle('dark', theme === 'dark');
            }
            updateThemeButtons();
        }

        function updateThemeButtons() {
            const current = localStorage.getItem('theme') || 'system';
            document.querySelectorAll('[data-theme]').forEach(btn => {
                if (btn.dataset.theme === current) {
                    const isDark = document.documentElement.classList.contains('dark');
                    btn.style.backgroundColor = isDark ? '#475569' : '#f1f5f9';
                    btn.style.color = '#3c83f6';
                } else {
                    btn.style.backgroundColor = '';
                    btn.style.color = '';
                }
            });
        }

        // Initial theme
        if (localStorage.theme === 'dark' || (!localStorage.theme && window.matchMedia('(prefers-color-scheme: dark)').matches)) {
            document.documentElement.classList.add('dark');
        }
        updateThemeButtons();
    </script>
</body>
</html>
