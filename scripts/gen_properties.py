#!/usr/bin/env python3
"""Generate properties-reference.md table from spring-configuration-metadata.json.

Usage:
    python scripts/gen_properties.py > docs/configuration/properties-reference.md

Or via mkdocs-gen-files plugin — see mkdocs.yml hooks section.
"""

import json
import sys
from pathlib import Path

METADATA_PATH = Path(
    "failover-spring-boot-autoconfigure/target/classes/META-INF/spring-configuration-metadata.json"
)


def main():
    if not METADATA_PATH.exists():
        print(
            f"ERROR: {METADATA_PATH} not found. Run 'mvn compile' first.",
            file=sys.stderr,
        )
        sys.exit(1)

    with open(METADATA_PATH) as f:
        data = json.load(f)

    properties = sorted(data.get("properties", []), key=lambda p: p["name"])

    lines = [
        "| Property | Type | Default | Description |",
        "|---|---|---|---|",
    ]
    for prop in properties:
        name = f'`{prop["name"]}`'
        type_ = f'`{prop.get("type", "").split(".")[-1]}`' if prop.get("type") else ""
        default = f'`{prop["defaultValue"]}`' if prop.get("defaultValue") is not None else ""
        description = prop.get("description", "").replace("\n", " ").strip()
        lines.append(f"| {name} | {type_} | {default} | {description} |")

    print("\n".join(lines))


if __name__ == "__main__":
    main()
