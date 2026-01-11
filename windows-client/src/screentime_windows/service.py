"""Windows service wrapper for Screen Time Tracker."""

import logging
import sys
import threading
from pathlib import Path

logger = logging.getLogger(__name__)

# Only import Windows service modules on Windows
if sys.platform == "win32":
    import servicemanager  # type: ignore[import-untyped]
    import win32event  # type: ignore[import-untyped]
    import win32service  # type: ignore[import-untyped]
    import win32serviceutil  # type: ignore[import-untyped]
    import firebase_admin  # type: ignore[import-untyped]
    from firebase_admin import credentials, firestore  # type: ignore[import-untyped]

    from .config import Config, load_config
    from .firebase_client import FirestoreClient
    from .loop import run_monitoring_loop

    class ScreenTimeService(win32serviceutil.ServiceFramework):
        """Windows service for Screen Time Tracker."""

        _svc_name_ = "ScreenTimeTracker"
        _svc_display_name_ = "Screen Time Tracker"
        _svc_description_ = (
            "Monitors application usage and enforces screen time limits. "
            "Part of the family screen time management system."
        )

        def __init__(self, args: list[str]) -> None:
            win32serviceutil.ServiceFramework.__init__(self, args)
            self._stop_event = win32event.CreateEvent(None, 0, 0, None)
            self._monitor_thread: threading.Thread | None = None
            self._stop_requested = False

        def SvcStop(self) -> None:
            """Handle service stop request."""
            logger.info("Service stop requested")
            self.ReportServiceStatus(win32service.SERVICE_STOP_PENDING)
            self._stop_requested = True
            win32event.SetEvent(self._stop_event)

        def SvcDoRun(self) -> None:
            """Main service entry point."""
            servicemanager.LogMsg(
                servicemanager.EVENTLOG_INFORMATION_TYPE,
                servicemanager.PYS_SERVICE_STARTED,
                (self._svc_name_, ""),
            )

            try:
                self._run_service()
            except Exception as e:
                logger.exception("Service failed")
                servicemanager.LogErrorMsg(f"Service failed: {e}")

            servicemanager.LogMsg(
                servicemanager.EVENTLOG_INFORMATION_TYPE,
                servicemanager.PYS_SERVICE_STOPPED,
                (self._svc_name_, ""),
            )

        def _run_service(self) -> None:
            """Run the monitoring service."""
            # Set up logging to Windows Event Log
            self._setup_logging()

            # Load configuration
            config_path = self._get_config_path()
            if not config_path.exists():
                raise FileNotFoundError(f"Config not found: {config_path}")

            config = load_config(config_path)
            logger.info("Loaded config for device: %s", config.device_name)

            # Initialize Firebase
            cred = credentials.Certificate(str(config.firebase_credentials_path))
            firebase_admin.initialize_app(cred)
            db = firestore.client()

            # Create client
            client = FirestoreClient(
                db=db,
                device_id=config.device_id,
                device_name=config.device_name,
                family_id=config.family_id,
                user_id=config.user_id,
            )

            # Run monitoring loop in background thread
            # (so we can respond to stop events)
            self._monitor_thread = threading.Thread(
                target=self._run_monitor_loop,
                args=(client, config),
                daemon=True,
            )
            self._monitor_thread.start()

            # Wait for stop signal
            while not self._stop_requested:
                rc = win32event.WaitForSingleObject(self._stop_event, 5000)
                if rc == win32event.WAIT_OBJECT_0:
                    break

            logger.info("Service stopping")

        def _run_monitor_loop(self, client: FirestoreClient, config: Config) -> None:
            """Run the monitoring loop (in background thread)."""
            try:
                run_monitoring_loop(
                    client=client,
                    poll_interval_seconds=config.poll_interval_seconds,
                    enable_tray=False,  # No tray icon when running as service
                )
            except Exception:
                logger.exception("Monitor loop failed")

        def _setup_logging(self) -> None:
            """Configure logging for service mode."""
            # Log to file in ProgramData
            log_dir = Path("C:/ProgramData/ScreenTimeTracker/logs")
            log_dir.mkdir(parents=True, exist_ok=True)
            log_file = log_dir / "service.log"

            logging.basicConfig(
                level=logging.INFO,
                format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
                handlers=[
                    logging.FileHandler(log_file),
                    logging.StreamHandler(),
                ],
            )

        def _get_config_path(self) -> Path:
            """Get the configuration file path."""
            # Check common locations
            candidates = [
                Path("C:/ProgramData/ScreenTimeTracker/config.json"),
                Path.home() / ".screentime" / "config.json",
            ]
            for path in candidates:
                if path.exists():
                    return path
            return candidates[0]  # Default location


def install_service() -> None:
    """Install the Windows service."""
    if sys.platform != "win32":
        print("Service installation only supported on Windows")
        return

    import win32serviceutil

    # Install the service
    win32serviceutil.InstallService(
        pythonClassString="screentime_windows.service.ScreenTimeService",
        serviceName="ScreenTimeTracker",
        displayName="Screen Time Tracker",
        description=(
            "Monitors application usage and enforces screen time limits. "
            "Part of the family screen time management system."
        ),
        startType=win32service.SERVICE_AUTO_START,
    )
    print("Service installed successfully")
    print("Start with: net start ScreenTimeTracker")
    print("Or use: sc start ScreenTimeTracker")


def uninstall_service() -> None:
    """Uninstall the Windows service."""
    if sys.platform != "win32":
        print("Service uninstallation only supported on Windows")
        return

    import win32serviceutil

    # Stop service first if running
    try:
        win32serviceutil.StopService("ScreenTimeTracker")
        print("Service stopped")
    except Exception:
        pass  # Service might not be running

    # Remove the service
    win32serviceutil.RemoveService("ScreenTimeTracker")
    print("Service uninstalled successfully")


def run_service() -> None:
    """Run as a Windows service (called by service manager)."""
    if sys.platform != "win32":
        print("Service mode only supported on Windows")
        return

    import servicemanager
    import win32serviceutil

    if len(sys.argv) == 1:
        # Started by service manager
        servicemanager.Initialize()
        servicemanager.PrepareToHostSingle(ScreenTimeService)
        servicemanager.StartServiceCtrlDispatcher()
    else:
        # Command line arguments (install, remove, start, stop, etc.)
        win32serviceutil.HandleCommandLine(ScreenTimeService)
