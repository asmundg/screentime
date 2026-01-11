# Screen Time Tracker with App Whitelisting

A cross-platform parental control system that tracks screen time on Windows PCs and Android devices, allows whitelisting specific apps (so they don't count toward daily limits), and provides remote management via parent's phone.

## Overview

### Core Features
- Track time spent in applications on Windows PCs and Android devices
- Unified time budget across all devices (e.g., 2 hours total across PC + phone)
- Whitelisted apps (e.g., Kerbal Space Program, educational apps) don't count toward the daily time budget
- When non-whitelisted time budget is exhausted, lock the device / block apps
- Parent can manage settings and approve extension requests from their phone
- Single dashboard showing usage across all family devices

### Architecture
```
┌─────────────────┐   ┌─────────────────┐   ┌─────────────────┐
│  Windows PC     │   │ Android Phone   │   │ Android Phone   │
│  (Child)        │   │ (Child)         │   │ (Parent)        │
│                 │   │                 │   │                 │
│  ┌───────────┐  │   │  ┌───────────┐  │   │  ┌───────────┐  │
│  │ Python    │  │   │  │ Kotlin    │  │   │  │ Flutter   │  │
│  │ Service   │  │   │  │ App       │  │   │  │ App       │  │
│  │           │  │   │  │           │  │   │  │           │  │
│  └─────┬─────┘  │   │  └─────┬─────┘  │   │  └─────┬─────┘  │
│        │        │   │        │        │   │        │        │
└────────┼────────┘   └────────┼────────┘   └────────┼────────┘
         │                     │                     │
         │         ┌───────────┴───────────┐         │
         └─────────┤       Firebase        ├─────────┘
                   │                       │
                   │  Firestore (data)     │
                   │  Auth (accounts)      │
                   │  FCM (push notify)    │
                   └───────────────────────┘
```

## Data Model (Firestore)

### Collection: `families`
```
families/{familyId}
  - name: string
  - createdAt: timestamp
  - parentEmail: string
  - timeTrackingMode: "per_device" | "unified"  // unified = shared budget across devices
```

### Collection: `devices`
```
devices/{deviceId}
  - familyId: string
  - userId: string (which child this device belongs to)
  - name: string (e.g., "Gaming PC", "Samsung Phone")
  - platform: "windows" | "android"
  - lastSeen: timestamp
  - currentApp: string (foreground app name)
  - currentAppPackage: string (android package or windows exe)
  - isLocked: boolean
  - fcmToken: string (for Android devices, to receive lock commands)
```

### Collection: `users` (children)
```
users/{userId}
  - familyId: string
  - name: string
  - dailyLimitMinutes: number (default: 120)
  - todayUsedMinutes: number (combined across all devices if unified mode)
  - lastResetDate: string (YYYY-MM-DD)
  - windowsUsername: string (optional, for Windows device matching)
```

### Collection: `whitelist`
```
whitelist/{id}
  - familyId: string
  - platform: "windows" | "android" | "both"
  - identifier: string (exe name for Windows, package name for Android)
  - displayName: string (e.g., "Kerbal Space Program", "Khan Academy")
  - addedAt: timestamp
```

### Collection: `extensionRequests`
```
extensionRequests/{requestId}
  - familyId: string
  - userId: string
  - deviceId: string
  - deviceName: string
  - platform: "windows" | "android"
  - requestedMinutes: number
  - reason: string (optional)
  - status: "pending" | "approved" | "denied"
  - createdAt: timestamp
  - respondedAt: timestamp (optional)
```

### Collection: `usageLogs` (for history/analytics)
```
usageLogs/{logId}
  - familyId: string
  - userId: string
  - deviceId: string
  - platform: "windows" | "android"
  - date: string (YYYY-MM-DD)
  - appIdentifier: string (exe or package name)
  - appDisplayName: string
  - minutes: number
  - wasWhitelisted: boolean
```

## Unified Time Tracking Logic

The key feature is that time spent on non-whitelisted apps counts toward a **single shared budget** across all devices.

### How It Works

1. **Each device client** (Windows service, Android app) monitors foreground apps
2. **Every N seconds**, if the foreground app is NOT whitelisted:
   - Client increments `users/{userId}/todayUsedMinutes` in Firestore
   - Uses Firestore transactions to ensure atomic updates
3. **Each device client** also reads `todayUsedMinutes` periodically
   - If `todayUsedMinutes >= dailyLimitMinutes`, enforce lock
4. **Result**: If kid uses 1hr on PC and 1hr on phone, both devices show 2hr used

### Firestore Transaction for Time Update
```javascript
// Pseudocode for atomic time increment
async function incrementTime(userId, secondsToAdd) {
  const userRef = firestore.doc(`users/${userId}`);
  
  await firestore.runTransaction(async (transaction) => {
    const userDoc = await transaction.get(userRef);
    const currentMinutes = userDoc.data().todayUsedMinutes || 0;
    const newMinutes = currentMinutes + (secondsToAdd / 60);
    
    transaction.update(userRef, { 
      todayUsedMinutes: newMinutes,
      lastUpdated: serverTimestamp()
    });
  });
}
```

### Daily Reset
- A Cloud Function runs at midnight (per timezone, or just UTC)
- Resets `todayUsedMinutes` to 0 for all users
- Updates `lastResetDate` to current date
- Alternatively: each client checks `lastResetDate` and resets locally if stale

### Per-Device Mode (Optional)
If `family.timeTrackingMode == "per_device"`:
- Each device tracks its own time budget
- `devices/{deviceId}/todayUsedMinutes` instead of user-level
- Useful if parent wants "2hr on PC AND 2hr on phone" instead of "2hr total"

## Component 1: Windows Service (Python)

### Dependencies
- `pywin32` - Windows API access
- `psutil` - Process information
- `firebase-admin` - Firestore SDK
- `pystray` - System tray icon
- `Pillow` - For tray icon images

### Core Logic

```python
# Pseudocode for main loop

every 10 seconds:
    foreground_window = get_foreground_window()
    exe_name = get_process_executable(foreground_window)
    
    is_whitelisted = check_whitelist(exe_name, platform="windows")
    
    if not is_whitelisted:
        increment_used_time(10 seconds)  # Updates user's todayUsedMinutes
    
    # Log app usage for analytics
    log_app_usage(exe_name, is_whitelisted)
    
    update_firestore_device({
        currentApp: exe_name,
        currentAppPackage: exe_name,
        lastSeen: now,
        platform: "windows"
    })
    
    # Check if we need to reset daily counter (server time)
    if today != lastResetDate:
        reset_daily_counter()
    
    # Check if limit exceeded (reads from user doc, which may include Android time)
    user = get_user_doc()
    if user.todayUsedMinutes >= user.dailyLimitMinutes:
        lock_workstation()
    
    # Pull latest config from Firestore
    refresh_whitelist()
    refresh_daily_limit()
    
    # Check for approved extensions
    check_extension_approvals()
```

### Service Installation
- Install as Windows service using `pywin32`
- Configure to run as SYSTEM account
- Set recovery options: restart on failure
- Start automatically on boot

### Tray Icon Features
- Shows remaining time (e.g., "45 min left")
- Color indicator (green/yellow/red)
- Right-click menu:
  - "Request Extension" → opens dialog
  - "View Whitelist" (read-only)
  - Current app status (whitelisted or counting)

### File Structure
```
windows-client/
├── src/
│   ├── main.py              # Entry point
│   ├── service.py           # Windows service wrapper
│   ├── monitor.py           # Foreground app monitoring
│   ├── firebase_client.py   # Firestore operations
│   ├── tray.py              # System tray icon
│   ├── lock.py              # Workstation locking
│   └── config.py            # Local config (device ID, etc.)
├── assets/
│   └── icons/               # Tray icons
├── requirements.txt
├── install_service.py       # Admin script to install service
└── README.md
```

### Key Implementation Details

1. **Foreground Window Detection**
```python
import win32gui
import win32process
import psutil

def get_foreground_exe():
    hwnd = win32gui.GetForegroundWindow()
    _, pid = win32process.GetWindowThreadProcessId(hwnd)
    try:
        process = psutil.Process(pid)
        return process.name()
    except:
        return None
```

2. **Workstation Locking**
```python
import ctypes

def lock_workstation():
    ctypes.windll.user32.LockWorkStation()
```

3. **Service Wrapper**
```python
import win32serviceutil
import win32service
import win32event

class ScreenTimeService(win32serviceutil.ServiceFramework):
    _svc_name_ = "ScreenTimeTracker"
    _svc_display_name_ = "Screen Time Tracker"
    _svc_description_ = "Monitors and limits screen time"
    
    # ... implementation
```

4. **Offline Handling**
- Cache whitelist locally
- Track time locally, sync when online
- Queue extension requests for when connection restored

## Component 2: Android Child App (Kotlin)

This app runs on the child's Android phone/tablet and monitors app usage, enforces time limits, and syncs with the shared Firebase backend.

### Required Permissions

1. **Usage Stats Access** (`android.permission.PACKAGE_USAGE_STATS`)
   - Allows reading which apps are being used and for how long
   - User must manually enable in Settings → Apps → Special access → Usage access

2. **Accessibility Service**
   - Detects foreground app changes in real-time
   - Enables blocking apps by drawing overlay
   - Makes the app harder to uninstall

3. **Display Over Other Apps** (`android.permission.SYSTEM_ALERT_WINDOW`)
   - Required to show "Time's Up" blocking overlay on top of other apps

4. **Device Admin** (optional but recommended)
   - Prevents child from uninstalling the app
   - Allows remote lock of device

5. **Foreground Service** (`android.permission.FOREGROUND_SERVICE`)
   - Keeps the monitoring service running continuously

6. **Receive Boot Completed** (`android.permission.RECEIVE_BOOT_COMPLETED`)
   - Auto-start service when device boots

### Core Logic

```kotlin
// Pseudocode for AccessibilityService

class ScreenTimeAccessibilityService : AccessibilityService() {
    
    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType == TYPE_WINDOW_STATE_CHANGED) {
            val packageName = event.packageName?.toString() ?: return
            
            // Ignore system UI, launcher, and our own app
            if (isSystemPackage(packageName)) return
            
            val isWhitelisted = checkWhitelist(packageName, platform = "android")
            
            if (!isWhitelisted) {
                incrementUsedTime(deltaSeconds)
            }
            
            logAppUsage(packageName, isWhitelisted)
            
            updateFirestoreDevice(
                currentApp = getAppName(packageName),
                currentAppPackage = packageName,
                lastSeen = now,
                platform = "android"
            )
            
            // Check if limit exceeded
            val user = getUserDoc()
            if (user.todayUsedMinutes >= user.dailyLimitMinutes) {
                showBlockingOverlay()
            }
        }
    }
}
```

### Blocking Mechanism

When time limit is reached:

1. **Overlay Blocker**
   - Draw a full-screen overlay on top of blocked apps
   - Overlay shows: "Time's up!", remaining time (0), "Request Extension" button
   - Overlay cannot be dismissed by child
   - Whitelisted apps can still be opened (overlay hides for them)

2. **App Detection**
   - When child tries to open a non-whitelisted app, immediately show overlay
   - Use AccessibilityService to detect app switches in real-time

3. **Lockdown Mode** (optional)
   - If device admin enabled, can fully lock the device screen
   - Less granular but more enforceable

### Anti-Bypass Measures

1. **Device Admin**
   - Register as device administrator
   - Cannot be uninstalled without first removing admin rights (requires parent password)

2. **Accessibility Service**
   - If disabled, show persistent notification asking to re-enable
   - Optionally: lock device if accessibility service is disabled

3. **Detect Settings Access**
   - Monitor if child opens Settings app
   - Can show overlay on Settings to prevent tampering
   - Or alert parent immediately

4. **Service Restart**
   - Use `START_STICKY` to restart service if killed
   - Use `WorkManager` for periodic health checks
   - Foreground service with persistent notification (harder to kill)

### File Structure
```
android-child-app/
├── app/
│   ├── src/main/
│   │   ├── java/com/familytime/child/
│   │   │   ├── ChildApp.kt                    # Application class
│   │   │   ├── MainActivity.kt                # Setup/status activity
│   │   │   ├── services/
│   │   │   │   ├── ScreenTimeAccessibilityService.kt
│   │   │   │   ├── MonitoringService.kt       # Foreground service
│   │   │   │   └── BootReceiver.kt
│   │   │   ├── ui/
│   │   │   │   ├── BlockingOverlay.kt         # Full-screen blocker
│   │   │   │   ├── ExtensionRequestDialog.kt
│   │   │   │   └── StatusWidget.kt            # Home screen widget
│   │   │   ├── data/
│   │   │   │   ├── FirebaseRepository.kt
│   │   │   │   ├── LocalCache.kt              # Room DB for offline
│   │   │   │   └── WhitelistManager.kt
│   │   │   └── utils/
│   │   │       ├── AppUtils.kt                # Package name → app name
│   │   │       └── TimeUtils.kt
│   │   ├── res/
│   │   │   ├── layout/
│   │   │   │   ├── activity_main.xml
│   │   │   │   ├── overlay_blocking.xml
│   │   │   │   └── dialog_extension_request.xml
│   │   │   └── xml/
│   │   │       ├── accessibility_service_config.xml
│   │   │       └── device_admin_receiver.xml
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts
├── build.gradle.kts
└── README.md
```

### Key Implementation Details

1. **Accessibility Service Configuration**
```xml
<!-- res/xml/accessibility_service_config.xml -->
<accessibility-service
    xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeWindowStateChanged"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:canRetrieveWindowContent="false"
    android:notificationTimeout="100"
    android:packageNames=""  
    android:description="@string/accessibility_description" />
```

2. **Blocking Overlay**
```kotlin
class BlockingOverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    
    private fun showOverlay() {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        
        overlayView = LayoutInflater.from(this)
            .inflate(R.layout.overlay_blocking, null)
        
        windowManager.addView(overlayView, params)
    }
    
    fun hideForWhitelistedApp() {
        overlayView.visibility = View.GONE
    }
}
```

3. **Device Admin Receiver**
```kotlin
class ScreenTimeDeviceAdmin : DeviceAdminReceiver() {
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        return "Disabling will remove parental controls. Parent password required."
    }
}
```

4. **Foreground Service**
```kotlin
class MonitoringService : Service() {
    override fun onCreate() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Screen Time Active")
            .setContentText("Monitoring app usage")
            .setSmallIcon(R.drawable.ic_timer)
            .build()
        
        startForeground(NOTIFICATION_ID, notification)
    }
}
```

### Dependencies (build.gradle.kts)
```kotlin
dependencies {
    // Firebase
    implementation(platform("com.google.firebase:firebase-bom:32.7.0"))
    implementation("com.google.firebase:firebase-firestore-ktx")
    implementation("com.google.firebase:firebase-messaging-ktx")
    
    // Room for local caching
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    
    // WorkManager for reliable background work
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
```

### Setup Flow (Child Device)

1. Parent installs app on child's device
2. App guides through permission grants:
   - Usage access
   - Accessibility service
   - Display over other apps
   - Device admin (optional)
3. Parent enters family code or scans QR from parent app
4. Device registers with Firebase
5. Service starts monitoring

## Component 3: Firebase Setup

### Firebase Console Setup
1. Create new Firebase project
2. Enable Firestore Database (start in test mode, secure later)
3. Enable Authentication (Email/Password)
4. Enable Cloud Messaging (for push notifications)
5. Create Android app in project settings
6. Generate service account key for Windows client

### Security Rules (Firestore)
```javascript
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    
    // Helper function to check family membership
    function isFamilyMember(familyId) {
      return request.auth != null && 
             get(/databases/$(database)/documents/families/$(familyId)).data.parentEmail == request.auth.token.email;
    }
    
    // Families - only parent can read/write
    match /families/{familyId} {
      allow read, write: if isFamilyMember(familyId);
    }
    
    // Devices - parent can read/write, device can update own status
    match /devices/{deviceId} {
      allow read: if isFamilyMember(resource.data.familyId);
      allow write: if isFamilyMember(resource.data.familyId) ||
                      request.auth.token.deviceId == deviceId;
    }
    
    // Users (children) - parent full access
    match /users/{userId} {
      allow read, write: if isFamilyMember(resource.data.familyId);
    }
    
    // Whitelist - parent full access, devices can read
    match /whitelist/{itemId} {
      allow read: if true;  // Devices need to read this
      allow write: if isFamilyMember(resource.data.familyId);
    }
    
    // Extension requests - devices can create, parent can update
    match /extensionRequests/{requestId} {
      allow create: if true;  // Devices can create requests
      allow read, update: if isFamilyMember(resource.data.familyId);
    }
  }
}
```

### Cloud Functions (optional, for push notifications)
```javascript
// functions/index.js
const functions = require('firebase-functions');
const admin = require('firebase-admin');
admin.initializeApp();

exports.onExtensionRequest = functions.firestore
    .document('extensionRequests/{requestId}')
    .onCreate(async (snap, context) => {
        const request = snap.data();
        
        // Get parent's FCM token
        const family = await admin.firestore()
            .collection('families')
            .doc(request.familyId)
            .get();
        
        const parentToken = family.data().fcmToken;
        
        if (parentToken) {
            await admin.messaging().send({
                token: parentToken,
                notification: {
                    title: 'Extension Request',
                    body: `${request.userName} is requesting ${request.requestedMinutes} more minutes`
                },
                data: {
                    requestId: context.params.requestId,
                    type: 'extension_request'
                }
            });
        }
    });
```

## Component 4: Parent App (Flutter)

The parent app provides a unified dashboard to manage all children's devices (both Windows and Android), configure whitelists, and approve extension requests.

### Tech Stack
**Flutter** - Cross-platform (Android + iOS), single codebase, excellent Firebase support

### Screens

1. **Login Screen**
   - Email/password authentication
   - "Create Family" for first-time setup
   - Generate family invite code / QR for child devices

2. **Dashboard**
   - List of children with today's usage (combined across all devices)
   - Per-child breakdown: PC time vs phone time
   - Quick status (online/offline, current app on each device)
   - Time remaining for each child
   - Pending extension requests (badge count)

3. **Child Detail Screen**
   - All devices for this child
   - Usage history (today, this week) with device breakdown
   - Current daily limit (editable)
   - Time tracking mode toggle: unified vs per-device
   - App usage breakdown (top apps by time, split by platform)
   - "Add Time" quick button

4. **Device Detail Screen**
   - Device name, platform, last seen
   - Current app being used
   - Device-specific stats
   - Remote lock button
   - Remove device option

5. **Whitelist Management**
   - List of whitelisted apps, grouped by platform
   - Add new:
     - For Android: pick from list of installed apps (synced from child device)
     - For Windows: manually enter exe name or pick from recently detected
   - Remove existing
   - Mark as "both platforms" if app exists on both

6. **Extension Requests**
   - List of pending requests (shows device and platform)
   - Approve/deny with one tap
   - Option to approve with different amount
   - Quick approve: +15min, +30min, +1hr buttons
   - History of past requests

7. **Settings**
   - Manage family members
   - Add new child
   - Invite code / QR for linking new devices
   - Notification preferences
   - Default daily limit for new users

### File Structure (Flutter)
```
parent-app/
├── lib/
│   ├── main.dart
│   ├── models/
│   │   ├── family.dart
│   │   ├── child_user.dart
│   │   ├── device.dart
│   │   ├── extension_request.dart
│   │   ├── whitelist_item.dart
│   │   └── usage_log.dart
│   ├── services/
│   │   ├── auth_service.dart
│   │   ├── firestore_service.dart
│   │   ├── notification_service.dart
│   │   └── family_service.dart
│   ├── screens/
│   │   ├── login_screen.dart
│   │   ├── dashboard_screen.dart
│   │   ├── child_detail_screen.dart
│   │   ├── device_detail_screen.dart
│   │   ├── whitelist_screen.dart
│   │   ├── requests_screen.dart
│   │   └── settings_screen.dart
│   ├── widgets/
│   │   ├── usage_card.dart
│   │   ├── device_status_card.dart
│   │   ├── time_remaining_indicator.dart
│   │   ├── platform_icon.dart
│   │   └── request_card.dart
│   └── utils/
│       ├── time_formatter.dart
│       └── platform_utils.dart
├── android/
├── ios/
├── pubspec.yaml
└── README.md
```

### Key Dependencies (pubspec.yaml)
```yaml
dependencies:
  flutter:
    sdk: flutter
  firebase_core: ^2.24.0
  firebase_auth: ^4.16.0
  cloud_firestore: ^4.14.0
  firebase_messaging: ^14.7.0
  provider: ^6.1.0          # State management
  intl: ^0.18.0             # Date formatting
  fl_chart: ^0.66.0         # Usage charts
  qr_flutter: ^4.1.0        # Generate QR codes for device linking
  cached_network_image: ^3.3.0
```

## Implementation Order

### Phase 1: Core Infrastructure
1. Firebase project setup (Firestore, Auth, FCM)
2. Data model implementation
3. Basic parent app - auth and family creation

### Phase 2: Windows Client MVP
4. Windows client - basic monitoring and time tracking
5. Windows client - Firestore sync
6. Windows client - workstation locking when limit hit
7. Parent app - view Windows device usage and adjust limits
8. Test Windows end-to-end

### Phase 3: Android Child App MVP
9. Android child app - accessibility service setup
10. Android child app - foreground app detection
11. Android child app - Firestore sync (shared time budget with Windows)
12. Android child app - blocking overlay
13. Parent app - view Android device usage
14. Test cross-platform time tracking

### Phase 4: Extension Requests
15. Extension request flow (both platforms)
16. Push notifications for requests
17. Quick approve buttons in parent app

### Phase 5: Whitelisting
18. Whitelist management in parent app
19. Windows client whitelist support
20. Android child app whitelist support
21. "Always allowed" apps don't count toward budget

### Phase 6: Polish & Security
22. Tray icon on Windows
23. Android anti-bypass measures (device admin, service restart)
24. Usage history and charts in parent app
25. Offline resilience (both clients)
26. Service installation scripts

### Phase 7: Advanced Features
27. Per-device vs unified time tracking toggle
28. Multiple children support
29. Scheduled limits (more time on weekends)
30. iOS parent app (Flutter already cross-platform)

## Setup Instructions (for end user)

### Firebase Setup
1. Go to https://console.firebase.google.com
2. Create new project (e.g., "family-screen-time")
3. Add Android app with package name (for parent app)
4. Add second Android app with different package name (for child app)
5. Download google-services.json files to respective app directories
6. Enable Firestore, Authentication, Cloud Messaging
7. Create service account and download JSON key for Windows client
8. Place service account key in windows-client/credentials/

### Windows Client Setup
1. Install Python 3.10+
2. Run `pip install -r requirements.txt`
3. Edit config.py with device name and family ID
4. Run `python install_service.py` as Administrator
5. Service starts automatically

### Android Child App Setup
1. Install APK on child's device (sideload or internal testing track)
2. Open app and grant permissions:
   - Usage access (redirects to Settings)
   - Accessibility service (redirects to Settings)
   - Display over other apps
   - Device admin (optional but recommended)
3. Enter family code or scan QR from parent app
4. Device appears in parent dashboard

### Parent App Setup
1. Install from Play Store / App Store (or build with Flutter)
2. Create account and family
3. Add children (names and daily limits)
4. Generate invite codes for child devices
5. Configure whitelist

## Security Considerations

1. **Service Account Key** - Store securely, don't commit to git
2. **Child Can't Bypass (Windows)** - Service runs as SYSTEM, child is standard user
3. **Child Can't Bypass (Android)** - Device admin + accessibility service make uninstall difficult
4. **Firebase Rules** - Properly restrict who can read/write what
5. **Local Caching** - Encrypt cached whitelist to prevent tampering
6. **Device Authentication** - Use unique device tokens, not user credentials
7. **Parent Account Security** - Strong password, consider 2FA
8. **Child Device Physical Access** - Factory reset is always possible; focus on making it inconvenient, not impossible
9. **Network Isolation** - Child could block Firebase IPs; handle offline mode gracefully, alert parent if device hasn't synced

## Edge Cases to Handle

### Time Tracking
1. **Clock manipulation** - Use server time for daily resets, not device time
2. **Time zone changes** - Handle gracefully, use UTC internally
3. **Daylight saving** - Use UTC internally
4. **Computer sleep/hibernate** - Don't count sleep time (Windows)
5. **Phone screen off** - Don't count time when screen is off (Android)
6. **Unified vs per-device** - If unified, both devices update same counter atomically

### App Detection
7. **Multiple monitors/desktops** - Track primary foreground window (Windows)
8. **App renamed** - Match on path or hash, not just name
9. **Steam/launcher games** - Detect actual game exe, not just Steam.exe (Windows)
10. **Split-screen / picture-in-picture** - Count time for primary app (Android)
11. **System UI / launcher** - Don't count time in launcher or settings (Android)
12. **Overlay apps** - Facebook Messenger chat heads, etc. - decide policy

### Service Reliability
13. **Service killed** - Restart on failure, monitor with watchdog (both platforms)
14. **Accessibility service disabled** - Alert parent, optionally lock device (Android)
15. **Offline mode** - Continue enforcing cached limits, sync when reconnected
16. **Firebase quota exceeded** - Graceful degradation, use local cache

### Cross-Platform
17. **Time sync between devices** - Firestore transactions for atomic updates
18. **Extension approved on one device** - Should apply to all devices
19. **Whitelist sync delay** - Cache locally, poll periodically
20. **Device removed from family** - Clean up local state, stop enforcing

## Future Enhancements

- **iOS child app** - More restrictive than Android, would need MDM or Screen Time API access
- **Web dashboard for parent** - React/Vue app as alternative to mobile app
- **Chromebook support** - Chrome extension for monitoring
- **Category-based limits** - 2hr games, 1hr social media, unlimited educational
- **Smart categorization** - Auto-detect app categories, suggest whitelist additions
- **Usage reports and trends** - Weekly email summaries, long-term trends
- **Reward system** - Earn extra time by completing chores (parent confirms)
- **Shared family whitelist vs per-child** - Some apps allowed for older kids only
- **Bedtime / scheduled restrictions** - No devices after 9pm on school nights
- **Location-based rules** - Different limits at home vs school
- **App install approval** - Parent approves new app installs
- **Focus mode** - Child can voluntarily lock themselves out of distracting apps
- **Screen time goal setting** - Child sets their own goals with parent oversight
