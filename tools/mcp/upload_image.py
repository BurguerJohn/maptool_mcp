#!/usr/bin/env python3
"""Import a local PNG/JPEG through MapTool's authenticated loopback bridge.

The image stays out of the agent's text context. Credentials come from the same
environment as maptool_mcp.py. This helper does not generate or transform images.
"""

from __future__ import annotations

import argparse
import base64
import json
import os
from pathlib import Path
import sys
from typing import Any

from maptool_mcp import (
    BridgeError,
    Configuration,
    ConfigurationError,
    MapToolBridge,
    ProtocolError,
)


CHUNK_BYTES = 600 * 1024
MAX_IMAGE_BYTES = 8 * 1024 * 1024


def import_image(
    bridge: MapToolBridge,
    path: Path,
    *,
    name: str,
    category: str,
    source: str,
) -> dict[str, Any]:
    """Import directly or in bounded chunks. Never retry uncertain mutations."""
    if not name or len(name) > 128:
        raise ValueError("Image name must contain 1 to 128 characters.")
    if category not in ("npc", "scenery", "misc"):
        raise ValueError("Image category must be npc, scenery or misc.")
    if source not in ("generated", "existing"):
        raise ValueError("Image source must be generated or existing.")
    with path.open("rb") as stream:
        data = stream.read(MAX_IMAGE_BYTES + 1)
    if len(data) > MAX_IMAGE_BYTES:
        raise ValueError(
            "Image exceeds 8 MiB. Export a smaller PNG/JPEG before importing; "
            "this helper does not resize or compress the original."
        )
    if not (data.startswith(b"\x89PNG\r\n\x1a\n") or data.startswith(b"\xff\xd8\xff")):
        raise ValueError("Image must be a PNG or JPEG file.")
    metadata = {"name": name, "category": category, "source": source}

    def call(arguments: dict[str, Any]) -> dict[str, Any]:
        return bridge.request(
            "tools/call", {"name": "maptool_image_upload", "arguments": arguments}
        )

    if len(data) <= CHUNK_BYTES:
        return bridge.request(
            "tools/call",
            {
                "name": "maptool_import_image",
                "arguments": {
                    **metadata,
                    "dataBase64": base64.b64encode(data).decode("ascii"),
                },
            },
        )

    started = call({"action": "begin", **metadata, "totalBytes": len(data)})
    if started.get("isError"):
        return started
    receipt = started.get("structuredContent")
    if not isinstance(receipt, dict) or not isinstance(receipt.get("uploadId"), str):
        raise BridgeError("MapTool returned an invalid upload receipt. Check pending uploads before retrying.")
    upload_id = receipt["uploadId"]
    try:
        for index, offset in enumerate(range(0, len(data), CHUNK_BYTES)):
            appended = call(
                {
                    "action": "append",
                    "uploadId": upload_id,
                    "index": index,
                    "dataBase64": base64.b64encode(data[offset : offset + CHUNK_BYTES]).decode("ascii"),
                }
            )
            if appended.get("isError"):
                return {**appended, "uploadId": upload_id}
        return {**call({"action": "finish", "uploadId": upload_id}), "uploadId": upload_id}
    except (BridgeError, ProtocolError) as error:
        raise BridgeError(
            f"{error} Upload ID: {upload_id}. Inspect maptool_image_upload with "
            "action=status and that uploadId before repeating an import."
        ) from None


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("path", type=Path, help="Local PNG/JPEG file to import")
    parser.add_argument("--name", required=True, help="Asset name in MapTool")
    parser.add_argument("--category", required=True, choices=("npc", "scenery", "misc"))
    parser.add_argument("--source", required=True, choices=("generated", "existing"))
    args = parser.parse_args()
    secret = os.environ.get("MAPTOOL_MCP_TOKEN", "")
    try:
        bridge = MapToolBridge(Configuration.from_environment(os.environ))
        result = import_image(
            bridge, args.path, name=args.name, category=args.category, source=args.source
        )
        output = json.dumps(result, ensure_ascii=True, allow_nan=False)
        print(output.replace(secret, "[REDACTED]") if secret else output)
        return 1 if result.get("isError") else 0
    except (ConfigurationError, BridgeError, ProtocolError, ValueError, OSError) as error:
        message = str(error)
        if secret:
            message = message.replace(secret, "[REDACTED]")
        print(message[:1000], file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
