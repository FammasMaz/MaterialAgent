#!/usr/bin/env python3
"""
Bridge a loopback-only Hermes gateway to a device.

Why this exists
---------------
`hermes serve` binds 127.0.0.1 and validates the Host header: it accepts only a
loopback hostname or the single hostname in `dashboard.public_url`
(GHSA-ppp5-vxwm-4cf7, DNS-rebinding defence). So a phone or emulator cannot
reach it by IP address — the request arrives with the wrong Host and is answered
with `400 Invalid Host header` — and it cannot reach it by public hostname
either when that name only resolves inside the tailnet.

This is a tiny TCP relay that rewrites the Host header on the way through and
then splices the two sockets together verbatim. Because it never parses framing,
WebSocket upgrades, keep-alive, and chunked bodies all pass through untouched —
which matters, since the app's whole transport is a WebSocket.

    phone/emulator --> this bridge (any Host) --> ssh tunnel --> hermes serve

Usage
-----
    scripts/hermes-bridge.py --listen 0.0.0.0:19121 --upstream 127.0.0.1:19120 \
                             --rewrite-host 127.0.0.1:9119

Point the app at `http://<bridge-host>:19121`. `--upstream` is wherever the
gateway is actually reachable (a `ssh -L` tunnel, usually); `--rewrite-host` is
the hostname the gateway will accept.
"""

import argparse
import socket
import sys
import threading

HEAD_LIMIT = 64 * 1024
BUFFER = 65536


def read_head(sock):
    """Read up to the end of the request head; return (head, leftover body)."""
    data = b""
    while b"\r\n\r\n" not in data:
        try:
            chunk = sock.recv(BUFFER)
        except OSError:
            return None, b""
        if not chunk:
            return None, b""
        data += chunk
        if len(data) > HEAD_LIMIT:
            return None, b""
    head, _, rest = data.partition(b"\r\n\r\n")
    return head, rest


def rewrite_host(head, host):
    """Replace the Host header, inserting one if the client omitted it."""
    lines = head.split(b"\r\n")
    for i, line in enumerate(lines):
        if line.lower().startswith(b"host:"):
            lines[i] = b"Host: " + host.encode()
            return b"\r\n".join(lines)
    lines.insert(1, b"Host: " + host.encode())
    return b"\r\n".join(lines)


def splice(a, b):
    """Pump both directions until either side closes."""
    def pump(src, dst):
        try:
            while True:
                chunk = src.recv(BUFFER)
                if not chunk:
                    break
                dst.sendall(chunk)
        except OSError:
            pass
        finally:
            for s in (src, dst):
                try:
                    s.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass

    t = threading.Thread(target=pump, args=(a, b), daemon=True)
    t.start()
    pump(b, a)
    t.join(timeout=5)


def handle(client, upstream_host, upstream_port, rewrite_host_value):
    try:
        head, body = read_head(client)
        if head is None:
            return
        head = rewrite_host(head, rewrite_host_value)
        with socket.create_connection((upstream_host, upstream_port), timeout=15) as upstream:
            upstream.sendall(head + b"\r\n\r\n" + body)
            splice(client, upstream)
    except OSError as error:
        print(f"  connection failed: {error}", file=sys.stderr)
    finally:
        try:
            client.close()
        except OSError:
            pass


def parse_address(value, default_port):
    if ":" in value:
        host, _, port = value.rpartition(":")
        return host, int(port)
    return value, default_port


def main():
    parser = argparse.ArgumentParser(description="Host-rewriting TCP bridge for a loopback-only Hermes gateway")
    parser.add_argument("--listen", default="0.0.0.0:19121", help="address to accept on (default 0.0.0.0:19121)")
    parser.add_argument("--upstream", default="127.0.0.1:19120", help="where the gateway is actually reachable")
    parser.add_argument("--rewrite-host", default="127.0.0.1:9119", help="Host header the gateway will accept")
    args = parser.parse_args()

    listen_host, listen_port = parse_address(args.listen, 19121)
    upstream_host, upstream_port = parse_address(args.upstream, 19120)

    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind((listen_host, listen_port))
    server.listen(64)
    print(f"bridging {listen_host}:{listen_port} -> {upstream_host}:{upstream_port} (Host: {args.rewrite_host})", flush=True)

    while True:
        try:
            client, peer = server.accept()
        except KeyboardInterrupt:
            break
        print(f"  {peer[0]}:{peer[1]}", flush=True)
        threading.Thread(
            target=handle,
            args=(client, upstream_host, upstream_port, args.rewrite_host),
            daemon=True,
        ).start()


if __name__ == "__main__":
    main()
