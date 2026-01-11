"""Firestore serialization helpers.

Handles conversion between Python snake_case and Firestore camelCase.
"""

import re
from datetime import datetime
from typing import Any

from pydantic import BaseModel


def to_camel(string: str) -> str:
    """Convert snake_case to camelCase."""
    components = string.split("_")
    return components[0] + "".join(x.title() for x in components[1:])


def to_snake(string: str) -> str:
    """Convert camelCase to snake_case."""
    return re.sub(r"(?<!^)(?=[A-Z])", "_", string).lower()


def model_to_firestore(model: BaseModel) -> dict[str, Any]:
    """Convert a pydantic model to Firestore document format.

    - Converts field names from snake_case to camelCase
    - Converts datetime to Firestore-compatible format
    - Converts enums to their string values
    """
    data = model.model_dump(mode="python")
    return _convert_keys_to_camel(data)


def _convert_keys_to_camel(data: dict[str, Any]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in data.items():
        camel_key = to_camel(key)
        if isinstance(value, dict):
            result[camel_key] = _convert_keys_to_camel(value)
        elif isinstance(value, datetime):
            result[camel_key] = value
        else:
            result[camel_key] = value
    return result


def firestore_to_dict(data: dict[str, Any]) -> dict[str, Any]:
    """Convert Firestore document to snake_case dict for pydantic parsing."""
    return _convert_keys_to_snake(data)


def _convert_keys_to_snake(data: dict[str, Any]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in data.items():
        snake_key = to_snake(key)
        if isinstance(value, dict):
            result[snake_key] = _convert_keys_to_snake(value)
        else:
            result[snake_key] = value
    return result
