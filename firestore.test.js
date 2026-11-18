const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_123";
const BOB_UID = "bob_456";
const ADMIN_UID = "admin_789";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function validTimestamp() {
  return new Date(Date.now() - 2000);
}

function validUserPayload(uid) {
  const ts = validTimestamp();
  return {
    userId: uid,
    displayName: "Alice Creator",
    email: "alice@example.com",
    spendingLimitUsd: 25.0,
    totalSpentUsd: 0.0,
    totalVideosCreated: 0,
    defaultAspectRatio: "16:9",
    defaultLanguage: "English",
    preferLowCostImageMode: false,
    createdAt: ts,
    updatedAt: ts,
  };
}

function validProjectPayload(uid) {
  const ts = validTimestamp();
  return {
    userId: uid,
    jobId: "job_001",
    title: "Deep Sea Bioluminescence",
    topic: "How deep sea organisms create bioluminescence",
    format: "DOCUMENTARY",
    aspectRatio: "16:9",
    language: "English",
    targetDurationSeconds: 60,
    voiceGender: "FEMALE",
    voiceStyle: "CINEMATIC",
    productionMode: "STANDARD_HYBRID",
    status: "QUEUED",
    currentStage: "Queued",
    progressPercent: 0,
    estimatedCostUsd: 0.12,
    actualCostUsd: 0.0,
    thumbnailStyle: "BOLD_CINEMA",
    videoFileSizeBytes: 0,
    renderedDurationSeconds: 0,
    sceneCount: 0,
    createdAt: ts,
    updatedAt: ts,
  };
}

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated user cannot read or write any collection", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).get());
  await assertFails(unauthDb.collection("projects").where("userId", "==", ALICE_UID).get());
  await assertFails(unauthDb.collection("projects").doc("proj_1").set(validProjectPayload(ALICE_UID)));
  await assertFails(unauthDb.collection("app_config").doc("global").get());
});

test("User profile creation, update, shadow field rejection, and cross-user isolation", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();

  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).set(validUserPayload(ALICE_UID)));
  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).get());
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).update({
      spendingLimitUsd: 50.0,
      updatedAt: validTimestamp(),
    })
  );

  // Shadow field & cross-user isolation
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      isAdmin: true,
      updatedAt: validTimestamp(),
    })
  );
  await assertFails(bobDb.collection("users").doc(ALICE_UID).get());
  await assertFails(bobDb.collection("users").get());
});

test("Project lifecycle, filtered queries, terminal state lock, and cross-user denial", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();

  await assertSucceeds(aliceDb.collection("projects").doc("proj_1").set(validProjectPayload(ALICE_UID)));

  // Filtered query succeeds, unfiltered query fails
  await assertSucceeds(aliceDb.collection("projects").where("userId", "==", ALICE_UID).get());
  await assertFails(aliceDb.collection("projects").get());

  // Cross-user read/write fails
  await assertFails(bobDb.collection("projects").doc("proj_1").get());
  await assertFails(
    bobDb.collection("projects").doc("proj_1").update({
      title: "Hijacked",
      updatedAt: validTimestamp(),
    })
  );

  // Pipeline update to COMPLETED, then terminal lock prevents reverting status
  await assertSucceeds(
    aliceDb.collection("projects").doc("proj_1").update({
      status: "COMPLETED",
      currentStage: "Exported 1080p MP4",
      progressPercent: 100,
      renderedDurationSeconds: 60,
      videoFileSizeBytes: 4096000,
      sceneCount: 4,
      updatedAt: validTimestamp(),
    })
  );
  await assertFails(
    aliceDb.collection("projects").doc("proj_1").update({
      status: "QUEUED",
      updatedAt: validTimestamp(),
    })
  );
  // Post-completion thumbnail re-export still allowed
  await assertSucceeds(
    aliceDb.collection("projects").doc("proj_1").update({
      thumbnailStyle: "NEON_PULSE",
      updatedAt: validTimestamp(),
    })
  );
});

test("Scene subcollection parent ownership, filtered list query, and research reports", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const ts = validTimestamp();

  await assertSucceeds(aliceDb.collection("projects").doc("proj_1").set(validProjectPayload(ALICE_UID)));

  const scenePayload = {
    userId: ALICE_UID,
    projectId: "proj_1",
    sceneIndex: 0,
    heading: "Abyssal Light",
    narration: "Beneath 1000 meters, sunlight vanishes completely.",
    visualPrompt: "Glowing deep sea jellyfish in dark abyssal ocean",
    subtitleText: "Beneath 1000 meters, sunlight vanishes.",
    durationSeconds: 12,
    status: "PLANNED",
    createdAt: ts,
    updatedAt: ts,
  };

  await assertSucceeds(
    aliceDb.collection("projects").doc("proj_1").collection("scenes").doc("scene_0").set(scenePayload)
  );
  await assertSucceeds(
    aliceDb.collection("projects").doc("proj_1").collection("scenes").where("userId", "==", ALICE_UID).get()
  );
  await assertFails(
    bobDb.collection("projects").doc("proj_1").collection("scenes").doc("scene_0").set({
      ...scenePayload,
      userId: BOB_UID,
    })
  );

  // Research report requires existing owned project
  const reportPayload = {
    userId: ALICE_UID,
    projectId: "proj_1",
    topic: "How deep sea organisms create bioluminescence",
    summary: "Marine organisms use luciferin and luciferase reactions.",
    verificationNotes: "Confirmed across NOAA and MBARI publications.",
    sources: [{ title: "NOAA Ocean Exploration", url: "https://oceanexplorer.noaa.gov", publishedDate: "2025-04-12" }],
    createdAt: ts,
    updatedAt: ts,
  };
  await assertSucceeds(aliceDb.collection("research_reports").doc("rep_1").set(reportPayload));
  await assertFails(
    aliceDb.collection("research_reports").doc("rep_orphan").set({
      ...reportPayload,
      projectId: "missing_proj",
    })
  );
});

test("Usage logs, content reports, and admin privileges with verified email", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const spoofedAdminDb = testEnv.authenticatedContext("spoof", {
    email: "skumari86555@gmail.com",
    email_verified: false,
  }).firestore();
  const realAdminDb = testEnv.authenticatedContext(ADMIN_UID, {
    email: "skumari86555@gmail.com",
    email_verified: true,
  }).firestore();
  const ts = validTimestamp();

  // Usage log create allowed for owner, update denied
  await assertSucceeds(
    aliceDb.collection("usage_logs").doc("log_1").set({
      userId: ALICE_UID,
      projectId: "proj_1",
      projectTitle: "Deep Sea",
      stage: "RESEARCHING",
      modelName: "gemini-3.8-flash",
      estimatedTokens: 1200,
      costUsd: 0.01,
      status: "SUCCESS",
      createdAt: ts,
    })
  );
  await assertFails(aliceDb.collection("usage_logs").doc("log_1").update({ costUsd: 0.0 }));

  // Spoofed unverified admin email rejected
  await assertFails(
    spoofedAdminDb.collection("app_config").doc("global").set({
      maxDurationSeconds: 1200,
      defaultSpendingLimitUsd: 25.0,
      searchGroundingEnabled: true,
      nanoBananaEnabled: true,
      geminiTtsEnabled: true,
      veoServerAdapterEnabled: false,
      updatedAt: ts,
    })
  );

  // Verified admin succeeds
  await assertSucceeds(
    realAdminDb.collection("app_config").doc("global").set({
      maxDurationSeconds: 1200,
      defaultSpendingLimitUsd: 25.0,
      searchGroundingEnabled: true,
      nanoBananaEnabled: true,
      geminiTtsEnabled: true,
      veoServerAdapterEnabled: false,
      updatedAt: ts,
    })
  );
  await assertSucceeds(aliceDb.collection("app_config").doc("global").get());
});
