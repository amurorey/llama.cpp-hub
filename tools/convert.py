#!/usr/bin/env python3
"""
Transplant / extract MTP (Multi-Token Prediction) tensors for GGUF models.

Modes:
  merge  — transplant MTP tensors from a donor GGUF into a base model GGUF
  extract — extract MTP tensors from a full GGUF into a lightweight donor file

Usage:
    python convert.py merge <base.gguf> <donor.gguf> <output.gguf>
    python convert.py extract <full.gguf> <donor.gguf>

Arguments (merge mode):
    base    — base GGUF (tensors + metadata kept as-is)
    donor   — GGUF with extra blocks to transplant (e.g. blk.64.* for MTP)
    output  — resulting mixed-quantization GGUF

Arguments (extract mode):
    full    — full GGUF that already contains MTP layers
    donor   — output GGUF containing only MTP tensors (lightweight, ~50MB)

The scripts preserves the exact on-disk layout including per-row metadata
for quantization types like IQ4_KS that have row_meta_size > 0. This is
critical for GPU inference to work correctly.

Donor files created by `extract` can be shared online, allowing other users
to download only the small donor (~50MB) instead of the full model (tens of GB),
then use `merge` to inject it into their base model GGUF.

Example:
    # Extract MTP donor from a full model
    python convert.py extract Qwen3.5-122B.gguf Qwen3.5-122B-MTP-donor.gguf

    # Transplant MTP donor into a base model
    python convert.py merge Qwen3.5-122B-Q4_K_M.gguf Qwen3.5-122B-MTP-donor.gguf Qwen3.5-122B-MTP-Q4_K_M.gguf

    # Donor can be any quantization (e.g. Q8_0 donor into IQ4_KS base)
    python convert.py merge Qwen3.5-122B-IQ4_KS.gguf Qwen3.5-122B-MTP-Q8_0.gguf Qwen3.5-122B-MTP-IQ4_KS.gguf
"""

import hashlib
import json
import sys
import struct
from pathlib import Path

from gguf import GGUFReader, GGUFValueType


def get_field_value(reader: GGUFReader, key: str):
    """Safely get a field value from GGUFReader."""
    field = reader.get_field(key)
    return field.contents() if field else None


def calculate_on_disk_sizes(tensors, file_size):
    """Calculate on-disk size for each tensor (including per-row metadata/padding)."""
    n_tensors = len(tensors)
    sizes = []
    for i in range(n_tensors):
        if i < n_tensors - 1:
            sizes.append(tensors[i + 1].data_offset - tensors[i].data_offset)
        else:
            sizes.append(file_size - tensors[i].data_offset)
    return sizes


def write_kv_value(fout, kv_type, value):
    """Write a KV value to the output file."""
    if kv_type == GGUFValueType.STRING:
        value_bytes = value.encode("utf-8")
        fout.write(struct.pack("<Q", len(value_bytes)))
        fout.write(value_bytes)
    elif kv_type == GGUFValueType.ARRAY:
        # This is handled separately in the main code
        pass
    elif kv_type in (GGUFValueType.UINT8, GGUFValueType.INT8, GGUFValueType.BOOL):
        fout.write(struct.pack("<B", value))
    elif kv_type in (GGUFValueType.UINT16, GGUFValueType.INT16):
        fout.write(struct.pack("<H", value))
    elif kv_type in (GGUFValueType.UINT32, GGUFValueType.INT32):
        fout.write(struct.pack("<I", value))
    elif kv_type == GGUFValueType.FLOAT32:
        fout.write(struct.pack("<f", value))
    elif kv_type in (GGUFValueType.UINT64, GGUFValueType.INT64):
        fout.write(struct.pack("<Q", value))
    elif kv_type == GGUFValueType.FLOAT64:
        fout.write(struct.pack("<d", value))


def write_array_value(fout, sub_type, arr):
    """Write an array KV value to the output file."""
    fout.write(struct.pack("<I", int(sub_type)))
    fout.write(struct.pack("<Q", len(arr)))

    for elem in arr:
        if sub_type == GGUFValueType.STRING:
            elem_bytes = elem.encode("utf-8")
            fout.write(struct.pack("<Q", len(elem_bytes)))
            fout.write(elem_bytes)
        elif sub_type in (GGUFValueType.UINT8, GGUFValueType.INT8, GGUFValueType.BOOL):
            fout.write(struct.pack("<B", elem))
        elif sub_type in (GGUFValueType.UINT16, GGUFValueType.INT16):
            fout.write(struct.pack("<H", elem))
        elif sub_type in (GGUFValueType.UINT32, GGUFValueType.INT32):
            fout.write(struct.pack("<I", elem))
        elif sub_type == GGUFValueType.FLOAT32:
            fout.write(struct.pack("<f", elem))
        elif sub_type in (GGUFValueType.UINT64, GGUFValueType.INT64):
            fout.write(struct.pack("<Q", elem))
        elif sub_type == GGUFValueType.FLOAT64:
            fout.write(struct.pack("<d", elem))


def do_merge(target_path, source_path, output_path):
    """Merge MTP tensors from a donor GGUF into a base model GGUF."""
    print(f"Reading base: {target_path}")
    target_reader = GGUFReader(target_path)

    print(f"Reading donor: {source_path}")
    source_reader = GGUFReader(source_path)

    target_file_size = Path(target_path).stat().st_size
    source_file_size = Path(source_path).stat().st_size

    print(
        f"  Base tensors: {len(target_reader.tensors)}, KVs: {len([k for k in target_reader.fields if not k.startswith('GGUF.')])}"
    )
    print(
        f"  Donor tensors: {len(source_reader.tensors)}, KVs: {len([k for k in source_reader.fields if not k.startswith('GGUF.')])}"
    )

    arch = get_field_value(target_reader, "general.architecture")
    if arch is None:
        print("ERROR: Base GGUF has no general.architecture key")
        sys.exit(1)

    source_block_count = get_field_value(source_reader, f"{arch}.block_count")
    source_nextn = get_field_value(source_reader, f"{arch}.nextn_predict_layers")

    if source_nextn is None:
        print("ERROR: Donor GGUF has no nextn_predict_layers key")
        sys.exit(1)

    target_block_count = get_field_value(target_reader, f"{arch}.block_count")

    print(f"\n  Arch: {arch}")
    print(f"  Base block_count: {target_block_count}")
    print(
        f"  Donor block_count: {source_block_count}, nextn_predict_layers: {source_nextn}"
    )

    source_extra = [
        t
        for t in source_reader.tensors
        if t.name.startswith(f"blk.{target_block_count}.")
    ]
    print(f"\n  Extra tensors to transplant: {len(source_extra)}")

    if not source_extra:
        print(
            f"ERROR: No tensors found with prefix 'blk.{target_block_count}.' in donor"
        )
        sys.exit(1)

    all_tensors = list(target_reader.tensors) + source_extra

    target_on_disk_sizes = calculate_on_disk_sizes(
        target_reader.tensors, target_file_size
    )
    source_on_disk_sizes = calculate_on_disk_sizes(
        source_reader.tensors, source_file_size
    )

    source_tensor_map = {
        t.name: (t, size)
        for t, size in zip(source_reader.tensors, source_on_disk_sizes)
    }

    # Serialize donor KVs for faithful round-trip extraction
    donor_kv_data = {}
    for key, field in source_reader.fields.items():
        if key.startswith("GGUF."):
            continue
        types = [int(t) for t in field.types]
        val = field.contents()
        if isinstance(val, list):
            val = [v.item() if hasattr(v, 'item') else v for v in val]
        elif hasattr(val, 'item'):
            val = val.item()
        donor_kv_data[key] = {"types": types, "value": val}
    donor_kv_json = json.dumps(donor_kv_data, ensure_ascii=False)

    print(f"\nWriting output: {output_path}")

    with (
        open(target_path, "rb") as target_fin,
        open(source_path, "rb") as source_fin,
        open(output_path, "wb") as fout,
    ):
        # Header
        fout.write(b"GGUF")
        fout.write(struct.pack("<I", 3))
        fout.write(struct.pack("<Q", len(all_tensors)))

        kv_count = len(
            [k for k in target_reader.fields.keys() if not k.startswith("GGUF.")]
        )
        kv_count += 1  # block_count override
        for key in source_reader.fields:
            if (
                not key.startswith("GGUF.")
                and key not in target_reader.fields
                and key != f"{arch}.block_count"
                and key != f"{arch}.nextn_predict_layers"
            ):
                kv_count += 1
        kv_count += 1  # general.mtp_donor_kv_json
        fout.write(struct.pack("<Q", kv_count))

        written_keys = set()
        for key, field in target_reader.fields.items():
            if key.startswith("GGUF."):
                continue
            if key == f"{arch}.block_count":
                continue
            key_bytes = key.encode("utf-8")
            fout.write(struct.pack("<Q", len(key_bytes)))
            fout.write(key_bytes)
            kv_type = field.types[0]
            fout.write(struct.pack("<I", int(kv_type)))
            if kv_type == GGUFValueType.STRING:
                write_kv_value(fout, kv_type, field.contents())
            elif kv_type == GGUFValueType.ARRAY:
                sub_type = (
                    field.types[1] if len(field.types) > 1 else GGUFValueType.FLOAT32
                )
                write_array_value(fout, sub_type, field.contents())
            else:
                write_kv_value(fout, kv_type, field.contents())
            written_keys.add(key)

        # Override block_count with source value
        key = f"{arch}.block_count"
        key_bytes = key.encode("utf-8")
        fout.write(struct.pack("<Q", len(key_bytes)))
        fout.write(key_bytes)
        fout.write(struct.pack("<I", int(GGUFValueType.UINT32)))
        fout.write(struct.pack("<I", source_block_count))
        written_keys.add(key)

        # Add nextn_predict_layers
        key = f"{arch}.nextn_predict_layers"
        key_bytes = key.encode("utf-8")
        fout.write(struct.pack("<Q", len(key_bytes)))
        fout.write(key_bytes)
        fout.write(struct.pack("<I", int(GGUFValueType.UINT32)))
        fout.write(struct.pack("<I", source_nextn))
        written_keys.add(key)

        # Copy source-only KVs
        for key, field in source_reader.fields.items():
            if (
                key.startswith("GGUF.")
                or key in written_keys
                or key == f"{arch}.nextn_predict_layers"
            ):
                continue
            key_bytes = key.encode("utf-8")
            fout.write(struct.pack("<Q", len(key_bytes)))
            fout.write(key_bytes)
            kv_type = field.types[0]
            fout.write(struct.pack("<I", int(kv_type)))
            if kv_type == GGUFValueType.STRING:
                write_kv_value(fout, kv_type, field.contents())
            elif kv_type == GGUFValueType.ARRAY:
                sub_type = (
                    field.types[1] if len(field.types) > 1 else GGUFValueType.FLOAT32
                )
                write_array_value(fout, sub_type, field.contents())
            else:
                write_kv_value(fout, kv_type, field.contents())

        # Store serialized donor KVs for faithful round-trip extraction
        key = "general.mtp_donor_kv_json"
        key_bytes = key.encode("utf-8")
        fout.write(struct.pack("<Q", len(key_bytes)))
        fout.write(key_bytes)
        fout.write(struct.pack("<I", int(GGUFValueType.STRING)))
        write_kv_value(fout, GGUFValueType.STRING, donor_kv_json)

        # Tensor info
        current_offset = 0
        tensor_offsets = []
        for i, tensor in enumerate(all_tensors):
            if i < len(target_reader.tensors):
                size = target_on_disk_sizes[i]
            else:
                _, size = source_tensor_map[tensor.name]
            tensor_offsets.append(current_offset)
            current_offset += size

        for i, tensor in enumerate(all_tensors):
            name_bytes = tensor.name.encode("utf-8")
            fout.write(struct.pack("<Q", len(name_bytes)))
            fout.write(name_bytes)
            shape = tensor.shape.tolist()
            fout.write(struct.pack("<I", len(shape)))
            for dim in shape:
                fout.write(struct.pack("<Q", dim))
            fout.write(struct.pack("<I", int(tensor.tensor_type)))
            fout.write(struct.pack("<Q", tensor_offsets[i]))

        current_pos = fout.tell()
        alignment = get_field_value(target_reader, "general.alignment") or 32
        padding_needed = (alignment - (current_pos % alignment)) % alignment
        if padding_needed:
            fout.write(b"\x00" * padding_needed)

        # Copy tensor data
        print(f"Copying {len(all_tensors)} tensors...")
        for i, tensor in enumerate(all_tensors):
            if i < len(target_reader.tensors):
                offset = target_reader.tensors[i].data_offset
                size = target_on_disk_sizes[i]
                fin = target_fin
            else:
                src_tensor, size = source_tensor_map[tensor.name]
                offset = src_tensor.data_offset
                fin = source_fin
            fin.seek(offset)
            raw_data = fin.read(size)
            fout.write(raw_data)
            if (i + 1) % 50 == 0 or i == len(all_tensors) - 1:
                print(f"  Copied {i + 1}/{len(all_tensors)} tensors")

    # Verify
    _verify_merge_output(output_path, arch, source_block_count, source_nextn,
                         source_extra, source_tensor_map, target_reader)


def _verify_merge_output(output_path, arch, expected_block_count, expected_nextn,
                         source_extra, source_tensor_map, target_reader):
    output_size = Path(output_path).stat().st_size
    print(f"\nOutput: {output_path}")
    print(f"  Size: {output_size / 1_000_000_000:.2f} GB")

    print("\nValidating output...")
    errors = []
    try:
        out_reader = GGUFReader(output_path)

        out_block_count = get_field_value(out_reader, f"{arch}.block_count")
        if out_block_count != expected_block_count:
            errors.append(
                f"block_count: expected {expected_block_count}, got {out_block_count}"
            )

        out_nextn = get_field_value(out_reader, f"{arch}.nextn_predict_layers")
        if out_nextn != expected_nextn:
            errors.append(
                f"nextn_predict_layers: expected {expected_nextn}, got {out_nextn}"
            )

        out_tensor_names = {t.name for t in out_reader.tensors}
        for tensor in source_extra:
            if tensor.name not in out_tensor_names:
                errors.append(f"Missing tensor: {tensor.name}")

        print("  Spot-checking tensor data integrity...")
        out_tensors = {t.name: t for t in out_reader.tensors}

        for name in ["token_embd.weight"]:
            if name in out_tensors and name in {t.name for t in target_reader.tensors}:
                target_t = next(
                    (t for t in target_reader.tensors if t.name == name), None
                )
                out_t = out_tensors.get(name)
                if target_t and out_t:
                    target_hash = hashlib.sha256(target_t.data.tobytes()).hexdigest()[:16]
                    out_hash = hashlib.sha256(out_t.data.tobytes()).hexdigest()[:16]
                    if target_hash == out_hash:
                        print(f"    {name}: OK ({out_hash})")
                    else:
                        errors.append(f"Data mismatch: {name}")

        if source_extra:
            extra_name = source_extra[0].name
            source_t = source_tensor_map[extra_name][0]
            out_t = out_tensors.get(extra_name)
            if out_t:
                source_hash = hashlib.sha256(source_t.data.tobytes()).hexdigest()[:16]
                out_hash = hashlib.sha256(out_t.data.tobytes()).hexdigest()[:16]
                if source_hash == out_hash:
                    print(f"    {extra_name}: OK ({out_hash})")
                else:
                    errors.append(f"Data mismatch: {extra_name}")

    except Exception as e:
        errors.append(f"Failed to read output: {e}")

    if errors:
        print("\nVALIDATION FAILED:")
        for err in errors:
            print(f"  - {err}")
        sys.exit(1)
    else:
        print("  OK — all checks passed")
        print(f"\nDone. Output: {output_path}")


def do_extract(input_path, output_path):
    """Extract MTP tensors from a full GGUF into a lightweight donor file."""
    print(f"Reading: {input_path}")
    reader = GGUFReader(input_path)
    file_size = Path(input_path).stat().st_size

    arch = get_field_value(reader, "general.architecture")
    if arch is None:
        print("ERROR: GGUF has no general.architecture key")
        sys.exit(1)

    block_count = get_field_value(reader, f"{arch}.block_count")
    nextn = get_field_value(reader, f"{arch}.nextn_predict_layers")

    if nextn is None or nextn == 0:
        print(f"ERROR: No MTP layers found (nextn_predict_layers={nextn})")
        sys.exit(1)

    trunk_count = block_count - nextn
    mtp_tensors = [
        t for t in reader.tensors
        if any(t.name.startswith(f"blk.{trunk_count + i}.") for i in range(nextn))
    ]

    print(f"\n  Arch: {arch}")
    print(f"  Trunk block_count: {trunk_count}")
    print(f"  MTP layers: {nextn}")
    print(f"  MTP tensors found: {len(mtp_tensors)}")

    if not mtp_tensors:
        print(f"ERROR: No tensors found with prefix 'blk.{trunk_count}.*'")
        sys.exit(1)

    on_disk_sizes = calculate_on_disk_sizes(reader.tensors, file_size)
    tensor_map = {t.name: (t, s) for t, s in zip(reader.tensors, on_disk_sizes)}

    print(f"\nWriting donor: {output_path}")

    with (
        open(input_path, "rb") as fin,
        open(output_path, "wb") as fout,
    ):
        # Header
        fout.write(b"GGUF")
        fout.write(struct.pack("<I", 3))
        fout.write(struct.pack("<Q", len(mtp_tensors)))

        # Restore donor KVs from serialized JSON, or fall back to source model values
        alignment = get_field_value(reader, "general.alignment") or 32
        donor_kv_field = reader.get_field("general.mtp_donor_kv_json")
        if donor_kv_field:
            donor_kv_data = json.loads(donor_kv_field.contents())
            kv_count = len(donor_kv_data)
            fout.write(struct.pack("<Q", kv_count))
            for key, entry in donor_kv_data.items():
                key_bytes = key.encode("utf-8")
                fout.write(struct.pack("<Q", len(key_bytes)))
                fout.write(key_bytes)
                kv_type = GGUFValueType(entry["types"][0])
                fout.write(struct.pack("<I", int(kv_type)))
                if kv_type == GGUFValueType.ARRAY:
                    sub_type = GGUFValueType(entry["types"][1]) if len(entry["types"]) > 1 else GGUFValueType.FLOAT32
                    write_array_value(fout, sub_type, entry["value"])
                elif kv_type == GGUFValueType.STRING:
                    write_kv_value(fout, kv_type, entry["value"])
                else:
                    write_kv_value(fout, kv_type, entry["value"])
        else:
            kv_keys = [
                "general.architecture", "general.name", "general.description",
                "general.alignment", f"{arch}.block_count", f"{arch}.nextn_predict_layers",
            ]
            kv_keys = [k for k in kv_keys if reader.get_field(k) is not None]
            kv_count = len(kv_keys)
            fout.write(struct.pack("<Q", kv_count))
            for kv_key in kv_keys:
                field = reader.get_field(kv_key)
                kv_type = field.types[0]
                key_bytes = kv_key.encode("utf-8")
                fout.write(struct.pack("<Q", len(key_bytes)))
                fout.write(key_bytes)
                fout.write(struct.pack("<I", int(kv_type)))
                if kv_type == GGUFValueType.STRING:
                    write_kv_value(fout, kv_type, field.contents())
                elif kv_type == GGUFValueType.ARRAY:
                    sub_type = field.types[1] if len(field.types) > 1 else GGUFValueType.FLOAT32
                    write_array_value(fout, sub_type, field.contents())
                else:
                    write_kv_value(fout, kv_type, field.contents())



        # Tensor info
        current_offset = 0
        tensor_offsets = []
        for tensor in mtp_tensors:
            _, size = tensor_map[tensor.name]
            tensor_offsets.append(current_offset)
            current_offset += size

        for i, tensor in enumerate(mtp_tensors):
            name_bytes = tensor.name.encode("utf-8")
            fout.write(struct.pack("<Q", len(name_bytes)))
            fout.write(name_bytes)
            shape = tensor.shape.tolist()
            fout.write(struct.pack("<I", len(shape)))
            for dim in shape:
                fout.write(struct.pack("<Q", dim))
            fout.write(struct.pack("<I", int(tensor.tensor_type)))
            fout.write(struct.pack("<Q", tensor_offsets[i]))

        # Padding
        current_pos = fout.tell()
        padding_needed = (alignment - (current_pos % alignment)) % alignment
        if padding_needed:
            fout.write(b"\x00" * padding_needed)

        # Copy tensor data
        print(f"Copying {len(mtp_tensors)} tensors...")
        for i, tensor in enumerate(mtp_tensors):
            src_tensor, size = tensor_map[tensor.name]
            fin.seek(src_tensor.data_offset)
            raw_data = fin.read(size)
            fout.write(raw_data)
            if (i + 1) % 25 == 0 or i == len(mtp_tensors) - 1:
                print(f"  Copied {i + 1}/{len(mtp_tensors)} tensors")

    # Verify
    output_size = Path(output_path).stat().st_size
    print(f"\nDonor: {output_path}")
    print(f"  Size: {output_size / 1_000_000:.2f} MB")
    print(f"  Tensors: {len(mtp_tensors)}")

    try:
        out_reader = GGUFReader(output_path)
        for tensor in mtp_tensors:
            if tensor.name not in {t.name for t in out_reader.tensors}:
                print(f"  WARNING: missing {tensor.name}")
    except Exception as e:
        print(f"  WARNING: verification failed: {e}")

    print("Done.")


def main() -> None:
    if len(sys.argv) < 2:
        print(
            f"Usage: {sys.argv[0]} merge <base.gguf> <donor.gguf> <output.gguf>",
            file=sys.stderr,
        )
        print(
            f"       {sys.argv[0]} extract <full.gguf> <donor.gguf>",
            file=sys.stderr,
        )
        sys.exit(1)

    mode = sys.argv[1]

    if mode == "merge":
        if len(sys.argv) != 5:
            print(
                f"Usage: {sys.argv[0]} merge <base.gguf> <donor.gguf> <output.gguf>",
                file=sys.stderr,
            )
            sys.exit(1)
        do_merge(sys.argv[2], sys.argv[3], sys.argv[4])

    elif mode == "extract":
        if len(sys.argv) != 4:
            print(
                f"Usage: {sys.argv[0]} extract <full.gguf> <donor.gguf>",
                file=sys.stderr,
            )
            sys.exit(1)
        do_extract(sys.argv[2], sys.argv[3])

    else:
        print(f"Unknown mode: {mode}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()