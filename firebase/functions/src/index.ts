import * as admin from "firebase-admin";
import {onDocumentCreated} from "firebase-functions/v2/firestore";
import {onSchedule} from "firebase-functions/v2/scheduler";

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

interface Family {
  name: string;
  parentEmail: string;
  fcmToken?: string;
}

interface User {
  familyId: string;
  name: string;
  todayUsedMinutes: number;
  lastResetDate: string;
}

/**
 * Send push notification to parent when a child requests a time extension.
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

    // Get family to find parent's FCM token
    const familyDoc = await db.collection("families").doc(request.familyId).get();
    if (!familyDoc.exists) {
      console.log(`Family ${request.familyId} not found`);
      return;
    }

    const family = familyDoc.data() as Family;
    if (!family.fcmToken) {
      console.log(`No FCM token for family ${request.familyId}`);
      return;
    }

    // Get child's name
    const userDoc = await db.collection("users").doc(request.userId).get();
    const userName = userDoc.exists ? (userDoc.data() as User).name : "Your child";

    // Send notification
    const message: admin.messaging.Message = {
      token: family.fcmToken,
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
      await messaging.send(message);
      console.log(`Notification sent for request ${event.params.requestId}`);
    } catch (error) {
      console.error("Error sending notification:", error);
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
        if (!family.fcmToken) continue;

        // Check if we already sent this alert today
        const alertKey = `lowTimeAlert_${userDoc.id}_${user.lastResetDate}`;
        const alertDoc = await db.collection("_alerts").doc(alertKey).get();
        if (alertDoc.exists) continue;

        // Send notification
        try {
          await messaging.send({
            token: family.fcmToken,
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
