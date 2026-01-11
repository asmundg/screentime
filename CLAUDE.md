# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Cross-platform parental control system tracking screen time on Windows PCs and Android devices with a unified time budget. Firebase (Firestore) is the shared backend.

## Architecture

```
screentime/
├── shared/              # Python pydantic models (Firestore schema)
├── windows-client/      # Python Windows service
├── android-child-app/   # Kotlin Android app (child device)
├── parent-app/          # Flutter app (parent dashboard)
└── firebase/            # Cloud Functions + Firestore rules
```

The `shared/` package defines the Firestore data model. All clients must conform to this schema. The Windows client imports it directly; other clients implement equivalent structures.

## Development Commands

### Shared Models
```bash
cd shared && uv sync
uv run python -c "from screentime_shared import *; print('OK')"
```

### Windows Client
```bash
cd windows-client && uv sync
uv run screentime              # Run the client
uv run pytest                  # Run tests
uv run mypy src/               # Type check
```

### Running Tests (any Python subproject)
```bash
uv run pytest tests/                           # All tests
uv run pytest tests/test_models.py             # Single file
uv run pytest tests/test_models.py::test_name  # Single test
```

### Firebase
```bash
cd firebase
firebase login                    # Authenticate
firebase use your-project-id      # Set project
firebase deploy --only firestore  # Deploy rules & indexes
firebase deploy --only functions  # Deploy Cloud Functions
firebase emulators:start          # Local testing
```

Cloud Functions setup:
```bash
cd firebase/functions
npm install
npm run build
```

## Firestore Schema

Collections: `families`, `devices`, `users`, `whitelist`, `extensionRequests`, `usageLogs`

Models are defined in `shared/src/screentime_shared/models.py`. Key points:
- Python uses snake_case, Firestore uses camelCase
- Use `firestore.model_to_firestore()` / `firestore.firestore_to_dict()` for conversion
- All timestamps are `datetime` objects
- Enums: `Platform`, `TimeTrackingMode`, `RequestStatus`

## Unified Time Tracking

The core feature: time on non-whitelisted apps counts toward a **single shared budget** across all devices.

1. Each client monitors foreground app
2. If app NOT whitelisted → atomically increment `users/{userId}/todayUsedMinutes`
3. All clients read this same field → when limit hit, enforce lock
4. Uses Firestore transactions for atomic updates

## Platform-Specific Notes

### Windows Client
- Runs as Windows service (SYSTEM account)
- Uses `win32gui`/`win32process` for foreground window detection
- `ctypes.windll.user32.LockWorkStation()` for locking
- Non-Windows stubs exist for development on macOS

### Android Child App (Kotlin)
- Requires: Usage Stats, Accessibility Service, Display Over Other Apps, Device Admin
- AccessibilityService detects foreground app changes
- Blocking overlay prevents access when time exceeded
- Anti-bypass: Device Admin prevents uninstall

### Parent App (Flutter)
- Cross-platform (Android/iOS)
- Dashboard shows combined usage across all child devices
- Manages whitelist, approves extension requests
