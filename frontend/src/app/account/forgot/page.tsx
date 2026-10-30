import type { Metadata } from "next";
import { LinkForm } from "../link-form";

export const metadata: Metadata = { title: "Forgot your password? — SkyHub", robots: { index: false, follow: false } };

export default function ForgotPage() {
  return (
    <div className="mx-auto max-w-3xl px-4 py-10">
      <h1 className="font-display text-3xl">Your account</h1>
      <div className="mt-8">
        <LinkForm mode="reset" />
      </div>
    </div>
  );
}
