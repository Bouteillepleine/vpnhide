#!/usr/bin/env python3
"""Build and package the ports-hiding Magisk/KernelSU module zip.

Assembles a module staging directory so the committed module.prop stays at
its release version while the zip carries the actual build version (git describe).
"""

from __future__ import annotations

import os
import re
import shutil
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "scripts"))
from build_lib import (  # type: ignore[import-not-found]
    build_activator_bin,
    get_build_version,
    make_zip,
)


def main() -> int:
    script_dir = Path(__file__).resolve().parent
    os.chdir(script_dir)

    # Assemble the module staging directory so the committed module.prop
    # stays at its release version while the zip carries the actual build
    # version (git describe).
    staging = script_dir / "module-staging"
    if staging.exists():
        shutil.rmtree(staging)
    shutil.copytree(script_dir / "module", staging)

    activator = build_activator_bin(script_dir.parent, "ports")
    shutil.copy2(activator, staging / "activator")

    # Get build version
    build_version = get_build_version(script_dir.parent)

    # Stamp version into module.prop
    module_prop = staging / "module.prop"
    content = module_prop.read_text(encoding="utf-8")
    content = re.sub(r"^version=.*", f"version=v{build_version}", content, flags=re.MULTILINE)
    module_prop.write_text(content, encoding="utf-8")
    print(f"Stamped module.prop version=v{build_version}")

    # CI sets UPDATE_JSON_URL so Magisk/KSU knows where to check for updates;
    # local dev builds leave it unset and ship without updateJson.
    update_json_url = os.environ.get("UPDATE_JSON_URL")
    if update_json_url:
        with open(module_prop, "a", encoding="utf-8") as f:
            f.write(f"updateJson={update_json_url}\n")

    # Create zip
    out_zip = script_dir / "vpnhide-ports.zip"
    if out_zip.exists():
        out_zip.unlink()

    make_zip(staging, out_zip)
    shutil.rmtree(staging)

    print()
    print(f"Built: {out_zip.name}")
    size_kb = out_zip.stat().st_size / 1024
    print(f"  {out_zip} ({size_kb:.1f} KB)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
