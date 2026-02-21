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
    <link href="https://fonts.googleapis.com/css2?family=Orbitron:wght@400..900&family=Inter:wght@400..900&display=swap" rel="stylesheet">
    <script src="https://unpkg.com/htmx.org@2.0.0"></script>
    <script src="https://d3js.org/d3.v7.min.js"></script>
    <script>
        if (localStorage.theme === 'dark' || (!('theme' in localStorage) && window.matchMedia('(prefers-color-scheme: dark)').matches)) {
            document.documentElement.classList.add('dark')
        } else {
            document.documentElement.classList.remove('dark')
        }
    </script>
    <style>
        .font-brand { font-family: 'Orbitron', sans-serif; }
        body { font-family: 'Inter', sans-serif; }
    </style>
</head>
<body class="bg-slate-50 text-slate-900 dark:bg-slate-950 dark:text-slate-100 min-h-screen flex flex-col transition-colors duration-300">
    <nav class="bg-white/80 dark:bg-slate-900/80 backdrop-blur-md border-b border-slate-200 dark:border-slate-800 sticky top-0 z-50 shadow-sm">
        <div class="container mx-auto px-6 py-3 flex justify-between items-center">
            <a href="/ui" class="flex items-center gap-3 group">
                <img src="/images/icon-64x64.png" alt="Jagratha" class="w-8 h-8 group-hover:rotate-12 transition-transform duration-300">
                <span class="text-xl font-bold tracking-wider font-brand uppercase text-slate-800 dark:text-white">Jagratha</span>
            </a>
            <div class="flex items-center space-x-6">
                <div class="hidden md:flex space-x-6 text-sm font-medium">
                    <a href="/ui" class="text-slate-600 dark:text-slate-400 hover:text-indigo-600 dark:hover:text-indigo-400 transition">Dashboard</a>
                    <a href="/ui/history" class="text-slate-600 dark:text-slate-400 hover:text-indigo-600 dark:hover:text-indigo-400 transition">History</a>
                    <a href="/swagger-ui.html" class="text-slate-600 dark:text-slate-400 hover:text-indigo-600 dark:hover:text-indigo-400 transition">API Docs</a>
                </div>
                <button onclick="toggleTheme()" class="p-2 rounded-lg bg-slate-100 dark:bg-slate-800 text-slate-500 dark:text-slate-400 hover:bg-slate-200 dark:hover:bg-slate-700 transition">
                    <svg id="theme-icon-dark" class="w-5 h-5 hidden dark:block" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 3v1m0 16v1m9-9h1m-16 0H3m15.364-6.364l-.707.707M6.343 17.657l-.707.707m12.728 0l-.707-.707M6.343 6.343l-.707-.707M12 5a7 7 0 100 14 7 7 0 000-14z"></path></svg>
                    <svg id="theme-icon-light" class="w-5 h-5 block dark:hidden" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M20.354 15.354A9 9 0 018.646 3.646 9.003 9.003 0 0012 21a9.003 9.003 0 008.354-5.646z"></path></svg>
                </button>
            </div>
        </div>
    </nav>

    <main class="container mx-auto px-6 py-8 flex-1">
        ${content}
    </main>

    <footer class="py-8 border-t border-slate-200 dark:border-slate-800 text-center text-slate-500 dark:text-slate-400 text-sm">
        <div class="container mx-auto px-6">
            <p>&copy; ${java.time.Year.now().getValue()} Infenia Private Limited. All rights reserved.</p>
            <p class="mt-2 text-xs opacity-50 font-brand uppercase tracking-widest">Vigilance Over Code</p>
        </div>
    </footer>

    <script>
        function toggleTheme() {
            if (document.documentElement.classList.contains('dark')) {
                document.documentElement.classList.remove('dark');
                localStorage.theme = 'light';
            } else {
                document.documentElement.classList.add('dark');
                localStorage.theme = 'dark';
            }
        }
    </script>
</body>
</html>
