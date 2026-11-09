"""Print reproducible raw-DEFLATE fixture expressions; host stdlib only, no project writes."""
import itertools
import zlib


def expression(payload):
    compressor = zlib.compressobj(level=9, wbits=-15)
    compressed = compressor.compress(payload) + compressor.flush()
    assert zlib.decompress(compressed, -15) == payload
    chunks = []
    pending = bytearray()
    for byte, group in itertools.groupby(compressed):
        count = len(list(group))
        if count > 16:
            if pending:
                chunks.append('"' + pending.hex() + '"')
                pending.clear()
            chunks.append('"%02x".repeat(%d)' % (byte, count))
        else:
            pending.extend([byte] * count)
    if pending:
        chunks.append('"' + pending.hex() + '"')
    print(" + ".join(chunks))
    print("compressed bytes:", len(compressed), "actual bytes:", len(payload), "crc:", zlib.crc32(payload))


if __name__ == "__main__":
    expression(b'{"version":"1.2","withoutSpace":false,"rows":"1","columns":"1","cards":[{"id":7,"cardType":0,"title":"legacy"}]}')
    expression(b" " * (8 * 1024 * 1024 + 1))
