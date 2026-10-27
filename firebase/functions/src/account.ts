import { logger } from "firebase-functions";
import { onCall } from "firebase-functions/v2/https";
import { auth, callableOptions, db, deleteStoragePrefix, requireAuth } from "./util.js";

/**
 * Removes a match and everything hanging off it: messages, typing docs, chat images, and private
 * album access in both directions. Safe to call twice.
 */
export async function deleteMatch(matchId: string, users: string[]): Promise<void> {
  const matchRef = db.collection("matches").doc(matchId);
  await db.recursiveDelete(matchRef);
  await deleteStoragePrefix(`chats/${matchId}/`);
  if (users.length === 2) {
    await revokeAlbumAccessBetween(users[0], users[1]);
  }
}

/** Deletes the private-album access docs between two users, whichever direction they point. */
export async function revokeAlbumAccessBetween(a: string, b: string): Promise<void> {
  const batch = db.batch();
  batch.delete(db.doc(`albums/${a}/access/${b}`));
  batch.delete(db.doc(`albums/${b}/access/${a}`));
  await batch.commit();
}

/**
 * Deletes everything Ember stores about `uid` except moderation reports, which are kept so that
 * abuse can still be investigated after the reported account disappears. Does not touch the
 * Auth user; callers decide whether to delete or disable it.
 */
export async function deleteUserData(uid: string): Promise<void> {
  const userRef = db.collection("users").doc(uid);

  const matches = await db.collection("matches").where("users", "array-contains", uid).get();
  for (const match of matches.docs) {
    await deleteMatch(match.id, (match.get("users") as string[] | undefined) ?? []);
  }

  // Album access this user holds on other people's albums.
  const viewerAccess = await db.collectionGroup("access").where("viewerId", "==", uid).get();
  if (!viewerAccess.empty) {
    const writer = db.bulkWriter();
    viewerAccess.docs.forEach((d) => writer.delete(d.ref));
    await writer.close();
  }

  // Block docs are written in pairs; remove the mirror copies held by the other side.
  const blocks = await userRef.collection("blocks").get();
  if (!blocks.empty) {
    const writer = db.bulkWriter();
    blocks.docs.forEach((d) => writer.delete(db.doc(`users/${d.id}/blocks/${uid}`)));
    await writer.close();
  }

  await db.recursiveDelete(db.collection("albums").doc(uid));
  await db.recursiveDelete(userRef);

  await Promise.all([
    deleteStoragePrefix(`users/${uid}/`),
    deleteStoragePrefix(`private/${uid}/`),
  ]);
  logger.info("Deleted user data", { uid, matches: matches.size });
}

/** Deletes the caller's account and all of their data. Irreversible. */
export const deleteAccount = onCall(callableOptions, async (request) => {
  const uid = requireAuth(request);
  await deleteUserData(uid);
  try {
    await auth.deleteUser(uid);
  } catch (error) {
    // Already gone is the outcome the caller wanted.
    logger.warn("Auth user delete failed", { uid, error: String(error) });
  }
  return { ok: true };
});
