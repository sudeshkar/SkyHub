import type { Metadata } from "next";
import { LinkForm } from "../link-form";

export const metadata: Metadata = { title: "Create an account — SkyHub", robots: { index: false, follow: false } };

export default function SignUpPage() {
  return (
    <div className="mx-auto max-w-3xl px-4 py-10">
      <h1 className="font-display text-3xl">Your account</h1>
      <div className="mt-8">
        <LinkForm mode="sign-up" />
      </div>
    </div>
  );
}
