/*
 * EVO shared interactive shell — splitters, command palette, keyboard shortcuts,
 * context menu and theme persistence. Vanilla JS, no framework, no dependencies.
 * Shared by EVO-Studio and EVO-Code; product code calls EVOShell.init(...) once.
 *
 * Public surface:
 *   EVOShell.init({ splits, commands, shortcuts, themeKey, onTheme })
 *   EVOShell.split(before, after, { direction, min, max, storageKey, defaultSize })
 *   EVOShell.commands(register)           — register additional palette commands
 *   EVOShell.openCommandPalette()
 *   EVOShell.contextMenu(items, x, y)     — show a context menu
 *   EVOShell.themeToggle()
 */
(function () {
    "use strict";

    var STORE = "evo.shell.";

    function el(tag, cls, text) {
        var node = document.createElement(tag);
        if (cls) node.className = cls;
        if (text !== undefined) node.textContent = text;
        return node;
    }

    /* ------------------------------------------------------------------
     * Splitter
     * ------------------------------------------------------------------ */
    function split(before, after, opts) {
        opts = opts || {};
        var direction = opts.direction === "vertical" ? "vertical" : "horizontal";
        var storageKey = opts.storageKey;
        var min = opts.min != null ? opts.min : 120;
        var max = opts.max != null ? opts.max : 1200;
        var defaultSize = opts.defaultSize != null ? opts.defaultSize : 260;
        // "before" (default) resizes the leading panel; "after" resizes the trailing one.
        var target = opts.side === "after" ? after : before;

        var handle = el("div", "evo-splitter evo-splitter--" + direction);
        var parent = before.parentNode;
        parent.insertBefore(handle, after);

        function read() {
            var raw = null;
            try { raw = localStorage.getItem(STORE + storageKey); } catch (e) { /* noop */ }
            return raw ? parseInt(raw, 10) : null;
        }
        function write(size) {
            try { localStorage.setItem(STORE + storageKey, String(size)); } catch (e) { /* noop */ }
        }

        function apply(size) {
            if (size == null) size = defaultSize;
            size = Math.max(min, Math.min(max, size));
            if (direction === "horizontal") {
                target.style.width = size + "px";
                target.style.flex = "none";
            } else {
                target.style.height = size + "px";
                target.style.flex = "none";
            }
        }

        var saved = storageKey ? read() : null;
        apply(saved != null ? saved : defaultSize);

        handle.addEventListener("mousedown", function (downEvent) {
            downEvent.preventDefault();
            handle.classList.add("evo-splitter--active");
            document.body.classList.add(direction === "horizontal" ? "evo-dragging" : "evo-dragging-vertical");

            var start = direction === "horizontal" ? downEvent.clientX : downEvent.clientY;
            var startSize = direction === "horizontal" ? target.offsetWidth : target.offsetHeight;

            function onMove(moveEvent) {
                var current = direction === "horizontal" ? moveEvent.clientX : moveEvent.clientY;
                var delta = current - start;
                var size = opts.side === "after" ? startSize - delta : startSize + delta;
                apply(size);
            }
            function onUp() {
                document.removeEventListener("mousemove", onMove);
                document.removeEventListener("mouseup", onUp);
                handle.classList.remove("evo-splitter--active");
                document.body.classList.remove("evo-dragging", "evo-dragging-vertical");
                var size = direction === "horizontal" ? target.offsetWidth : target.offsetHeight;
                if (storageKey) write(size);
            }
            document.addEventListener("mousemove", onMove);
            document.addEventListener("mouseup", onUp);
        });

        handle.addEventListener("dblclick", function () {
            apply(defaultSize);
            if (storageKey) write(defaultSize);
        });

        return { handle: handle, apply: apply };
    }

    /* ------------------------------------------------------------------
     * Command palette
     * ------------------------------------------------------------------ */
    var paletteCommands = [];
    var paletteOpen = false;

    function openCommandPalette() {
        if (paletteOpen) return;
        paletteOpen = true;

        var overlay = el("div", "evo-palette-overlay");
        var box = el("div", "evo-palette");
        var input = el("input", "evo-palette-input");
        input.type = "text";
        input.placeholder = "Type a command…";
        var list = el("div", "evo-palette-list");

        box.appendChild(input);
        box.appendChild(list);
        overlay.appendChild(box);
        document.body.appendChild(overlay);

        var activeIndex = 0;

        function render(filter) {
            list.textContent = "";
            var q = (filter || "").trim().toLowerCase();
            var items = paletteCommands.filter(function (c) {
                return !q || (c.label && c.label.toLowerCase().indexOf(q) !== -1)
                    || (c.keywords && c.keywords.some(function (k) { return k.indexOf(q) !== -1; }));
            });
            if (items.length === 0) {
                list.appendChild(el("div", "evo-palette-empty", "No matching commands"));
                return;
            }
            items.forEach(function (c, i) {
                var item = el("div", "evo-palette-item");
                item.setAttribute("data-active", i === activeIndex ? "true" : "false");
                item.appendChild(el("span", null, c.label));
                if (c.shortcut) item.appendChild(el("span", "evo-palette-key", c.shortcut));
                item.addEventListener("click", function () {
                    close();
                    if (c.action) c.action();
                });
                list.appendChild(item);
            });
        }

        function close() {
            paletteOpen = false;
            overlay.remove();
        }

        function selectActive() {
            var items = list.querySelectorAll(".evo-palette-item");
            if (items.length === 0) return;
            var item = items[Math.min(activeIndex, items.length - 1)];
            close();
            var idx = Array.prototype.indexOf.call(paletteCommands, paletteCommands.find(function (c) {
                return c.label === item.querySelector("span").textContent;
            }));
            // resolve by label text
            var label = item.querySelector("span").textContent;
            var cmd = paletteCommands.filter(function (c) { return c.label === label; })[0];
            if (cmd && cmd.action) cmd.action();
        }

        input.addEventListener("input", function () {
            activeIndex = 0;
            render(input.value);
        });
        input.addEventListener("keydown", function (e) {
            if (e.key === "ArrowDown") { e.preventDefault(); activeIndex = (activeIndex + 1) % list.children.length; render(input.value); }
            else if (e.key === "ArrowUp") { e.preventDefault(); activeIndex = (activeIndex - 1 + list.children.length) % list.children.length; render(input.value); }
            else if (e.key === "Enter") { e.preventDefault(); selectActive(); }
            else if (e.key === "Escape") { e.preventDefault(); close(); }
        });
        overlay.addEventListener("mousedown", function (e) { if (e.target === overlay) close(); });

        render("");
        input.focus();
    }

    function registerCommand(command) {
        if (command && command.label) paletteCommands.push(command);
    }

    /* ------------------------------------------------------------------
     * Context menu
     * ------------------------------------------------------------------ */
    var openMenu = null;
    function contextMenu(items, x, y) {
        closeMenu();
        var menu = el("div", "evo-context-menu");
        items.forEach(function (item) {
            if (item.separator) { menu.appendChild(el("div", "evo-menu-sep")); return; }
            var node = el("div", "evo-menu-item", item.label);
            if (item.danger) node.setAttribute("data-danger", "true");
            if (item.shortcut) node.appendChild(el("span", "evo-menu-key", item.shortcut));
            node.addEventListener("click", function () {
                closeMenu();
                if (item.action) item.action();
            });
            menu.appendChild(node);
        });
        document.body.appendChild(menu);
        var vw = window.innerWidth, vh = window.innerHeight;
        var rect = menu.getBoundingClientRect();
        if (x + rect.width > vw) x = Math.max(4, vw - rect.width - 4);
        if (y + rect.height > vh) y = Math.max(4, vh - rect.height - 4);
        menu.style.left = x + "px";
        menu.style.top = y + "px";
        openMenu = menu;
    }
    function closeMenu() {
        if (openMenu) { openMenu.remove(); openMenu = null; }
    }

    /* ------------------------------------------------------------------
     * Theme
     * ------------------------------------------------------------------ */
    function themeToggle(themeKey) {
        var current = document.documentElement.getAttribute("data-theme") === "light" ? "light" : "dark";
        var next = current === "dark" ? "light" : "dark";
        document.documentElement.setAttribute("data-theme", next);
        try { localStorage.setItem(STORE + (themeKey || "theme"), next); } catch (e) { /* noop */ }
        return next;
    }
    function themeInit(themeKey) {
        var saved = null;
        try { saved = localStorage.getItem(STORE + (themeKey || "theme")); } catch (e) { /* noop */ }
        if (saved === "light" || saved === "dark") {
            document.documentElement.setAttribute("data-theme", saved);
        }
    }

    /* ------------------------------------------------------------------
     * Keyboard shortcuts
     * ------------------------------------------------------------------ */
    var shortcutMap = {};
    function registerShortcut(combo, handler) { shortcutMap[combo] = handler; }

    function comboFromEvent(e) {
        var keys = [];
        if (e.ctrlKey || e.metaKey) keys.push("Ctrl");
        if (e.altKey) keys.push("Alt");
        if (e.shiftKey) keys.push("Shift");
        var key = e.key;
        if (key && key.length === 1) key = key.toLowerCase();
        keys.push(key);
        return keys.join("+");
    }

    function wireGlobalShortcuts(commands) {
        document.addEventListener("keydown", function (e) {
            var target = e.target;
            var typing = target && (target.tagName === "INPUT" || target.tagName === "TEXTAREA" || target.isContentEditable);
            var combo = comboFromEvent(e);

            // Global palette/shortcuts (not while typing, except Ctrl+K).
            if (combo === "Ctrl+k" || combo === "Ctrl+K") {
                e.preventDefault();
                openCommandPalette();
                return;
            }
            if (typing) return;

            var handler = shortcutMap[combo];
            if (handler) {
                e.preventDefault();
                handler();
                return;
            }
            if (commands && commands[combo]) {
                e.preventDefault();
                commands[combo]();
            }
        });

        // Close transient surfaces on Escape / outside click.
        document.addEventListener("keydown", function (e) {
            if (e.key === "Escape") closeMenu();
        });
        document.addEventListener("mousedown", function (e) {
            if (openMenu && !openMenu.contains(e.target)) closeMenu();
        });
    }

    /* ------------------------------------------------------------------
     * Init
     * ------------------------------------------------------------------ */
    function init(cfg) {
        cfg = cfg || {};
        themeInit(cfg.themeKey);
        if (cfg.splits) {
            cfg.splits.forEach(function (s) {
                split(s.before, s.after, s);
            });
        }
        if (cfg.commands) cfg.commands.forEach(registerCommand);
        if (cfg.shortcuts) {
            Object.keys(cfg.shortcuts).forEach(function (k) { registerShortcut(k, cfg.shortcuts[k]); });
        }
        wireGlobalShortcuts();
    }

    window.EVOShell = {
        init: init,
        split: split,
        commands: registerCommand,
        openCommandPalette: openCommandPalette,
        contextMenu: contextMenu,
        closeMenu: closeMenu,
        themeToggle: themeToggle,
        themeInit: themeInit,
        onShortcut: registerShortcut
    };
})();
