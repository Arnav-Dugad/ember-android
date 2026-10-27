import { logger } from "firebase-functions";
import { onSchedule } from "firebase-functions/v2/scheduler";
import { ageOn } from "./ageGate.js";
import { REGION, db, invalidateRemoteConfigCache, remoteConfig } from "./util.js";

/** Recomputes every profile's `age` from the server-held birth date, once a day. */
export const refreshAges = onSchedule(
  { schedule: "every day 03:00", timeZone: "Etc/UTC", region: REGION, timeoutSeconds: 540 },
  async () => {
    const accounts = await db.collectionGroup("private").select("birthDate").get();
    const writer = db.bulkWriter();
    let updated = 0;
    for (const account of accounts.docs) {
      if (account.id !== "account") continue;
      const birthDate = account.get("birthDate");
      const userRef = account.ref.parent.parent;
      if (typeof birthDate !== "string" || !userRef) continue;
      const age = ageOn(birthDate);
      if (age === null) continue;
      const user = await userRef.get();
      if (user.exists && user.get("age") !== age) {
        writer.update(userRef, { age });
        updated++;
      }
    }
    await writer.close();
    logger.info("Ages refreshed", { checked: accounts.size, updated });
  },
);

/**
 * Hides the seeded demo profiles once enough real people have signed up. One-way on purpose:
 * flapping the deck between demo and real users as the count hovers around the threshold would
 * be confusing. To show demo profiles again, set `hide_demo_profiles` to false by hand.
 */
export const updateDemoVisibility = onSchedule(
  { schedule: "every 60 minutes", region: REGION },
  async () => {
    const users = db.collection("users");
    const [onboarded, demo] = await Promise.all([
      users.where("onboarded", "==", true).count().get(),
      users.where("isDemo", "==", true).where("onboarded", "==", true).count().get(),
    ]);
    const realUsers = onboarded.data().count - demo.data().count;

    const template = await remoteConfig.getTemplate();
    const thresholdParam = template.parameters.demo_hide_threshold?.defaultValue;
    const threshold = thresholdParam && "value" in thresholdParam ? Number(thresholdParam.value) : 500;
    const hideParam = template.parameters.hide_demo_profiles?.defaultValue;
    const hidden = hideParam && "value" in hideParam && hideParam.value === "true";

    logger.info("Demo visibility check", { realUsers, threshold, hidden });
    if (hidden || !Number.isFinite(threshold) || realUsers < threshold) return;

    template.parameters.hide_demo_profiles = {
      ...(template.parameters.hide_demo_profiles ?? {}),
      defaultValue: { value: "true" },
      valueType: "BOOLEAN",
    };
    await remoteConfig.publishTemplate(template);
    invalidateRemoteConfigCache();
    logger.info("Demo profiles hidden", { realUsers, threshold });
  },
);
