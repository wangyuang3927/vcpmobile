#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
from pathlib import Path


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description="Project-local anchored-loop feedback pass scaffold")
    p.add_argument("--output-dir", required=True)
    p.add_argument("--slot", action="append", default=[])
    p.add_argument("--role", action="append", default=[])
    return p


def dump(path: Path, payload: dict) -> None:
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main() -> int:
    args = build_parser().parse_args()
    out = Path(args.output_dir)
    out.mkdir(parents=True, exist_ok=True)

    open_slots = {
        "open_slots": [
            {
                "slot": slot,
                "why_open": "",
                "change_budget": "narrow",
                "no_change_ok": True,
            }
            for slot in (args.slot or [""])
        ]
    }
    dump(out / "open-slots.json", open_slots)

    for idx, role in enumerate(args.role or ["supporting-agent"], 1):
        payload = {
            "role": role,
            "target_slot": "",
            "proposed_delta": "",
            "why_it_beats_current": "",
            "evidence_or_risk": "",
            "expected_cost": "",
            "confidence": "",
            "no_change": False,
        }
        dump(out / f"feedback-delta.{idx}.json", payload)

    disposition = {
        "slot": "",
        "decision": "accept",
        "accepted_delta": "",
        "state_change": "",
        "notes": "",
    }
    dump(out / "feedback-disposition.json", disposition)
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
