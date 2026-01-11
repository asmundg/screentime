"""Tests for local cache functionality."""

from datetime import datetime
from pathlib import Path

import pytest

from screentime_shared import Platform, WhitelistItem
from screentime_windows.cache import LocalCache


@pytest.fixture
def cache(tmp_path: Path) -> LocalCache:
    return LocalCache(tmp_path / "cache")


def test_pending_time_accumulates(cache: LocalCache) -> None:
    cache.add_pending_time(30.0)
    cache.add_pending_time(60.0)
    cache.add_pending_time(10.0)

    total = cache.get_and_clear_pending_time()
    assert total == 100.0


def test_pending_time_clears_after_get(cache: LocalCache) -> None:
    cache.add_pending_time(50.0)
    cache.get_and_clear_pending_time()

    total = cache.get_and_clear_pending_time()
    assert total == 0.0


def test_pending_time_persists_to_disk(tmp_path: Path) -> None:
    cache_dir = tmp_path / "cache"

    cache1 = LocalCache(cache_dir)
    cache1.add_pending_time(120.0)

    # New instance should read from disk
    cache2 = LocalCache(cache_dir)
    total = cache2.get_and_clear_pending_time()
    assert total == 120.0


def test_whitelist_round_trip(cache: LocalCache) -> None:
    items = [
        WhitelistItem(
            family_id="fam1",
            platform=Platform.WINDOWS,
            identifier="game.exe",
            display_name="Cool Game",
            added_at=datetime.now(),
        ),
        WhitelistItem(
            family_id="fam1",
            platform=Platform.BOTH,
            identifier="edu.exe",
            display_name="Education App",
            added_at=datetime.now(),
        ),
    ]

    cache.save_whitelist(items)
    loaded = cache.load_whitelist()

    assert loaded is not None
    assert len(loaded) == 2
    assert loaded[0].identifier == "game.exe"
    assert loaded[1].display_name == "Education App"


def test_whitelist_returns_none_when_no_cache(cache: LocalCache) -> None:
    assert cache.load_whitelist() is None


def test_user_state_round_trip(cache: LocalCache) -> None:
    cache.save_user_state(
        daily_limit_minutes=90,
        today_used_minutes=45.5,
        last_reset_date="2024-01-15",
    )

    state = cache.load_user_state()
    assert state is not None
    assert state["daily_limit_minutes"] == 90
    assert state["today_used_minutes"] == 45.5
    assert state["last_reset_date"] == "2024-01-15"


def test_user_state_returns_none_when_no_cache(cache: LocalCache) -> None:
    assert cache.load_user_state() is None
