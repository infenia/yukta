import Alpine from 'alpinejs';
import dagComponent from './components/dag';

// Initialize Alpine globally
window.Alpine = Alpine;

Alpine.data('dagComponent', dagComponent);

// Theme management component
Alpine.data('themeManager', () => ({
    theme: localStorage.getItem('theme') || 'system',
    themeDropdownOpen: false,
    systemTheme: window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light',

    init() {
        this.applyTheme();
        window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', e => {
            this.systemTheme = e.matches ? 'dark' : 'light';
            if (this.theme === 'system') {
                this.applyTheme();
            }
        });
    },

    setTheme(newTheme) {
        this.theme = newTheme;
        if (newTheme === 'system') {
            localStorage.removeItem('theme');
        } else {
            localStorage.setItem('theme', newTheme);
        }
        this.applyTheme();
        this.themeDropdownOpen = false;
    },

    applyTheme() {
        const isDark = this.theme === 'dark' || (this.theme === 'system' && this.systemTheme === 'dark');
        if (isDark) {
            document.documentElement.classList.add('dark');
        } else {
            document.documentElement.classList.remove('dark');
        }
    },

    get currentIcon() {
        if (this.theme === 'dark') return 'dark_mode';
        if (this.theme === 'light') return 'light_mode';
        return 'smart_toy';
    }
}));

// Session search component
Alpine.data('sessionSearch', () => ({
    query: '',
    init() {
        window.addEventListener('keydown', (e) => {
            if ((e.metaKey || e.ctrlKey) && e.key === 'k') {
                e.preventDefault();
                this.$refs.searchInput?.focus();
            }
        });
    },
    filterSessions() {
        const q = this.query.toLowerCase();
        document.querySelectorAll('.session-card').forEach(card => {
            const text = card.innerText.toLowerCase();
            card.style.display = text.includes(q) ? '' : 'none';
        });
    }
}));

Alpine.start();
