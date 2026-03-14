// ==UserScript==
// @name         Hubitat Timestamp Converter
// @version      16.2
// @description  Hubitat UI timestamp conversion
// @namespace    vinnyw
// @author       vinnyw

// pages 
// @match        http://*/device/*
// @match        https://*/device/*
// @match        http://*/device/events/*
// @match        https://*/device/events/*
// @match        http://*/device/list*
// @match        https://*/device/list*
// @match        http://*/logs*
// @match        https://*/logs*
// @match        http://*/hub/*
// @match        https://*/hub/*
// @match        http://*/app/*
// @match        https://*/app/*
// @match        http://*/driver/*
// @match        https://*/driver/*
// @match        http://*/driver/list*
// @match        https://*/driver/list*

// auto-update
// @downloadURL  https://raw.githubusercontent.com/vinnyw/hubitat/master/Tools/Hubitat-Timestamp/hubitat-timestamp.user.js
// @updateURL    https://raw.githubusercontent.com/vinnyw/hubitat/master/Tools/Hubitat-Timestamp/hubitat-timestamp.meta.js

// @run-at       document-end
// @grant        none
// ==/UserScript==

(function () {
    'use strict';

    /* ========= CONFIG ========= */
    const MODE = "ISO";            // "ISO" or "UK"
    const KEEP_MS = false
    const KEEP_TZ = false
    const RESCAN_DOM = 5000

    /* ========= TAGS ========= */
    const SKIP_TAGS = new Set([
        "SVG", "PATH", "BUTTON", "INPUT", "TEXTAREA", "SELECT",
        "SCRIPT", "STYLE", "NOSCRIPT", "CODE", "PRE", "IFRAME"
    ])

    /* ========= MATCH ========= */
    const usRegex = /\b(\d{1,2})\/(\d{1,2})\/(\d{4}) (\d{2}:\d{2}:\d{2})(\.\d+)?\b/g
    const isoRegex = /\b(\d{4})-(\d{2})-(\d{2}) (\d{2}:\d{2}:\d{2})(\.\d+)?(?:\s([A-Z]+))?\b/g

    function pad(n) {
        return String(n).padStart(2, '0')
    }

    function convertUS(match, m, d, y, t, ms) {
        const mm = pad(m)
        const dd = pad(d)
        const suffix = (KEEP_MS && ms) ? ms : ""

        if (MODE === "UK") {
            return `${dd}/${mm}/${y} ${t}${suffix}`
        }

        return `${y}-${mm}-${dd} ${t}${suffix}`
    }

    function convertISO(match, y, m, d, t, ms, tz) {

        const suffix = (KEEP_MS && ms) ? ms : ""
        const timezone = (KEEP_TZ && tz) ? " " + tz : ""

        if (MODE === "UK") {
            return `${d}/${m}/${y} ${t}${suffix}${timezone}`
        }

        return `${y}-${m}-${d} ${t}${suffix}${timezone}`
    }

    function convertText(text) {
        if (!text || text.indexOf(':') === -1) return text
        return text
            .replace(usRegex, convertUS)
            .replace(isoRegex, convertISO)
    }

    function processNode(node) {

        if (node.nodeType === 3) {

            const updated = convertText(node.nodeValue)

            if (updated !== node.nodeValue) {
                node.nodeValue = updated
            }

            return
        }

        if (node.nodeType !== 1) return

        if (SKIP_TAGS.has(node.tagName)) return

        const children = node.childNodes

        for (let i = 0; i < children.length; i++) {
            processNode(children[i])
        }

    }

    /* ---------- initial scan ---------- */

    processNode(document.body)

    /* ---------- intercept DOM insertions ---------- */

    const originalAppend = Element.prototype.appendChild
    const originalInsert = Element.prototype.insertBefore

    Element.prototype.appendChild = function (node) {

        processNode(node)

        return originalAppend.call(this, node)

    }

    Element.prototype.insertBefore = function (node, ref) {

        processNode(node)

        return originalInsert.call(this, node, ref)

    }

    /* ---------- periodic safety rescan ---------- */

    setInterval(() => {

        processNode(document.body)

    }, RESCAN_DOM)

})();