@import gg.jte.Content
@param String title
@param Content content

<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>${title} - Jagratha</title>
    <link rel="stylesheet" href="/css/style.css">
    <script src="https://unpkg.com/htmx.org@2.0.0"></script>
    <script src="https://cdn.jsdelivr.net/npm/mermaid@11/dist/mermaid.min.js"></script>
    <script>mermaid.initialize({ startOnLoad: true, theme: 'neutral' });</script>
</head>
<body class="bg-slate-50 text-slate-900 font-sans">
    <nav class="bg-indigo-700 text-white shadow-lg">
        <div class="container mx-auto px-6 py-4 flex justify-between items-center">
            <a href="/ui" class="text-2xl font-bold tracking-tight">Jagratha <span class="text-indigo-200 font-light">Vigilance</span></a>
            <div class="space-x-4">
                <a href="/ui" class="hover:text-indigo-200 transition">Dashboard</a>
                <a href="/ui/history" class="hover:text-indigo-200 transition">History</a>
                <a href="/swagger-ui.html" class="hover:text-indigo-200 transition">API Docs</a>
            </div>
        </div>
    </nav>

    <main class="container mx-auto px-6 py-8">
        ${content}
    </main>

    <footer class="mt-auto py-6 border-t border-slate-200 text-center text-slate-500 text-sm">
        &copy; ${java.time.Year.now().getValue()} Infenia Private Limited. All rights reserved.
    </footer>
</body>
</html>
