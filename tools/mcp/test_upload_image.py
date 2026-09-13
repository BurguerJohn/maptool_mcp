from __future__ import annotations

import base64
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock

from maptool_mcp import BridgeError
from upload_image import CHUNK_BYTES, MAX_IMAGE_BYTES, import_image


PNG = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j2ioAAAAASUVORK5CYII="
)


class UploadImageTests(unittest.TestCase):
    def test_preserves_image_bytes_and_generation_provenance(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "npc.png"
            path.write_bytes(PNG)
            bridge = Mock()
            bridge.request.return_value = {"imageAssetId": "f" * 32}
            result = import_image(
                bridge, path, name="Guarda", category="npc", source="generated"
            )
        self.assertEqual(result, {"imageAssetId": "f" * 32})
        bridge.request.assert_called_once()
        method, params = bridge.request.call_args.args
        self.assertEqual(method, "tools/call")
        self.assertEqual(params["name"], "maptool_import_image")
        self.assertEqual(params["arguments"]["category"], "npc")
        self.assertEqual(params["arguments"]["source"], "generated")
        self.assertEqual(base64.b64decode(params["arguments"]["dataBase64"]), PNG)

    def test_oversized_and_nonimage_files_never_reach_bridge(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "image.png"
            bridge = Mock()
            for data in (PNG + b"x" * MAX_IMAGE_BYTES, b"not an image"):
                with self.subTest(size=len(data)):
                    path.write_bytes(data)
                    with self.assertRaises(ValueError):
                        import_image(
                            bridge, path, name="Casa", category="scenery", source="existing"
                        )
            bridge.request.assert_not_called()

    def test_uncertain_import_is_not_retried(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "object.png"
            path.write_bytes(PNG)
            bridge = Mock()
            bridge.request.side_effect = BridgeError("Timeout; inspect state before retrying")
            with self.assertRaises(BridgeError):
                import_image(bridge, path, name="Bau", category="misc", source="existing")
            bridge.request.assert_called_once()

    def test_chunked_upload_preserves_all_bytes_in_order(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "city.png"
            original = PNG + b"x" * CHUNK_BYTES
            path.write_bytes(original)
            bridge = Mock()
            bridge.request.side_effect = [
                {"structuredContent": {"uploadId": "upload-123", "nextIndex": 0}},
                {"structuredContent": {"nextIndex": 1}},
                {"structuredContent": {"nextIndex": 2}},
                {"structuredContent": {"imageAssetId": "a" * 32}},
            ]
            result = import_image(
                bridge, path, name="Cidade", category="scenery", source="generated"
            )
        calls = [entry.args[1]["arguments"] for entry in bridge.request.call_args_list]
        self.assertEqual([call["action"] for call in calls], ["begin", "append", "append", "finish"])
        self.assertEqual(calls[0]["totalBytes"], len(original))
        self.assertEqual([call["index"] for call in calls[1:3]], [0, 1])
        self.assertTrue(all(call["uploadId"] == "upload-123" for call in calls[1:]))
        combined = b"".join(base64.b64decode(call["dataBase64"]) for call in calls[1:3])
        self.assertEqual(combined, original)
        self.assertEqual(result["structuredContent"]["imageAssetId"], "a" * 32)

    def test_uncertain_chunk_reports_upload_without_retry_or_finish(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "city.png"
            path.write_bytes(PNG + b"x" * CHUNK_BYTES)
            bridge = Mock()
            bridge.request.side_effect = [
                {"structuredContent": {"uploadId": "upload-123", "nextIndex": 0}},
                BridgeError("Connection timed out"),
            ]
            with self.assertRaisesRegex(BridgeError, "Upload ID: upload-123"):
                import_image(
                    bridge, path, name="Cidade", category="scenery", source="generated"
                )
            self.assertEqual(bridge.request.call_count, 2)


if __name__ == "__main__":
    unittest.main()
