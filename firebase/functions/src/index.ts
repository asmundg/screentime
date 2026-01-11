import * as admin from "firebase-admin";
import {onDocumentCreated} from "firebase-functions/v2/firestore";
import {onSchedule} from "firebase-functions/v2/scheduler";
import {onCall, HttpsError} from "firebase-functions/v2/https";

admin.initializeApp();

const db = admin.firestore();
const messaging = admin.messaging();

interface ExtensionRequest {
  familyId: string;
  userId: string;
  deviceId: string;
  deviceName: string;
  platform: string;
  requestedMinutes: number;
  reason?: string;
  status: string;
  createdAt: admin.firestore.Timestamp;
}

type MemberRole = "owner" | "admin" | "viewer";

interface Family {
  name: string;
  ownerEmail: string;
  members: Record<string, MemberRole>;
  fcmTokens?: Record<string, string>;  // email -> FCM token for each member
}

interface User {
  familyId: string;
  name: string;
  todayUsedMinutes: number;
  lastResetDate: string;
}

/**
 * Send push notification to all admins/owners when a child requests a time extension.
 */
export const onExtensionRequest = onDocumentCreated(
  "extensionRequests/{requestId}",
  async (event) => {
    const snapshot = event.data;
    if (!snapshot) {
      console.log("No data in extension request");
      return;
    }

    const request = snapshot.data() as ExtensionRequest;
    console.log(`Extension request created: ${event.params.requestId}`);

    // Get family
    const familyDoc = await db.collection("families").doc(request.familyId).get();
    if (!familyDoc.exists) {
      console.log(`Family ${request.familyId} not found`);
      return;
    }

    const family = familyDoc.data() as Family;

    // Get FCM tokens for all admins and owners
    const tokens: string[] = [];
    if (family.fcmTokens) {
      for (const [email, role] of Object.entries(family.members)) {
        if (role === "owner" || role === "admin") {
          const token = family.fcmTokens[email];
          if (token) tokens.push(token);
        }
      }
    }

    if (tokens.length === 0) {
      console.log(`No FCM tokens for admins in family ${request.familyId}`);
      return;
    }

    // Get child's name
    const userDoc = await db.collection("users").doc(request.userId).get();
    const userName = userDoc.exists ? (userDoc.data() as User).name : "Your child";

    // Send notification to all admins/owners
    const message: admin.messaging.MulticastMessage = {
      tokens,
      notification: {
        title: "Extension Request",
        body: `${userName} is requesting ${request.requestedMinutes} more minutes`,
      },
      data: {
        type: "extension_request",
        requestId: event.params.requestId,
        userId: request.userId,
        deviceName: request.deviceName,
        requestedMinutes: request.requestedMinutes.toString(),
      },
      android: {
        priority: "high",
        notification: {
          channelId: "extension_requests",
          priority: "high",
        },
      },
      apns: {
        payload: {
          aps: {
            sound: "default",
            badge: 1,
          },
        },
      },
    };

    try {
      const response = await messaging.sendEachForMulticast(message);
      console.log(`Notifications sent: ${response.successCount} success, ${response.failureCount} failed`);
    } catch (error) {
      console.error("Error sending notifications:", error);
    }
  }
);

/**
 * Daily reset of screen time counters.
 * Runs at midnight UTC. Adjust schedule for timezone needs.
 */
export const dailyReset = onSchedule(
  {
    schedule: "0 0 * * *", // Midnight UTC daily
    timeZone: "UTC",
  },
  async () => {
    const today = new Date().toISOString().split("T")[0];
    console.log(`Running daily reset for ${today}`);

    // Get all users and reset their counters
    const usersSnapshot = await db.collection("users").get();

    const batch = db.batch();
    let count = 0;

    usersSnapshot.forEach((doc) => {
      const user = doc.data() as User;
      if (user.lastResetDate !== today) {
        batch.update(doc.ref, {
          todayUsedMinutes: 0,
          lastResetDate: today,
        });
        count++;
      }
    });

    if (count > 0) {
      await batch.commit();
      console.log(`Reset ${count} users`);
    } else {
      console.log("No users needed reset");
    }
  }
);

/**
 * Optional: Alert parent when child's time is running low.
 * This checks periodically and sends a notification if time is almost up.
 */
export const lowTimeAlert = onSchedule(
  {
    schedule: "*/5 * * * *", // Every 5 minutes
    timeZone: "UTC",
  },
  async () => {
    // Get all users with low time remaining
    const usersSnapshot = await db.collection("users").get();

    for (const userDoc of usersSnapshot.docs) {
      const user = userDoc.data() as User;
      const dailyLimit = (user as unknown as {dailyLimitMinutes: number}).dailyLimitMinutes || 120;
      const remaining = dailyLimit - user.todayUsedMinutes;

      // Alert at 10 minutes remaining
      if (remaining > 5 && remaining <= 10) {
        const familyDoc = await db.collection("families").doc(user.familyId).get();
        if (!familyDoc.exists) continue;

        const family = familyDoc.data() as Family;

        // Get FCM tokens for all admins and owners
        const tokens: string[] = [];
        if (family.fcmTokens) {
          for (const [email, role] of Object.entries(family.members)) {
            if (role === "owner" || role === "admin") {
              const token = family.fcmTokens[email];
              if (token) tokens.push(token);
            }
          }
        }
        if (tokens.length === 0) continue;

        // Check if we already sent this alert today
        const alertKey = `lowTimeAlert_${userDoc.id}_${user.lastResetDate}`;
        const alertDoc = await db.collection("_alerts").doc(alertKey).get();
        if (alertDoc.exists) continue;

        // Send notification to all admins/owners
        try {
          await messaging.sendEachForMulticast({
            tokens,
            notification: {
              title: "Low Screen Time",
              body: `${user.name} has ${Math.round(remaining)} minutes remaining`,
            },
            data: {
              type: "low_time_alert",
              userId: userDoc.id,
              remaining: Math.round(remaining).toString(),
            },
          });

          // Mark alert as sent
          await db.collection("_alerts").doc(alertKey).set({
            sentAt: admin.firestore.FieldValue.serverTimestamp(),
          });

          console.log(`Low time alert sent for user ${userDoc.id}`);
        } catch (error) {
          console.error(`Error sending low time alert for ${userDoc.id}:`, error);
        }
      }
    }
  }
);

// =============================================================================
// Device Registration & Authentication
// =============================================================================

interface RegistrationCode {
  familyId: string;
  userId: string;
  createdAt: admin.firestore.Timestamp;
  expiresAt: admin.firestore.Timestamp;
  used: boolean;
}

/**
 * Generate a registration code for linking a new device.
 * Called by parent app when adding a new device.
 */
export const generateRegistrationCode = onCall(
  {cors: true},
  async (request) => {
    // Must be authenticated as parent
    if (!request.auth) {
      throw new HttpsError("unauthenticated", "Must be logged in");
    }

    const {familyId, userId} = request.data as {familyId: string; userId: string};
    if (!familyId || !userId) {
      throw new HttpsError("invalid-argument", "familyId and userId required");
    }

    // Verify caller is admin/owner of this family
    const familyDoc = await db.collection("families").doc(familyId).get();
    if (!familyDoc.exists) {
      throw new HttpsError("not-found", "Family not found");
    }
    const family = familyDoc.data() as Family;
    const callerEmail = request.auth.token.email as string;
    const callerRole = family.members[callerEmail];
    if (!callerRole || (callerRole !== "owner" && callerRole !== "admin")) {
      throw new HttpsError("permission-denied", "Not authorized for this family");
    }

    // Generate 6-digit code
    const code = Math.random().toString().slice(2, 8);
    const now = admin.firestore.Timestamp.now();
    const expiresAt = admin.firestore.Timestamp.fromMillis(
      now.toMillis() + 15 * 60 * 1000 // 15 minutes
    );

    await db.collection("_registrationCodes").doc(code).set({
      familyId,
      userId,
      createdAt: now,
      expiresAt,
      used: false,
    } as RegistrationCode);

    console.log(`Registration code ${code} created for family ${familyId}`);
    return {code, expiresAt: expiresAt.toDate().toISOString()};
  }
);

/**
 * Register a device using a registration code.
 * Called by child device (Windows/Android) during setup.
 * Returns a custom auth token the device can use.
 */
export const registerDevice = onCall(
  {cors: true},
  async (request) => {
    const {code, deviceId, deviceName, platform} = request.data as {
      code: string;
      deviceId: string;
      deviceName: string;
      platform: "windows" | "android";
    };

    if (!code || !deviceId || !deviceName || !platform) {
      throw new HttpsError("invalid-argument", "Missing required fields");
    }

    // Look up registration code
    const codeDoc = await db.collection("_registrationCodes").doc(code).get();
    if (!codeDoc.exists) {
      throw new HttpsError("not-found", "Invalid registration code");
    }

    const codeData = codeDoc.data() as RegistrationCode;

    // Check if expired
    if (codeData.expiresAt.toMillis() < Date.now()) {
      throw new HttpsError("deadline-exceeded", "Registration code expired");
    }

    // Check if already used
    if (codeData.used) {
      throw new HttpsError("already-exists", "Registration code already used");
    }

    // Mark code as used
    await codeDoc.ref.update({used: true});

    // Create device document
    await db.collection("devices").doc(deviceId).set({
      familyId: codeData.familyId,
      userId: codeData.userId,
      name: deviceName,
      platform,
      lastSeen: admin.firestore.FieldValue.serverTimestamp(),
      currentApp: null,
      currentAppPackage: null,
      isLocked: false,
      fcmToken: null,
    });

    // Create custom token with device claims
    const customToken = await admin.auth().createCustomToken(deviceId, {
      deviceId,
      familyId: codeData.familyId,
      userId: codeData.userId,
      isDevice: true,
    });

    console.log(`Device ${deviceId} registered to family ${codeData.familyId}`);
    return {
      token: customToken,
      familyId: codeData.familyId,
      userId: codeData.userId,
    };
  }
);

/**
 * Refresh a device's auth token.
 * Called periodically by devices to get a fresh token.
 */
export const refreshDeviceToken = onCall(
  {cors: true},
  async (request) => {
    if (!request.auth) {
      throw new HttpsError("unauthenticated", "Must be authenticated");
    }

    const deviceId = request.auth.token.deviceId as string | undefined;
    if (!deviceId) {
      throw new HttpsError("permission-denied", "Not a device token");
    }

    // Verify device still exists
    const deviceDoc = await db.collection("devices").doc(deviceId).get();
    if (!deviceDoc.exists) {
      throw new HttpsError("not-found", "Device not found");
    }

    const device = deviceDoc.data()!;
    const customToken = await admin.auth().createCustomToken(deviceId, {
      deviceId,
      familyId: device.familyId,
      userId: device.userId,
      isDevice: true,
    });

    return {token: customToken};
  }
);

// =============================================================================
// Member Management
// =============================================================================

/**
 * Add a member to a family.
 * Only owners can add members.
 */
export const addFamilyMember = onCall(
  {cors: true},
  async (request) => {
    if (!request.auth) {
      throw new HttpsError("unauthenticated", "Must be logged in");
    }

    const {familyId, email, role} = request.data as {
      familyId: string;
      email: string;
      role: MemberRole;
    };

    if (!familyId || !email || !role) {
      throw new HttpsError("invalid-argument", "familyId, email, and role required");
    }

    if (!["owner", "admin", "viewer"].includes(role)) {
      throw new HttpsError("invalid-argument", "Invalid role");
    }

    // Get family and verify caller is owner
    const familyDoc = await db.collection("families").doc(familyId).get();
    if (!familyDoc.exists) {
      throw new HttpsError("not-found", "Family not found");
    }

    const family = familyDoc.data() as Family;
    const callerEmail = request.auth.token.email as string;

    if (family.members[callerEmail] !== "owner") {
      throw new HttpsError("permission-denied", "Only owners can add members");
    }

    // Add member
    await familyDoc.ref.update({
      [`members.${email}`]: role,
    });

    console.log(`Added ${email} as ${role} to family ${familyId}`);
    return {success: true};
  }
);

/**
 * Remove a member from a family.
 * Only owners can remove members. Cannot remove the owner.
 */
export const removeFamilyMember = onCall(
  {cors: true},
  async (request) => {
    if (!request.auth) {
      throw new HttpsError("unauthenticated", "Must be logged in");
    }

    const {familyId, email} = request.data as {
      familyId: string;
      email: string;
    };

    if (!familyId || !email) {
      throw new HttpsError("invalid-argument", "familyId and email required");
    }

    // Get family and verify caller is owner
    const familyDoc = await db.collection("families").doc(familyId).get();
    if (!familyDoc.exists) {
      throw new HttpsError("not-found", "Family not found");
    }

    const family = familyDoc.data() as Family;
    const callerEmail = request.auth.token.email as string;

    if (family.members[callerEmail] !== "owner") {
      throw new HttpsError("permission-denied", "Only owners can remove members");
    }

    // Cannot remove the owner
    if (email === family.ownerEmail) {
      throw new HttpsError("invalid-argument", "Cannot remove the family owner");
    }

    // Remove member
    await familyDoc.ref.update({
      [`members.${email}`]: admin.firestore.FieldValue.delete(),
      [`fcmTokens.${email}`]: admin.firestore.FieldValue.delete(),
    });

    console.log(`Removed ${email} from family ${familyId}`);
    return {success: true};
  }
);

/**
 * Update a member's role.
 * Only owners can change roles.
 */
export const updateMemberRole = onCall(
  {cors: true},
  async (request) => {
    if (!request.auth) {
      throw new HttpsError("unauthenticated", "Must be logged in");
    }

    const {familyId, email, role} = request.data as {
      familyId: string;
      email: string;
      role: MemberRole;
    };

    if (!familyId || !email || !role) {
      throw new HttpsError("invalid-argument", "familyId, email, and role required");
    }

    if (!["owner", "admin", "viewer"].includes(role)) {
      throw new HttpsError("invalid-argument", "Invalid role");
    }

    // Get family and verify caller is owner
    const familyDoc = await db.collection("families").doc(familyId).get();
    if (!familyDoc.exists) {
      throw new HttpsError("not-found", "Family not found");
    }

    const family = familyDoc.data() as Family;
    const callerEmail = request.auth.token.email as string;

    if (family.members[callerEmail] !== "owner") {
      throw new HttpsError("permission-denied", "Only owners can change roles");
    }

    // Cannot change owner's role
    if (email === family.ownerEmail && role !== "owner") {
      throw new HttpsError("invalid-argument", "Cannot change owner's role");
    }

    // Update role
    await familyDoc.ref.update({
      [`members.${email}`]: role,
    });

    console.log(`Updated ${email} role to ${role} in family ${familyId}`);
    return {success: true};
  }
);
