import os
from pathlib import Path

# Tweak this if needed: size per chunk in bytes (e.g. ~0.8 MB)
MAX_CHUNK_SIZE = 800_000

# Directories to include
SOURCE_DIRS = [
    "src/main/java",
    "src/test/java",
]

OUTPUT_DIR = "chatgpt_bundles"
CHUNK_BASENAME = "project-part"  # project-part-01.txt, project-part-02.txt, ...


def gather_java_files():
    files = []
    for base in SOURCE_DIRS:
        root = Path(base)
        if not root.exists():
            continue
        for p in root.rglob("*.java"):
            files.append(p)
    # Sort for deterministic order
    return sorted(files, key=lambda p: str(p))


def ensure_output_dir():
    out = Path(OUTPUT_DIR)
    out.mkdir(exist_ok=True)
    return out


def open_new_chunk(out_dir, index):
    filename = f"{CHUNK_BASENAME}-{index:02d}.txt"
    fpath = out_dir / filename
    f = fpath.open("w", encoding="utf-8")
    print(f"Opened new chunk: {fpath}")
    return f, fpath


def main():
    java_files = gather_java_files()
    if not java_files:
        print("No Java files found.")
        return

    print(f"Found {len(java_files)} Java files.")

    out_dir = ensure_output_dir()
    chunk_index = 1
    chunk_file, chunk_path = open_new_chunk(out_dir, chunk_index)
    current_size = 0

    for src_path in java_files:
        rel_path = src_path.relative_to(Path("."))

        # Prepare header
        header = f"\n// ===== FILE: {rel_path} =====\n"
        header_bytes = header.encode("utf-8")

        # Read content
        with src_path.open("r", encoding="utf-8", errors="ignore") as src:
            content = src.read()
        content_bytes = content.encode("utf-8")

        # If adding this file would exceed the chunk size, start a new chunk
        if current_size + len(header_bytes) + len(content_bytes) > MAX_CHUNK_SIZE:
            chunk_file.close()
            chunk_index += 1
            chunk_file, chunk_path = open_new_chunk(out_dir, chunk_index)
            current_size = 0

        # Write header and content
        chunk_file.write(header)
        chunk_file.write(content)
        current_size += len(header_bytes) + len(content_bytes)

    chunk_file.close()
    print("Done.")
    print(f"Chunks written to: {out_dir.resolve()}")
    print("Please upload these chunk files (project-part-XX.txt) to ChatGPT.")


if __name__ == "__main__":
    main()
