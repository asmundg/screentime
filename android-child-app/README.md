# Android Child App

The Android child app monitors app usage on the child's Android device and enforces screen time limits set by parents.

## Features

- **Real-time app detection** via Accessibility Service
- **Unified time tracking** across all devices (shares budget with Windows PC)
- **Blocking overlay** when time limit is exceeded
- **Whitelist support** for apps that don't count toward the budget
- **Extension requests** that parents can approve from their phone
- **Offline support** with local caching
- **Anti-bypass protection** via Device Admin

## Architecture

### Core Components

1. **ScreenTimeAccessibilityService** - Detects foreground app changes
2. **MonitoringService** - Foreground service for persistent monitoring
3. **BlockingOverlayService** - Shows full-screen overlay when time is up
4. **FirebaseRepository** - Handles all Firestore operations
5. **LocalCache** - Room database for offline support
6. **WhitelistManager** - Manages whitelist synchronization

### Data Flow

```
App Switch Detected
    ↓
Check Whitelist
    ↓
If NOT whitelisted → Increment User.todayUsedMinutes (Firestore transaction)
    ↓
Check if todayUsedMinutes >= dailyLimitMinutes
    ↓
If exceeded → Show Blocking Overlay
    ↓
Whitelist is automatically shown hiding overlay when switching to whitelisted app
```

## Required Permissions

The app requires these permissions to function:

1. **Display Over Other Apps** - Show blocking overlay
2. **Accessibility Service** - Detect foreground app changes
3. **Usage Stats Access** - Backup method for app detection
4. **Device Admin** (optional) - Prevent uninstall

## Setup

### Prerequisites

1. Android Studio Hedgehog or later
2. Kotlin 1.9.20+
3. Firebase project with Firestore enabled
4. google-services.json from Firebase Console

### Build Instructions

1. **Clone the repository**
   ```bash
   cd android-child-app
   ```

2. **Add Firebase configuration**
   - Download `google-services.json` from Firebase Console
   - Place it in `app/google-services.json`

3. **Build the app**
   ```bash
   ./gradlew assembleDebug
   ```

4. **Install on device**
   ```bash
   ./gradlew installDebug
   ```

### First-Time Setup on Device

1. Open the app
2. Grant required permissions:
   - Tap "Grant Display Over Apps" → Enable in settings
   - Tap "Enable Accessibility Service" → Enable "Screen Time" in Accessibility settings
   - Tap "Grant Usage Stats Access" → Enable in settings
   - (Optional) Tap "Activate Device Admin" → Activate to prevent uninstall

3. Register device:
   - Enter 6-digit registration code from parent app
   - Tap "Register Device"

4. Monitoring starts automatically

## Implementation Details

### Firestore Integration

The app interacts with these Firestore collections:

- **users/{userId}** - Child user data (dailyLimitMinutes, todayUsedMinutes, etc.)
- **devices/{deviceId}** - Device status (currentApp, lastSeen, etc.)
- **whitelist/{id}** - Whitelisted apps that don't count toward budget
- **extensionRequests/{id}** - Time extension requests pending parent approval
- **usageLogs/{id}** - Historical usage data for analytics

### Offline Support

The app uses Room database to cache:
- Whitelist items
- User state (daily limit, used time, reset date)
- Pending time updates to sync when reconnected

When offline:
- Continues enforcing cached limits
- Queues time updates locally
- Syncs automatically when connection restored

### Anti-Bypass Measures

1. **Device Admin** - Makes uninstall difficult (requires parent password)
2. **Accessibility Service** - System-level service, hard to disable
3. **Foreground Service** - Persistent notification, runs continuously
4. **START_STICKY** - Service restarts if killed by system
5. **Boot Receiver** - Auto-starts on device boot

## Known Limitations

1. **Physical reset** - Factory reset always possible (extreme measure)
2. **Root access** - Rooted devices can bypass everything
3. **Network blocking** - Child could block Firebase IPs (handled by offline mode)
4. **Settings tampering** - Child can disable accessibility service (app detects and alerts)
5. **Device registration** - Currently requires manual configuration (Cloud Function not yet implemented)

## Testing

### Manual Testing

1. **App detection**
   - Switch between different apps
   - Verify device status updates in Firestore

2. **Time tracking**
   - Use a non-whitelisted app for 1 minute
   - Check that `todayUsedMinutes` increased in Firestore

3. **Whitelisting**
   - Add an app to whitelist in parent app
   - Use that app on child device
   - Verify time doesn't increase

4. **Blocking**
   - Manually set `todayUsedMinutes` >= `dailyLimitMinutes` in Firestore
   - Try to open non-whitelisted app
   - Verify blocking overlay appears

5. **Extension requests**
   - Tap "Request Extension" on overlay
   - Enter minutes and reason
   - Verify request appears in parent app

### Unit Testing

```bash
./gradlew testDebugUnitTest
```

### Instrumentation Testing

```bash
./gradlew connectedAndroidTest
```

## File Structure

```
android-child-app/
├── app/
│   ├── src/main/
│   │   ├── java/com/familytime/child/
│   │   │   ├── MainActivity.kt
│   │   │   ├── data/
│   │   │   │   ├── FirebaseRepository.kt
│   │   │   │   ├── LocalCache.kt
│   │   │   │   ├── WhitelistManager.kt
│   │   │   │   ├── models/
│   │   │   │   │   ├── Enums.kt
│   │   │   │   │   ├── User.kt
│   │   │   │   │   ├── Device.kt
│   │   │   │   │   ├── WhitelistItem.kt
│   │   │   │   │   ├── ExtensionRequest.kt
│   │   │   │   │   └── UsageLog.kt
│   │   │   │   └── local/
│   │   │   │       ├── AppDatabase.kt
│   │   │   │       ├── CachedWhitelistItem.kt
│   │   │   │       ├── CachedUserState.kt
│   │   │   │       ├── PendingTimeUpdate.kt
│   │   │   │       └── *Dao.kt files
│   │   │   ├── services/
│   │   │   │   ├── ScreenTimeAccessibilityService.kt
│   │   │   │   ├── MonitoringService.kt
│   │   │   │   ├── BootReceiver.kt
│   │   │   │   └── ScreenTimeDeviceAdmin.kt
│   │   │   ├── ui/
│   │   │   │   ├── BlockingOverlayService.kt
│   │   │   │   └── ExtensionRequestActivity.kt
│   │   │   └── utils/
│   │   │       ├── AppUtils.kt
│   │   │       └── TimeUtils.kt
│   │   ├── res/
│   │   │   ├── layout/
│   │   │   │   ├── activity_main.xml
│   │   │   │   ├── overlay_blocking.xml
│   │   │   │   └── activity_extension_request.xml
│   │   │   ├── values/
│   │   │   │   └── strings.xml
│   │   │   └── xml/
│   │   │       ├── accessibility_service_config.xml
│   │   │       └── device_admin_config.xml
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts
├── build.gradle.kts
├── settings.gradle.kts
└── README.md
```

## Troubleshooting

### Accessibility Service Not Working

1. Go to Settings → Accessibility → Screen Time
2. Ensure the toggle is ON
3. If not listed, reinstall the app

### Overlay Not Showing

1. Check Display Over Apps permission is granted
2. Verify time limit is actually exceeded in Firestore
3. Check logcat for errors: `adb logcat -s AccessibilityService BlockingOverlay`

### Time Not Syncing

1. Check internet connection
2. Verify Firestore rules allow device to update `todayUsedMinutes`
3. Check pending time updates in Room database
4. Look for network errors in logcat

### Service Keeps Stopping

1. Disable battery optimization for the app
2. Ensure Accessibility Service is enabled
3. Check device doesn't have aggressive task killers

## Development

### Adding New Features

1. Update data models if Firestore schema changes
2. Update FirebaseRepository for new Firestore operations
3. Update LocalCache if new data needs caching
4. Test offline behavior

### Debugging

Enable verbose logging:
```bash
adb logcat -s AccessibilityService MonitoringService BlockingOverlay FirebaseRepository
```

## License

See LICENSE file in repository root.

## Related Components

- **Windows Client**: `../windows-client/` - Python service for Windows PCs
- **Parent App**: `../parent-app/` - Flutter app for parent dashboard
- **Firebase Functions**: `../firebase/functions/` - Cloud Functions for backend logic
- **Shared Models**: `../shared/` - Python data models (reference for Kotlin models)
