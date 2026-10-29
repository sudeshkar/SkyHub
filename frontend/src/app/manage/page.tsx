import type { Metadata } from "next";
import { ManageForm } from "./manage-form";

export const metadata: Metadata = {
  title: "Manage booking — SkyHub",
};

export default function ManagePage() {
  return (
    <div className="mx-auto max-w-5xl px-4 py-10">
      <h1 className="font-display text-3xl">Manage your booking</h1>
      <p className="mb-8 mt-2 text-muted">You need your booking reference and the last name of any passenger.</p>
      <ManageForm />
    </div>
  );
}
