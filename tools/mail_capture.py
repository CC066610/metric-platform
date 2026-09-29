#!/usr/bin/env python3
"""A minimal SMTP server that captures messages instead of delivering them.

This exists to verify the platform's alert delivery path without involving a
real mail provider: it speaks enough SMTP for Spring's JavaMailSender, writes
each accepted message to a file, and prints a one-line summary per message.

What it proves: the platform opened a connection, negotiated a session, and
emitted a well-formed message with the expected envelope, headers, and body.
What it does not prove: anything about the final hop to a mailbox, which belongs
to the mail provider and is outside this project.

Usage:
    python mail_capture.py                 # listen on 127.0.0.1:2525
    python mail_capture.py --port 1025 --out D:\PgTemp\mail
"""

from __future__ import annotations

import argparse
import socket
import sys
import threading
from datetime import datetime
from pathlib import Path

# Enough of the protocol for a client that sends and then quits. AUTH is
# advertised only if credentials are configured, which is the default here, so
# the client is not asked for a login it cannot provide.
GREETING = "220 mail-capture ESMTP ready\r\n"


class Session(threading.Thread):
    """One client connection, handled on its own thread."""

    def __init__(self, conn: socket.socket, peer: tuple[str, int], out_dir: Path) -> None:
        super().__init__(daemon=True)
        self.conn = conn
        self.peer = peer
        self.out_dir = out_dir
        self.sender = ""
        self.recipients: list[str] = []

    def run(self) -> None:
        try:
            self._serve()
        except (ConnectionError, OSError) as failure:
            print(f"  connection from {self.peer[0]} ended: {failure}", flush=True)
        finally:
            self.conn.close()

    def _serve(self) -> None:
        stream = self.conn.makefile("rwb")
        self._write(stream, GREETING)
        while True:
            line = stream.readline()
            if not line:
                return
            command = line.decode("utf-8", "replace").strip()
            upper = command.upper()

            if upper.startswith("EHLO"):
                self._write(stream, "250-mail-capture\r\n250-8BITMIME\r\n250-SMTPUTF8\r\n250 SIZE 10485760\r\n")
            elif upper.startswith("HELO"):
                self._write(stream, "250 mail-capture\r\n")
            elif upper.startswith(("MAIL FROM")):
                self.sender = _between(command, ":", "<", ">")
                self._write(stream, "250 OK\r\n")
            elif upper.startswith("RCPT TO"):
                self.recipients.append(_between(command, ":", "<", ">"))
                self._write(stream, "250 OK\r\n")
            elif upper.startswith("DATA"):
                self._write(stream, "354 End data with <CR><LF>.<CR><LF>\r\n")
                self._read_data(stream)
                self._write(stream, "250 OK queued\r\n")
            elif upper.startswith("RSET"):
                self.sender, self.recipients = "", []
                self._write(stream, "250 OK\r\n")
            elif upper.startswith("NOOP"):
                self._write(stream, "250 OK\r\n")
            elif upper.startswith("QUIT"):
                self._write(stream, "221 Bye\r\n")
                return
            else:
                self._write(stream, "250 OK\r\n")

    def _read_data(self, stream) -> None:
        raw = bytearray()
        while True:
            line = stream.readline()
            if not line:
                break
            if line.rstrip(b"\r\n") == b".":
                break
            # Undo dot-stuffing: a line beginning with a dot had it doubled.
            if line.startswith(b".."):
                line = line[1:]
            raw.extend(line)
        self._store(bytes(raw))

    def _store(self, raw: bytes) -> None:
        stamp = datetime.now().strftime("%Y%m%d-%H%M%S-%f")
        path = self.out_dir / f"{stamp}.eml"
        path.write_bytes(raw)

        text = raw.decode("utf-8", "replace")
        subject = _header(text, "Subject")
        to = _header(text, "To")
        from_ = _header(text, "From")
        print("", flush=True)
        print("=" * 72, flush=True)
        print(f"captured message -> {path.name}", flush=True)
        print(f"  envelope from : {self.sender}", flush=True)
        print(f"  envelope to   : {', '.join(self.recipients)}", flush=True)
        print(f"  header From   : {from_}", flush=True)
        print(f"  header To     : {to}", flush=True)
        print(f"  Subject       : {subject}", flush=True)
        print(f"  size          : {len(raw)} bytes", flush=True)
        print("-" * 72, flush=True)
        # Print the body after the first blank line, which is where headers end.
        _, _, body = text.partition("\n\n")
        print(body.rstrip(), flush=True)
        print("=" * 72, flush=True)

    @staticmethod
    def _write(stream, text: str) -> None:
        stream.write(text.encode("utf-8"))
        stream.flush()


def _between(command: str, sep: str, open_char: str, close_char: str) -> str:
    """Extract the address from `MAIL FROM:<a@b>` style commands."""
    _, _, rest = command.partition(sep)
    start = rest.find(open_char)
    end = rest.find(close_char)
    if start == -1 or end == -1:
        return rest.strip()
    return rest[start + 1:end].strip()


def _header(text: str, name: str) -> str:
    for line in text.splitlines():
        if line.lower().startswith(name.lower() + ":"):
            return line.split(":", 1)[1].strip()
        if line.strip() == "":
            break
    return "(none)"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Capture SMTP messages to files")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=2525)
    parser.add_argument("--out", default=str(Path(__file__).with_name("captured")))
    args = parser.parse_args(argv)

    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)

    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind((args.host, args.port))
    server.listen(16)

    print(f"capture server listening on {args.host}:{args.port}", flush=True)
    print(f"messages written to {out_dir}", flush=True)
    print("press Ctrl+C to stop", flush=True)

    try:
        while True:
            conn, peer = server.accept()
            Session(conn, peer, out_dir).start()
    except KeyboardInterrupt:
        print("\nstopping", flush=True)
    finally:
        server.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
