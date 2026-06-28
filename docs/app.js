// --- Translation Dictionary (I18n) ---
const translations = {
    en: {
        searchPlaceholder: "Search docs...",
        navTitleGettingStarted: "Getting Started",
        navTitleGuides: "Guides",
        navTitleReference: "Reference",
        navTitleResources: "Resources",
        linkHome: "Home",
        linkGettingStarted: "Getting Started",
        linkTutorials: "Tutorials",
        linkHowToGuides: "How-To Guides",
        linkConcepts: "Core Concepts",
        linkApiReference: "API Reference",
        linkBenchmarks: "Benchmarks",
        linkFaq: "FAQ",
        linkMigration: "Migration Guide",
        linkChangelog: "Changelog",
        linkRoadmap: "Roadmap",
        linkSecurity: "Security Policy",
        linkLicense: "License",
        tocTitleText: "On this page",
        copyBtnText: "Copy",
        copyBtnSuccess: "Copied!",
        errorLoading: "Failed to load content. Please verify your connection.",
        noResults: "No results found."
    },
    fr: {
        searchPlaceholder: "Rechercher...",
        navTitleGettingStarted: "Démarrage",
        navTitleGuides: "Guides",
        navTitleReference: "Référence",
        navTitleResources: "Ressources",
        linkHome: "Accueil",
        linkGettingStarted: "Mise en Route",
        linkTutorials: "Tutoriels",
        linkHowToGuides: "Guides Pratiques",
        linkConcepts: "Concepts Clés",
        linkApiReference: "Référence API",
        linkBenchmarks: "Benchmarks",
        linkFaq: "FAQ",
        linkMigration: "Guide de Migration",
        linkChangelog: "Notes de Version",
        linkRoadmap: "Feuille de Route",
        linkSecurity: "Sécurité Opérationnelle",
        linkLicense: "Licence",
        tocTitleText: "Sur cette page",
        copyBtnText: "Copier",
        copyBtnSuccess: "Copié !",
        errorLoading: "Échec du chargement du contenu. Veuillez vérifier votre connexion.",
        noResults: "Aucun résultat trouvé."
    }
};

// --- Documentation Search Index ---
const searchIndex = {
    en: [
        { section: "home", title: "Introduction to Veridot", content: "Veridot is a high-performance cryptographic token verification protocol and Java library designed for large-scale distributed architectures." },
        { section: "getting_started", title: "Installation and Setup", content: "Include the Veridot Core library in your project build file. Minimal configuration needs a default keys rotation interval." },
        { section: "tutorials", title: "Building Your First Project", content: "Set up a standard Java project structure with Maven and implement a mock authentication system using Veridot." },
        { section: "tutorials", title: "Writing a Custom Rule", content: "Rules in Veridot run during the verification pipeline. You can enforce policies like domain restrictions or email verification." },
        { section: "how_to_guides", title: "Spring Boot Integration", content: "Integrate Veridot into a Spring Boot application by defining a custom security filter and registering verifier as a Spring Bean." },
        { section: "concepts", title: "Binary Envelope wire format", content: "Every piece of data uses a single, unified binary envelope layout. It starts with magic bytes 0x56 0x44." },
        { section: "concepts", title: "Monotonic Version Invariant", content: "To guard against rollback attacks, verifiers maintain a local watermark version. Updates are accepted only if version is higher." },
        { section: "api_reference", title: "DataSigner Interface", content: "DataSigner is the Java public API surface used to issue and sign new user session tokens." },
        { section: "benchmarks", title: "Verification Latency sub-ms", content: "Validation is executed in memory using a concurrent hash map or off-heap RocksDB. Average latency is 0.08ms to 0.24ms." },
        { section: "security", title: "Clock Synchronization NTP", content: "Maximum allowed clock skew between any signer node and gateway is 5 minutes. Use NTP like systemd-timesyncd." }
    ],
    fr: [
        { section: "home", title: "Introduction à Veridot", content: "Veridot est un protocole de vérification de jetons cryptographiques à haute performance et une bibliothèque Java." },
        { section: "getting_started", title: "Installation et Démarrage", content: "Ajoutez la dépendance Veridot Core à votre projet Maven ou Gradle. Configurez l'intervalle de rotation." },
        { section: "tutorials", title: "Créer votre Premier Projet", content: "Mettez en place un projet Java standard avec Maven et implémentez une logique d'authentification simple." },
        { section: "tutorials", title: "Écrire une Règle Personnalisée", content: "Les règles s'exécutent lors de la validation. Exemple pour vérifier si l'adresse e-mail a été validée." },
        { section: "how_to_guides", title: "Intégration avec Spring Boot", content: "Intégrez Veridot dans une application Spring Boot en définissant un filtre de sécurité personnalisé OncePerRequestFilter." },
        { section: "concepts", title: "Format de l'Enveloppe Binaire", content: "Chaque message utilise un format binaire strict débutant par les octets magiques 0x56 0x44." },
        { section: "concepts", title: "Invariant de Monotonicité", content: "Pour contrer les attaques par rejeu, les validateurs enregistrent la dernière version et rejettent les anciennes." },
        { section: "api_reference", title: "Interface DataSigner", content: "Interface utilisée pour signer et émettre des jetons utilisateur Veridot." },
        { section: "benchmarks", title: "Latence et Benchmarks", content: "La validation locale s'exécute en moins d'une milliseconde (0.08ms en Ed25519)." },
        { section: "security", title: "Synchronisation d'Horloge NTP", content: "L'écart de temps maximal toléré est de 5 minutes. Configurez timesyncd avec ntp.org." }
    ]
};

// --- Application State ---
let currentLang = localStorage.getItem("veridot_lang") || (navigator.language.startsWith("fr") ? "fr" : "en");

// --- DOM Elements ---
const mainContent = document.getElementById("main-content");
const contentBody = document.getElementById("content-body");
const searchInput = document.getElementById("search-input");
const searchResults = document.getElementById("search-results");
const tocMenu = document.getElementById("toc-menu");
const sidebar = document.getElementById("sidebar");
const menuToggleBtn = document.getElementById("menu-toggle-btn");
const menuCloseBtn = document.getElementById("menu-close-btn");

// --- Route Mapping ---
const sectionRoutes = {
    "": "home",
    "home": "home",
    "getting-started": "getting_started",
    "tutorials": "tutorials",
    "how-to-guides": "how_to_guides",
    "concepts": "concepts",
    "api-reference": "api_reference",
    "benchmarks": "benchmarks",
    "faq": "faq",
    "migration": "migration",
    "changelog": "changelog",
    "roadmap": "roadmap",
    "security": "security",
    "license": "license"
};

// --- Initialize Language Selection ---
function initLanguage() {
    document.querySelectorAll(".lang-btn").forEach(btn => {
        if (btn.dataset.lang === currentLang) {
            btn.classList.add("active");
        } else {
            btn.classList.remove("active");
        }
        
        btn.addEventListener("click", (e) => {
            const selectedLang = e.target.dataset.lang;
            if (selectedLang !== currentLang) {
                currentLang = selectedLang;
                localStorage.setItem("veridot_lang", currentLang);
                
                // Update UI state & Reload
                applyTranslations();
                initLanguage();
                loadSectionFromHash();
            }
        });
    });
    applyTranslations();
}

function applyTranslations() {
    const t = translations[currentLang];
    
    // Static fields
    searchInput.placeholder = t.searchPlaceholder;
    document.getElementById("nav-title-getting-started").textContent = t.navTitleGettingStarted;
    document.getElementById("nav-title-guides").textContent = t.navTitleGuides;
    document.getElementById("nav-title-reference").textContent = t.navTitleReference;
    document.getElementById("nav-title-resources").textContent = t.navTitleResources;
    document.getElementById("toc-title-text").textContent = t.tocTitleText;
    
    // Menu links
    document.getElementById("link-home").textContent = t.linkHome;
    document.getElementById("link-getting-started").textContent = t.linkGettingStarted;
    document.getElementById("link-tutorials").textContent = t.linkTutorials;
    document.getElementById("link-how-to-guides").textContent = t.linkHowToGuides;
    document.getElementById("link-concepts").textContent = t.linkConcepts;
    document.getElementById("link-api-reference").textContent = t.linkApiReference;
    document.getElementById("link-benchmarks").textContent = t.linkBenchmarks;
    document.getElementById("link-faq").textContent = t.linkFaq;
    document.getElementById("link-migration").textContent = t.linkMigration;
    document.getElementById("link-changelog").textContent = t.linkChangelog;
    document.getElementById("link-roadmap").textContent = t.linkRoadmap;
    document.getElementById("link-security").textContent = t.linkSecurity;
    document.getElementById("link-license").textContent = t.linkLicense;
}

// --- Load Content dynamically ---
async function loadSection(sectionName) {
    contentBody.innerHTML = `<div class="loading-spinner"></div>`;
    
    // Highlight nav link
    document.querySelectorAll(".nav-link").forEach(link => {
        if (link.dataset.section === sectionName) {
            link.classList.add("active");
        } else {
            link.classList.remove("active");
        }
    });

    try {
        const response = await fetch(`content/${currentLang}/${sectionName}.html`);
        if (!response.ok) throw new Error("File not found");
        
        const html = await response.text();
        contentBody.innerHTML = html;
        
        // Post-render processing
        processContent();
    } catch (err) {
        contentBody.innerHTML = `
            <div class="alert alert-danger">
                <div class="alert-title">Error / Erreur</div>
                <p>${translations[currentLang].errorLoading}</p>
            </div>
        `;
    }
}

function processContent() {
    // 1. Copy Buttons for Code blocks
    document.querySelectorAll("pre").forEach(preBlock => {
        const copyBtn = document.createElement("button");
        copyBtn.className = "copy-btn";
        copyBtn.textContent = translations[currentLang].copyBtnText;
        preBlock.appendChild(copyBtn);
        
        copyBtn.addEventListener("click", () => {
            const code = preBlock.querySelector("code").innerText;
            navigator.clipboard.writeText(code).then(() => {
                copyBtn.textContent = translations[currentLang].copyBtnSuccess;
                copyBtn.style.borderColor = "var(--accent-green)";
                setTimeout(() => {
                    copyBtn.textContent = translations[currentLang].copyBtnText;
                    copyBtn.style.borderColor = "var(--border-color)";
                }, 2000);
            });
        });
    });

    // 2. Tab switching inside Code block selectors
    document.querySelectorAll(".code-tabs").forEach(tabContainer => {
        const btns = tabContainer.querySelectorAll(".code-tab-btn");
        const contents = tabContainer.querySelectorAll(".code-tabs-content");
        
        btns.forEach(btn => {
            btn.addEventListener("click", () => {
                const target = btn.dataset.tab;
                
                btns.forEach(b => b.classList.remove("active"));
                contents.forEach(c => c.classList.remove("active"));
                
                btn.classList.add("active");
                tabContainer.querySelector(`.code-tabs-content[data-tab="${target}"]`).classList.add("active");
            });
        });
    });

    // 3. Render Mermaid diagrams if any
    try {
        mermaid.run();
    } catch (e) {
        console.error("Mermaid execution error", e);
    }

    // 4. Generate dynamic Table of Contents (TOC)
    generateTOC();
}

// --- Dynamic Table of Contents (TOC) ---
function generateTOC() {
    tocMenu.innerHTML = "";
    const headings = contentBody.querySelectorAll("h2, h3");
    
    if (headings.length === 0) {
        document.getElementById("toc-sidebar").style.display = "none";
        return;
    }
    
    document.getElementById("toc-sidebar").style.display = "block";
    headings.forEach((heading, idx) => {
        const id = heading.id || `heading-${idx}`;
        heading.id = id;
        
        const link = document.createElement("a");
        link.href = `#${window.location.hash.slice(1)}#${id}`;
        link.className = "toc-link";
        link.textContent = heading.innerText;
        if (heading.tagName === "H3") {
            link.style.paddingLeft = "10px";
            link.style.fontSize = "0.8rem";
        }
        
        link.addEventListener("click", (e) => {
            e.preventDefault();
            heading.scrollIntoView({ behavior: "smooth" });
        });
        
        tocMenu.appendChild(link);
    });
}

// --- Client-Side Search Logic ---
function initSearch() {
    searchInput.addEventListener("input", (e) => {
        const value = e.target.value.toLowerCase().trim();
        searchResults.innerHTML = "";
        
        if (value === "") {
            searchResults.style.display = "none";
            return;
        }

        const items = searchIndex[currentLang].filter(item => 
            item.title.toLowerCase().includes(value) || 
            item.content.toLowerCase().includes(value)
        );

        if (items.length === 0) {
            searchResults.innerHTML = `<div class="search-result-item" style="cursor: default; color: var(--text-muted);">${translations[currentLang].noResults}</div>`;
        } else {
            items.forEach(item => {
                const div = document.createElement("div");
                div.className = "search-result-item";
                div.innerHTML = `
                    <div class="search-result-title">${item.title}</div>
                    <div class="search-result-snippet">${item.content}</div>
                `;
                div.addEventListener("click", () => {
                    window.location.hash = `#${Object.keys(sectionRoutes).find(key => sectionRoutes[key] === item.section)}`;
                    searchResults.style.display = "none";
                    searchInput.value = "";
                });
                searchResults.appendChild(div);
            });
        }
        searchResults.style.display = "block";
    });

    document.addEventListener("click", (e) => {
        if (!searchInput.contains(e.target) && !searchResults.contains(e.target)) {
            searchResults.style.display = "none";
        }
    });
}

// --- Hash Router ---
function loadSectionFromHash() {
    // Hash format: #sectionName#headingId or #sectionName
    const hashParts = window.location.hash.split("#");
    const route = hashParts[1] || "";
    const sectionName = sectionRoutes[route] || "home";
    
    loadSection(sectionName).then(() => {
        // If heading target ID is specified in URL, scroll to it
        if (hashParts[2]) {
            setTimeout(() => {
                const headingEl = document.getElementById(hashParts[2]);
                if (headingEl) {
                    headingEl.scrollIntoView({ behavior: "smooth" });
                }
            }, 300);
        }
    });
}

// --- Mobile Navigation ---
function initMobileMenu() {
    menuToggleBtn.addEventListener("click", () => {
        sidebar.classList.add("open");
    });
    
    menuCloseBtn.addEventListener("click", () => {
        sidebar.classList.remove("open");
    });

    document.querySelectorAll(".nav-link").forEach(link => {
        link.addEventListener("click", () => {
            sidebar.classList.remove("open");
        });
    });
}

// --- Initialize App ---
document.addEventListener("DOMContentLoaded", () => {
    initLanguage();
    initSearch();
    initMobileMenu();
    loadSectionFromHash();
    window.addEventListener("hashchange", loadSectionFromHash);
});
