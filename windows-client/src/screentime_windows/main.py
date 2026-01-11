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


def setup_logging(verbose: bool) -> None:
    """Configure logging for the application."""
    level = logging.DEBUG if verbose else logging.INFO
    logging.basicConfig(
        level=level,
        format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
        datefmt="%Y-%m-%d %H:%M:%S",
    )


def init_firebase(config: Config) -> firestore.Client:
    """Initialize Firebase Admin SDK and return Firestore client."""
    cred = credentials.Certificate(str(config.firebase_credentials_path))
    firebase_admin.initialize_app(cred)
    return firestore.client()


def main() -> None:
    """Main entry point."""
    parser = argparse.ArgumentParser(description="Screen Time Tracker for Windows")
    parser.add_argument(
        "--config",
        type=Path,
        default=Path("config.json"),
        help="Path to configuration file (default: config.json)",
    )
    parser.add_argument(
        "--verbose", "-v",
        action="store_true",
        help="Enable verbose logging",
    )
    args = parser.parse_args()

    setup_logging(args.verbose)

    # Load configuration
    config_path: Path = args.config
    if not config_path.exists():
        logger.error("Configuration file not found: %s", config_path)
        sys.exit(1)

    config = load_config(config_path)
    logger.info("Loaded configuration for device: %s", config.device_name)

    # Initialize Firebase
    db = init_firebase(config)
    logger.info("Firebase initialized")

    # Create Firestore client
    client = FirestoreClient(
        db=db,
        device_id=config.device_id,
        device_name=config.device_name,
        family_id=config.family_id,
        user_id=config.user_id,
    )

    # Run monitoring loop
    logger.info("Starting monitoring loop...")
    run_monitoring_loop(
        client=client,
        poll_interval_seconds=config.poll_interval_seconds,
    )


if __name__ == "__main__":
    main()
