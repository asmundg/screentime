"""Workstation locking functionality."""

import ctypes
import sys


def lock_workstation() -> bool:
    """Lock the Windows workstation.

    Returns True if successful, False otherwise.
    """
    if sys.platform != "win32":
        return False

    return bool(ctypes.windll.user32.LockWorkStation())
