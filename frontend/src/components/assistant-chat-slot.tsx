import { connection } from "next/server";
import { assistantConfigured } from "@/lib/api/assistant";
import { AssistantChat } from "./assistant-chat";

/**
 * The chat, only where the assistant is configured (ADR-0095). The layout is prerendered at build time, but the token
 * is the running server's: connection() stops prerendering here, so the environment is read per request on the
 * server, as every address is (ADR-0082). Rendered inside a Suspense boundary, so the rest of the page stays static.
 */
export async function AssistantChatSlot() {
  await connection();
  return assistantConfigured() ? <AssistantChat /> : null;
}
