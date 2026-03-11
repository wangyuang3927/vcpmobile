#!/usr/bin/env python3
from __future__ import annotations

import argparse
from pathlib import Path
from typing import Dict


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(
        description="Minimal anchored-loop backfill helper for project runtime files."
    )
    p.add_argument("task_dir", nargs="?", help="Task runtime directory, e.g. .runtime/tasks/rikkahub-rust-redesign")

    p.add_argument("--objective")
    p.add_argument("--current-round")
    p.add_argument("--next-action")
    p.add_argument("--resume-action")
    p.add_argument("--delta")
    p.add_argument("--done-gate")

    p.add_argument("--implement-status")
    p.add_argument("--implement-blockers")

    p.add_argument("--doc-status")
    p.add_argument("--doc-next-step")

    p.add_argument("--loop-reason-continue")
    p.add_argument("--loop-next-action")

    p.add_argument("--card-loop")
    p.add_argument("--card-invariant")
    p.add_argument("--card-feedback")
    p.add_argument("--card-pressure")
    p.add_argument("--card-strange-loop")
    p.add_argument("--card-compression-name")
    p.add_argument("--card-next-move")
    p.add_argument("--card-next-question")
    p.add_argument("--card-decision")
    p.add_argument("--card-decision-reason")
    p.add_argument("--card-beats-best")
    p.add_argument("--card-why-best")

    p.add_argument("--print-template", action="store_true")
    return p.parse_args()


def ensure_section(text: str, heading: str, default_body: str = "- pending") -> str:
    marker = f"## {heading}\n"
    if marker in text:
        return text

    anchor = "## State\n"
    if anchor in text:
        start = text.find(anchor)
        body_start = start + len(anchor)
        next_idx = text.find("\n## ", body_start)
        if next_idx == -1:
            next_idx = len(text)
        insertion = f"\n## {heading}\n\n{default_body}\n"
        return text[:next_idx] + insertion + text[next_idx:]

    return text.rstrip() + f"\n\n## {heading}\n\n{default_body}\n"


def replace_section(text: str, heading: str, new_body: str) -> str:
    marker = f"## {heading}\n"
    start = text.find(marker)
    if start == -1:
        raise ValueError(f"Section not found: {heading}")
    body_start = start + len(marker)
    next_idx = text.find("\n## ", body_start)
    if next_idx == -1:
        next_idx = len(text)
    replacement = marker + "\n" + new_body.strip() + "\n"
    suffix = text[next_idx:]
    if suffix and not suffix.startswith("\n"):
        suffix = "\n" + suffix
    return text[:start] + replacement + suffix


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def write(path: Path, content: str) -> None:
    path.write_text(content.rstrip() + "\n", encoding="utf-8")


def update_meta(path: Path, args: argparse.Namespace) -> bool:
    text = read(path)
    changed = False
    mapping = {
        "Objective": args.objective,
        "Current Round": args.current_round,
        "Next Action": args.next_action,
        "Resume Action": args.resume_action,
        "Delta": args.delta,
        "Done Gate": args.done_gate,
    }
    for heading, value in mapping.items():
        if value:
            text = ensure_section(text, heading)
            text = replace_section(text, heading, f"- {value}")
            changed = True
    if changed:
        write(path, text)
    return changed


def update_implement(path: Path, args: argparse.Namespace) -> bool:
    text = read(path)
    changed = False
    if args.implement_status:
        text = replace_section(text, "Current Status", f"- {args.implement_status}")
        changed = True
    if args.implement_blockers:
        text = replace_section(text, "Blockers", args.implement_blockers)
        changed = True
    if changed:
        write(path, text)
    return changed


def update_documentation(path: Path, args: argparse.Namespace) -> bool:
    text = read(path)
    changed = False
    if args.doc_status:
        snapshot = text.split("## Snapshot\n", 1)
        if len(snapshot) == 2:
            block, rest = snapshot[1].split("\n## ", 1)
            lines = block.strip().splitlines()
            out = []
            replaced = False
            for line in lines:
                if line.startswith("- Current status:"):
                    out.append(f"- Current status: {args.doc_status}")
                    replaced = True
                else:
                    out.append(line)
            if not replaced:
                out.append(f"- Current status: {args.doc_status}")
            text = snapshot[0] + "## Snapshot\n\n" + "\n".join(out).rstrip() + "\n\n## " + rest
            changed = True
    if args.doc_next_step:
        text = replace_section_line(text, "Snapshot", "- Next step:", f"- Next step: {args.doc_next_step}")
        changed = True
    if changed:
        write(path, text)
    return changed


def replace_section_line(text: str, section: str, prefix: str, new_line: str) -> str:
    marker = f"## {section}\n"
    start = text.find(marker)
    if start == -1:
        raise ValueError(f"Section not found: {section}")
    body_start = start + len(marker)
    next_idx = text.find("\n## ", body_start)
    if next_idx == -1:
        next_idx = len(text)
    body = text[body_start:next_idx]
    lines = [line for line in body.splitlines()]
    replaced = False
    out = []
    for line in lines:
        if line.startswith(prefix):
            out.append(new_line)
            replaced = True
        else:
            out.append(line)
    if not replaced:
        out.append(new_line)
    return text[:body_start] + "\n" + "\n".join(out).strip() + text[next_idx:]


def update_loop(path: Path, args: argparse.Namespace) -> bool:
    text = read(path)
    changed = False
    if args.loop_reason_continue:
        text = replace_section(text, "Reason To Continue", f"- {args.loop_reason_continue}")
        changed = True
    if args.loop_next_action:
        text = replace_section(text, "Next External Action", f"- {args.loop_next_action}")
        changed = True

    card_fields = {
        "Loop": args.card_loop,
        "Invariant": args.card_invariant,
        "Feedback": args.card_feedback,
        "Pressure": args.card_pressure,
        "Strange loop": args.card_strange_loop,
        "Compression name": args.card_compression_name,
        "Next move": args.card_next_move,
        "Next refinement question": args.card_next_question,
        "Decision": args.card_decision,
        "Decision reason": args.card_decision_reason,
        "Beats best so far": args.card_beats_best,
        "Why this beats best": args.card_why_best,
    }
    if any(v for v in card_fields.values()):
        card = []
        for key, value in card_fields.items():
            if value:
                card.append(f"{key}: {value}  ")
        card_text = "\n".join(card).rstrip()
        text = replace_section(text, "Best Card So Far", card_text)
        changed = True
    if changed:
        write(path, text)
    return changed


def print_template() -> None:
    print("""Suggested minimal invocation:
python3 scripts/anchored_loop_sync.py .runtime/tasks/<slug> \\
  --next-action \"下一轮外部动作\" \\
  --delta \"本轮真实增益\" \\
  --loop-reason-continue \"为什么继续\" \\
  --loop-next-action \"Loop 的 next external action\" \\
  --card-loop \"07\" \\
  --card-invariant \"本轮不变量\" \\
  --card-feedback \"反馈证据\" \\
  --card-pressure \"主压力\" \\
  --card-compression-name \"压缩名\" \\
  --card-next-move \"下一刀\" \\
  --card-next-question \"下一轮问题\" \\
  --card-decision \"本轮决策\" \\
  --card-decision-reason \"决策原因\" \\
  --card-beats-best \"是\" \\
  --card-why-best \"为什么优于 best-so-far\"""")


def main() -> int:
    args = parse_args()
    if args.print_template:
        print_template()
        return 0

    if not args.task_dir:
        raise SystemExit("task_dir is required unless --print-template is used")

    task_dir = Path(args.task_dir)
    required = ["Meta.md", "Implement.md", "Documentation.md", "Loop.md"]
    missing = [name for name in required if not (task_dir / name).exists()]
    if missing:
        raise SystemExit(f"Missing runtime files in {task_dir}: {', '.join(missing)}")

    changes: Dict[str, bool] = {}
    changes["Meta.md"] = update_meta(task_dir / "Meta.md", args)
    changes["Implement.md"] = update_implement(task_dir / "Implement.md", args)
    changes["Documentation.md"] = update_documentation(task_dir / "Documentation.md", args)
    changes["Loop.md"] = update_loop(task_dir / "Loop.md", args)

    touched = [name for name, changed in changes.items() if changed]
    if touched:
        print("Updated:", ", ".join(touched))
    else:
        print("No changes requested.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
