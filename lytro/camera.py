"""Client for the first-generation Lytro camera's WiFi protocol (TCP 5678).

See PROTOCOL.md for the reverse-engineered details. The camera is fragile:
keep a single connection, send one command at a time, and never send
commands that are not known to be safe (C2 00 03 reboots it).
"""
from __future__ import annotations

import socket
import struct
import threading
import time
from dataclasses import dataclass
from typing import Callable

MAGIC = 0xFAAA55AF
DEFAULT_HOST = "10.100.1.1"
DEFAULT_PORT = 5678
CHUNK = 0x10000

# Safe, known commands only.
CMD_LOAD_HWINFO = bytes([0xC2, 0x00, 0x00])
CMD_LOAD_FILE = bytes([0xC2, 0x00, 0x01])
CMD_LOAD_PICLIST = bytes([0xC2, 0x00, 0x02])
CMD_QUERY_SIZE = bytes([0xC6, 0x00])
CMD_DOWNLOAD = bytes([0xC4, 0x00, 0x00])  # + u32 LE offset at byte 3

FLAG_SEND = 0
FLAG_RECV = 1


class Cancelled(Exception):
    pass


class CameraError(Exception):
    pass


@dataclass
class Picture:
    folder_num: int
    folder_suffix: str
    prefix: str
    file_num: int
    picture_id: str  # "sha1-..."
    taken: str  # ISO 8601
    focus_lambda: float

    @property
    def base_path(self) -> str:
        """Camera path without extension, e.g. I:\\DCIM\\100PHOTO\\IMG_0001."""
        return f"I:\\DCIM\\{self.folder_num:03d}{self.folder_suffix}\\{self.prefix}{self.file_num:04d}"

    @property
    def name(self) -> str:
        return f"{self.prefix}{self.file_num:04d}"


@dataclass
class HardwareInfo:
    manufacturer: str
    serial: str
    hw_version: str
    firmware: str


class LytroCamera:
    def __init__(self, host: str = DEFAULT_HOST, port: int = DEFAULT_PORT, timeout: float = 20.0):
        self.host, self.port, self.timeout = host, port, timeout
        self._sock: socket.socket | None = None
        self._lock = threading.RLock()

    # ------------------------------------------------------------ connection
    def connect(self):
        with self._lock:
            self.close()
            self._sock = socket.create_connection((self.host, self.port), timeout=self.timeout)

    def close(self):
        with self._lock:
            if self._sock is not None:
                try:
                    self._sock.close()
                except OSError:
                    pass
                self._sock = None

    def _recvn(self, n: int) -> bytes:
        buf = bytearray()
        while len(buf) < n:
            d = self._sock.recv(n - len(buf))
            if not d:
                raise CameraError("camera closed the connection")
            buf += d
        return bytes(buf)

    def _cmd(self, cmd: bytes, length: int = 0, flags: int = FLAG_SEND, payload: bytes = b""):
        if self._sock is None:
            self.connect()
        self._sock.sendall(struct.pack("<III", MAGIC, length, flags) + cmd.ljust(16, b"\0") + payload)
        hdr = self._recvn(28)
        magic, rlen, rflags = struct.unpack("<III", hdr[:12])
        if magic != MAGIC:
            raise CameraError(f"bad reply magic {magic:#x}")
        body = self._recvn(rlen) if rlen else b""
        return rflags, body

    def _load(self, cmd: bytes, payload: bytes = b"",
              progress: Callable[[int, int], None] | None = None,
              cancel: Callable[[], bool] | None = None) -> bytes:
        """Load an object on the camera, then download it in chunks."""
        with self._lock:
            try:
                self._cmd(cmd, len(payload), FLAG_SEND, payload)
                _, b = self._cmd(CMD_QUERY_SIZE, 4, FLAG_RECV)
                if len(b) != 4:
                    raise CameraError("camera has nothing loaded (unknown file?)")
                size = struct.unpack("<I", b)[0]
                out = bytearray()
                while len(out) < size:
                    if cancel and cancel():
                        raise Cancelled()
                    n = min(size - len(out), CHUNK)
                    _, chunk = self._cmd(CMD_DOWNLOAD + struct.pack("<I", len(out)), n, FLAG_RECV)
                    if not chunk:
                        raise CameraError(f"empty chunk at offset {len(out)}")
                    out += chunk
                    if progress:
                        progress(len(out), size)
                return bytes(out)
            except (OSError, CameraError):
                # drop the connection; the next call reconnects
                self.close()
                raise

    # ------------------------------------------------------------- commands
    def hardware_info(self) -> HardwareInfo:
        d = self._load(CMD_LOAD_HWINFO)
        # null-padded strings (256 + 3 x 128 bytes) followed by a u32
        s = [x.decode("ascii", "replace") for x in d[:640].split(b"\0") if x] + ["", "", "", ""]
        return HardwareInfo(*s[:4])

    def list_pictures(self) -> list[Picture]:
        d = self._load(CMD_LOAD_PICLIST)
        if len(d) < 12:
            return []
        _version, entry_size, nfields = struct.unpack_from("<III", d, 0)
        start = 12 + nfields * 8
        pics = []
        for off in range(start, len(d) - entry_size + 1, entry_size):
            e = d[off:off + entry_size]

            def s(a, b):
                return e[a:b].split(b"\0")[0].decode("ascii", "replace")

            pics.append(Picture(
                folder_suffix=s(0x00, 0x08), prefix=s(0x08, 0x10),
                folder_num=struct.unpack_from("<I", e, 0x10)[0],
                file_num=struct.unpack_from("<I", e, 0x14)[0],
                focus_lambda=struct.unpack_from("<f", e, 0x2C)[0],
                picture_id=s(0x30, 0x60), taken=s(0x60, 0x7C)))
        return pics

    def read_file(self, path: str, progress=None, cancel=None) -> bytes:
        return self._load(CMD_LOAD_FILE, path.encode("ascii") + b"\0", progress, cancel)

    def read_metadata(self, pic: Picture) -> bytes:
        return self.read_file(pic.base_path + ".TXT")

    def read_thumbnail(self, pic: Picture) -> bytes:
        return self.read_file(pic.base_path + ".128")

    def read_raw(self, pic: Picture, progress=None, cancel=None) -> bytes:
        return self.read_file(pic.base_path + ".RAW", progress, cancel)


def thumbnail_to_rgb(data: bytes):
    """Decode a 128x128 YUYV 4:2:2 thumbnail into an RGB numpy array."""
    import numpy as np

    d = np.frombuffer(data, np.uint8)[:128 * 128 * 2].reshape(128, 64, 4).astype(np.float32)
    y = np.stack([d[..., 0], d[..., 2]], -1).reshape(128, 128)
    u = np.repeat(d[..., 1] - 128, 2, 1)
    v = np.repeat(d[..., 3] - 128, 2, 1)
    rgb = np.stack([y + 1.402 * v, y - 0.344 * u - 0.714 * v, y + 1.772 * u], -1)
    return rgb.clip(0, 255).astype(np.uint8)
