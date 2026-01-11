"""Firebase/Firestore client for the Windows service."""

from datetime import UTC, datetime

from google.cloud.firestore import Client, Transaction  # type: ignore[import-untyped]

from screentime_shared import ExtensionRequest, Platform, RequestStatus, User, WhitelistItem
from screentime_shared.firestore import firestore_to_dict


class FirestoreClient:
    """Handles all Firestore operations for the Windows client."""

    def __init__(
        self,
        db: Client,
        device_id: str,
        device_name: str,
        family_id: str,
        user_id: str,
    ):
        self._db = db
        self._device_id = device_id
        self._device_name = device_name
        self._family_id = family_id
        self._user_id = user_id

    def update_device_status(
        self,
        current_app: str | None,
        current_app_package: str | None,
    ) -> None:
        """Update this device's status in Firestore."""
        doc_ref = self._db.collection("devices").document(self._device_id)
        doc_ref.update(
            {
                "currentApp": current_app,
                "currentAppPackage": current_app_package,
                "lastSeen": datetime.now(),
            }
        )

    def get_user(self) -> User:
        """Get the current user document."""
        doc = self._db.collection("users").document(self._user_id).get()
        if not doc.exists:
            raise ValueError(f"User {self._user_id} not found")
        return User.model_validate(firestore_to_dict(doc.to_dict()))

    def increment_used_time(self, seconds: float) -> float:
        """Atomically increment the user's used time.

        Returns the new total used minutes.
        """
        user_ref = self._db.collection("users").document(self._user_id)

        @self._db.transaction
        def update_in_transaction(transaction: Transaction) -> float:
            user_doc = user_ref.get(transaction=transaction)
            if not user_doc.exists:
                raise ValueError(f"User {self._user_id} not found")

            data = user_doc.to_dict()
            current_minutes = data.get("todayUsedMinutes", 0.0)
            new_minutes = current_minutes + (seconds / 60.0)

            transaction.update(user_ref, {"todayUsedMinutes": new_minutes})
            return new_minutes

        return update_in_transaction()  # type: ignore[return-value]

    def get_whitelist(self) -> list[WhitelistItem]:
        """Get all whitelist items for this family that apply to Windows."""
        query = (
            self._db.collection("whitelist")
            .where("familyId", "==", self._family_id)
            .where("platform", "in", [Platform.WINDOWS.value, Platform.BOTH.value])
        )
        return [
            WhitelistItem.model_validate(firestore_to_dict(doc.to_dict()))
            for doc in query.stream()
        ]

    def is_whitelisted(self, exe_name: str, whitelist: list[WhitelistItem]) -> bool:
        """Check if an executable is in the whitelist."""
        exe_lower = exe_name.lower()
        return any(item.identifier.lower() == exe_lower for item in whitelist)

    def log_app_usage(
        self,
        app_identifier: str,
        app_display_name: str,
        minutes: float,
        was_whitelisted: bool,
    ) -> None:
        """Log app usage for analytics."""
        today = datetime.now(UTC).strftime("%Y-%m-%d")
        self._db.collection("usageLogs").add(
            {
                "familyId": self._family_id,
                "userId": self._user_id,
                "deviceId": self._device_id,
                "platform": Platform.WINDOWS.value,
                "date": today,
                "appIdentifier": app_identifier,
                "appDisplayName": app_display_name,
                "minutes": minutes,
                "wasWhitelisted": was_whitelisted,
            }
        )

    def reset_daily_counter(self, today: str) -> None:
        """Reset the daily usage counter for the user."""
        user_ref = self._db.collection("users").document(self._user_id)
        user_ref.update(
            {
                "todayUsedMinutes": 0.0,
                "lastResetDate": today,
            }
        )

    def get_and_clear_approved_extensions(self) -> list[ExtensionRequest]:
        """Get approved extension requests for this device and mark them processed.

        Returns the list of approved extensions that were found.
        """
        query = (
            self._db.collection("extensionRequests")
            .where("deviceId", "==", self._device_id)
            .where("status", "==", RequestStatus.APPROVED.value)
        )

        approved: list[ExtensionRequest] = []
        for doc in query.stream():
            ext = ExtensionRequest.model_validate(firestore_to_dict(doc.to_dict()))
            approved.append(ext)
            # Mark as processed by updating status to prevent re-processing
            doc.reference.update({"status": "processed"})

        return approved

    def create_extension_request(self, minutes: int, reason: str | None = None) -> str:
        """Create a new extension request.

        Returns the request ID.
        """
        now = datetime.now(UTC)
        doc_ref = self._db.collection("extensionRequests").add(
            {
                "familyId": self._family_id,
                "userId": self._user_id,
                "deviceId": self._device_id,
                "deviceName": self._device_name,
                "platform": Platform.WINDOWS.value,
                "requestedMinutes": minutes,
                "reason": reason,
                "status": RequestStatus.PENDING.value,
                "createdAt": now,
                "respondedAt": None,
            }
        )
        return doc_ref[1].id
