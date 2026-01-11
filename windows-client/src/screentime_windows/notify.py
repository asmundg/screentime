"""Windows toast notifications for time warnings."""

import logging
import sys
from dataclasses import dataclass, field
from enum import IntEnum

logger = logging.getLogger(__name__)


class WarningLevel(IntEnum):
    """Warning thresholds in minutes."""

    TEN_MINUTES = 10
    FIVE_MINUTES = 5
    ONE_MINUTE = 1


@dataclass
class NotificationState:
    """Tracks which warnings have been shown today."""

    shown_warnings: set[WarningLevel] = field(default_factory=set)
    last_reset_date: str = ""

    def reset_if_new_day(self, today: str) -> None:
        """Reset shown warnings on new day."""
        if self.last_reset_date != today:
            self.shown_warnings.clear()
            self.last_reset_date = today


def get_warning_level(minutes_remaining: float) -> WarningLevel | None:
    """Get the warning level for remaining time, if any."""
    if minutes_remaining <= WarningLevel.ONE_MINUTE:
        return WarningLevel.ONE_MINUTE
    elif minutes_remaining <= WarningLevel.FIVE_MINUTES:
        return WarningLevel.FIVE_MINUTES
    elif minutes_remaining <= WarningLevel.TEN_MINUTES:
        return WarningLevel.TEN_MINUTES
    return None


def should_show_warning(
    minutes_remaining: float,
    is_whitelisted: bool,
    state: NotificationState,
) -> WarningLevel | None:
    """Determine if we should show a warning.

    Returns the warning level to show, or None if no warning needed.
    """
    if is_whitelisted:
        return None

    level = get_warning_level(minutes_remaining)
    if level is None:
        return None

    if level in state.shown_warnings:
        return None

    return level


def show_time_warning(level: WarningLevel, minutes_remaining: float) -> bool:
    """Show a toast notification for the time warning.

    Returns True if notification was shown successfully.
    """
    if sys.platform != "win32":
        logger.debug("Notifications only supported on Windows")
        return False

    try:
        from winotify import Notification, audio

        title = _get_warning_title(level)
        message = _get_warning_message(level, minutes_remaining)

        toast = Notification(
            app_id="Screen Time Tracker",
            title=title,
            msg=message,
            duration="short",
        )
        toast.set_audio(audio.Default, loop=False)
        toast.show()

        logger.info("Showed %d-minute warning notification", level)
        return True

    except ImportError:
        logger.warning("winotify not installed, falling back to print warning")
        print(f"⚠️  {_get_warning_title(level)}: {_get_warning_message(level, minutes_remaining)}")
        return True
    except Exception:
        logger.exception("Failed to show notification")
        return False


def _get_warning_title(level: WarningLevel) -> str:
    """Get notification title for warning level."""
    if level == WarningLevel.ONE_MINUTE:
        return "⚠️ 1 Minute Left!"
    elif level == WarningLevel.FIVE_MINUTES:
        return "⏰ 5 Minutes Left"
    else:
        return "Screen Time Warning"


def _get_warning_message(level: WarningLevel, minutes_remaining: float) -> str:
    """Get notification message for warning level."""
    mins = int(minutes_remaining)
    if level == WarningLevel.ONE_MINUTE:
        return "Your screen time is almost up. Save your work!"
    elif level == WarningLevel.FIVE_MINUTES:
        return f"You have {mins} minutes of screen time remaining."
    else:
        return f"You have {mins} minutes of screen time remaining today."
