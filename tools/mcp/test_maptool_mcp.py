"""Fresh-process protocol and security tests; no MapTool GUI or dependencies.

Run: python3 -m unittest discover -s tools/mcp -p 'test_*.py' -v
"""

from __future__ import annotations

import contextlib
import copy
import http.server
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import threading
import time
import unittest

import maptool_mcp


SCRIPT = Path(__file__).with_name("maptool_mcp.py")
SECRET = "test-only-maptool-secret-" + "a" * 40
TOOL = {
    "name": "maptool_get_state",
    "title": "Get current campaign",
    "description": "Inspect the campaign.",
    "inputSchema": {"type": "object", "properties": {}},
    "outputSchema": {"type": "object"},
    "annotations": {"readOnlyHint": True},
    "icons": [],
    "execution": {"taskSupport": "optional"},
}
DOMAIN_RESULT = {"mapId": "map-1", "name": "Salão\nDragão", "doors": []}
CALL_RESULT = {
    "content": [{"type": "text", "text": json.dumps(DOMAIN_RESULT, ensure_ascii=False)}],
    "structuredContent": DOMAIN_RESULT,
    "isError": False,
}


def rpc(request_id, method, params=None):
    request = {"jsonrpc": "2.0", "id": request_id, "method": method}
    if params is not None:
        request["params"] = params
    return request


def handshake(version="2025-11-25"):
    return [
        rpc(1, "initialize", {
            "protocolVersion": version,
            "capabilities": {},
            "clientInfo": {"name": "subprocess-test", "version": "1.0"},
        }),
        {"jsonrpc": "2.0", "method": "notifications/initialized"},
    ]


def standard_reply(request):
    if request["method"] == "tools/list":
        return 200, {"tools": [copy.deepcopy(TOOL)]}, {}
    return 200, copy.deepcopy(CALL_RESULT), {}


@contextlib.contextmanager
def fake_bridge(reply=standard_reply):
    requests = []

    class Handler(http.server.BaseHTTPRequestHandler):
        def do_POST(self):
            raw = self.rfile.read(int(self.headers["Content-Length"]))
            request = json.loads(raw)
            requests.append({"path": self.path, "headers": self.headers, "body": request})
            status, payload, headers = reply(request)
            raw_reply = payload if isinstance(payload, bytes) else json.dumps(payload).encode("utf-8")
            self.send_response(status)
            if "Content-Length" not in headers:
                self.send_header("Content-Length", str(len(raw_reply)))
            self.send_header("Content-Type", "application/json")
            for key, value in headers.items():
                self.send_header(key, value)
            self.end_headers()
            try:
                self.wfile.write(raw_reply)
            except (BrokenPipeError, ConnectionResetError):
                pass

        def log_message(self, *args):
            pass

    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    server.daemon_threads = True
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield f"http://127.0.0.1:{server.server_port}/mcp-bridge", requests
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)


class MCPIntegrationTests(unittest.TestCase):
    def run_server(self, messages, url=None, env=None, timeout_override=None):
        environ = {key: value for key, value in os.environ.items() if not key.startswith("MAPTOOL_MCP_")}
        environ["MAPTOOL_MCP_TOKEN"] = SECRET
        if url is not None:
            environ["MAPTOOL_MCP_URL"] = url
        if env:
            for key, value in env.items():
                if value is None:
                    environ.pop(key, None)
                else:
                    environ[key] = value
        payload = messages if isinstance(messages, bytes) else b"".join(
            json.dumps(message, ensure_ascii=False).encode("utf-8") + b"\n" for message in messages
        )
        command = [sys.executable, str(SCRIPT)]
        if timeout_override is not None:
            # Still run the real main() in a new process; shorten only the timeout
            # so this transport failure test does not wait the production 10s.
            command = [sys.executable, "-c", (
                "import sys; sys.path.insert(0, sys.argv[1]); "
                "import maptool_mcp; maptool_mcp.BRIDGE_TIMEOUT_SECONDS=float(sys.argv[2]); "
                "sys.argv=sys.argv[:1]; "
                "raise SystemExit(maptool_mcp.main())"
            ), str(SCRIPT.parent), str(timeout_override)]
        completed = subprocess.run(command, input=payload, capture_output=True, env=environ, timeout=15)
        self.assertNotIn(SECRET.encode(), completed.stdout)
        self.assertNotIn(SECRET.encode(), completed.stderr)
        responses = [json.loads(line) for line in completed.stdout.splitlines()]
        return completed, responses

    def assert_clean(self, completed):
        self.assertEqual(completed.returncode, 0, completed.stderr.decode())
        self.assertEqual(completed.stderr, b"")

    def test_complete_exchange_authentication_utf8_and_no_stdout_noise(self):
        with fake_bridge() as (url, requests):
            completed, responses = self.run_server(handshake() + [
                rpc("list", "tools/list"),
                rpc("call", "tools/call", {"name": TOOL["name"], "arguments": {"label": "Portão"}}),
                rpc("ping", "ping"),
            ], url)
        self.assert_clean(completed)
        self.assertEqual([item["id"] for item in responses], [1, "list", "call", "ping"])
        self.assertEqual(responses[0]["result"]["capabilities"], {"tools": {}})
        self.assertEqual(responses[2]["result"], CALL_RESULT)
        self.assertEqual(responses[3]["result"], {})
        self.assertNotIn("execution", responses[1]["result"]["tools"][0])
        self.assertEqual(len(requests), 2)
        for request in requests:
            self.assertEqual(request["path"], "/mcp-bridge")
            self.assertEqual(request["headers"]["Authorization"], "Bearer " + SECRET)
            self.assertIn("application/json", request["headers"]["Content-Type"])
        self.assertEqual(requests[0]["body"], {"method": "tools/list"})
        self.assertEqual(requests[1]["body"], {
            "method": "tools/call", "params": {"name": TOOL["name"], "arguments": {"label": "Portão"}},
        })

    def test_supported_protocol_versions_and_unknown_version_negotiation(self):
        with fake_bridge() as (url, _):
            for version in (*maptool_mcp.SUPPORTED_VERSIONS, "2099-01-01"):
                with self.subTest(version=version):
                    completed, responses = self.run_server(handshake(version) + [
                        rpc(2, "tools/list"), rpc(3, "tools/call", {"name": TOOL["name"]}),
                    ], url)
                    self.assert_clean(completed)
                    expected = version if version in maptool_mcp.SUPPORTED_VERSIONS else "2025-11-25"
                    self.assertEqual(responses[0]["result"]["protocolVersion"], expected)
                    tool = responses[1]["result"]["tools"][0]
                    if version == "2024-11-05":
                        self.assertEqual(set(tool), {"name", "description", "inputSchema"})
                        self.assertNotIn("structuredContent", responses[2]["result"])
                        self.assertEqual(json.loads(responses[2]["result"]["content"][0]["text"]), DOMAIN_RESULT)
                    else:
                        self.assertIn("structuredContent", responses[2]["result"])

    def test_notifications_do_not_trigger_mutations_or_responses(self):
        with fake_bridge() as (url, requests):
            completed, responses = self.run_server([
                {"jsonrpc": "2.0", "method": "notifications/initialized"},
                rpc(0, "tools/list"),
                *handshake(),
                {"jsonrpc": "2.0", "method": "tools/call", "params": {"name": TOOL["name"]}},
                {"jsonrpc": "2.0", "method": "notifications/cancelled", "params": {"requestId": "done"}},
                {"jsonrpc": "2.0", "method": "notifications/unknown"},
                {"jsonrpc": "2.0", "id": "reply", "result": {}},
                rpc(2, "ping"),
            ], url)
        self.assert_clean(completed)
        self.assertEqual(requests, [])
        self.assertEqual([item["id"] for item in responses], [0, 1, 2])
        self.assertEqual(responses[0]["error"]["code"], -32002)

    def test_initialization_validation_and_ready_notification_required(self):
        completed, responses = self.run_server([
            rpc(0, "ping"),
            rpc(1, "initialize", {"protocolVersion": "2025-11-25"}),
            handshake()[0],
            rpc(3, "tools/list"),
            handshake()[1],
            rpc(4, "initialize", handshake()[0]["params"]),
        ])
        self.assert_clean(completed)
        self.assertEqual(responses[0]["result"], {})
        self.assertEqual(responses[1]["error"]["code"], -32602)
        self.assertIn("result", responses[2])
        self.assertEqual(responses[3]["error"]["code"], -32002)
        self.assertEqual(responses[4]["error"]["code"], -32600)

    def test_malformed_envelopes_and_unsupported_capabilities_never_reach_bridge(self):
        malformed = [
            [], {"jsonrpc": "1.0", "id": 2, "method": "ping"},
            rpc(None, "ping"), rpc(True, "ping"), rpc(2.5, "ping"),
            rpc(3, "tools/call", []), rpc(4, "tools/call", {"name": "x", "arguments": []}),
            rpc(5, "tools/call", {"name": "x", "task": {}}), rpc(6, "tools/call", {}),
            rpc(7, "tools/list", {"cursor": "bad"}),
            rpc(8, "resources/list"), rpc(9, "prompts/list"), rpc(10, "logging/setLevel"),
        ]
        with fake_bridge() as (url, requests):
            completed, responses = self.run_server(handshake() + malformed + [rpc(11, "ping")], url)
        self.assert_clean(completed)
        self.assertEqual(requests, [])
        self.assertTrue(all("error" in response for response in responses[1:-1]))
        self.assertEqual(responses[-1]["result"], {})
        self.assertTrue(all(response["error"]["code"] == -32601 for response in responses[-4:-1]))

    def test_bad_json_utf8_duplicate_keys_nonfinite_numbers_and_size_recover(self):
        invalid_lines = [
            b"{broken}\n", b"\xff\n", b'{"jsonrpc":"2.0","jsonrpc":"2.0"}\n',
            b'{"jsonrpc":"2.0","id":1,"method":"ping","params":{"x":NaN}}\n',
            b'{"jsonrpc":"2.0","id":1,"method":"ping","params":{"x":1e999}}\n',
            b"x" * (maptool_mcp.MAX_MESSAGE_BYTES + 10) + b"\n",
        ]
        payload = b"".join(invalid_lines) + json.dumps(rpc("alive", "ping")).encode() + b"\n"
        completed, responses = self.run_server(payload)
        self.assert_clean(completed)
        self.assertEqual(len(responses), len(invalid_lines) + 1)
        self.assertTrue(all(item["error"]["code"] == -32700 for item in responses[:-1]))
        self.assertEqual(responses[-1]["id"], "alive")

    def test_escaped_surrogate_ids_and_arguments_do_not_crash_the_transport(self):
        messages = handshake() + [
            rpc("\ud800", "ping"),
            rpc("unicode", "tools/call", {"name": TOOL["name"], "arguments": {"label": "\ud800"}}),
            rpc("alive", "ping"),
        ]
        payload = b"".join(json.dumps(message, ensure_ascii=True).encode() + b"\n" for message in messages)
        with fake_bridge() as (url, requests):
            completed, responses = self.run_server(payload, url)
        self.assert_clean(completed)
        self.assertEqual(responses[1]["id"], "\ud800")
        self.assertEqual(responses[2]["result"], CALL_RESULT)
        self.assertEqual(responses[3]["id"], "alive")
        self.assertEqual(requests[0]["body"]["params"]["arguments"]["label"], "\ud800")

    def test_bridge_failures_are_tool_errors_but_listing_failure_is_rpc_error(self):
        failures = [
            (401, {"error": {"message": SECRET}}, {}),
            (403, {"error": {"message": SECRET}}, {}),
            (500, {"error": {"message": SECRET}}, {}),
            (200, b"not JSON " + SECRET.encode(), {}),
            (200, {"content": "wrong", "debug": SECRET}, {}),
            (200, {}, {"Content-Length": str(maptool_mcp.MAX_RESPONSE_BYTES + 1)}),
            (200, {"content": [], "isError": "yes"}, {}),
        ]
        for failure in failures:
            with self.subTest(status=failure[0], payload=str(failure[1])[:30]):
                with fake_bridge(lambda request: failure) as (url, requests):
                    completed, responses = self.run_server(handshake() + [
                        rpc(2, "tools/call", {"name": TOOL["name"]}),
                        rpc(3, "tools/list"), rpc(4, "ping"),
                    ], url)
                self.assert_clean(completed)
                self.assertTrue(responses[1]["result"]["isError"])
                self.assertEqual(responses[2]["error"]["code"], -32000)
                self.assertEqual(responses[3]["result"], {})
                self.assertEqual(len(requests), 2)

    def test_unknown_tool_is_protocol_error_and_domain_error_is_preserved(self):
        def reply(request):
            if request["params"]["name"] == "unknown":
                return 400, {"error": {"code": "UNKNOWN_TOOL", "message": SECRET}}, {}
            return 200, {"content": [{"type": "text", "text": "You do not own this token."}], "isError": True}, {}

        with fake_bridge(reply) as (url, _):
            completed, responses = self.run_server(handshake() + [
                rpc(2, "tools/call", {"name": "unknown"}),
                rpc(3, "tools/call", {"name": TOOL["name"]}),
            ], url)
        self.assert_clean(completed)
        self.assertEqual(responses[1]["error"]["code"], -32602)
        self.assertTrue(responses[2]["result"]["isError"])
        self.assertIn("do not own", responses[2]["result"]["content"][0]["text"])

    def test_bridge_action_failure_warns_about_possible_partial_mutations(self):
        for code in ("TIMEOUT", "INTERNAL_ERROR", "UNAVAILABLE"):
            with self.subTest(code=code):
                with fake_bridge(lambda request: (503, {"error": {"code": code, "message": SECRET}}, {})) as (url, requests):
                    completed, responses = self.run_server(handshake() + [
                        rpc(2, "tools/call", {"name": TOOL["name"]}),
                    ], url)
                self.assert_clean(completed)
                self.assertEqual(len(requests), 1)
                self.assertTrue(responses[1]["result"]["isError"])
                text = responses[1]["result"]["content"][0]["text"]
                self.assertIn("may already have been", text)
                self.assertIn("before retrying", text)

    def test_redirects_are_not_followed_and_proxies_are_ignored(self):
        with fake_bridge() as (target_url, redirected):
            with fake_bridge(lambda request: (302, {}, {"Location": target_url})) as (url, requests):
                completed, responses = self.run_server(handshake() + [
                    rpc(2, "tools/call", {"name": TOOL["name"]}),
                ], url, env={"HTTP_PROXY": target_url, "http_proxy": target_url, "ALL_PROXY": target_url, "NO_PROXY": ""})
        self.assert_clean(completed)
        self.assertTrue(responses[1]["result"]["isError"])
        self.assertIn("redirect", responses[1]["result"]["content"][0]["text"])
        self.assertEqual(redirected, [])
        self.assertEqual(len(requests), 1)

    def test_disconnection_and_timeout_do_not_retry_mutations(self):
        with socket.socket() as unused_port:
            unused_port.bind(("127.0.0.1", 0))
            url = f"http://127.0.0.1:{unused_port.getsockname()[1]}"
            completed, responses = self.run_server(handshake() + [
                rpc(2, "tools/call", {"name": TOOL["name"]}),
            ], url)
        self.assert_clean(completed)
        self.assertTrue(responses[1]["result"]["isError"])
        self.assertIn("Inspect the map", responses[1]["result"]["content"][0]["text"])

        def slow_reply(request):
            time.sleep(0.15)
            return standard_reply(request)

        with fake_bridge(slow_reply) as (url, requests):
            completed, responses = self.run_server(handshake() + [
                rpc(2, "tools/call", {"name": TOOL["name"]}), rpc(3, "ping"),
            ], url, timeout_override=0.05)
        self.assert_clean(completed)
        self.assertEqual(len(requests), 1)
        self.assertTrue(responses[1]["result"]["isError"])
        self.assertIn("may already have been applied", responses[1]["result"]["content"][0]["text"])
        self.assertEqual(responses[2]["result"], {})

    def test_success_results_cannot_echo_bearer_secret(self):
        def reply(request):
            return 200, {"content": [{"type": "text", "text": "redacted " + SECRET}]}, {}

        with fake_bridge(reply) as (url, _):
            completed, responses = self.run_server(handshake() + [rpc(2, "tools/call", {"name": TOOL["name"]})], url)
        self.assert_clean(completed)
        self.assertIn("[REDACTED]", responses[1]["result"]["content"][0]["text"])

    def test_error_truncation_cannot_leak_a_secret_prefix(self):
        def reply(request):
            return 400, {"error": {"code": -32602, "message": "x" * 490 + SECRET}}, {}

        with fake_bridge(reply) as (url, _):
            completed, responses = self.run_server(handshake() + [rpc(2, "tools/call", {"name": TOOL["name"]})], url)
        self.assert_clean(completed)
        message = responses[1]["error"]["message"]
        self.assertIn("[REDACTED]", message)
        self.assertNotIn(SECRET[:10], message)

    def test_invalid_configuration_fails_closed_without_echoing_values(self):
        for url in [
            "https://127.0.0.1:27182", "http://example.com", "http://localhost:27182",
            "http://192.168.1.2:27182", "http://0.0.0.0:27182", "http://[::]:27182",
            "http://[::1]:27182", "http://127.0.0.2:27182", "http://127.1:27182",
            "http://127.0.0.1:0", "http://127.0.0.1:65536", "http://127.0.0.1:abc",
            "http://127.0.0.1/other", "http://127.0.0.1?token=" + SECRET,
            "http://127.0.0.1#" + SECRET, "http://user:" + SECRET + "@127.0.0.1",
            " http://127.0.0.1", "http://[::1%25eth0]:27182",
        ]:
            with self.subTest(url=url):
                completed, responses = self.run_server([], url)
                self.assertEqual(completed.returncode, 2)
                self.assertEqual(responses, [])
                self.assertIn(b"MAPTOOL_MCP_URL", completed.stderr)
        for token in [None, "short", SECRET + "\n", "é" * 32, "a" * 513]:
            with self.subTest(token_length=len(token) if token else 0):
                completed, responses = self.run_server([], env={"MAPTOOL_MCP_TOKEN": token})
                self.assertEqual(completed.returncode, 2)
                self.assertEqual(responses, [])
                self.assertIn(b"MAPTOOL_MCP_TOKEN", completed.stderr)

    def test_root_url_normalization_and_default_configuration(self):
        with fake_bridge() as (url, requests):
            completed, responses = self.run_server(handshake() + [rpc(2, "tools/list")], url.removesuffix("/mcp-bridge"))
        self.assert_clean(completed)
        self.assertIn("tools", responses[1]["result"])
        self.assertEqual(requests[0]["path"], "/mcp-bridge")
        config = maptool_mcp.Configuration.from_environment({
            "MAPTOOL_MCP_TOKEN": SECRET,
        })
        self.assertEqual((config.host, config.port), ("127.0.0.1", 27182))


if __name__ == "__main__":
    unittest.main()
