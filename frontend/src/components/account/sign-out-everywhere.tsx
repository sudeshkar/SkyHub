import { signOutEverywhere } from "@/app/account/security-actions";

/** "Sign out everywhere" (ADR-0047): a form post, so nothing about it is in an address. It says what it does before it is pressed. */
export function SignOutEverywhere() {
  return (
    <section aria-labelledby="sign-out-everywhere" className="mt-8 border-t border-sand pt-6">
      <h2 id="sign-out-everywhere" className="font-display text-xl">Security</h2>
      <p className="mt-2 max-w-prose text-sm text-muted">
        Lost a phone, or left yourself signed in on a computer that is not yours? This signs you out on every device, including this
        one. Your password is not changed, so you can sign in again straight away.
      </p>
      <form action={signOutEverywhere} className="mt-3">
        <button type="submit" className="rounded-md border border-sand px-4 py-2 text-sm hover:bg-white">
          Sign out everywhere
        </button>
      </form>
    </section>
  );
}
