#!/usr/bin/env python3
"""Expose the running MapTool desktop bridge to Codex over MCP stdio.

Python 3.10+; standard library only. Run this file directly, with the same
MAPTOOL_MCP_TOKEN used by MapTool in the environment. stdout is reserved for
newline-delimited JSON-RPC; configuration errors are written to stderr.

This is a stdio MCP server. Its private, authenticated loopback HTTP connection
to MapTool is an implementation detail, not an MCP Streamable HTTP endpoint.
"""

from __future__ import annotations

import argparse
import http.client
import json
import math
import os
import re
import socket
import sys
from dataclasses import dataclass
from typing import Any, BinaryIO, Mapping
from urllib.parse import urlsplit


SUPPORTED_VERSIONS = ("2025-11-25", "2025-06-18", "2024-11-05")
DEFAULT_URL = "http://127.0.0.1:27182/mcp-bridge"
MAX_MESSAGE_BYTES = 1024 * 1024
MAX_RESPONSE_BYTES = 4 * 1024 * 1024
BRIDGE_TIMEOUT_SECONDS = 10


class ConfigurationError(Exception):
    """An invalid local configuration, without its sensitive value."""


class ProtocolError(Exception):
    def __init__(self, code: int, message: str):
        super().__init__(message)
        self.code = code


class BridgeError(Exception):
    """An actionable, safe description of a MapTool connection failure."""


def strict_json(data: bytes) -> Any:
    def invalid_constant(value: str) -> None:
        raise ValueError("Non-finite JSON number")

    def unique_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("Duplicate JSON key")
            result[key] = value
        return result

    def finite_float(value: str) -> float:
        result = float(value)
        if not math.isfinite(result):
            raise ValueError("Non-finite JSON number")
        return result

    return json.loads(
        data.decode("utf-8"),
        parse_constant=invalid_constant,
        parse_float=finite_float,
        object_pairs_hook=unique_keys,
    )


@dataclass(frozen=True)
class Configuration:
    host: str
    port: int
    token: str

    @classmethod
    def from_environment(cls, environ: Mapping[str, str]) -> Configuration:
        token = environ.get("MAPTOOL_MCP_TOKEN", "")
        if not 32 <= len(token) <= 512 or not re.fullmatch(r"[A-Za-z0-9._~+/\-]+=*", token):
            raise ConfigurationError(
                "Set MAPTOOL_MCP_TOKEN to the same secret as MapTool: at least "
                "32 and at most 512 characters, using letters, numbers or bearer-token punctuation."
            )
        url = environ.get("MAPTOOL_MCP_URL", DEFAULT_URL)
        try:
            if any(char.isspace() or ord(char) < 32 for char in url):
                raise ValueError("Whitespace in URL")
            parsed = urlsplit(url)
            host = parsed.hostname or ""
            port = parsed.port or 80
            if (
                parsed.scheme != "http"
                or host != "127.0.0.1"
                or parsed.username is not None
                or parsed.password is not None
                or parsed.query
                or parsed.fragment
                or parsed.path not in ("", "/", "/mcp-bridge")
                or not 1 <= port <= 65535
                or parsed.port == 0
            ):
                raise ValueError("Invalid bridge URL")
        except (ValueError, TypeError):
            raise ConfigurationError(
                "MAPTOOL_MCP_URL must use http with a literal loopback address "
                "(127.0.0.1), a valid port, and /mcp-bridge; "
                "credentials, query strings and fragments are not allowed."
            ) from None
        return cls(host, port, token)


class MapToolBridge:
    def __init__(self, configuration: Configuration):
        self.configuration = configuration

    def request(self, method: str, params: dict[str, Any] | None = None) -> dict[str, Any]:
        payload: dict[str, Any] = {"method": method}
        if params is not None:
            payload["params"] = params
        body = json.dumps(payload, ensure_ascii=True, allow_nan=False).encode("utf-8")
        if len(body) > MAX_MESSAGE_BYTES:
            raise ProtocolError(-32602, "Tool request exceeds the 1 MiB size limit.")
        # HTTPConnection connects directly: environment proxies and redirects
        # cannot move the bearer credential away from the configured loopback IP.
        connection = http.client.HTTPConnection(
            self.configuration.host,
            self.configuration.port,
            timeout=BRIDGE_TIMEOUT_SECONDS,
        )
        try:
            connection.request(
                "POST",
                "/mcp-bridge",
                body=body,
                headers={
                    "Authorization": "Bearer " + self.configuration.token,
                    "Content-Type": "application/json; charset=utf-8",
                    "Accept": "application/json",
                    "Connection": "close",
                },
            )
            response = connection.getresponse()
            if 300 <= response.status < 400:
                raise BridgeError("MapTool returned a redirect; redirects are disabled. Check MAPTOOL_MCP_URL.")
            if response.status in (401, 403):
                raise BridgeError(
                    "MapTool denied access. Check that MAPTOOL_MCP_TOKEN matches "
                    "the secret configured in the running MapTool instance."
                )
            length = response.getheader("Content-Length")
            if length is not None and (not length.isdecimal() or len(length) > 9 or int(length) > MAX_RESPONSE_BYTES):
                raise BridgeError("MapTool returned an invalid or oversized response (maximum 4 MiB).")
            raw = response.read(MAX_RESPONSE_BYTES + 1)
            if len(raw) > MAX_RESPONSE_BYTES:
                raise BridgeError("MapTool response exceeds the 4 MiB size limit; request a smaller result.")
            try:
                result = strict_json(raw)
            except (ValueError, UnicodeError, RecursionError):
                raise BridgeError("MapTool returned invalid JSON. Check the MapTool bridge version.") from None
            if not isinstance(result, dict):
                raise BridgeError("MapTool returned an invalid response. Check the MapTool bridge version.")
            if "error" in result:
                error = result["error"]
                code = error.get("code") if isinstance(error, dict) else None
                if code == "UNKNOWN_TOOL":
                    raise ProtocolError(-32602, "Unknown MapTool tool. Use tools/list to discover available tools.")
                if code == "TIMEOUT":
                    raise BridgeError(
                        "MapTool timed out while processing the request. Inspect the map "
                        "before retrying: the action may already have been applied."
                    )
                if code in ("INTERNAL_ERROR", "UNAVAILABLE"):
                    raise BridgeError(
                        "MapTool could not complete the request. Check the campaign and "
                        "bridge status, then inspect the map before retrying: the action "
                        "may already have been partially applied."
                    )
                if type(code) is int and code in (-32600, -32601, -32602):
                    message = error.get("message")
                    safe_message = (
                        message.replace(self.configuration.token, "[REDACTED]")[:500]
                        if isinstance(message, str)
                        else "MapTool rejected the request."
                    )
                    raise ProtocolError(code, safe_message)
                raise BridgeError(
                    "MapTool could not process the request. Check the campaign and bridge "
                    "status and inspect the map before retrying a mutation."
                )
            if not 200 <= response.status < 300:
                raise BridgeError(f"MapTool returned HTTP {response.status}. Check the campaign and bridge status.")
            return result
        except (TimeoutError, socket.timeout):
            raise BridgeError(
                "MapTool did not respond within the connection timeout. Inspect the map "
                "before retrying: the previous action may already have been applied."
            ) from None
        except (OSError, http.client.HTTPException):
            raise BridgeError(
                "Cannot reach the MapTool bridge. Start MapTool with MCP enabled and "
                "check MAPTOOL_MCP_URL. Inspect the map before retrying a mutation."
            ) from None
        finally:
            connection.close()


class MCPServer:
    def __init__(self, bridge: MapToolBridge):
        self.bridge = bridge
        self.protocol_version: str | None = None
        self.ready = False

    def handle(self, message: Any) -> dict[str, Any] | None:
        request_id = message.get("id") if isinstance(message, dict) else None
        valid_id = type(request_id) in (str, int)
        try:
            if not isinstance(message, dict) or message.get("jsonrpc") != "2.0":
                raise ProtocolError(-32600, "Expected a JSON-RPC 2.0 message object.")
            # The server makes no requests of the client. Ignore client responses
            # rather than responding to a response and creating a protocol loop.
            if "method" not in message and ("result" in message or "error" in message):
                return None
            method = message.get("method")
            if not isinstance(method, str) or not method or ("id" in message and not valid_id):
                raise ProtocolError(-32600, "Requests require a method and a string or integer id.")
            if "result" in message or "error" in message:
                raise ProtocolError(-32600, "Requests cannot contain result or error fields.")
            if "id" not in message:
                if method == "notifications/initialized" and self.protocol_version is not None:
                    self.ready = True
                # Tool invocations sent as notifications must never change maps.
                return None
            params = message.get("params", {})
            if not isinstance(params, dict):
                raise ProtocolError(-32602, "params must be an object.")
            if method == "ping":
                result: dict[str, Any] = {}
            elif method == "initialize":
                result = self.initialize(params)
            else:
                if not self.ready:
                    raise ProtocolError(-32002, "Complete initialize and notifications/initialized first.")
                if method == "tools/list":
                    result = self.list_tools(params)
                elif method == "tools/call":
                    result = self.call_tool(params)
                else:
                    raise ProtocolError(-32601, "Method not supported.")
            return {"jsonrpc": "2.0", "id": request_id, "result": result}
        except ProtocolError as error:
            return self.error(request_id if valid_id else None, error.code, str(error))
        except BridgeError as error:
            return self.error(request_id, -32000, str(error))

    @staticmethod
    def error(request_id: str | int | None, code: int, message: str) -> dict[str, Any]:
        return {"jsonrpc": "2.0", "id": request_id, "error": {"code": code, "message": message}}

    def initialize(self, params: dict[str, Any]) -> dict[str, Any]:
        if self.protocol_version is not None:
            raise ProtocolError(-32600, "This connection has already been initialized.")
        requested = params.get("protocolVersion")
        client = params.get("clientInfo")
        if (
            not isinstance(requested, str)
            or not requested
            or not isinstance(params.get("capabilities"), dict)
            or not isinstance(client, dict)
            or not isinstance(client.get("name"), str)
            or not client["name"]
            or not isinstance(client.get("version"), str)
            or not client["version"]
        ):
            raise ProtocolError(-32602, "initialize requires protocolVersion, capabilities and clientInfo (name, version).")
        self.protocol_version = requested if requested in SUPPORTED_VERSIONS else SUPPORTED_VERSIONS[0]
        return {
            "protocolVersion": self.protocol_version,
            "capabilities": {"tools": {}},
            "serverInfo": {"name": "maptool-mcp", "version": "0.1.0"},
            "instructions": (
                "Control the current MapTool desktop session. Inspect the current "
                "campaign and tool schemas before changes; respect player ownership "
                "and GM permissions. Coordinates use each tool's documented units. "
                "After a timeout, inspect state before retrying a mutation."
            ),
        }

    def list_tools(self, params: dict[str, Any]) -> dict[str, Any]:
        if "cursor" in params:
            raise ProtocolError(-32602, "This bridge returns all tools in one page; omit cursor.")
        result = self.bridge.request("tools/list")
        tools = result.get("tools")
        if not isinstance(tools, list):
            raise BridgeError("MapTool returned an invalid tool catalog.")
        names: set[str] = set()
        for tool in tools:
            if (
                not isinstance(tool, dict)
                or not isinstance(tool.get("name"), str)
                or not tool["name"]
                or tool["name"] in names
                or not isinstance(tool.get("inputSchema"), dict)
                or tool["inputSchema"].get("type") != "object"
            ):
                raise BridgeError("MapTool returned an invalid tool catalog.")
            names.add(tool["name"])
            # Tasks are not implemented by this proxy, regardless of bridge data.
            tool.pop("execution", None)
            if self.protocol_version == "2024-11-05":
                for key in ("outputSchema", "annotations", "title", "icons", "_meta"):
                    tool.pop(key, None)
            elif self.protocol_version == "2025-06-18":
                tool.pop("icons", None)
        return {"tools": tools}

    def call_tool(self, params: dict[str, Any]) -> dict[str, Any]:
        name = params.get("name")
        arguments = params.get("arguments", {})
        if not isinstance(name, str) or not name or not isinstance(arguments, dict):
            raise ProtocolError(-32602, "tools/call requires a tool name and an arguments object.")
        if "task" in params:
            raise ProtocolError(-32602, "Task-augmented tool calls are not supported.")
        try:
            result = self.bridge.request("tools/call", {"name": name, "arguments": arguments})
            content = result.get("content")
            if (
                not isinstance(content, list)
                or any(not isinstance(item, dict) or item.get("type") != "text" or not isinstance(item.get("text"), str) for item in content)
                or ("isError" in result and type(result["isError"]) is not bool)
                or ("structuredContent" in result and not isinstance(result["structuredContent"], dict))
            ):
                raise BridgeError("MapTool returned an invalid tool result. Inspect state before retrying.")
            if self.protocol_version == "2024-11-05":
                result.pop("structuredContent", None)
            return result
        except BridgeError as error:
            return {"content": [{"type": "text", "text": str(error)}], "isError": True}


def run(server: MCPServer, source: BinaryIO, destination: BinaryIO) -> None:
    while True:
        line = source.readline(MAX_MESSAGE_BYTES + 1)
        if not line:
            return
        if len(line) > MAX_MESSAGE_BYTES:
            while line and not line.endswith(b"\n"):
                line = source.readline(MAX_MESSAGE_BYTES + 1)
            response = server.error(None, -32700, "Message exceeds the 1 MiB size limit.")
        else:
            try:
                response = server.handle(strict_json(line))
            except (ValueError, UnicodeError, RecursionError):
                response = server.error(None, -32700, "Invalid UTF-8 JSON message.")
            except Exception:
                # Never write tracebacks or request bodies containing credentials.
                response = server.error(None, -32603, "Internal server error.")
        if response is not None:
            # ASCII escapes are valid UTF-8 JSON and also preserve unusual JSON
            # string IDs (including escaped lone surrogates) without crashing.
            encoded = json.dumps(response, ensure_ascii=True, allow_nan=False, separators=(",", ":"))
            encoded = encoded.replace(server.bridge.configuration.token, "[REDACTED]")
            destination.write(encoded.encode("utf-8") + b"\n")
            destination.flush()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.parse_args()
    try:
        configuration = Configuration.from_environment(os.environ)
    except ConfigurationError as error:
        print(f"maptool-mcp: {error}", file=sys.stderr)
        return 2
    try:
        run(MCPServer(MapToolBridge(configuration)), sys.stdin.buffer, sys.stdout.buffer)
    except (BrokenPipeError, KeyboardInterrupt):
        return 0
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
