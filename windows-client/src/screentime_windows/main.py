"""Entry point for the Windows screen time service."""

import argparse
import logging
import sys
from pathlib import Path

import firebase_admin  # type: ignore[import-untyped]
from firebase_admin import credentials, firestore  # type: ignore[import-untyped]

from .config import Config, load_config
from .firebase_client import FirestoreClient
from .loop import run_monitoring_loop

logger = logging.getLogger(__name__)


def setup_logging(verbose: bool, log_file: Path | None = None) -> None:
    """Configure logging for the application."""
    level = logging.DEBUG if verbose else logging.INFO
    handlers: list[logging.Handler] = [logging.StreamHandler()]

    if log_file:
        log_file.parent.mkdir(parents=True, exist_ok=True)
        handlers.append(logging.FileHandler(log_file))

    logging.basicConfig(
        level=level,
        format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
        datefmt="%Y-%m-%d %H:%M:%S",
        handlers=handlers,
    )


def init_firebase(config: Config) -> firestore.Client:
    """Initialize Firebase Admin SDK and return Firestore client."""
    cred = credentials.Certificate(str(config.firebase_credentials_path))
    firebase_admin.initialize_app(cred)
    return firestore.client()


def cmd_run(args: argparse.Namespace) -> None:
    """Run the screen time tracker interactively."""
    setup_logging(args.verbose)

    config_path: Path = args.config
    if not config_path.exists():
        logger.error("Configuration file not found: %s", config_path)
        sys.exit(1)

    config = load_config(config_path)
    logger.info("Loaded configuration for device: %s", config.device_name)

    db = init_firebase(config)
    logger.info("Firebase initialized")

    client = FirestoreClient(
        db=db,
        device_id=config.device_id,
        device_name=config.device_name,
        family_id=config.family_id,
        user_id=config.user_id,
    )

    logger.info("Starting monitoring loop...")
    run_monitoring_loop(
        client=client,
        poll_interval_seconds=config.poll_interval_seconds,
        enable_tray=not args.no_tray,
    )


def cmd_install(args: argparse.Namespace) -> None:
    """Install as Windows service."""
    from .service import install_service
    install_service()


def cmd_uninstall(args: argparse.Namespace) -> None:
    """Uninstall Windows service."""
    from .service import uninstall_service
    uninstall_service()


def cmd_service(args: argparse.Namespace) -> None:
    """Run as Windows service (called by service manager)."""
    from .service import run_service
    run_service()


def main() -> None:
    """Main entry point."""
    parser = argparse.ArgumentParser(
        description="Screen Time Tracker for Windows",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  screentime                     Run interactively with tray icon
  screentime --no-tray           Run without tray icon
  screentime install             Install as Windows service
  screentime uninstall           Remove Windows service
  net start ScreenTimeTracker    Start the installed service
  net stop ScreenTimeTracker     Stop the installed service
""",
    )

    subparsers = parser.add_subparsers(dest="command")

    # Run command (default)
    run_parser = subparsers.add_parser("run", help="Run interactively")
    run_parser.add_argument(
        "--config", "-c",
        type=Path,
        default=Path("config.json"),
        help="Path to configuration file",
    )
    run_parser.add_argument(
        "--verbose", "-v",
        action="store_true",
        help="Enable verbose logging",
    )
    run_parser.add_argument(
        "--no-tray",
        action="store_true",
        help="Disable system tray icon",
    )
    run_parser.set_defaults(func=cmd_run)

    # Install command
    install_parser = subparsers.add_parser(
        "install",
        help="Install as Windows service (requires admin)",
    )
    install_parser.set_defaults(func=cmd_install)

    # Uninstall command
    uninstall_parser = subparsers.add_parser(
        "uninstall",
        help="Uninstall Windows service (requires admin)",
    )
    uninstall_parser.set_defaults(func=cmd_uninstall)

    # Service command (internal, called by service manager)
    service_parser = subparsers.add_parser("service", help=argparse.SUPPRESS)
    service_parser.set_defaults(func=cmd_service)

    # Add common args to main parser for default behavior
    parser.add_argument(
        "--config", "-c",
        type=Path,
        default=Path("config.json"),
        help="Path to configuration file",
    )
    parser.add_argument(
        "--verbose", "-v",
        action="store_true",
        help="Enable verbose logging",
    )
    parser.add_argument(
        "--no-tray",
        action="store_true",
        help="Disable system tray icon",
    )

    args = parser.parse_args()

    # Default to run if no subcommand
    if args.command is None:
        cmd_run(args)
    else:
        args.func(args)


if __name__ == "__main__":
    main()
