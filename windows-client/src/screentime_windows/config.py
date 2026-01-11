"""Configuration for the Windows client."""

from pathlib import Path

from pydantic import BaseModel


class Config(BaseModel):
    """Local configuration for this device."""

    device_id: str
    device_name: str
    family_id: str
    user_id: str
    firebase_credentials_path: Path
    poll_interval_seconds: int = 10


def load_config(path: Path) -> Config:
    """Load configuration from a JSON file."""
    return Config.model_validate_json(path.read_text())
