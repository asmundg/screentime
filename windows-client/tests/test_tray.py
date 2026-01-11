"""Tests for tray icon logic."""

import pytest

from screentime_windows.tray import TrayColor, create_tray_icon_image, get_tray_color


class TestGetTrayColor:
    def test_gray_when_whitelisted(self) -> None:
        assert get_tray_color(minutes_remaining=100.0, is_whitelisted=True) == TrayColor.GRAY
        assert get_tray_color(minutes_remaining=5.0, is_whitelisted=True) == TrayColor.GRAY
        assert get_tray_color(minutes_remaining=0.0, is_whitelisted=True) == TrayColor.GRAY

    def test_green_above_30_minutes(self) -> None:
        assert get_tray_color(minutes_remaining=31.0, is_whitelisted=False) == TrayColor.GREEN
        assert get_tray_color(minutes_remaining=60.0, is_whitelisted=False) == TrayColor.GREEN
        assert get_tray_color(minutes_remaining=120.0, is_whitelisted=False) == TrayColor.GREEN

    def test_yellow_between_10_and_30(self) -> None:
        assert get_tray_color(minutes_remaining=30.0, is_whitelisted=False) == TrayColor.YELLOW
        assert get_tray_color(minutes_remaining=20.0, is_whitelisted=False) == TrayColor.YELLOW
        assert get_tray_color(minutes_remaining=11.0, is_whitelisted=False) == TrayColor.YELLOW

    def test_red_below_10_minutes(self) -> None:
        assert get_tray_color(minutes_remaining=10.0, is_whitelisted=False) == TrayColor.RED
        assert get_tray_color(minutes_remaining=5.0, is_whitelisted=False) == TrayColor.RED
        assert get_tray_color(minutes_remaining=0.0, is_whitelisted=False) == TrayColor.RED


class TestCreateTrayIconImage:
    def test_creates_image_with_correct_size(self) -> None:
        img = create_tray_icon_image(45.0, TrayColor.GREEN, size=64)
        assert img.size == (64, 64)

    def test_creates_image_with_custom_size(self) -> None:
        img = create_tray_icon_image(45.0, TrayColor.GREEN, size=32)
        assert img.size == (32, 32)

    def test_handles_large_numbers(self) -> None:
        # Should show "99+" for large values
        img = create_tray_icon_image(150.0, TrayColor.GREEN)
        assert img is not None

    def test_handles_zero(self) -> None:
        img = create_tray_icon_image(0.0, TrayColor.RED)
        assert img is not None

    def test_handles_negative(self) -> None:
        # Edge case: negative remaining time
        img = create_tray_icon_image(-5.0, TrayColor.RED)
        assert img is not None
