#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
SYNC_SCRIPT = ROOT / "scripts" / "anchored_loop_sync.py"
FEEDBACK_SCRIPT = ROOT / "scripts" / "feedback_pass_local.py"
LEGACY_WARNING = (
    "anchored_loop_run.py is a legacy markdown helper. "
    "Prefer scripts/loop_runner.py for real loop sessions under "
    ".runtime/tasks/<task>/loops/<loop>/."
)


def read_text(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def write_text(path: Path, content: str) -> None:
    path.write_text(content.rstrip() + "\n", encoding="utf-8")


def section_body(text: str, heading: str) -> str:
    marker = f"## {heading}\n"
    start = text.find(marker)
    if start == -1:
        return ""
    body_start = start + len(marker)
    next_idx = text.find("\n## ", body_start)
    if next_idx == -1:
        next_idx = len(text)
    return text[body_start:next_idx].strip()


def bullet_value(text: str, heading: str) -> str:
    body = section_body(text, heading)
    if not body:
        return ""
    return body.removeprefix("- ").strip()


def runtime_paths(task_dir: Path) -> dict[str, Path]:
    return {
        "meta": task_dir / "Meta.md",
        "loop": task_dir / "Loop.md",
        "implement": task_dir / "Implement.md",
        "documentation": task_dir / "Documentation.md",
    }


def ensure_runtime(task_dir: Path) -> None:
    required = runtime_paths(task_dir)
    missing = [name for name, path in required.items() if not path.exists()]
    if missing:
        raise SystemExit(f"Missing runtime files: {', '.join(missing)} in {task_dir}")


def replace_section(text: str, heading: str, new_body: str) -> str:
    marker = f"## {heading}\n"
    start = text.find(marker)
    if start == -1:
        raise SystemExit(f"Section not found: {heading}")
    body_start = start + len(marker)
    next_idx = text.find("\n## ", body_start)
    if next_idx == -1:
        next_idx = len(text)
    replacement = marker + "\n" + new_body.strip() + "\n"
    suffix = text[next_idx:]
    if suffix and not suffix.startswith("\n"):
        suffix = "\n" + suffix
    return text[:start] + replacement + suffix


def current_round(task_dir: Path) -> int:
    meta = read_text(task_dir / "Meta.md")
    raw = bullet_value(meta, "Current Round")
    try:
        return int(raw)
    except ValueError:
        return 0


def print_status(task_dir: Path) -> int:
    ensure_runtime(task_dir)
    meta = read_text(task_dir / "Meta.md")
    loop = read_text(task_dir / "Loop.md")
    implement = read_text(task_dir / "Implement.md")

    payload = {
        "task_dir": str(task_dir),
        "current_round": bullet_value(meta, "Current Round"),
        "state": bullet_value(meta, "State"),
        "continuation_decision": bullet_value(meta, "Continuation Decision"),
        "next_action": bullet_value(meta, "Next Action"),
        "resume_action": bullet_value(meta, "Resume Action"),
        "delta": bullet_value(meta, "Delta"),
        "reason_to_continue": bullet_value(loop, "Reason To Continue"),
        "next_external_action": bullet_value(loop, "Next External Action"),
        "implement_status": bullet_value(implement, "Current Status"),
    }
    print(json.dumps(payload, ensure_ascii=False, indent=2))
    return 0


def run_sync(task_dir: Path, extra_args: list[str]) -> int:
    cmd = [sys.executable, str(SYNC_SCRIPT), str(task_dir), *extra_args]
    return subprocess.run(cmd, check=False).returncode


def cmd_next_round(args: argparse.Namespace) -> int:
    task_dir = Path(args.task_dir)
    ensure_runtime(task_dir)
    next_round = current_round(task_dir) + 1
    extra = ["--current-round", str(next_round)]
    if args.next_action:
        extra += ["--next-action", args.next_action]
    if args.resume_action:
        extra += ["--resume-action", args.resume_action]
    if args.loop_next_action:
        extra += ["--loop-next-action", args.loop_next_action]
    if args.reason_continue:
        extra += ["--loop-reason-continue", args.reason_continue]
    code = run_sync(task_dir, extra)
    if code == 0:
        print(f"Opened round {next_round} for {task_dir}")
    return code


def cmd_checkpoint(args: argparse.Namespace) -> int:
    task_dir = Path(args.task_dir)
    ensure_runtime(task_dir)
    extra: list[str] = []
    mapping = {
        "--next-action": args.next_action,
        "--resume-action": args.resume_action,
        "--delta": args.delta,
        "--done-gate": args.done_gate,
        "--implement-status": args.implement_status,
        "--implement-blockers": args.implement_blockers,
        "--doc-status": args.doc_status,
        "--doc-next-step": args.doc_next_step,
        "--loop-reason-continue": args.reason_continue,
        "--loop-next-action": args.loop_next_action,
        "--card-loop": args.card_loop,
        "--card-invariant": args.card_invariant,
        "--card-feedback": args.card_feedback,
        "--card-pressure": args.card_pressure,
        "--card-strange-loop": args.card_strange_loop,
        "--card-compression-name": args.card_compression_name,
        "--card-next-move": args.card_next_move,
        "--card-next-question": args.card_next_question,
        "--card-decision": args.card_decision,
        "--card-decision-reason": args.card_decision_reason,
        "--card-beats-best": args.card_beats_best,
        "--card-why-best": args.card_why_best,
    }
    for flag, value in mapping.items():
        if value:
            extra += [flag, value]
    return run_sync(task_dir, extra)


def cmd_feedback_open(args: argparse.Namespace) -> int:
    task_dir = Path(args.task_dir)
    ensure_runtime(task_dir)
    round_num = args.round or current_round(task_dir)
    output_dir = task_dir / "feedback-pass" / f"round-{round_num:02d}"
    cmd = [sys.executable, str(FEEDBACK_SCRIPT), "--output-dir", str(output_dir)]
    for slot in args.slot or []:
        cmd += ["--slot", slot]
    for role in args.role or []:
        cmd += ["--role", role]
    code = subprocess.run(cmd, check=False).returncode
    if code == 0:
        print(f"Feedback scaffold created at {output_dir}")
    return code


def cmd_feedback_status(args: argparse.Namespace) -> int:
    task_dir = Path(args.task_dir)
    ensure_runtime(task_dir)
    round_num = args.round or current_round(task_dir)
    target = task_dir / "feedback-pass" / f"round-{round_num:02d}"
    payload = {
        "task_dir": str(task_dir),
        "round": round_num,
        "exists": target.exists(),
        "files": sorted([path.name for path in target.glob("*")]) if target.exists() else [],
    }
    print(json.dumps(payload, ensure_ascii=False, indent=2))
    return 0


def cmd_block(args: argparse.Namespace) -> int:
    task_dir = Path(args.task_dir)
    ensure_runtime(task_dir)
    meta_path = task_dir / "Meta.md"
    meta = read_text(meta_path)
    meta = replace_section(meta, "Continuation Decision", "- blocked")
    meta = replace_section(meta, "Blocker Type", f"- {args.blocker_type}")
    meta = replace_section(meta, "Blocker Reason", f"- {args.blocker_reason}")
    meta = replace_section(meta, "Unblock Condition", f"- {args.unblock_condition}")
    meta = replace_section(meta, "Resume Action", f"- {args.resume_action}")
    write_text(meta_path, meta)
    print(f"Marked blocked in {meta_path}")
    return 0


def cmd_close_round(args: argparse.Namespace) -> int:
    task_dir = Path(args.task_dir)
    ensure_runtime(task_dir)
    meta_path = task_dir / "Meta.md"
    meta = read_text(meta_path)
    meta = replace_section(meta, "Continuation Decision", f"- {args.decision}")
    if args.next_action:
        meta = replace_section(meta, "Next Action", f"- {args.next_action}")
    if args.resume_action:
        meta = replace_section(meta, "Resume Action", f"- {args.resume_action}")
    write_text(meta_path, meta)
    print(f"Closed round with continuation decision={args.decision}")
    return 0


def cmd_best_card(args: argparse.Namespace) -> int:
    task_dir = Path(args.task_dir)
    ensure_runtime(task_dir)
    loop_path = task_dir / "Loop.md"
    loop = read_text(loop_path)
    fields = {
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
    lines = [f"{key}: {value}  " for key, value in fields.items() if value]
    if not lines:
        raise SystemExit("best-card requires at least one --card-* field")
    loop = replace_section(loop, "Best Card So Far", "\n".join(lines).rstrip())
    write_text(loop_path, loop)
    print(f"Updated best card in {loop_path}")
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description=(
            "Legacy anchored-loop markdown helper. "
            "Prefer scripts/loop_runner.py for the real loop runtime."
        )
    )
    sub = parser.add_subparsers(dest="command", required=True)

    status = sub.add_parser("status", help="Print compact loop status from runtime files")
    status.add_argument("task_dir")
    status.set_defaults(func=lambda args: print_status(Path(args.task_dir)))

    next_round = sub.add_parser("next-round", help="Advance Current Round and optionally set next actions")
    next_round.add_argument("task_dir")
    next_round.add_argument("--next-action")
    next_round.add_argument("--resume-action")
    next_round.add_argument("--loop-next-action")
    next_round.add_argument("--reason-continue")
    next_round.set_defaults(func=cmd_next_round)

    checkpoint = sub.add_parser("checkpoint", help="Checkpoint loop/runtime state through the sync helper")
    checkpoint.add_argument("task_dir")
    checkpoint.add_argument("--next-action")
    checkpoint.add_argument("--resume-action")
    checkpoint.add_argument("--delta")
    checkpoint.add_argument("--done-gate")
    checkpoint.add_argument("--implement-status")
    checkpoint.add_argument("--implement-blockers")
    checkpoint.add_argument("--doc-status")
    checkpoint.add_argument("--doc-next-step")
    checkpoint.add_argument("--reason-continue")
    checkpoint.add_argument("--loop-next-action")
    checkpoint.add_argument("--card-loop")
    checkpoint.add_argument("--card-invariant")
    checkpoint.add_argument("--card-feedback")
    checkpoint.add_argument("--card-pressure")
    checkpoint.add_argument("--card-strange-loop")
    checkpoint.add_argument("--card-compression-name")
    checkpoint.add_argument("--card-next-move")
    checkpoint.add_argument("--card-next-question")
    checkpoint.add_argument("--card-decision")
    checkpoint.add_argument("--card-decision-reason")
    checkpoint.add_argument("--card-beats-best")
    checkpoint.add_argument("--card-why-best")
    checkpoint.set_defaults(func=cmd_checkpoint)

    feedback = sub.add_parser("feedback-open", help="Create a visible supporting-agent feedback scaffold")
    feedback.add_argument("task_dir")
    feedback.add_argument("--round", type=int)
    feedback.add_argument("--slot", action="append")
    feedback.add_argument("--role", action="append")
    feedback.set_defaults(func=cmd_feedback_open)

    feedback_status = sub.add_parser("feedback-status", help="Inspect feedback scaffold files for a round")
    feedback_status.add_argument("task_dir")
    feedback_status.add_argument("--round", type=int)
    feedback_status.set_defaults(func=cmd_feedback_status)

    block = sub.add_parser("block", help="Mark the current loop as blocked with explicit unblock data")
    block.add_argument("task_dir")
    block.add_argument("--blocker-type", required=True)
    block.add_argument("--blocker-reason", required=True)
    block.add_argument("--unblock-condition", required=True)
    block.add_argument("--resume-action", required=True)
    block.set_defaults(func=cmd_block)

    close_round = sub.add_parser("close-round", help="Set continuation decision and optionally next/resume actions")
    close_round.add_argument("task_dir")
    close_round.add_argument("--decision", choices=["continue", "blocked", "done"], required=True)
    close_round.add_argument("--next-action")
    close_round.add_argument("--resume-action")
    close_round.set_defaults(func=cmd_close_round)

    best_card = sub.add_parser("best-card", help="Update only the Best Card So Far section in Loop.md")
    best_card.add_argument("task_dir")
    best_card.add_argument("--card-loop")
    best_card.add_argument("--card-invariant")
    best_card.add_argument("--card-feedback")
    best_card.add_argument("--card-pressure")
    best_card.add_argument("--card-strange-loop")
    best_card.add_argument("--card-compression-name")
    best_card.add_argument("--card-next-move")
    best_card.add_argument("--card-next-question")
    best_card.add_argument("--card-decision")
    best_card.add_argument("--card-decision-reason")
    best_card.add_argument("--card-beats-best")
    best_card.add_argument("--card-why-best")
    best_card.set_defaults(func=cmd_best_card)

    return parser


def main() -> int:
    parser = build_parser()
    args = parser.parse_args()
    print(f"[legacy] {LEGACY_WARNING}", file=sys.stderr)
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
