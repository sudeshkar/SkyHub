/**
 * What a password may be (ADR-0048): at least 12 characters, and at most 72 bytes in UTF-8. The same rule, with the same words, as
 * the booking service's `PasswordPolicy`, so the page never promises what the service refuses. The upper limit is the hashing's
 * (BCrypt cannot store more than 72 bytes), which is why it is counted in bytes: 50 accented letters are 100 bytes. The lower limit
 * is counted in characters, because it is about what a person typed. The password is judged exactly as given.
 */
export const MIN_PASSWORD_CHARACTERS = 12;
export const MAX_PASSWORD_BYTES = 72;

export const PASSWORD_RULE = `A password needs ${MIN_PASSWORD_CHARACTERS} to ${MAX_PASSWORD_BYTES} characters, counting accents and symbols as more than one (${MAX_PASSWORD_BYTES} bytes at most)`;

const encoder = new TextEncoder();

/** @returns why the password is not acceptable, or null if it is. It never contains the password. */
export function passwordProblem(password: string): string | null {
  // Spreading counts code points, so a symbol that takes two UTF-16 units is one character, as it is to the person.
  if ([...password].length < MIN_PASSWORD_CHARACTERS) return PASSWORD_RULE;
  // Every UTF-16 unit takes at least one byte, so a long string is over the limit without encoding all of it.
  if (password.length > MAX_PASSWORD_BYTES || encoder.encode(password).length > MAX_PASSWORD_BYTES) return PASSWORD_RULE;
  return null;
}
