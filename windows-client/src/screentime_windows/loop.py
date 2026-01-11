"""Main monitoring loop for the Windows screen time service."""

import logging
import time
from dataclasses import dataclass, field
from datetime import UTC, date, datetime
from pathlib import Path

from screentime_shared import WhitelistItem

from .cache import LocalCache
from .firebase_client import FirestoreClient
from .lock import lock_workstation
from .monitor import get_foreground_exe

logger = logging.getLogger(__name__)


@dataclass
class MonitorState:
    """Mutable state for the monitoring loop."""

    whitelist: list[WhitelistItem] = field(default_factory=list)
    last_whitelist_refresh: datetime | None = None
    last_foreground_exe: str | None = None
    daily_limit_minutes: int = 120
    today_used_minutes: float = 0.0
    last_reset_date: str = ""
    is_locked: bool = False
    is_online: bool = True


def run_monitoring_loop(
    client: FirestoreClient,
    poll_interval_seconds: int = 10,
    whitelist_refresh_seconds: int = 60,
    cache_dir: Path | None = None,
) -> None:
    """Run the main monitoring loop. Does not return unless interrupted."""
    state = MonitorState()
    cache = LocalCache(cache_dir or Path.home() / ".screentime" / "cache")

    # Initial load - try online first, fall back to cache
    _initial_sync(client, state, cache)

    logger.info(
        "Starting monitoring loop (poll=%ds, whitelist_refresh=%ds, online=%s)",
        poll_interval_seconds,
        whitelist_refresh_seconds,
        state.is_online,
    )

    while True:
        try:
            _tick(client, state, cache, poll_interval_seconds, whitelist_refresh_seconds)
        except KeyboardInterrupt:
            logger.info("Monitoring loop interrupted")
            break
        except Exception:
            logger.exception("Error in monitoring loop tick")

        time.sleep(poll_interval_seconds)


def _initial_sync(
    client: FirestoreClient, state: MonitorState, cache: LocalCache
) -> None:
    """Perform initial sync, falling back to cache if offline."""
    # Try to sync pending time from previous offline session
    pending_seconds = cache.get_and_clear_pending_time()
    if pending_seconds > 0:
        try:
            client.increment_used_time(pending_seconds)
            logger.info("Synced %.1f pending seconds from previous session", pending_seconds)
        except Exception:
            # Put it back in the queue
            cache.add_pending_time(pending_seconds)
            logger.warning("Failed to sync pending time, will retry later")

    # Try to load from Firestore
    try:
        _refresh_whitelist(client, state, cache)
        _sync_user_state(client, state, cache)
        state.is_online = True
    except Exception:
        logger.warning("Failed to connect to Firestore, using cached data")
        state.is_online = False
        _load_from_cache(state, cache)


def _load_from_cache(state: MonitorState, cache: LocalCache) -> None:
    """Load state from local cache."""
    whitelist = cache.load_whitelist()
    if whitelist is not None:
        state.whitelist = whitelist
        logger.info("Loaded %d whitelist items from cache", len(whitelist))

    user_state = cache.load_user_state()
    if user_state is not None:
        state.daily_limit_minutes = user_state["daily_limit_minutes"]
        state.today_used_minutes = user_state["today_used_minutes"]
        state.last_reset_date = user_state["last_reset_date"]
        logger.info("Loaded user state from cache")


def _tick(
    client: FirestoreClient,
    state: MonitorState,
    cache: LocalCache,
    poll_interval_seconds: int,
    whitelist_refresh_seconds: int,
) -> None:
    """Single iteration of the monitoring loop."""
    now = datetime.now(UTC)

    # Refresh whitelist periodically
    if _should_refresh_whitelist(state, now, whitelist_refresh_seconds):
        try:
            _refresh_whitelist(client, state, cache)
            # If we were offline and are now online, sync pending time
            if not state.is_online:
                _sync_pending_time(client, cache)
            state.is_online = True
        except Exception:
            if state.is_online:
                logger.warning("Lost connection to Firestore, switching to offline mode")
            state.is_online = False

    # Check for daily reset
    today = date.today().isoformat()
    if state.last_reset_date != today:
        _handle_daily_reset(client, state, cache, today)

    # Get current foreground app
    exe_name = get_foreground_exe()
    state.last_foreground_exe = exe_name

    # Update device status in Firestore (if online)
    if state.is_online:
        try:
            client.update_device_status(
                current_app=exe_name,
                current_app_package=exe_name,
            )
        except Exception:
            logger.debug("Failed to update device status")

    if exe_name is None:
        logger.debug("No foreground window detected")
        return

    # Check if whitelisted
    is_whitelisted = client.is_whitelisted(exe_name, state.whitelist)

    if is_whitelisted:
        logger.debug("App %s is whitelisted, not counting time", exe_name)
    else:
        # Increment used time
        _increment_time(client, state, cache, poll_interval_seconds)

    # Log usage for analytics (if online)
    if state.is_online:
        try:
            client.log_app_usage(
                app_identifier=exe_name,
                app_display_name=exe_name,
                minutes=poll_interval_seconds / 60.0,
                was_whitelisted=is_whitelisted,
            )
        except Exception:
            logger.debug("Failed to log app usage")

    # Check for approved extension requests (if online)
    if state.is_online:
        _check_extension_approvals(client, state)

    # Check if limit exceeded
    if state.today_used_minutes >= state.daily_limit_minutes:
        if not state.is_locked:
            logger.warning(
                "Time limit exceeded (%.1f >= %d minutes), locking workstation",
                state.today_used_minutes,
                state.daily_limit_minutes,
            )
            state.is_locked = True
            lock_workstation()
    else:
        state.is_locked = False


def _increment_time(
    client: FirestoreClient,
    state: MonitorState,
    cache: LocalCache,
    seconds: float,
) -> None:
    """Increment used time, with offline fallback."""
    if state.is_online:
        try:
            new_total = client.increment_used_time(seconds)
            state.today_used_minutes = new_total
            cache.save_user_state(
                state.daily_limit_minutes,
                state.today_used_minutes,
                state.last_reset_date,
            )
            logger.debug("Synced time, total used: %.1f minutes", new_total)
            return
        except Exception:
            logger.debug("Failed to sync time, queueing locally")
            state.is_online = False

    # Offline: track locally
    cache.add_pending_time(seconds)
    state.today_used_minutes += seconds / 60.0
    cache.save_user_state(
        state.daily_limit_minutes,
        state.today_used_minutes,
        state.last_reset_date,
    )
    logger.debug("Queued time locally, local total: %.1f minutes", state.today_used_minutes)


def _sync_pending_time(client: FirestoreClient, cache: LocalCache) -> None:
    """Sync any pending time from offline operation."""
    pending_seconds = cache.get_and_clear_pending_time()
    if pending_seconds > 0:
        try:
            client.increment_used_time(pending_seconds)
            logger.info("Synced %.1f pending seconds", pending_seconds)
        except Exception:
            cache.add_pending_time(pending_seconds)
            logger.warning("Failed to sync pending time")


def _should_refresh_whitelist(
    state: MonitorState, now: datetime, refresh_seconds: int
) -> bool:
    if state.last_whitelist_refresh is None:
        return True
    elapsed = (now - state.last_whitelist_refresh).total_seconds()
    return elapsed >= refresh_seconds


def _refresh_whitelist(
    client: FirestoreClient, state: MonitorState, cache: LocalCache
) -> None:
    """Refresh the whitelist from Firestore and cache it."""
    state.whitelist = client.get_whitelist()
    state.last_whitelist_refresh = datetime.now(UTC)
    cache.save_whitelist(state.whitelist)
    logger.debug("Refreshed whitelist: %d items", len(state.whitelist))


def _sync_user_state(
    client: FirestoreClient, state: MonitorState, cache: LocalCache
) -> None:
    """Sync user state (limit, used time) from Firestore."""
    user = client.get_user()
    state.daily_limit_minutes = user.daily_limit_minutes
    state.today_used_minutes = user.today_used_minutes
    state.last_reset_date = user.last_reset_date
    cache.save_user_state(
        state.daily_limit_minutes,
        state.today_used_minutes,
        state.last_reset_date,
    )
    logger.debug(
        "Synced user state: limit=%d, used=%.1f, reset_date=%s",
        state.daily_limit_minutes,
        state.today_used_minutes,
        state.last_reset_date,
    )


def _handle_daily_reset(
    client: FirestoreClient,
    state: MonitorState,
    cache: LocalCache,
    today: str,
) -> None:
    """Handle daily counter reset."""
    logger.info("New day detected (%s), resetting counter", today)
    if state.is_online:
        try:
            client.reset_daily_counter(today)
        except Exception:
            logger.warning("Failed to reset counter in Firestore")
    state.today_used_minutes = 0.0
    state.last_reset_date = today
    state.is_locked = False
    cache.save_user_state(
        state.daily_limit_minutes,
        state.today_used_minutes,
        state.last_reset_date,
    )


def _check_extension_approvals(client: FirestoreClient, state: MonitorState) -> None:
    """Check for approved extension requests and apply them."""
    try:
        approved = client.get_and_clear_approved_extensions()
        for ext in approved:
            state.daily_limit_minutes += ext.requested_minutes
            logger.info(
                "Applied extension of %d minutes, new limit: %d",
                ext.requested_minutes,
                state.daily_limit_minutes,
            )
    except Exception:
        logger.debug("Failed to check extension approvals")
