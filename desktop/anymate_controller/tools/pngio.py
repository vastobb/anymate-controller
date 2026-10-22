"""A tiny, dependency-free PNG reader/writer.

Only what the animation tools need: 8-bit PNGs in gray / RGB / RGBA, no
interlacing. Enough to build sprite sheets, sample palettes and export frames
without pulling in Pillow.
"""

from __future__ import annotations

import struct
import zlib
from dataclasses import dataclass
from pathlib import Path
from typing import List, Tuple


class PngError(RuntimeError):
    """Raised when a PNG cannot be decoded."""


@dataclass
class Image:
    """An 8-bit RGBA image."""

    width: int
    height: int
    pixels: bytearray  # RGBA, row major

    def pixel(self, x: int, y: int) -> Tuple[int, int, int, int]:
        offset = (y * self.width + x) * 4
        return tuple(self.pixels[offset : offset + 4])  # type: ignore[return-value]

    def set_pixel(self, x: int, y: int, rgba: Tuple[int, int, int, int]) -> None:
        offset = (y * self.width + x) * 4
        self.pixels[offset : offset + 4] = bytes(rgba)

    def crop(self, x: int, y: int, width: int, height: int) -> "Image":
        x = max(0, min(x, self.width - 1))
        y = max(0, min(y, self.height - 1))
        width = max(1, min(width, self.width - x))
        height = max(1, min(height, self.height - y))
        out = Image(width, height, bytearray(width * height * 4))
        for row in range(height):
            src = ((y + row) * self.width + x) * 4
            dst = row * width * 4
            out.pixels[dst : dst + width * 4] = self.pixels[src : src + width * 4]
        return out


def _paeth(a: int, b: int, c: int) -> int:
    p = a + b - c
    pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
    if pa <= pb and pa <= pc:
        return a
    if pb <= pc:
        return b
    return c


def read_png(path: Path) -> Image:
    """Decode an 8-bit PNG into an RGBA :class:`Image`."""

    data = path.read_bytes()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise PngError(f"{path.name} is not a PNG file.")
    pos = 8
    width = height = 0
    bit_depth = color_type = interlace = 0
    idat = bytearray()
    while pos + 8 <= len(data):
        (length,) = struct.unpack(">I", data[pos : pos + 4])
        chunk_type = data[pos + 4 : pos + 8]
        body = data[pos + 8 : pos + 8 + length]
        pos += 12 + length
        if chunk_type == b"IHDR":
            width, height, bit_depth, color_type, _, _, interlace = struct.unpack(">IIBBBBB", body)
        elif chunk_type == b"IDAT":
            idat += body
        elif chunk_type == b"IEND":
            break
    if bit_depth != 8:
        raise PngError(f"Only 8-bit PNGs are supported (got {bit_depth}-bit).")
    if interlace:
        raise PngError("Interlaced PNGs are not supported.")
    channels = {0: 1, 2: 3, 4: 2, 6: 4}.get(color_type)
    if channels is None:
        raise PngError(f"Unsupported PNG colour type {color_type}.")

    raw = zlib.decompress(bytes(idat))
    stride = width * channels
    lines: List[bytearray] = []
    previous = bytearray(stride)
    offset = 0
    for _ in range(height):
        filter_type = raw[offset]
        offset += 1
        line = bytearray(raw[offset : offset + stride])
        offset += stride
        if filter_type == 1:
            for i in range(channels, stride):
                line[i] = (line[i] + line[i - channels]) & 0xFF
        elif filter_type == 2:
            for i in range(stride):
                line[i] = (line[i] + previous[i]) & 0xFF
        elif filter_type == 3:
            for i in range(stride):
                left = line[i - channels] if i >= channels else 0
                line[i] = (line[i] + ((left + previous[i]) >> 1)) & 0xFF
        elif filter_type == 4:
            for i in range(stride):
                left = line[i - channels] if i >= channels else 0
                up_left = previous[i - channels] if i >= channels else 0
                line[i] = (line[i] + _paeth(left, previous[i], up_left)) & 0xFF
        lines.append(line)
        previous = line

    pixels = bytearray(width * height * 4)
    for y, line in enumerate(lines):
        for x in range(width):
            src = x * channels
            dst = (y * width + x) * 4
            if color_type == 6:
                pixels[dst : dst + 4] = line[src : src + 4]
            elif color_type == 2:
                pixels[dst : dst + 3] = line[src : src + 3]
                pixels[dst + 3] = 255
            elif color_type == 0:
                value = line[src]
                pixels[dst : dst + 4] = bytes((value, value, value, 255))
            else:  # gray + alpha
                value = line[src]
                alpha = line[src + 1]
                pixels[dst : dst + 4] = bytes((value, value, value, alpha))
    return Image(width, height, pixels)


def write_png(path: Path, image: Image) -> Path:
    """Write an RGBA image as a PNG."""

    path.parent.mkdir(parents=True, exist_ok=True)
    raw = bytearray()
    stride = image.width * 4
    for y in range(image.height):
        raw.append(0)
        raw += image.pixels[y * stride : (y + 1) * stride]

    def chunk(tag: bytes, body: bytes) -> bytes:
        return (
            struct.pack(">I", len(body))
            + tag
            + body
            + struct.pack(">I", zlib.crc32(tag + body) & 0xFFFFFFFF)
        )

    header = struct.pack(">IIBBBBB", image.width, image.height, 8, 6, 0, 0, 0)
    payload = (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", header)
        + chunk(b"IDAT", zlib.compress(bytes(raw), 6))
        + chunk(b"IEND", b"")
    )
    path.write_bytes(payload)
    return path
