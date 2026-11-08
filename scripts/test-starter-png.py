import struct
import unittest
import zlib
import starter_png as png


class PngTest(unittest.TestCase):
    def test_all_filters_rgb_rgba_roundtrip_and_crop(self):
        for channels in [3, 4]:
            width, height = 7, 5
            stride = width * channels
            pixels = bytes((i * 79 + i // 3) % 256 for i in range(stride * height))
            for kind in range(5):
                filtered = bytearray()
                for y in range(height):
                    filtered.append(kind)
                    for x in range(stride):
                        i = y * stride + x
                        left = pixels[i - channels] if x >= channels else 0
                        above = pixels[i - stride] if y else 0
                        corner = pixels[i - stride - channels] if y and x >= channels else 0
                        if kind == 4:
                            # Independent nearest-distance oracle, including left/above tie order.
                            estimate = left + above - corner
                            predictor = min((left, above, corner), key=lambda v: abs(v - estimate))
                        else:
                            predictor = [0, left, above, (left + above) // 2][kind]
                        filtered.append((pixels[i] - predictor) & 255)
                def chunk(tag, data):
                    return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xffffffff)
                data = (png.SIGNATURE + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2 if channels == 3 else 6, 0, 0, 0))
                        + chunk(b"IDAT", zlib.compress(filtered)) + chunk(b"IEND", b""))
                with self.subTest(channels=channels, filter=kind):
                    decoded = png.decode(data)
                    self.assertEqual(decoded, (width, height, channels, pixels))
                    result = png.decode(png.crop(decoded, 2, 1, 3, 2))
                    expected = b"".join(pixels[(row * width + 2) * channels:(row * width + 5) * channels] for row in [1, 2])
                    self.assertEqual(result, (3, 2, channels, expected))
                    self.assertEqual(png.decode(png.encode(*decoded)), decoded)

    def test_invalid_png_and_bounds(self):
        data = png.encode(2, 2, 3, bytes(12))
        for invalid in [b"bad", data[:-1], data + b"extra", data[:30] + b"bad" + data[33:]]:
            with self.assertRaises(ValueError):
                png.decode(invalid)
        with self.assertRaises(ValueError):
            png.encode(8193, 1, 3, b"")
        with self.assertRaises(ValueError):
            png.encode(8192, 8192, 3, b"")
        with self.assertRaises(ValueError):
            png.crop(png.decode(data), 1, 1, 2, 2)
        with self.assertRaises(ValueError):
            png.encode(2, 2, 3, b"short")


if __name__ == "__main__":
    unittest.main()
