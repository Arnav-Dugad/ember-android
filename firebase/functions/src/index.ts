/**
 * Ember Cloud Functions. The contract with the app is docs/DATA_MODEL.md.
 *
 * Anything that decides who someone is (age), who they are connected to (matches, blocks), or
 * what happens to their data (reports, deletion) runs here, never on the client.
 */
export { completeAgeGate } from "./ageGate.js";
export { getDiscoveryDeck } from "./discovery.js";
export { onSwipeCreated, rewindSwipe } from "./matching.js";
export { onMessageCreated } from "./chat.js";
export { onAlbumAccessWritten } from "./album.js";
export { blockUser, reportUser, unmatch } from "./safety.js";
export { deleteAccount } from "./account.js";
export { refreshAges, updateDemoVisibility } from "./scheduled.js";
