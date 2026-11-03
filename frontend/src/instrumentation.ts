/**
 * Next calls this once when the server starts (ADR-0079). The tracing itself is only ever loaded on the Node server, never in the Edge
 * runtime, and starts nothing unless `TRACING_EXPORT_ENABLED` is `true`.
 */
export async function register(): Promise<void> {
  if (process.env.NEXT_RUNTIME === "nodejs") {
    const { startTracing } = await import("./instrumentation.node");
    startTracing(process.env);
  }
}
