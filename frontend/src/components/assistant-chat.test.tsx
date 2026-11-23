import { act, fireEvent, render, screen, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { askAssistant } from "@/app/assistant/actions";
import { AssistantChat } from "./assistant-chat";

let pathname = "/";
vi.mock("next/navigation", () => ({ usePathname: () => pathname }));
vi.mock("@/app/assistant/actions", () => ({ askAssistant: vi.fn() }));

function open() {
  render(<AssistantChat />);
  fireEvent.click(screen.getByRole("button", { name: "Ask SkyHub" }));
  return screen.getByRole("region", { name: "SkyHub assistant" });
}

async function send(text: string) {
  fireEvent.change(screen.getByLabelText("Your message"), { target: { value: text } });
  await act(async () => {
    fireEvent.click(screen.getByRole("button", { name: "Send" }));
  });
}

beforeEach(() => {
  pathname = "/";
  vi.mocked(askAssistant).mockReset();
  vi.mocked(askAssistant).mockResolvedValue({
    kind: "answer",
    reply: "Online check-in opens 24 hours before departure.",
    sources: ["Checking in online"],
    usedBooking: false,
  });
});

describe("AssistantChat", () => {
  it("starts closed, as a button, and opens a panel that says what it can and cannot do", () => {
    render(<AssistantChat />);
    const button = screen.getByRole("button", { name: "Ask SkyHub" });
    expect(button).toHaveAttribute("aria-expanded", "false");

    fireEvent.click(button);

    const panel = screen.getByRole("region", { name: "SkyHub assistant" });
    expect(panel).toHaveTextContent(/can't change a booking/);
    expect(panel).toHaveTextContent(/demonstration/);
    expect(button).toHaveAttribute("aria-expanded", "true");
  });

  it("asks, shows the traveller's message and the reply with the articles it came from", async () => {
    const panel = open();

    await send("When can I check in?");

    expect(askAssistant).toHaveBeenCalledWith("When can I check in?", []);
    expect(within(panel).getByText("When can I check in?")).toBeInTheDocument();
    expect(within(panel).getByText("Online check-in opens 24 hours before departure.")).toBeInTheDocument();
    expect(within(panel).getByText("From the help centre: Checking in online")).toBeInTheDocument();
    expect(screen.getByLabelText("Your message")).toHaveValue("");
  });

  it("says when an answer came from the booking the traveller has open", async () => {
    vi.mocked(askAssistant).mockResolvedValue({ kind: "answer", reply: "Passenger 1 is checked in.", sources: [], usedBooking: true });
    const panel = open();

    await send("Am I checked in?");

    expect(within(panel).getByText("From the booking you have open")).toBeInTheDocument();
  });

  it("sends the conversation so far with the next message", async () => {
    open();
    await send("When can I check in?");
    await send("And when does it close?");

    expect(askAssistant).toHaveBeenLastCalledWith("And when does it close?", [
      { role: "traveller", text: "When can I check in?" },
      { role: "assistant", text: "Online check-in opens 24 hours before departure." },
    ]);
  });

  it("sends no more than the last six turns", async () => {
    open();
    for (const n of [1, 2, 3, 4]) await send(`question ${n}`);

    const history = vi.mocked(askAssistant).mock.calls[3][1];
    expect(history).toHaveLength(6);
    expect(history[0]).toEqual({ role: "traveller", text: "question 1" });
    await send("question 5");
    expect(vi.mocked(askAssistant).mock.calls[4][1][0]).toEqual({ role: "traveller", text: "question 2" });
  });

  it("shows a reply as text, so nothing a model writes can run in the page", async () => {
    vi.mocked(askAssistant).mockResolvedValue({
      kind: "answer",
      reply: '<img src="x" onerror="alert(1)"> **bold**',
      sources: [],
      usedBooking: false,
    });
    const panel = open();

    await send("Hi");

    expect(within(panel).getByText('<img src="x" onerror="alert(1)"> **bold**')).toBeInTheDocument();
    expect(panel.querySelector("img")).toBeNull();
  });

  it("says plainly when it cannot answer, and gives the message back to try again", async () => {
    vi.mocked(askAssistant).mockResolvedValue({ kind: "error", message: "The assistant is busy with other travellers. Please try again in a moment." });
    open();

    await send("When can I check in?");

    expect(screen.getByRole("alert")).toHaveTextContent("busy with other travellers");
    expect(screen.getByLabelText("Your message")).toHaveValue("When can I check in?");
  });

  it("leaves a message that failed out of the conversation it sends next time", async () => {
    vi.mocked(askAssistant).mockResolvedValueOnce({ kind: "error", message: "Busy." });
    open();
    await send("first try");
    await send("second try");

    expect(askAssistant).toHaveBeenLastCalledWith("second try", []);
  });

  it("does not send an empty message", () => {
    open();

    fireEvent.change(screen.getByLabelText("Your message"), { target: { value: "   " } });

    expect(screen.getByRole("button", { name: "Send" })).toBeDisabled();
  });

  it("holds a message to what the assistant accepts", () => {
    open();

    expect(screen.getByLabelText("Your message")).toHaveAttribute("maxLength", "1000");
  });

  it("sends with Enter and starts a new line with Shift and Enter", async () => {
    open();
    const box = screen.getByLabelText("Your message");
    fireEvent.change(box, { target: { value: "Hi" } });

    fireEvent.keyDown(box, { key: "Enter", shiftKey: true });
    expect(askAssistant).not.toHaveBeenCalled();
    await act(async () => {
      fireEvent.keyDown(box, { key: "Enter" });
    });
    expect(askAssistant).toHaveBeenCalledWith("Hi", []);
  });

  it("keeps the conversation when it is closed and opened again", async () => {
    open();
    await send("When can I check in?");

    fireEvent.click(screen.getByRole("button", { name: "Close the assistant" }));
    fireEvent.click(screen.getByRole("button", { name: "Ask SkyHub" }));

    expect(screen.getByText("When can I check in?")).toBeInTheDocument();
  });

  it("is not on the operations pages", () => {
    pathname = "/ops/gate";

    const { container } = render(<AssistantChat />);

    expect(container).toBeEmptyDOMElement();
  });
});
