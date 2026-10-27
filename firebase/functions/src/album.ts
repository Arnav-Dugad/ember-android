import { onDocumentWritten } from "firebase-functions/v2/firestore";
import { REGION, db, matchIdOf, pushToUser } from "./util.js";

async function nameOf(uid: string): Promise<string> {
  const user = await db.doc(`users/${uid}`).get();
  return (user.get("name") as string) || "Your match";
}

/**
 * Notifies the owner of a new private-album request, and the viewer when access is approved.
 * Denials and revocations are deliberately silent.
 */
export const onAlbumAccessWritten = onDocumentWritten(
  { document: "albums/{ownerId}/access/{viewerId}", region: REGION },
  async (event) => {
    const { ownerId, viewerId } = event.params;
    const before = event.data?.before;
    const after = event.data?.after;
    if (!after?.exists) return;

    const oldStatus = before?.exists ? before.get("status") : null;
    const newStatus = after.get("status");
    if (oldStatus === newStatus) return;
    const matchId = matchIdOf(ownerId, viewerId);

    if (newStatus === "pending") {
      await pushToUser(ownerId, {
        type: "album",
        title: "Private album request",
        body: `${await nameOf(viewerId)} asked to see your private album.`,
        matchId,
      });
    } else if (newStatus === "approved") {
      await pushToUser(viewerId, {
        type: "album",
        title: "Private album unlocked",
        body: `${await nameOf(ownerId)} shared their private album with you.`,
        matchId,
      });
    }
  },
);
