import { logger } from "firebase-functions";
import { onDocumentCreated } from "firebase-functions/v2/firestore";
import { REGION, db, pushToUser } from "./util.js";

const PREVIEW_LENGTH = 120;

function preview(type: string, text: string): string {
  switch (type) {
    case "image":
      return text ? `Photo: ${text}` : "Sent a photo";
    case "game":
      return "Sent a Truth or Dare card";
    default:
      return text.length > PREVIEW_LENGTH ? `${text.slice(0, PREVIEW_LENGTH - 1)}…` : text;
  }
}

/**
 * Keeps the match list fresh (last message, sender's read marker) and pushes the message to the
 * other participant. Messages from anyone who is not a participant are ignored; rules already
 * reject them, this is the belt to those braces.
 */
export const onMessageCreated = onDocumentCreated(
  { document: "matches/{matchId}/messages/{messageId}", region: REGION },
  async (event) => {
    const { matchId } = event.params;
    const message = event.data;
    if (!message) return;

    const senderId = message.get("senderId") as string;
    const type = (message.get("type") as string) ?? "text";
    const text = (message.get("text") as string) ?? "";
    const sentAt = message.get("createdAt") ?? message.createTime;

    const matchRef = db.collection("matches").doc(matchId);
    const match = await matchRef.get();
    if (!match.exists) return;
    const users = (match.get("users") as string[]) ?? [];
    if (!users.includes(senderId)) {
      logger.warn("Message from non-participant ignored", { matchId, senderId });
      return;
    }

    await matchRef.update({
      lastMessage: { text: preview(type, text), senderId, type, sentAt },
      [`lastRead.${senderId}`]: sentAt,
    });

    const recipient = users.find((u) => u !== senderId);
    if (!recipient) return;
    const senderName = (match.get(`profiles.${senderId}.name`) as string) || "New message";
    await pushToUser(recipient, {
      type: "message",
      title: senderName,
      body: preview(type, text),
      matchId,
    });
  },
);
