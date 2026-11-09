"""Bounded non-interlaced RGB/RGBA8 PNG inspection, unfilter and lossless crop."""
import re
import struct
import zlib

SIGNATURE = b"\x89PNG\r\n\x1a\n"
LIMIT = 64 * 1024 * 1024


def bounds(width, height, channels):
    if (type(width) is not int or type(height) is not int or channels not in (3, 4)
            or not 0 < width <= 8192 or not 0 < height <= 8192 or width * height > 16000000):
        raise ValueError("PNG bounds/encoding unsupported")


def parse(data):
    if len(data) > LIMIT or data[:8] != SIGNATURE:
        raise ValueError("Not a bounded PNG")
    offset, image, ended, idat_closed = 8, bytearray(), False, False
    dimensions = None
    while offset < len(data):
        if offset + 12 > len(data):
            raise ValueError("Truncated PNG chunk")
        length, kind = struct.unpack_from(">I4s", data, offset)
        end = offset + 12 + length
        if (end > len(data) or not re.fullmatch(rb"[A-Za-z]{4}", kind)
                or kind[2] & 32 or kind in {b"acTL", b"fcTL", b"fdAT"}):
            raise ValueError("Invalid PNG chunk")
        payload = data[offset + 8:end - 4]
        if zlib.crc32(kind + payload) & 0xffffffff != struct.unpack_from(">I", data, end - 4)[0]:
            raise ValueError("PNG CRC mismatch")
        if dimensions is None:
            if kind != b"IHDR" or length != 13:
                raise ValueError("PNG must start with IHDR")
            width, height, depth, color, compression, filtering, interlace = struct.unpack(">IIBBBBB", payload)
            if depth != 8 or color not in (2, 6) or compression or filtering or interlace:
                raise ValueError("PNG bounds/encoding unsupported")
            channels = 3 if color == 2 else 4
            bounds(width, height, channels)
            dimensions = width, height, channels
        elif kind == b"IDAT":
            if idat_closed:
                raise ValueError("Noncontiguous PNG image data")
            image.extend(payload)
        elif kind == b"IEND":
            if length or not image or end != len(data):
                raise ValueError("Invalid PNG ending/trailing bytes")
            ended = True
            break
        else:
            if kind == b"IHDR" or kind[0] & 32 == 0:
                raise ValueError("Unsupported critical PNG chunk")
            idat_closed = bool(image)
        offset = end
    if not ended:
        raise ValueError("Missing PNG IEND")
    width, height, channels = dimensions
    stride = width * channels + 1
    expected = stride * height
    decoder = zlib.decompressobj()
    raw = decoder.decompress(image, expected + 1)
    if (len(raw) != expected or not decoder.eof or decoder.unused_data or decoder.unconsumed_tail
            or any(raw[row * stride] > 4 for row in range(height))):
        raise ValueError("Invalid PNG raster")
    return width, height, channels, raw


def dimensions(data):
    width, height, _, _ = parse(data)
    return width, height


def paeth(left, above, upper_left):
    estimate = left + above - upper_left
    a, b, c = abs(estimate - left), abs(estimate - above), abs(estimate - upper_left)
    return left if a <= b and a <= c else above if b <= c else upper_left


def decode(data):
    width, height, channels, filtered = parse(data)
    row_bytes = width * channels
    pixels = bytearray(row_bytes * height)
    for y in range(height):
        src = y * (row_bytes + 1)
        kind = filtered[src]
        start = y * row_bytes
        if kind == 0:
            pixels[start:start + row_bytes] = filtered[src + 1:src + 1 + row_bytes]
            continue
        for x in range(row_bytes):
            left = pixels[start + x - channels] if x >= channels else 0
            above = pixels[start + x - row_bytes] if y else 0
            upper_left = pixels[start + x - row_bytes - channels] if y and x >= channels else 0
            predictor = (left if kind == 1 else above if kind == 2 else
                         (left + above) // 2 if kind == 3 else paeth(left, above, upper_left))
            pixels[start + x] = (filtered[src + 1 + x] + predictor) & 255
    return width, height, channels, pixels


def encode(width, height, channels, pixels):
    bounds(width, height, channels)
    stride = width * channels
    if len(pixels) != stride * height:
        raise ValueError("Invalid pixel buffer")
    raw = b"".join(b"\0" + pixels[y * stride:(y + 1) * stride] for y in range(height))
    def chunk(kind, payload):
        return struct.pack(">I", len(payload)) + kind + payload + struct.pack(">I", zlib.crc32(kind + payload) & 0xffffffff)
    result = (SIGNATURE + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2 if channels == 3 else 6, 0, 0, 0))
              + chunk(b"IDAT", zlib.compress(raw, level=9)) + chunk(b"IEND", b""))
    if len(result) > LIMIT:
        raise ValueError("Encoded PNG too large")
    return result


def crop(decoded, x, y, width, height):
    source_width, source_height, channels, pixels = decoded
    if (any(type(v) is not int for v in (x, y, width, height)) or min(x, y) < 0
            or min(width, height) <= 0 or x + width > source_width or y + height > source_height):
        raise ValueError("Crop outside source bounds")
    bounds(source_width, source_height, channels)
    if len(pixels) != source_width * source_height * channels:
        raise ValueError("Invalid source pixels")
    rows = []
    for row in range(y, y + height):
        start = (row * source_width + x) * channels
        rows.append(pixels[start:start + width * channels])
    return encode(width, height, channels, b"".join(rows))
