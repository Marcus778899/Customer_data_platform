"""Minimal PostgreSQL client (v3 wire protocol, simple query) using only the standard library.

Why: the perf run must talk to the local PostgreSQL from `make perf`, but this machine has no host psql
and no Python driver, and docker cannot be invoked from inside make. Supports trust, cleartext, md5 and
SCRAM-SHA-256 authentication. Every value is returned as str (or None). Not a general driver:
no parameters (callers build literals themselves), no TLS, no COPY.
"""
import base64
import hashlib
import hmac
import os
import socket
import struct


class PgError(Exception):
    pass


def _i32(n):
    return struct.pack("!i", n)


class Connection:
    def __init__(self, host, port, user, password, database, timeout=600):
        self.sock = socket.create_connection((host, int(port)), timeout=timeout)
        self.password = password
        self.pid = None
        params = b"user\0" + user.encode() + b"\0database\0" + database.encode() + b"\0application_name\0perf\0\0"
        body = _i32(196608) + params
        self.sock.sendall(_i32(len(body) + 4) + body)
        self._authenticate(user)

    # -- framing --
    def _read(self, n):
        buf = b""
        while len(buf) < n:
            chunk = self.sock.recv(n - len(buf))
            if not chunk:
                raise PgError("connection closed by server")
            buf += chunk
        return buf

    def _message(self):
        t = self._read(1)
        (length,) = struct.unpack("!i", self._read(4))
        return t, self._read(length - 4)

    def _send(self, t, body):
        self.sock.sendall(t + _i32(len(body) + 4) + body)

    @staticmethod
    def _error_text(body):
        fields = {}
        for part in body.split(b"\0"):
            if part:
                fields[part[:1]] = part[1:].decode("utf-8", "replace")
        return "%s: %s" % (fields.get(b"C", "?"), fields.get(b"M", "?"))

    # -- auth --
    def _authenticate(self, user):
        nonce = base64.b64encode(os.urandom(18)).decode()
        first_bare = "n=,r=" + nonce
        server_first = None
        while True:
            t, body = self._message()
            if t == b"E":
                raise PgError(self._error_text(body))
            if t == b"K":
                (self.pid,) = struct.unpack("!i", body[:4])
            elif t == b"Z":
                return
            elif t == b"R":
                (code,) = struct.unpack("!i", body[:4])
                if code == 0:
                    continue
                if code == 3:
                    self._send(b"p", self.password.encode() + b"\0")
                elif code == 5:
                    salt = body[4:8]
                    inner = hashlib.md5(self.password.encode() + user.encode()).hexdigest()
                    outer = hashlib.md5(inner.encode() + salt).hexdigest()
                    self._send(b"p", b"md5" + outer.encode() + b"\0")
                elif code == 10:
                    data = ("n,," + first_bare).encode()
                    self._send(b"p", b"SCRAM-SHA-256\0" + _i32(len(data)) + data)
                elif code == 11:
                    server_first = body[4:].decode()
                    attrs = dict(p.split("=", 1) for p in server_first.split(","))
                    salted = hashlib.pbkdf2_hmac(
                        "sha256", self.password.encode(), base64.b64decode(attrs["s"]), int(attrs["i"]))
                    client_key = hmac.new(salted, b"Client Key", hashlib.sha256).digest()
                    stored = hashlib.sha256(client_key).digest()
                    final_no_proof = "c=biws,r=" + attrs["r"]
                    auth_msg = (first_bare + "," + server_first + "," + final_no_proof).encode()
                    sig = hmac.new(stored, auth_msg, hashlib.sha256).digest()
                    proof = base64.b64encode(bytes(a ^ b for a, b in zip(client_key, sig))).decode()
                    self._send(b"p", (final_no_proof + ",p=" + proof).encode())
                elif code == 12:
                    pass
                else:
                    raise PgError("unsupported authentication method %d" % code)

    # -- queries --
    def query(self, sql):
        """Run one or more statements; return a list of result sets (each a list of tuples).
        Statements without rows give an empty list. Raises PgError after draining the server reply."""
        self._send(b"Q", sql.encode("utf-8") + b"\0")
        results, current, error = [], None, None
        while True:
            t, body = self._message()
            if t == b"T":
                current = []
            elif t == b"D":
                (n,) = struct.unpack("!h", body[:2])
                pos, row = 2, []
                for _ in range(n):
                    (ln,) = struct.unpack("!i", body[pos:pos + 4])
                    pos += 4
                    if ln == -1:
                        row.append(None)
                    else:
                        row.append(body[pos:pos + ln].decode("utf-8"))
                        pos += ln
                current.append(tuple(row))
            elif t == b"C":
                results.append(current if current is not None else [])
                current = None
            elif t == b"E":
                error = self._error_text(body)
            elif t == b"Z":
                if error:
                    raise PgError(error)
                return results
            # N (notice), S (parameter status), I (empty query) are ignored

    def rows(self, sql):
        """Rows of the last result set of the statement(s)."""
        res = self.query(sql)
        return res[-1] if res else []

    def scalar(self, sql):
        r = self.rows(sql)
        return r[0][0] if r else None

    def close(self):
        try:
            self._send(b"X", b"")
        except OSError:
            pass
        self.sock.close()


def lit(s):
    """SQL string literal (standard_conforming_strings is on by default)."""
    return "'" + str(s).replace("'", "''") + "'"
