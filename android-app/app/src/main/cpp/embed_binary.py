#!/usr/bin/env python3
import sys
src, dst, symbol = sys.argv[1:4]
data = open(src, "rb").read()
with open(dst, "w", encoding="utf-8") as f:
    f.write("#pragma once\n#include <cstddef>\n#include <cstdint>\n")
    f.write(f"static const unsigned int {symbol}[] = {{\n")
    for i in range(0, len(data), 12):
        f.write("    " + ", ".join(f"0x{b:02x}" for b in data[i:i+12]) + ",\n")
    f.write("};\n")
    f.write(f"static const size_t {symbol}_len = sizeof({symbol});\n")
