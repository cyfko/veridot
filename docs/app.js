// --- Translation Dictionary (I18n) ---
const translations = {
    en: {
        searchPlaceholder: "Search docs...",
        navTitleGettingStarted: "Getting Started",
        navTitleCoreConcepts: "Core Concepts",
        navTitleDevGuides: "Developer Guides",
        linkIntro: "Introduction",
        linkQuickstart: "Quickstart Guide",
        linkArchitecture: "System Architecture",
        linkSecurity: "Operational Security",
        linkAdvanced: "Advanced Guides",
        linkReference: "API Reference",
        tocTitleText: "On this page",
        copyBtnText: "Copy",
        copyBtnSuccess: "Copied!",
        errorLoading: "Failed to load content. Please verify your connection.",
        noResults: "No results found."
    },
    fr: {
        searchPlaceholder: "Rechercher...",
        navTitleGettingStarted: "Démarrage",
        navTitleCoreConcepts: "Concepts Clés",
        navTitleDevGuides: "Guides Développeur",
        linkIntro: "Introduction",
        linkQuickstart: "Guide Rapide",
        linkArchitecture: "Architecture Système",
        linkSecurity: "Sécurité Opérationnelle",
        linkAdvanced: "Guides Avancés",
        linkReference: "Référence API",
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
        { section: "1_introduction", title: "What is Veridot?", content: "Veridot is a high-performance cryptographic token verification protocol and Java library designed for large-scale distributed architectures. It guarantees immediate revocation verification without state lookup bottlenecks." },
        { section: "1_introduction", title: "Comparison with JWT", content: "Unlike raw stateless JWT which has no efficient revocation, and stateful tokens that require database calls, Veridot operates on localized validation with async status propagation." },
        { section: "2_architecture", title: "Binary Envelope Format", content: "Veridot V4 uses a Type-Length-Value (TLV) binary format starting with magic byte 0x04. It contains entries like CONFIG, KEY_EPOCH, LIVENESS, FENCE, and SNAPSHOT_MARKER." },
        { section: "2_architecture", title: "Version Watermark & Reconciliation", content: "To protect against snapshot replay and rollback attacks, Veridot nodes maintain watermarks reconciled periodically against the metadata broker." },
        { section: "3_quickstart", title: "Maven Dependency", content: "Add veridot-core dependency in your pom.xml: groupId io.github.cyfko, artifactId veridot-core, version 4.0.0." },
        { section: "3_quickstart", title: "Hello World Code", content: "Initialize GenericSignerVerifier, build config, sign payload data and verify token using the TokenVerifier API." },
        { section: "4_advanced", title: "Session & Group Revocation", content: "Revoke a single active session or all sessions within a group by publishing a liveness revoked envelope." },
        { section: "4_advanced", title: "Custom Metadata Brokers", content: "Implement the Broker interface for custom databases or use the veridot-kafka and veridot-databases adapters." },
        { section: "5_reference", title: "Java Public API", content: "Explore the core interfaces: DataSigner, TokenVerifier, TokenRevoker, TokenTracker." },
        { section: "5_reference", title: "Configuration keys", content: "Environmental variables configuration: VDOT_KEYS_ROTATION_MINUTES, VDOT_RECONCILIATION_INTERVAL_MINUTES, VDOT_CAPABILITY_CACHE_TTL_SECONDS." },
        { section: "6_security", title: "Clock Drift", content: "Verify clock drift sync between nodes. Clock drift skew must not exceed 5 minutes to maintain safety guarantees." }
    ],
    fr: [
        { section: "1_introduction", title: "Qu'est-ce que Veridot ?", content: "Veridot est un protocole de vérification de jetons cryptographiques à haute performance conçu pour les architectures distribuées à grande échelle. Il garantit une vérification immédiate des révocations sans goulot d'étranglement de base de données." },
        { section: "1_introduction", title: "Comparaison avec JWT", content: "Contrairement aux JWT sans état qui ne gèrent pas la révocation rapide, ou aux jetons avec état qui exigent des requêtes réseau, Veridot valide localement avec mise à jour asynchrone." },
        { section: "2_architecture", title: "Format d'enveloppe binaire", content: "Veridot V4 utilise un format binaire TLV débutant par le magic byte 0x04. Il contient des entrées CONFIG, KEY_EPOCH, LIVENESS, FENCE, et SNAPSHOT_MARKER." },
        { section: "2_architecture", title: "Version Watermark & Réconciliation", content: "Pour contrer les attaques par rejeu de snapshot, les instances Veridot maintiennent un filigrane de version (watermark) réconcilié régulièrement." },
        { section: "3_quickstart", title: "Dépendance Maven", content: "Ajoutez la dépendance veridot-core dans votre fichier pom.xml : groupId io.github.cyfko, artifactId veridot-core, version 4.0.0." },
        { section: "3_quickstart", title: "Code d'exemple", content: "Initialisez le GenericSignerVerifier, configurez-le, signez la payload utilisateur et validez via le TokenVerifier." },
        { section: "4_advanced", title: "Révocation de session & groupe", content: "Révochez une session spécifique par son sequenceId ou l'intégralité du groupe de sessions via une enveloppe signée LIVENESS=REVOKED." },
        { section: "4_advanced", title: "Brokers personnalisés", content: "Implémentez l'interface Broker ou utilisez les modules veridot-kafka et veridot-databases pour SQL." },
        { section: "5_reference", title: "API Publique Java", content: "Explorez les interfaces clés : DataSigner, TokenVerifier, TokenRevoker, TokenTracker." },
        { section: "5_reference", title: "Clés de Configuration", content: "Configuration par variables d'environnement : VDOT_KEYS_ROTATION_MINUTES, VDOT_RECONCILIATION_INTERVAL_MINUTES." },
        { section: "6_security", title: "Dérive d'horloge (Clock Drift)", content: "Vérifiez la synchronisation des horloges entre nœuds. La dérive maximale tolérée est de 5 minutes." }
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
    "": "1_introduction",
    "introduction": "1_introduction",
    "quickstart": "3_quickstart",
    "architecture": "2_architecture",
    "security": "6_security",
    "advanced": "4_advanced",
    "reference": "5_reference"
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
    document.getElementById("nav-title-core-concepts").textContent = t.navTitleCoreConcepts;
    document.getElementById("nav-title-developer-guides").textContent = t.navTitleDevGuides;
    document.getElementById("toc-title-text").textContent = t.tocTitleText;
    
    // Menu links
    document.getElementById("link-intro").textContent = t.linkIntro;
    document.getElementById("link-quickstart").textContent = t.linkQuickstart;
    document.getElementById("link-architecture").textContent = t.linkArchitecture;
    document.getElementById("link-security").textContent = t.linkSecurity;
    document.getElementById("link-advanced").textContent = t.linkAdvanced;
    document.getElementById("link-reference").textContent = t.linkReference;
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
    const sectionName = sectionRoutes[route] || "1_introduction";
    
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
