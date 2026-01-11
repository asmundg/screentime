"""Tests for notification logic."""

import pytest

from screentime_windows.notify import (
    NotificationState,
    WarningLevel,
    get_warning_level,
    should_show_warning,
)


class TestGetWarningLevel:
    def test_above_ten_minutes_no_warning(self) -> None:
        assert get_warning_level(15.0) is None
        assert get_warning_level(30.0) is None
        assert get_warning_level(100.0) is None

    def test_ten_minute_threshold(self) -> None:
        assert get_warning_level(10.0) == WarningLevel.TEN_MINUTES
        assert get_warning_level(9.9) == WarningLevel.TEN_MINUTES
        assert get_warning_level(5.1) == WarningLevel.TEN_MINUTES

    def test_five_minute_threshold(self) -> None:
        assert get_warning_level(5.0) == WarningLevel.FIVE_MINUTES
        assert get_warning_level(4.0) == WarningLevel.FIVE_MINUTES
        assert get_warning_level(1.1) == WarningLevel.FIVE_MINUTES

    def test_one_minute_threshold(self) -> None:
        assert get_warning_level(1.0) == WarningLevel.ONE_MINUTE
        assert get_warning_level(0.5) == WarningLevel.ONE_MINUTE
        assert get_warning_level(0.0) == WarningLevel.ONE_MINUTE


class TestShouldShowWarning:
    def test_no_warning_when_whitelisted(self) -> None:
        state = NotificationState()
        result = should_show_warning(
            minutes_remaining=5.0,
            is_whitelisted=True,
            state=state,
        )
        assert result is None

    def test_no_warning_when_plenty_of_time(self) -> None:
        state = NotificationState()
        result = should_show_warning(
            minutes_remaining=60.0,
            is_whitelisted=False,
            state=state,
        )
        assert result is None

    def test_shows_warning_at_threshold(self) -> None:
        state = NotificationState()
        result = should_show_warning(
            minutes_remaining=10.0,
            is_whitelisted=False,
            state=state,
        )
        assert result == WarningLevel.TEN_MINUTES

    def test_no_repeat_warning(self) -> None:
        state = NotificationState()
        state.shown_warnings.add(WarningLevel.TEN_MINUTES)

        result = should_show_warning(
            minutes_remaining=8.0,
            is_whitelisted=False,
            state=state,
        )
        assert result is None

    def test_shows_next_warning_level(self) -> None:
        state = NotificationState()
        state.shown_warnings.add(WarningLevel.TEN_MINUTES)

        # Still above 5 min - no new warning
        result = should_show_warning(minutes_remaining=6.0, is_whitelisted=False, state=state)
        assert result is None

        # At 5 min - show 5 min warning
        result = should_show_warning(minutes_remaining=5.0, is_whitelisted=False, state=state)
        assert result == WarningLevel.FIVE_MINUTES


class TestNotificationState:
    def test_reset_clears_warnings(self) -> None:
        state = NotificationState()
        state.shown_warnings.add(WarningLevel.TEN_MINUTES)
        state.shown_warnings.add(WarningLevel.FIVE_MINUTES)
        state.last_reset_date = "2024-01-15"

        state.reset_if_new_day("2024-01-16")

        assert len(state.shown_warnings) == 0
        assert state.last_reset_date == "2024-01-16"

    def test_no_reset_same_day(self) -> None:
        state = NotificationState()
        state.shown_warnings.add(WarningLevel.TEN_MINUTES)
        state.last_reset_date = "2024-01-15"

        state.reset_if_new_day("2024-01-15")

        assert WarningLevel.TEN_MINUTES in state.shown_warnings
