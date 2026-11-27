/*
 * EVO-Studio shell integration — wires the shared EVOShell foundation onto the
 * AI Inference application shell. Layout-only: never re-implements backend or
 * application logic, only the resizable shell, command palette and shortcuts.
 */
(function () {
    "use strict";

    function onReady(fn) {
        if (document.readyState === "loading") {
            document.addEventListener("DOMContentLoaded", fn);
        } else {
            fn();
        }
    }

    onReady(function () {
        var left = document.getElementById("left-sidebar");
        var work = document.getElementById("main-workspace");
        var inspector = document.getElementById("inspector");
        var appBody = document.getElementById("app-body");
        var bottom = document.getElementById("bottom-panel");

        if (!left || !work || !inspector || !appBody || !bottom) {
            return; // shell missing.
        }

        EVOShell.split(left, work, {
            direction: "horizontal", side: "before",
            storageKey: "dstudio.left.width", defaultSize: 260, min: 180, max: 600
        });
        EVOShell.split(work, inspector, {
            direction: "horizontal", side: "after",
            storageKey: "dstudio.inspector.width", defaultSize: 320, min: 200, max: 800
        });
        EVOShell.split(appBody, bottom, {
            direction: "vertical", side: "after",
            storageKey: "dstudio.bottom.height", defaultSize: 200, min: 80, max: 600
        });

        function togglePanel(panel, key) {
            var hidden = panel.style.display === "none";
            panel.style.display = hidden ? "" : "none";
            try { localStorage.setItem("evo.shell." + key, hidden ? "shown" : "hidden"); } catch (e) {}
        }

        EVOShell.commands({ label: "Toggle theme", keywords: ["dark", "light", "theme"],
            action: function () { EVOShell.themeToggle("dstudio.theme"); } });
        EVOShell.commands({ label: "Toggle left sidebar", keywords: ["sidebar", "navigation"],
            shortcut: "Ctrl+B", action: function () { togglePanel(left, "dstudio.left"); } });
        EVOShell.commands({ label: "Toggle inspector", keywords: ["context", "inspector"],
            action: function () { togglePanel(inspector, "dstudio.inspector"); } });
        EVOShell.commands({ label: "Toggle bottom panel", keywords: ["console", "logs", "events"],
            shortcut: "Ctrl+J", action: function () { togglePanel(bottom, "dstudio.bottom"); } });
        EVOShell.commands({ label: "Clear console", keywords: ["clear"],
            action: function () {
                var c = document.getElementById("panel-console");
                if (c) c.textContent = "";
            } });

        EVOShell.onShortcut("Ctrl+b", function () { togglePanel(left, "dstudio.left"); });
        EVOShell.onShortcut("Ctrl+j", function () { togglePanel(bottom, "dstudio.bottom"); });
    });
})();
