#!/usr/bin/env node
// Builds the PWR Live bookmark: the shared metric engine (the /*ENGINE:BEGIN*/ blocks in
// index.html) plus live/pwr-live.js, embedded into index.html as LIVE_CODE.
// It also writes live/pwr-live.min.js, which the short "loader" bookmark fetches from
// jsDelivr at a pinned commit. Run after editing either file:  node tools/build.js
// then commit, then run it once more so the loader points at that commit.
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
// Pin the loader to the last commit whose min file matches what we just built.
let pinned = null;
try {
  const sha = execSync("git log -1 --format=%H -- live/pwr-live.min.js", { cwd: ROOT }).toString().trim();
  if (sha && execSync(`git show ${sha}:live/pwr-live.min.js`, { cwd: ROOT, maxBuffer: 1e8 }).toString() === minCode) pinned = sha;
} catch (e) { /* not a git checkout */ }
const repo = "maskeralexander-byte/blah";
if (pinned) {
  const url = `https://cdn.jsdelivr.net/gh/${repo}@${pinned}/live/pwr-live.min.js`;
  html = html.replace(/\/\*LOADER:BEGIN\*\/[\s\S]*?\/\*LOADER:END\*\//, () => `/*LOADER:BEGIN*/const LIVE_URL = ${JSON.stringify(url)};/*LOADER:END*/`);
  console.log("Loader pinned to", pinned.slice(0, 10));
} else console.log("live/pwr-live.min.js changed: commit it (and push), then run this again to pin the loader.");

const LS = String.fromCharCode(0x2028), PS = String.fromCharCode(0x2029);
const literal = JSON.stringify(out).replace(/<\//g, "<\\/").split(LS).join("\\u2028").split(PS).join("\\u2029");
const before = html;
html = html.replace(/\/\*BOOKMARKLET:BEGIN\*\/[\s\S]*?\/\*BOOKMARKLET:END\*\//, () => `/*BOOKMARKLET:BEGIN*/const LIVE_CODE = ${literal};/*BOOKMARKLET:END*/`);
if (html === before && !before.includes(literal)) throw new Error("BOOKMARKLET markers not found in index.html");
fs.writeFileSync(htmlPath, html);
console.log(`PWR Live bookmark: ${(out.length / 1024).toFixed(1)} KB of code, ${(encodeURIComponent(out).length / 1024).toFixed(1)} KB as a bookmark URL.`);
})().catch(e => { console.error(e); process.exit(1); });
