"""Lists the NU20 blocks of a GSC file with their offsets and versions (for comparing LCU to LB2)."""
import struct
import sys

TAGS = ["NTBL", "TXGH", "MESH", "UMTL", "LMDT", "CPUS", "DISP", "INFO", "BNDS", "TANB", "ALA3", "PORT",
        "PINS", "BSCB", "OCCB", "IVOL", "IVL5", "META", "02UN", "NU20"]

data = open(sys.argv[1], "rb").read()
print("file", len(data), "RESH size", struct.unpack_from(">I", data, 0)[0])
for t in TAGS:
    o = data.find(t[::-1].encode())
    if o >= 0:
        print(f"{t} @ {o:#x} next u32 {struct.unpack_from('>I', data, o + 4)[0]:#x} "
              f"{struct.unpack_from('>I', data, o + 8)[0]:#x}")
print("DDS count", data.count(b"DDS |"))
