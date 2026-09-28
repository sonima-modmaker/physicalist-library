"""Generate the 5x5x5 empty GameTest structure used by PhysicalistGameTests."""

import gzip
import struct
from pathlib import Path


def name(text: str) -> bytes:
    data = text.encode("utf-8")
    return struct.pack(">H", len(data)) + data


def tag(kind: int, key: str, payload: bytes) -> bytes:
    return bytes([kind]) + name(key) + payload


def list_tag(key: str, element: int, entries: list[bytes]) -> bytes:
    return tag(9, key, bytes([element]) + struct.pack(">i", len(entries)) + b"".join(entries))


def main() -> None:
    palette_air = tag(8, "Name", name("minecraft:air")) + b"\x00"
    root = (
        list_tag("size", 3, [struct.pack(">i", 5)] * 3)
        + list_tag("palette", 10, [palette_air])
        + list_tag("blocks", 10, [])
        + list_tag("entities", 10, [])
        + tag(3, "DataVersion", struct.pack(">i", 3955))
        + b"\x00"
    )
    output = Path(__file__).resolve().parents[1] / "src/main/resources/data/physicalist_library/structure/empty.nbt"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes(gzip.compress(b"\x0a\x00\x00" + root, mtime=0))


if __name__ == "__main__":
    main()
