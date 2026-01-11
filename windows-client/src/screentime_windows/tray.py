"""System tray icon for Screen Time Tracker."""

import logging
import threading
from dataclasses import dataclass
from enum import Enum
from typing import Callable

from PIL import Image, ImageDraw, ImageFont
from pystray import Icon, Menu, MenuItem

logger = logging.getLogger(__name__)


class TrayColor(Enum):
    """Color states for the tray icon."""

    GREEN = (76, 175, 80)    # > 30 min remaining
    YELLOW = (255, 193, 7)   # 10-30 min remaining
    RED = (244, 67, 54)      # < 10 min remaining
    GRAY = (158, 158, 158)   # Paused/whitelisted


def get_tray_color(minutes_remaining: float, is_whitelisted: bool) -> TrayColor:
    """Determine tray icon color based on remaining time."""
    if is_whitelisted:
        return TrayColor.GRAY
    if minutes_remaining > 30:
        return TrayColor.GREEN
    if minutes_remaining > 10:
        return TrayColor.YELLOW
    return TrayColor.RED


def create_tray_icon_image(
    minutes_remaining: float,
    color: TrayColor,
    size: int = 64,
) -> Image.Image:
    """Create a tray icon image showing remaining time."""
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)

    # Draw filled circle background
    padding = 2
    draw.ellipse(
        [padding, padding, size - padding, size - padding],
        fill=color.value,
    )

    # Draw time text
    mins = max(0, int(minutes_remaining))
    text = str(mins) if mins < 100 else "99+"

    # Try to use a reasonable font size
    font_size = size // 2 if len(text) <= 2 else size // 3
    try:
        font = ImageFont.truetype("arial.ttf", font_size)
    except OSError:
        font = ImageFont.load_default()

    # Center the text
    bbox = draw.textbbox((0, 0), text, font=font)
    text_width = bbox[2] - bbox[0]
    text_height = bbox[3] - bbox[1]
    x = (size - text_width) // 2
    y = (size - text_height) // 2 - bbox[1]

    draw.text((x, y), text, fill=(255, 255, 255), font=font)

    return img


@dataclass
class TrayState:
    """State displayed in the tray icon."""

    minutes_remaining: float = 120.0
    minutes_used: float = 0.0
    daily_limit: int = 120
    current_app: str | None = None
    is_whitelisted: bool = False
    is_online: bool = True


class TrayManager:
    """Manages the system tray icon."""

    def __init__(
        self,
        on_request_extension: Callable[[int], None] | None = None,
        on_quit: Callable[[], None] | None = None,
    ):
        self._state = TrayState()
        self._icon: Icon | None = None
        self._on_request_extension = on_request_extension
        self._on_quit = on_quit
        self._whitelist: list[str] = []
        self._lock = threading.Lock()

    def start(self) -> None:
        """Start the tray icon in a background thread."""
        self._icon = Icon(
            name="Screen Time Tracker",
            icon=self._create_icon(),
            title=self._get_tooltip(),
            menu=self._create_menu(),
        )
        # Run in background thread so it doesn't block
        thread = threading.Thread(target=self._icon.run, daemon=True)
        thread.start()
        logger.info("Tray icon started")

    def stop(self) -> None:
        """Stop the tray icon."""
        if self._icon:
            self._icon.stop()
            self._icon = None
            logger.info("Tray icon stopped")

    def update(
        self,
        minutes_remaining: float,
        minutes_used: float,
        daily_limit: int,
        current_app: str | None,
        is_whitelisted: bool,
        is_online: bool,
        whitelist: list[str] | None = None,
    ) -> None:
        """Update the tray icon state."""
        with self._lock:
            self._state = TrayState(
                minutes_remaining=minutes_remaining,
                minutes_used=minutes_used,
                daily_limit=daily_limit,
                current_app=current_app,
                is_whitelisted=is_whitelisted,
                is_online=is_online,
            )
            if whitelist is not None:
                self._whitelist = whitelist

        if self._icon:
            self._icon.icon = self._create_icon()
            self._icon.title = self._get_tooltip()
            # Update menu to reflect current state
            self._icon.menu = self._create_menu()

    def _create_icon(self) -> Image.Image:
        """Create the current tray icon image."""
        with self._lock:
            color = get_tray_color(
                self._state.minutes_remaining,
                self._state.is_whitelisted,
            )
            return create_tray_icon_image(self._state.minutes_remaining, color)

    def _get_tooltip(self) -> str:
        """Get the tooltip text for the tray icon."""
        with self._lock:
            mins = int(self._state.minutes_remaining)
            if self._state.is_whitelisted:
                return f"Screen Time: {mins} min left (paused - whitelisted app)"
            return f"Screen Time: {mins} min left"

    def _create_menu(self) -> Menu:
        """Create the right-click menu."""
        with self._lock:
            state = self._state
            whitelist = self._whitelist.copy()

        # Current status
        if state.current_app:
            if state.is_whitelisted:
                status_text = f"✓ {state.current_app} (whitelisted)"
            else:
                status_text = f"● {state.current_app} (counting)"
        else:
            status_text = "No active app"

        # Connection status
        connection_text = "🟢 Online" if state.is_online else "🔴 Offline"

        # Time info
        used = int(state.minutes_used)
        limit = state.daily_limit
        remaining = int(state.minutes_remaining)
        time_text = f"{used}/{limit} min used ({remaining} left)"

        items = [
            MenuItem(status_text, None, enabled=False),
            MenuItem(time_text, None, enabled=False),
            MenuItem(connection_text, None, enabled=False),
            Menu.SEPARATOR,
            MenuItem(
                "Request Extension",
                Menu(
                    MenuItem("+15 minutes", lambda: self._request_extension(15)),
                    MenuItem("+30 minutes", lambda: self._request_extension(30)),
                    MenuItem("+60 minutes", lambda: self._request_extension(60)),
                ),
            ),
            MenuItem(
                "Whitelist",
                Menu(*[
                    MenuItem(app, None, enabled=False)
                    for app in (whitelist[:10] if whitelist else ["(empty)"])
                ]),
            ),
            Menu.SEPARATOR,
            MenuItem("Quit", self._on_quit_clicked),
        ]

        return Menu(*items)

    def _request_extension(self, minutes: int) -> None:
        """Handle extension request from menu."""
        logger.info("Extension requested: %d minutes", minutes)
        if self._on_request_extension:
            self._on_request_extension(minutes)

    def _on_quit_clicked(self) -> None:
        """Handle quit from menu."""
        logger.info("Quit requested from tray")
        if self._on_quit:
            self._on_quit()
        self.stop()
