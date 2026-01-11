"""Firestore data models for Screen Time Tracker.

These models define the schema for all Firestore collections.
All clients (Windows, Android, Flutter) must conform to this schema.
"""

from datetime import datetime
from enum import StrEnum
from typing import Annotated

from pydantic import BaseModel, ConfigDict, Field


class Platform(StrEnum):
    WINDOWS = "windows"
    ANDROID = "android"
    BOTH = "both"


class TimeTrackingMode(StrEnum):
    PER_DEVICE = "per_device"
    UNIFIED = "unified"


class RequestStatus(StrEnum):
    PENDING = "pending"
    APPROVED = "approved"
    DENIED = "denied"


class Family(BaseModel):
    """Firestore: families/{familyId}"""

    model_config = ConfigDict(populate_by_name=True)

    name: str
    created_at: datetime
    parent_email: str
    time_tracking_mode: TimeTrackingMode = TimeTrackingMode.UNIFIED


class Device(BaseModel):
    """Firestore: devices/{deviceId}"""

    family_id: str
    user_id: str
    name: str
    platform: Platform
    last_seen: datetime
    current_app: str | None = None
    current_app_package: str | None = None
    is_locked: bool = False
    fcm_token: str | None = None


class User(BaseModel):
    """Firestore: users/{userId}

    Represents a child user in the family.
    """

    family_id: str
    name: str
    daily_limit_minutes: Annotated[int, Field(ge=0)] = 120
    today_used_minutes: Annotated[float, Field(ge=0)] = 0.0
    last_reset_date: str  # YYYY-MM-DD
    windows_username: str | None = None


class WhitelistItem(BaseModel):
    """Firestore: whitelist/{id}

    Apps in the whitelist don't count toward time budget.
    """

    family_id: str
    platform: Platform
    identifier: str  # exe name (Windows) or package name (Android)
    display_name: str
    added_at: datetime


class ExtensionRequest(BaseModel):
    """Firestore: extensionRequests/{requestId}"""

    family_id: str
    user_id: str
    device_id: str
    device_name: str
    platform: Platform
    requested_minutes: Annotated[int, Field(gt=0)]
    reason: str | None = None
    status: RequestStatus = RequestStatus.PENDING
    created_at: datetime
    responded_at: datetime | None = None


class UsageLog(BaseModel):
    """Firestore: usageLogs/{logId}

    Historical usage data for analytics.
    """

    family_id: str
    user_id: str
    device_id: str
    platform: Platform
    date: str  # YYYY-MM-DD
    app_identifier: str
    app_display_name: str
    minutes: Annotated[float, Field(ge=0)]
    was_whitelisted: bool
