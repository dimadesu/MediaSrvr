#!/usr/bin/env python3
"""
Re-align ELF shared library LOAD segments to 16 KB page boundaries.

Google Play requires all native libraries to have LOAD segments aligned
to 16 KB (0x4000) for Android 15+ device compatibility.

This script patches a prebuilt .so file so that every LOAD segment satisfies:
    p_offset % 0x4000 == p_vaddr % 0x4000
and sets p_align = 0x4000.

Usage:
    python3 align_elf_16kb.py <input.so> [output.so]

If output is not specified, the input file is modified **in place**
(a .bak backup is created automatically).
"""

import struct
import sys
import shutil
from pathlib import Path

PAGE_16K = 0x4000

# ELF constants
EI_CLASS = 4
ELFCLASS64 = 2
PT_LOAD = 1
PT_PHDR = 6

# ---------- 64-bit ELF struct formats (little-endian) ----------
ELF64_EHDR_FMT = "<16sHHIQQQIHHHHHH"
ELF64_PHDR_FMT = "<IIQQQQQQ"
ELF64_SHDR_FMT = "<IIQQQQIIQQ"


# ======================= 64-bit helpers ==========================

def parse_elf64_ehdr(data):
    fields = struct.unpack_from(ELF64_EHDR_FMT, data, 0)
    return {
        "e_ident": fields[0],
        "e_type": fields[1],
        "e_machine": fields[2],
        "e_version": fields[3],
        "e_entry": fields[4],
        "e_phoff": fields[5],
        "e_shoff": fields[6],
        "e_flags": fields[7],
        "e_ehsize": fields[8],
        "e_phentsize": fields[9],
        "e_phnum": fields[10],
        "e_shentsize": fields[11],
        "e_shnum": fields[12],
        "e_shstrndx": fields[13],
    }


def parse_elf64_phdr(data, offset):
    fields = struct.unpack_from(ELF64_PHDR_FMT, data, offset)
    return {
        "p_type": fields[0],
        "p_flags": fields[1],
        "p_offset": fields[2],
        "p_vaddr": fields[3],
        "p_paddr": fields[4],
        "p_filesz": fields[5],
        "p_memsz": fields[6],
        "p_align": fields[7],
    }


def pack_elf64_phdr(phdr):
    return struct.pack(
        ELF64_PHDR_FMT,
        phdr["p_type"],
        phdr["p_flags"],
        phdr["p_offset"],
        phdr["p_vaddr"],
        phdr["p_paddr"],
        phdr["p_filesz"],
        phdr["p_memsz"],
        phdr["p_align"],
    )


def parse_elf64_shdr(data, offset):
    fields = struct.unpack_from(ELF64_SHDR_FMT, data, offset)
    return {
        "sh_name": fields[0],
        "sh_type": fields[1],
        "sh_flags": fields[2],
        "sh_addr": fields[3],
        "sh_offset": fields[4],
        "sh_size": fields[5],
        "sh_link": fields[6],
        "sh_info": fields[7],
        "sh_addralign": fields[8],
        "sh_entsize": fields[9],
    }


def pack_elf64_shdr(shdr):
    return struct.pack(
        ELF64_SHDR_FMT,
        shdr["sh_name"],
        shdr["sh_type"],
        shdr["sh_flags"],
        shdr["sh_addr"],
        shdr["sh_offset"],
        shdr["sh_size"],
        shdr["sh_link"],
        shdr["sh_info"],
        shdr["sh_addralign"],
        shdr["sh_entsize"],
    )


def needed_padding(current_offset, vaddr, page_size):
    """Calculate how many bytes of padding to insert before a segment
    so that (new_offset % page_size) == (vaddr % page_size)."""
    cur_mod = current_offset % page_size
    want_mod = vaddr % page_size
    if cur_mod == want_mod:
        return 0
    if want_mod > cur_mod:
        return want_mod - cur_mod
    return page_size - cur_mod + want_mod


def realign_elf(input_path, output_path):
    data = bytearray(Path(input_path).read_bytes())

    # Verify ELF magic
    if data[:4] != b"\x7fELF":
        raise ValueError(f"{input_path} is not an ELF file")

    ei_class = data[EI_CLASS]
    if ei_class != ELFCLASS64:
        raise ValueError(f"{input_path} is not a 64-bit ELF (class={ei_class}). "
                         "16 KB alignment is only needed for 64-bit (arm64-v8a/x86_64).")

    parse_ehdr = parse_elf64_ehdr
    parse_phdr = parse_elf64_phdr
    pack_phdr  = pack_elf64_phdr
    parse_shdr = parse_elf64_shdr
    pack_shdr  = pack_elf64_shdr
    off_fmt    = "<Q"          # 8-byte offsets
    phoff_pos  = 32            # e_phoff position in ELF header
    shoff_pos  = 40            # e_shoff position in ELF header

    ehdr = parse_ehdr(data)

    # Parse all program headers
    phdrs = []
    for i in range(ehdr["e_phnum"]):
        off = ehdr["e_phoff"] + i * ehdr["e_phentsize"]
        phdrs.append(parse_phdr(data, off))

    # Parse all section headers
    shdrs = []
    for i in range(ehdr["e_shnum"]):
        off = ehdr["e_shoff"] + i * ehdr["e_shentsize"]
        shdrs.append(parse_shdr(data, off))

    # Check if already 16 KB aligned
    needs_fix = False
    for ph in phdrs:
        if ph["p_type"] == PT_LOAD and ph["p_align"] < PAGE_16K:
            needs_fix = True
            break

    if not needs_fix:
        print(f"  {input_path}: already 16 KB aligned, skipping.")
        if str(input_path) != str(output_path):
            shutil.copy2(input_path, output_path)
        return

    # Build a list of LOAD segments sorted by file offset
    load_indices = [i for i, ph in enumerate(phdrs) if ph["p_type"] == PT_LOAD]
    load_indices.sort(key=lambda i: phdrs[i]["p_offset"])

    # We'll reconstruct the binary by copying chunks and inserting padding.
    # Strategy: walk through the file sequentially. For each LOAD segment,
    # if its current position doesn't satisfy the 16 KB congruence with its
    # vaddr, insert zero-padding before it.

    # Build a mapping from old offset → (padding_to_insert_before, segment_index)
    # We process segments in file-offset order.
    insertions = []  # list of (old_offset, padding_bytes)
    cumulative_shift = 0

    for idx in load_indices:
        ph = phdrs[idx]
        old_off = ph["p_offset"]
        new_off = old_off + cumulative_shift
        pad = needed_padding(new_off, ph["p_vaddr"], PAGE_16K)
        if pad > 0:
            insertions.append((old_off, pad))
            cumulative_shift += pad

    if cumulative_shift == 0:
        # Segments happen to be congruent already; just update p_align
        for idx in load_indices:
            phdrs[idx]["p_align"] = PAGE_16K
        # Write updated program headers back
        for i, ph in enumerate(phdrs):
            off = ehdr["e_phoff"] + i * ehdr["e_phentsize"]
            data[off : off + ehdr["e_phentsize"]] = pack_phdr(ph)
        Path(output_path).write_bytes(data)
        print(f"  {input_path}: segments already congruent; p_align patched to 16 KB.")
        return

    # Build the new binary with padding inserted
    # Sort insertions by old_offset
    insertions.sort(key=lambda x: x[0])

    new_data = bytearray()
    prev_end = 0
    shift_at = []  # list of (old_offset, cumulative_shift_after)
    cum = 0

    for old_off, pad in insertions:
        # Copy everything from prev_end to old_off
        new_data.extend(data[prev_end:old_off])
        # Insert padding
        new_data.extend(b"\x00" * pad)
        cum += pad
        shift_at.append((old_off, cum))
        prev_end = old_off

    # Copy remainder of file
    new_data.extend(data[prev_end:])

    total_shift = cum

    def shifted_offset(old_offset):
        """Given an old file offset, return the new offset after padding insertions."""
        result = old_offset
        for boundary, shift in shift_at:
            if old_offset >= boundary:
                result = old_offset + shift
            else:
                break
        return result

    # Update program headers
    for i, ph in enumerate(phdrs):
        if ph["p_type"] == PT_LOAD:
            ph["p_offset"] = shifted_offset(ph["p_offset"])
            ph["p_align"] = PAGE_16K
        elif ph["p_type"] == PT_PHDR:
            ph["p_offset"] = shifted_offset(ph["p_offset"])
        else:
            # Other segments (DYNAMIC, NOTE, GNU_RELRO, etc.) also need offset updates
            ph["p_offset"] = shifted_offset(ph["p_offset"])

    # Update section headers
    for sh in shdrs:
        sh["sh_offset"] = shifted_offset(sh["sh_offset"])

    # Update ELF header
    new_ehdr_phoff = shifted_offset(ehdr["e_phoff"])
    new_ehdr_shoff = shifted_offset(ehdr["e_shoff"])

    # Write updated ELF header fields into new_data
    struct.pack_into(off_fmt, new_data, phoff_pos, new_ehdr_phoff)
    struct.pack_into(off_fmt, new_data, shoff_pos, new_ehdr_shoff)

    # Write updated program headers
    for i, ph in enumerate(phdrs):
        off = new_ehdr_phoff + i * ehdr["e_phentsize"]
        packed = pack_phdr(ph)
        new_data[off : off + len(packed)] = packed

    # Write updated section headers
    for i, sh in enumerate(shdrs):
        off = new_ehdr_shoff + i * ehdr["e_shentsize"]
        packed = pack_shdr(sh)
        new_data[off : off + len(packed)] = packed

    Path(output_path).write_bytes(new_data)

    orig_size = len(data)
    new_size = len(new_data)
    print(f"  {input_path}:")
    print(f"    Original size: {orig_size:,} bytes")
    print(f"    New size:      {new_size:,} bytes  (+{new_size - orig_size:,} padding)")
    print(f"    LOAD segments re-aligned to 16 KB (0x4000)")


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)

    input_path = sys.argv[1]
    output_path = sys.argv[2] if len(sys.argv) > 2 else None

    if output_path is None:
        # In-place mode: create backup
        backup = input_path + ".bak"
        shutil.copy2(input_path, backup)
        print(f"  Backup: {backup}")
        output_path = input_path

    realign_elf(input_path, output_path)


if __name__ == "__main__":
    main()
