"""Foreground window monitoring for Windows."""

import sys

if sys.platform == "win32":
    import win32gui
    import win32process
    import psutil

    def get_foreground_exe() -> str | None:
        """Get the executable name of the current foreground window."""
        hwnd = win32gui.GetForegroundWindow()
        if not hwnd:
            return None

        _, pid = win32process.GetWindowThreadProcessId(hwnd)
        try:
            process = psutil.Process(pid)
            return process.name()
        except (psutil.NoSuchProcess, psutil.AccessDenied):
            return None

else:
    # Stub for non-Windows development
    def get_foreground_exe() -> str | None:
        """Stub for non-Windows platforms."""
        return None
