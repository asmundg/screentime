"""Local cache for offline operation."""

import json
import logging
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path

from pydantic import BaseModel

from screentime_shared import WhitelistItem
from screentime_shared.firestore import firestore_to_dict

logger = logging.getLogger(__name__)


class CachedWhitelist(BaseModel):
    """Cached whitelist with timestamp."""

    items: list[WhitelistItem]
    cached_at: datetime


class PendingTimeIncrement(BaseModel):
    """Time increment that couldn't be synced due to offline state."""

    seconds: float
    timestamp: datetime


class LocalCache:
    """Manages local cache for offline operation."""

    def __init__(self, cache_dir: Path):
        self._cache_dir = cache_dir
        self._cache_dir.mkdir(parents=True, exist_ok=True)

    @property
    def _whitelist_path(self) -> Path:
        return self._cache_dir / "whitelist.json"

    @property
    def _pending_time_path(self) -> Path:
        return self._cache_dir / "pending_time.json"

    @property
    def _user_state_path(self) -> Path:
        return self._cache_dir / "user_state.json"

    def save_whitelist(self, items: list[WhitelistItem]) -> None:
        """Cache whitelist locally."""
        cached = CachedWhitelist(items=items, cached_at=datetime.now())
        self._whitelist_path.write_text(cached.model_dump_json(indent=2))
        logger.debug("Saved %d whitelist items to cache", len(items))

    def load_whitelist(self) -> list[WhitelistItem] | None:
        """Load cached whitelist. Returns None if no cache exists."""
        if not self._whitelist_path.exists():
            return None
        try:
            cached = CachedWhitelist.model_validate_json(self._whitelist_path.read_text())
            logger.debug("Loaded %d whitelist items from cache", len(cached.items))
            return cached.items
        except Exception:
            logger.exception("Failed to load whitelist cache")
            return None

    def add_pending_time(self, seconds: float) -> None:
        """Add time that couldn't be synced to Firestore."""
        pending = self._load_pending_time()
        pending.append(PendingTimeIncrement(seconds=seconds, timestamp=datetime.now()))
        self._save_pending_time(pending)
        logger.debug("Added %.1f seconds to pending time queue", seconds)

    def get_and_clear_pending_time(self) -> float:
        """Get total pending time and clear the queue.

        Returns total seconds that need to be synced.
        """
        pending = self._load_pending_time()
        if not pending:
            return 0.0
        total = sum(p.seconds for p in pending)
        self._save_pending_time([])
        logger.debug("Cleared %.1f seconds from pending time queue", total)
        return total

    def _load_pending_time(self) -> list[PendingTimeIncrement]:
        if not self._pending_time_path.exists():
            return []
        try:
            data = json.loads(self._pending_time_path.read_text())
            return [PendingTimeIncrement.model_validate(item) for item in data]
        except Exception:
            logger.exception("Failed to load pending time cache")
            return []

    def _save_pending_time(self, pending: list[PendingTimeIncrement]) -> None:
        data = [p.model_dump(mode="json") for p in pending]
        self._pending_time_path.write_text(json.dumps(data, indent=2, default=str))

    def save_user_state(
        self,
        daily_limit_minutes: int,
        today_used_minutes: float,
        last_reset_date: str,
    ) -> None:
        """Cache user state locally."""
        state = {
            "dailyLimitMinutes": daily_limit_minutes,
            "todayUsedMinutes": today_used_minutes,
            "lastResetDate": last_reset_date,
        }
        self._user_state_path.write_text(json.dumps(state, indent=2))

    def load_user_state(self) -> dict | None:
        """Load cached user state. Returns None if no cache exists."""
        if not self._user_state_path.exists():
            return None
        try:
            data = json.loads(self._user_state_path.read_text())
            return {
                "daily_limit_minutes": data["dailyLimitMinutes"],
                "today_used_minutes": data["todayUsedMinutes"],
                "last_reset_date": data["lastResetDate"],
            }
        except Exception:
            logger.exception("Failed to load user state cache")
            return None
