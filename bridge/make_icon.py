"""Generate a simple 256x256 Telegram-blue icon with a white play/plane shape.

Pure stdlib (zlib + struct) so no PIL is required.
"""
import struct
import zlib


def png_chunk(tag: bytes, data: bytes) -> bytes:
    chunk = tag + data
    return (
        struct.pack(">I", len(data))
        + chunk
        + struct.pack(">I", zlib.crc32(chunk) & 0xFFFFFFFF)
    )


W = H = 256
BG = (42, 171, 238)  # Telegram blue
FG = (255, 255, 255)


def inside_plane(x: int, y: int) -> bool:
    cx, cy = 128, 128
    dx = x - cx
    dy = y - cy
    if dx >= -70 and dx <= 80 and dy >= -55 and dy <= 55:
        if abs(dy) <= (55 * (1 - (dx + 70) / 150)):
            return True
    return False


rows = b""
for y in range(H):
    row = bytearray()
    for x in range(W):
        r, g, b = FG if inside_plane(x, y) else BG
        row += bytes((r, g, b))
    rows += b"\x00" + bytes(row)

raw = b"\x89PNG\r\n\x1a\n"
raw += png_chunk(b"IHDR", struct.pack(">IIBBBBB", W, H, 8, 2, 0, 0, 0))
raw += png_chunk(b"IDAT", zlib.compress(rows, 9))
raw += png_chunk(b"IEND", b"")

with open("TelegramEera/icon.png", "wb") as f:
    f.write(raw)
print("wrote TelegramEera/icon.png", len(raw), "bytes")
