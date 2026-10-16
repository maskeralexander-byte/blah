#!/usr/bin/env node
// Builds the PWR Live bookmark: the shared metric engine (the /*ENGINE:BEGIN*/ blocks in
// index.html) plus live/pwr-live.js, embedded into index.html as LIVE_CODE.
// It also writes live/pwr-live.min.js, which the short "loader" bookmark fetches from jsDelivr.
// Run after editing either file:  node tools/build.js
// Uses terser to shrink the bookmark when it's installed (npm i -g terser or npx), else a light trim.
"use strict";
const fs = require("fs"), path = require("path");
const ROOT = path.join(__dirname, "..");
const htmlPath = path.join(ROOT, "index.html");
let html = fs.readFileSync(htmlPath, "utf8");

const engine = [...html.matchAll(/\/\*ENGINE:BEGIN\*\/([\s\S]*?)\/\*ENGINE:END\*\//g)].map(m => m[1]).join("\n");
const live = fs.readFileSync(path.join(ROOT, "live", "pwr-live.js"), "utf8");

// Light size trim: drop full-line // comments and leading indentation. Nothing else changes.
const code = (engine + "\n" + live)
  .replace(/^\s*\/\*[\s\S]*?\*\/\s*$/m, "")
  .split("\n").map(l => l.trimStart()).filter(l => l && !l.startsWith("//")).join("\n");

new Function("T", code); // syntax check; throws on error

(async () => {
let out = `(function(T){${code}})(__TARGETS__)`;
try {
  const { minify } = require(process.env.TERSER_PATH || "terser");
  out = (await minify(out, { ecma: 2020, compress: { passes: 2 }, mangle: { reserved: ["__TARGETS__"] }, format: { ascii_only: true } })).code;
} catch (e) { if (e.code !== "MODULE_NOT_FOUND") throw e; console.log("terser not found; using the untrimmed build."); }
if (!out.includes("__TARGETS__")) throw new Error("targets placeholder lost during minify");

const { execSync } = require("child_process");
const minPath = path.join(ROOT, "live", "pwr-live.min.js");
const minCode = out.replace("__TARGETS__", "window.__PWR_T||{}") + "\n";
fs.writeFileSync(minPath, minCode);
// The loader follows this branch on jsDelivr, so a pushed build reaches existing bookmarks without
// re-copying (jsDelivr caches branches; purge after pushing: see README).
const repo = "maskeralexander-byte/blah";
let branch = "main";
try { branch = execSync("git rev-parse --abbrev-ref HEAD", { cwd: ROOT }).toString().trim(); } catch (e) {}
const url = `https://cdn.jsdelivr.net/gh/${repo}@${encodeURIComponent(branch)}/live/pwr-live.min.js`;
html = html.replace(/\/\*LOADER:BEGIN\*\/[\s\S]*?\/\*LOADER:END\*\//, () => `/*LOADER:BEGIN*/const LIVE_URL = ${JSON.stringify(url)};/*LOADER:END*/`);
console.log("Loader follows", url);
console.log("After pushing, refresh the CDN copy:  curl " + url.replace("cdn.jsdelivr.net", "purge.jsdelivr.net"));

const LS = String.fromCharCode(0x2028), PS = String.fromCharCode(0x2029);
const literal = JSON.stringify(out).replace(/<\//g, "<\\/").split(LS).join("\\u2028").split(PS).join("\\u2029");
const before = html;
html = html.replace(/\/\*BOOKMARKLET:BEGIN\*\/[\s\S]*?\/\*BOOKMARKLET:END\*\//, () => `/*BOOKMARKLET:BEGIN*/const LIVE_CODE = ${literal};/*BOOKMARKLET:END*/`);
if (html === before && !before.includes(literal)) throw new Error("BOOKMARKLET markers not found in index.html");
fs.writeFileSync(htmlPath, html);
console.log(`PWR Live bookmark: ${(out.length / 1024).toFixed(1)} KB of code, ${(encodeURIComponent(out).length / 1024).toFixed(1)} KB as a bookmark URL.`);
})().catch(e => { console.error(e); process.exit(1); });
