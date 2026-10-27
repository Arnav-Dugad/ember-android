import { logger } from "firebase-functions";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { deleteUserData } from "./account.js";
import { FieldValue, auth, callableOptions, db, inputObject, requireAuth } from "./util.js";

export const MIN_AGE = 18;
export const MAX_AGE = 100;

/**
 * Whole years between `birthDate` (YYYY-MM-DD, interpreted in UTC) and `now`, or null when the
 * string is malformed, names a day that does not exist (2001-02-30), or lies in the future.
 * Someone born on 29 February turns a year older on 1 March in non-leap years, the
 * conservative reading.
 */
export function ageOn(birthDate: string, now: Date = new Date()): number | null {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(birthDate);
  if (!match) return null;
  const [y, m, d] = [Number(match[1]), Number(match[2]), Number(match[3])];
  const date = new Date(Date.UTC(y, m - 1, d));
  if (date.getUTCFullYear() !== y || date.getUTCMonth() !== m - 1 || date.getUTCDate() !== d) return null;

  const ny = now.getUTCFullYear();
  const nm = now.getUTCMonth() + 1;
  const nd = now.getUTCDate();
  const today = Date.UTC(ny, nm - 1, nd);
  if (date.getTime() > today) return null;

  const hadBirthday = nm > m || (nm === m && nd >= d);
  return ny - y - (hadBirthday ? 0 : 1);
}

/**
 * The 18+ gate. Verifies the date of birth server side, records terms acceptance, and grants the
 * `adult` custom claim that every security rule depends on. An under-18 date disables the
 * account and deletes whatever it had stored. A verified date of birth cannot be changed later.
 */
export const completeAgeGate = onCall(callableOptions, async (request) => {
  const uid = requireAuth(request);
  const input = inputObject(request.data);
  const birthDate = input.birthDate;
  const termsVersion = input.termsVersion;

  if (typeof birthDate !== "string") throw new HttpsError("invalid-argument", "birthDate must be YYYY-MM-DD.");
  if (typeof termsVersion !== "string" || termsVersion.length === 0 || termsVersion.length > 32) {
    throw new HttpsError("invalid-argument", "termsVersion is required.");
  }

  const accountRef = db.doc(`users/${uid}/private/account`);
  const existing = await accountRef.get();
  const verifiedBirthDate = existing.get("birthDate") as string | undefined;
  if (existing.get("ageVerifiedAt") && verifiedBirthDate && verifiedBirthDate !== birthDate) {
    throw new HttpsError("failed-precondition", "birthdate-locked");
  }

  const age = ageOn(birthDate);
  if (age === null || age > MAX_AGE) {
    throw new HttpsError("invalid-argument", "That date of birth is not valid.");
  }

  if (age < MIN_AGE) {
    logger.warn("Underage sign-up blocked", { uid });
    await auth.updateUser(uid, { disabled: true });
    await auth.revokeRefreshTokens(uid);
    await deleteUserData(uid);
    throw new HttpsError("failed-precondition", "underage");
  }

  const user = await auth.getUser(uid);
  await auth.setCustomUserClaims(uid, { ...(user.customClaims ?? {}), adult: true });

  const userRef = db.collection("users").doc(uid);
  await db.runTransaction(async (tx) => {
    const profile = await tx.get(userRef);
    tx.set(accountRef, {
      birthDate,
      termsVersion,
      termsAcceptedAt: FieldValue.serverTimestamp(),
      ageVerifiedAt: existing.get("ageVerifiedAt") ?? FieldValue.serverTimestamp(),
    }, { merge: true });
    tx.set(userRef, {
      uid,
      age,
      ...(profile.exists && profile.get("createdAt") ? {} : { createdAt: FieldValue.serverTimestamp() }),
    }, { merge: true });
  });

  return { age };
});
