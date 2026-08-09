// ==UserScript==
// @name         LinkCast TV Browser Receiver
// @namespace    https://linkcast.local/
// @version      1.0.0
// @description  Opens links sent to the local LinkCast TV Host app.
// @match        http://*/*
// @match        https://*/*
// @run-at       document-start
// @grant        GM_xmlhttpRequest
// @connect      127.0.0.1
// @connect      localhost
// ==/UserScript==

(() => {
    "use strict";

    const NEXT_URL = "http://127.0.0.1:8765/next";
    let stopped = false;
    let request = null;

    function isSafeHttpUrl(value) {
        try {
            const parsed = new URL(value);
            return parsed.protocol === "http:" || parsed.protocol === "https:";
        } catch (_) {
            return false;
        }
    }

    function poll() {
        if (stopped || document.visibilityState === "hidden") return;

        request = GM_xmlhttpRequest({
            method: "GET",
            url: NEXT_URL + "?t=" + Date.now(),
            timeout: 30000,
            headers: { "Cache-Control": "no-store" },
            onload(response) {
                request = null;
                if (response.status === 200) {
                    const target = response.responseText.trim();
                    if (isSafeHttpUrl(target) && target !== location.href) {
                        location.assign(target);
                        return;
                    }
                }
                setTimeout(poll, response.status === 204 ? 50 : 1000);
            },
            ontimeout() {
                request = null;
                setTimeout(poll, 250);
            },
            onerror() {
                request = null;
                setTimeout(poll, 2000);
            }
        });
    }

    document.addEventListener("visibilitychange", () => {
        if (document.visibilityState === "hidden") {
            if (request && typeof request.abort === "function") request.abort();
            request = null;
        } else {
            poll();
        }
    });

    window.addEventListener("pagehide", () => {
        stopped = true;
        if (request && typeof request.abort === "function") request.abort();
    });

    poll();
})();
