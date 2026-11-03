// Proves the website's tracing end to end against a real production server (ADR-0079). Not part of `npm test`: it starts `next start`.
//
//   npm run build && node scripts/trace-check.mjs
//
// It starts a stub collector (what Tempo would receive) and a stub backend (what the booking and search services would receive), starts the
// built site with tracing on, asks for pages, and checks four things:
//   1. spans reach the collector, named for the website;
//   2. a one-time token in a page's address (?token=...) appears in NO exported span;
//   3. the backend is called with a W3C `traceparent`, and its trace id is the one the collector got, so one request is one trace;
//   4. with tracing off (the default) nothing is exported at all.
// It always stops what it started. Exit code 0 only if every check passes.

import { spawn } from "node:child_process";
import http from "node:http";
import { setTimeout as sleep } from "node:timers/promises";

const SECRET = "SUPERSECRET-ONE-TIME-TOKEN-0123456789";
const SITE_PORT = 3981;

/** A server that records every request it gets; `reply` decides the answer. */
function stub(reply) {
  const seen = [];
  const server = http.createServer((request, response) => {
    const chunks = [];
    request.on("data", (c) => chunks.push(c));
    request.on("end", () => {
      seen.push({ url: request.url, headers: request.headers, body: Buffer.concat(chunks).toString("utf8") });
      reply(request, response);
    });
  });
  return new Promise((resolve) => server.listen(0, "127.0.0.1", () => resolve({ server, seen, port: server.address().port })));
}

function startSite(env) {
  const child = spawn(process.execPath, ["node_modules/next/dist/bin/next", "start", "-p", String(SITE_PORT)], {
    env: { ...process.env, ...env, NODE_ENV: "production" }, stdio: ["ignore", "pipe", "pipe"],
  });
  const output = [];
  child.stdout.on("data", (c) => output.push(String(c)));
  child.stderr.on("data", (c) => output.push(String(c)));
  return { child, output };
}

async function waitUntilUp() {
  for (let i = 0; i < 60; i++) {
    try {
      const r = await fetch(`http://127.0.0.1:${SITE_PORT}/account/sign-up`);
      if (r.status < 500) return;
    } catch { /* not yet */ }
    await sleep(500);
  }
  throw new Error("the site did not start");
}

async function stop(site) {
  site.child.kill("SIGTERM");
  await sleep(1500);
  if (site.child.exitCode === null) site.child.kill("SIGKILL");
}

const results = [];
const check = (name, ok, detail = "") => {
  results.push(ok);
  console.log(`${ok ? "PASS" : "FAIL"}  ${name}${ok ? "" : "  -> " + detail}`);
};

const collector = await stub((_req, res) => { res.writeHead(200, { "Content-Type": "application/json" }); res.end("{}"); });
const backend = await stub((_req, res) => { res.writeHead(404, { "Content-Type": "application/json" }); res.end("{}"); });
const baseEnv = {
  SKYHUB_SEARCH_API_URL: `http://127.0.0.1:${backend.port}`, SKYHUB_BOOKING_API_URL: `http://127.0.0.1:${backend.port}`,
  SKYHUB_AGENT_API_URL: `http://127.0.0.1:${backend.port}`,
};

let site;
try {
  // ---- tracing on ----------------------------------------------------------------------------------------------------------
  site = startSite({ ...baseEnv, TRACING_EXPORT_ENABLED: "true", OTLP_TRACES_ENDPOINT: `http://127.0.0.1:${collector.port}/v1/traces` });
  await waitUntilUp();
  await fetch(`http://127.0.0.1:${SITE_PORT}/account/set-password?token=${SECRET}`);
  await fetch(`http://127.0.0.1:${SITE_PORT}/search?origin=DXB&destination=LHR&date=2030-01-15&passengers=1`);
  for (let i = 0; i < 20 && collector.seen.length === 0; i++) await sleep(1000);   // the exporter sends in batches, a few seconds apart
  await sleep(2000);
  await stop(site);
  site = undefined;

  const exported = collector.seen.map((r) => r.body).join("\\n");
  check("spans reach the collector", collector.seen.length > 0, "nothing was exported");
  check("they are named for the website", exported.includes("skyhub-website"), "service name missing");
  check("a one-time token in a page's address is in none of them", collector.seen.length > 0 && !exported.includes(SECRET), "THE TOKEN WAS EXPORTED");
  check("the page's path is still there to find the request by", exported.includes("/account/set-password"), "path missing");

  const calls = backend.seen.filter((r) => r.headers.traceparent);
  check("the backend is called with a W3C traceparent", calls.length > 0, `${backend.seen.length} calls, none with traceparent`);
  const traceId = calls[0]?.headers.traceparent.split("-")[1];
  check("and that trace is the one the collector received", Boolean(traceId) && exported.includes(traceId), `trace ${traceId} not exported`);

  // ---- tracing off (the default) -------------------------------------------------------------------------------------------
  const before = collector.seen.length;
  site = startSite({ ...baseEnv, OTLP_TRACES_ENDPOINT: `http://127.0.0.1:${collector.port}/v1/traces` });
  await waitUntilUp();
  await fetch(`http://127.0.0.1:${SITE_PORT}/account/set-password?token=${SECRET}`);
  await sleep(8000);
  await stop(site);
  site = undefined;
  check("with tracing off nothing is exported", collector.seen.length === before, `${collector.seen.length - before} more exports`);
} catch (error) {
  console.log(`ERROR  ${error.message}`);
  results.push(false);
} finally {
  if (site) await stop(site);
  collector.server.close();
  backend.server.close();
}

const failed = results.filter((ok) => !ok).length;
console.log(failed === 0 ? "\nAll checks passed." : `\n${failed} check(s) failed.`);
process.exit(failed === 0 ? 0 : 1);
