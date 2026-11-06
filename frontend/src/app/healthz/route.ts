/**
 * Liveness for whatever runs the container (compose, Kubernetes): the site is answering. It says nothing about the
 * services behind it, so a slow backend never gets the website restarted for something a restart cannot fix (ADR-0083).
 */
export async function GET() {
  return Response.json({ status: "UP" }, { headers: { "Cache-Control": "no-store" } });
}
