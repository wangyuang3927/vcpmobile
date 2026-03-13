#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import re
import shutil
import subprocess
import sys
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


REQUIRED_ROUND_FIELDS = [
    "loop",
    "invariant",
    "feedback",
    "pressure",
    "strange_loop",
    "strengthen",
    "cut",
    "compression_name",
    "next_move",
    "next_refinement_question",
    "decision",
    "decision_reason",
    "beats_best_so_far",
    "why_this_beats_best",
]


def utc_now() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat()


def load_json(path: Path) -> dict[str, Any]:
    with path.open("r", encoding="utf-8") as f:
        return json.load(f)


def dump_json(path: Path, data: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
        f.write("\n")


def ensure_task_dir(root: Path, task_slug: str) -> Path:
    task_dir = root / ".runtime" / "tasks" / task_slug
    if not task_dir.exists():
        raise SystemExit(f"Task not found: {task_dir}")
    return task_dir


def loop_dir(root: Path, task_slug: str, loop_slug: str) -> Path:
    return ensure_task_dir(root, task_slug) / "loops" / loop_slug


def meta_path(root: Path, task_slug: str) -> Path:
    return ensure_task_dir(root, task_slug) / "Meta.md"


def documentation_path(root: Path, task_slug: str) -> Path:
    return ensure_task_dir(root, task_slug) / "Documentation.md"


@dataclass
class LoopPaths:
    base: Path
    session: Path
    best: Path
    prompt: Path
    rounds: Path
    feedback: Path


def build_paths(root: Path, task_slug: str, loop_slug: str) -> LoopPaths:
    base = loop_dir(root, task_slug, loop_slug)
    return LoopPaths(
        base=base,
        session=base / "session.json",
        best=base / "best.json",
        prompt=base / "prompt.txt",
        rounds=base / "rounds",
        feedback=base / "feedback",
    )


def round_filename(n: int) -> str:
    return f"{n:03d}.json"


def validate_round_card(card: dict[str, Any]) -> None:
    missing = [field for field in REQUIRED_ROUND_FIELDS if field not in card]
    if missing:
        raise SystemExit(f"Round card missing required fields: {', '.join(missing)}")

    decision = card["decision"]
    if decision not in {"continue", "stop"}:
        raise SystemExit("Round card field 'decision' must be 'continue' or 'stop'")

    if not isinstance(card["beats_best_so_far"], bool):
        raise SystemExit("Round card field 'beats_best_so_far' must be boolean")


def load_session(paths: LoopPaths) -> dict[str, Any]:
    if not paths.session.exists():
        raise SystemExit(f"Loop session not found: {paths.session}")
    return load_json(paths.session)


def render_prompt(session: dict[str, Any], best: dict[str, Any] | None) -> str:
    round_number = session["current_round"] + 1
    best_block = (
        json.dumps(best, ensure_ascii=False, indent=2)
        if best is not None
        else "No best card yet. This is the opening round."
    )
    return (
        "You are running one anchored loop round.\n\n"
        f"Loop objective:\n{session['loop_objective']}\n\n"
        f"Why opened:\n{session['why_opened']}\n\n"
        f"Current round:\n{round_number}\n\n"
        f"Reason to continue:\n{session['reason_to_continue']}\n\n"
        f"Reason to stop:\n{session['reason_to_stop']}\n\n"
        f"Next external action this loop serves:\n{session['next_external_action']}\n\n"
        "Best loop card so far:\n"
        f"{best_block}\n\n"
        "Return exactly one JSON object with these fields:\n"
        f"{json.dumps(REQUIRED_ROUND_FIELDS, ensure_ascii=False)}\n\n"
        "Guidance:\n"
        "- Keep each field compact.\n"
        "- Compare against the best card so far, not the whole history.\n"
        "- Set beats_best_so_far=true only if this round is clearly more true, more simple, or more actionable.\n"
        "- In why_this_beats_best, name the gain explicitly: truer, simpler, or more actionable.\n"
        "- Use decision='stop' if another round would mostly be decorative.\n"
    )


def render_round_card_template() -> dict[str, Any]:
    return {
        "loop": "",
        "invariant": "",
        "feedback": "",
        "pressure": "",
        "strange_loop": "",
        "strengthen": "",
        "cut": "",
        "compression_name": "",
        "next_move": "",
        "next_refinement_question": "",
        "decision": "continue",
        "decision_reason": "",
        "beats_best_so_far": False,
        "why_this_beats_best": "",
    }


def render_prefilled_round_card_template(session: dict[str, Any], best: dict[str, Any] | None) -> dict[str, Any]:
    template = render_round_card_template()
    next_refinement = ""
    if best is not None:
        next_refinement = best.get("card", {}).get("next_refinement_question", "") or ""
    if not next_refinement:
        next_refinement = session.get("reason_to_continue", "")
    template["next_refinement_question"] = next_refinement
    return template


def render_open_slots_template() -> dict[str, Any]:
    return {
        "open_slots": [
            {
                "slot": "",
                "why_open": "",
                "change_budget": "narrow",
                "no_change_ok": True,
            }
        ]
    }


def render_feedback_delta_template(role: str | None = None) -> dict[str, Any]:
    return {
        "role": role or "",
        "target_slot": "",
        "proposed_delta": "",
        "why_it_beats_current": "",
        "evidence_or_risk": "",
        "expected_cost": "",
        "confidence": "",
        "no_change": False,
    }


def render_feedback_disposition_template() -> dict[str, Any]:
    return {
        "slot": "",
        "decision": "accept",
        "accepted_delta": "",
        "state_change": "",
        "notes": "",
    }


def normalize_slug_fragment(value: str, fallback: str = "item") -> str:
    slug = re.sub(r"[^a-z0-9]+", "-", value.strip().lower()).strip("-")
    return slug or fallback


def render_feedback_pass_manifest(roles: list[str]) -> dict[str, Any]:
    return {
        "kind": "feedback-pass-template",
        "topology": [
            "open_slots",
            "feedback_deltas",
            "feedback_disposition",
            "state_change_inside_disposition",
        ],
        "roles": roles,
        "scaffold_only": True,
        "notes": [
            "This bundle is additive scaffolding, not orchestration.",
            "Keep slot -> delta -> disposition visible.",
            "Primary owner still decides disposition and resulting state change.",
            "No synthesis, no apply, no hidden state mutation.",
        ],
        "canonical_primitives": [
            "open-slots-template",
            "feedback-delta-template",
            "feedback-disposition-template",
        ],
    }


def write_feedback_pass_bundle(output_dir: Path, roles: list[str]) -> list[Path]:
    output_dir.mkdir(parents=True, exist_ok=True)
    written: list[Path] = []

    manifest_path = output_dir / "manifest.json"
    dump_json(manifest_path, render_feedback_pass_manifest(roles))
    written.append(manifest_path)

    slots_path = output_dir / "open-slots.json"
    dump_json(slots_path, render_open_slots_template())
    written.append(slots_path)

    disposition_path = output_dir / "feedback-disposition.json"
    dump_json(disposition_path, render_feedback_disposition_template())
    written.append(disposition_path)

    if not roles:
        delta_path = output_dir / "feedback-delta.json"
        dump_json(delta_path, render_feedback_delta_template())
        written.append(delta_path)
        return written

    seen: dict[str, int] = {}
    for role in roles:
        role_slug = normalize_slug_fragment(role, fallback="role")
        seen[role_slug] = seen.get(role_slug, 0) + 1
        suffix = f"-{seen[role_slug]}" if seen[role_slug] > 1 else ""
        delta_path = output_dir / f"feedback-delta.{role_slug}{suffix}.json"
        dump_json(delta_path, render_feedback_delta_template(role))
        written.append(delta_path)

    return written


def read_feedback_bundle(bundle_dir: Path) -> dict[str, Any]:
    if not bundle_dir.exists():
        raise SystemExit(f"Feedback bundle not found: {bundle_dir}")

    slots_path = bundle_dir / "open-slots.json"
    disposition_path = bundle_dir / "feedback-disposition.json"
    delta_paths = sorted(bundle_dir.glob("feedback-delta*.json"))

    slots = load_json(slots_path)["open_slots"] if slots_path.exists() else []
    disposition = load_json(disposition_path) if disposition_path.exists() else {}
    deltas = [load_json(path) for path in delta_paths]

    return {
        "bundle_dir": str(bundle_dir),
        "slots": slots,
        "deltas": deltas,
        "disposition": disposition,
    }


def render_feedback_ingest_card(
    session: dict[str, Any],
    best: dict[str, Any] | None,
    feedback_bundle: dict[str, Any],
) -> dict[str, Any]:
    disposition = feedback_bundle.get("disposition", {}) or {}
    deltas = feedback_bundle.get("deltas", []) or []
    slots = feedback_bundle.get("slots", []) or []

    accepted_delta = str(disposition.get("accepted_delta", "") or "").strip()
    state_change = str(disposition.get("state_change", "") or "").strip()
    slot_name = str(disposition.get("slot", "") or "").strip()
    notes = str(disposition.get("notes", "") or "").strip()
    decision = str(disposition.get("decision", "accept") or "accept").strip()

    delta_summary = []
    for item in deltas:
        role = str(item.get("role", "") or "").strip()
        proposed = str(item.get("proposed_delta", "") or "").strip()
        if role or proposed:
            delta_summary.append(f"{role or 'unknown'}: {proposed or 'no delta'}")

    open_slot_names = [str(item.get("slot", "") or "").strip() for item in slots if str(item.get("slot", "") or "").strip()]
    best_card = best["card"] if best else {}
    next_refinement = accepted_delta or state_change or best_card.get("next_refinement_question", "") or session.get("reason_to_continue", "")

    accepted_or_review = "accepted" if decision == "accept" else decision or "reviewed"
    feedback_text = " | ".join(delta_summary) if delta_summary else "No supporting-agent deltas recorded."
    pressure_bits = []
    if slot_name:
        pressure_bits.append(f"slot={slot_name}")
    if open_slot_names:
        pressure_bits.append("open=" + ", ".join(open_slot_names))
    if notes:
        pressure_bits.append(notes)

    card = render_round_card_template()
    card.update(
        {
            "loop": f"Supporting-agent feedback bundle ingested from {feedback_bundle['bundle_dir']} and compiled into one owner-visible state change.",
            "invariant": best_card.get("invariant", "Only owner disposition should mutate loop truth."),
            "feedback": feedback_text,
            "pressure": " | ".join(pressure_bits) if pressure_bits else "Feedback bundle supplied no additional pressure context.",
            "strange_loop": "Without ingestion, feedback bundles stay as files and never become next-round truth; with ingestion, slot -> delta -> disposition becomes restartable state.",
            "strengthen": accepted_delta or state_change or "Turn visible feedback into one restartable state change.",
            "cut": "Do not synthesize hidden judgment; only compile explicit disposition and accepted delta into the next round surface.",
            "compression_name": accepted_delta or state_change or f"Feedback bundle {accepted_or_review}",
            "next_move": state_change or accepted_delta or best_card.get("next_move", "") or session.get("next_external_action", ""),
            "next_refinement_question": next_refinement,
            "decision": "continue",
            "decision_reason": f"Feedback bundle was {accepted_or_review} and converted into loop-visible state change.",
            "beats_best_so_far": False,
            "why_this_beats_best": "It does not necessarily beat the current best card; it preserves admission and state change durably.",
        }
    )
    return card


def managed_anchor_markers(loop_slug: str) -> tuple[str, str]:
    start = f"<!-- loop-runner:{loop_slug}:start -->"
    end = f"<!-- loop-runner:{loop_slug}:end -->"
    return start, end


def upsert_managed_block(document_text: str, loop_slug: str, heading: str, block: str) -> str:
    start, end = managed_anchor_markers(loop_slug)
    pattern = re.compile(rf"{re.escape(start)}\n.*?\n{re.escape(end)}", re.DOTALL)
    if pattern.search(document_text):
        return pattern.sub(block, document_text, count=1)

    heading_pattern = re.compile(rf"^{re.escape(heading)}\s*$", re.MULTILINE)
    heading_match = heading_pattern.search(document_text)
    if heading_match is None:
        trimmed = document_text.rstrip()
        if trimmed:
            return f"{trimmed}\n\n{heading}\n\n{block}\n"
        return f"{heading}\n\n{block}\n"

    insert_at = heading_match.end()
    before = document_text[:insert_at].rstrip()
    after = document_text[insert_at:].lstrip("\n")
    if after:
        return f"{before}\n\n{block}\n\n{after}"
    return f"{before}\n\n{block}\n"


def render_meta_anchor(task_slug: str, loop_slug: str, session: dict[str, Any], best: dict[str, Any]) -> str:
    best_card = best["card"]
    start, end = managed_anchor_markers(loop_slug)
    source_dir = f".runtime/tasks/{task_slug}/loops/{loop_slug}/"
    lines = [
        start,
        f"### Loop Anchor: `{loop_slug}`",
        "",
        f"- synced_at: {utc_now()}",
        f"- loop state: {session['state']}",
        f"- current round: {session['current_round']}",
        f"- best round: {best['round']}",
        f"- loop objective: {session['loop_objective']}",
        f"- compression name: {best_card['compression_name']}",
        f"- invariant: {best_card['invariant']}",
        f"- next move: {best_card['next_move']}",
        f"- continue posture: {best_card['decision']} — {best_card['decision_reason']}",
        f"- next external action: {session['next_external_action']}",
        f"- source: `{source_dir}`",
        end,
    ]
    return "\n".join(lines)


def render_documentation_anchor(task_slug: str, loop_slug: str, session: dict[str, Any], best: dict[str, Any]) -> str:
    best_card = best["card"]
    start, end = managed_anchor_markers(loop_slug)
    source_dir = f".runtime/tasks/{task_slug}/loops/{loop_slug}/"
    lines = [
        start,
        f"### Loop Recovery Anchor: `{loop_slug}`",
        "",
        f"- synced_at: {utc_now()}",
        f"- compression name: {best_card['compression_name']}",
        f"- invariant: {best_card['invariant']}",
        f"- next move: {best_card['next_move']}",
        f"- stop posture: {best_card['decision']} — {best_card['decision_reason']}",
        f"- recovery use: restart from this loop reading instead of replaying the full loop history",
        f"- next external action: {session['next_external_action']}",
        f"- source: `{source_dir}`",
        end,
    ]
    return "\n".join(lines)


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


def apply_round_card(paths: LoopPaths, session: dict[str, Any], card: dict[str, Any]) -> dict[str, Any]:
    validate_round_card(card)

    next_round = session["current_round"] + 1
    if next_round > session["hard_budget"]:
        raise SystemExit("Hard budget reached; close or reopen the loop before applying more rounds")

    round_record = {
        "round": next_round,
        "applied_at": utc_now(),
        "card": card,
    }
    dump_json(paths.rounds / round_filename(next_round), round_record)

    if card["beats_best_so_far"] or not paths.best.exists():
        best_record = {
            "round": next_round,
            "updated_at": utc_now(),
            "card": card,
        }
        dump_json(paths.best, best_record)
        session["best_round"] = next_round
        session["best_updated_at"] = utc_now()

    session["current_round"] = next_round
    session["updated_at"] = utc_now()

    if card["decision"] == "stop":
        session["state"] = "closed"
        session["stop_reason"] = card["decision_reason"]
    elif next_round >= session["soft_budget"]:
        session["reason_to_continue"] = (
            "Soft budget has been reached. Another round must justify a sharper gain than the current best card."
        )

    dump_json(paths.session, session)

    best = load_json(paths.best) if paths.best.exists() else None
    if session["state"] == "open":
        paths.prompt.write_text(render_prompt(session, best), encoding="utf-8")

    return session


def reconcile_loop_runtime(paths: LoopPaths) -> dict[str, Any]:
    session = load_session(paths)
    max_round = 0
    round_files = sorted(paths.rounds.glob("*.json")) if paths.rounds.exists() else []
    for path in round_files:
        record = load_json(path)
        max_round = max(max_round, int(record.get("round", 0)))

    if session["current_round"] != max_round:
        session["current_round"] = max_round

    if paths.best.exists():
        best = load_json(paths.best)
        best_round = int(best.get("round", 0))
        if best_round > max_round:
            raise SystemExit("best.json points to a round that does not exist")
        session["best_round"] = best_round
    else:
        session["best_round"] = None
        session["best_updated_at"] = None

    if session["state"] == "open":
        best = load_json(paths.best) if paths.best.exists() else None
        paths.prompt.write_text(render_prompt(session, best), encoding="utf-8")

    session["updated_at"] = utc_now()
    dump_json(paths.session, session)
    return session


def reconcile_task_meta(meta: Path) -> None:
    text = meta.read_text(encoding="utf-8")
    continuation = bullet_value(text, "Continuation Decision")
    blocker_type = bullet_value(text, "Blocker Type")
    blocker_reason = bullet_value(text, "Blocker Reason")
    unblock_condition = bullet_value(text, "Unblock Condition")

    if continuation == "continue" and any([blocker_type, blocker_reason, unblock_condition]):
        text = replace_section(text, "Blocker Type", "- none")
        text = replace_section(text, "Blocker Reason", "- none")
        text = replace_section(text, "Unblock Condition", "- none")

    if continuation == "blocked":
        if not blocker_type or blocker_type == "none":
            raise SystemExit("Meta.md is blocked but Blocker Type is empty/none")
        if not blocker_reason or blocker_reason == "none":
            raise SystemExit("Meta.md is blocked but Blocker Reason is empty/none")
        if not unblock_condition or unblock_condition == "none":
            raise SystemExit("Meta.md is blocked but Unblock Condition is empty/none")

    meta.write_text(text.rstrip() + "\n", encoding="utf-8")


def emit_json_template(template: dict[str, Any], output: str | None, label: str = "template") -> int:
    if output:
        output_path = Path(output).resolve()
        dump_json(output_path, template)
        print(f"Wrote {label} to: {output_path}")
        return 0

    print(json.dumps(template, ensure_ascii=False, indent=2))
    return 0


def cmd_init(args: argparse.Namespace) -> int:
    root = Path(args.root).resolve()
    paths = build_paths(root, args.task_slug, args.loop_slug)
    if paths.session.exists():
        raise SystemExit(f"Loop already exists: {paths.session}")

    paths.base.mkdir(parents=True, exist_ok=True)
    paths.rounds.mkdir(parents=True, exist_ok=True)
    paths.feedback.mkdir(parents=True, exist_ok=True)

    session = {
        "task_slug": args.task_slug,
        "loop_slug": args.loop_slug,
        "state": "open",
        "created_at": utc_now(),
        "updated_at": utc_now(),
        "current_round": 0,
        "soft_budget": args.soft_budget,
        "hard_budget": args.hard_budget,
        "loop_objective": args.objective,
        "why_opened": args.why_opened,
        "reason_to_continue": args.reason_to_continue,
        "reason_to_stop": args.reason_to_stop,
        "next_external_action": args.next_external_action,
        "best_round": None,
        "best_updated_at": None,
        "stop_reason": None,
    }
    dump_json(paths.session, session)
    paths.prompt.write_text(render_prompt(session, None), encoding="utf-8")

    print(f"Initialized loop: {paths.base}")
    print(f"Next prompt written to: {paths.prompt}")
    return 0


def cmd_prompt(args: argparse.Namespace) -> int:
    root = Path(args.root).resolve()
    paths = build_paths(root, args.task_slug, args.loop_slug)
    session = load_session(paths)
    best = load_json(paths.best) if paths.best.exists() else None
    prompt_text = render_prompt(session, best)
    paths.prompt.write_text(prompt_text, encoding="utf-8")
    print(prompt_text)
    return 0


def cmd_apply(args: argparse.Namespace) -> int:
    root = Path(args.root).resolve()
    paths = build_paths(root, args.task_slug, args.loop_slug)
    session = load_session(paths)
    if session["state"] != "open":
        raise SystemExit("Cannot apply a round to a closed loop")

    card = load_json(Path(args.card_file).resolve())
    session = apply_round_card(paths, session, card)
    print(f"Applied round {session['current_round']}")
    print(f"Loop state: {session['state']}")
    if session["best_round"] is not None:
        print(f"Best round: {session['best_round']}")
    return 0


def cmd_status(args: argparse.Namespace) -> int:
    root = Path(args.root).resolve()
    paths = build_paths(root, args.task_slug, args.loop_slug)
    session = load_session(paths)
    best = load_json(paths.best) if paths.best.exists() else None
    payload = {"session": session, "best": best}
    print(json.dumps(payload, ensure_ascii=False, indent=2))
    return 0


def cmd_close(args: argparse.Namespace) -> int:
    root = Path(args.root).resolve()
    paths = build_paths(root, args.task_slug, args.loop_slug)
    session = load_session(paths)
    session["state"] = "closed"
    session["stop_reason"] = args.reason
    session["updated_at"] = utc_now()
    dump_json(paths.session, session)
    print(f"Closed loop: {paths.base}")
    return 0


def cmd_export_best_to_meta(args: argparse.Namespace) -> int:
    root = Path(args.root).resolve()
    paths = build_paths(root, args.task_slug, args.loop_slug)
    session = load_session(paths)
    if not paths.best.exists():
        raise SystemExit("Cannot export to Meta.md because no best card exists yet")

    best = load_json(paths.best)
    meta = meta_path(root, args.task_slug)
    if not meta.exists():
        raise SystemExit(f"Meta.md not found: {meta}")

    meta_text = meta.read_text(encoding="utf-8")
    anchor_block = render_meta_anchor(args.task_slug, args.loop_slug, session, best)
    updated_text = upsert_managed_block(meta_text, args.loop_slug, "## Loop Anchors", anchor_block)
    meta.write_text(updated_text, encoding="utf-8")
    print(f"Exported best loop anchor to: {meta}")
    return 0


def cmd_export_best_to_documentation(args: argparse.Namespace) -> int:
    root = Path(args.root).resolve()
    paths = build_paths(root, args.task_slug, args.loop_slug)
    session = load_session(paths)
    if not paths.best.exists():
        raise SystemExit("Cannot export to Documentation.md because no best card exists yet")

    best = load_json(paths.best)
    documentation = documentation_path(root, args.task_slug)
    if not documentation.exists():
        raise SystemExit(f"Documentation.md not found: {documentation}")

    document_text = documentation.read_text(encoding="utf-8")
    anchor_block = render_documentation_anchor(args.task_slug, args.loop_slug, session, best)
    updated_text = upsert_managed_block(document_text, args.loop_slug, "## Loop Recovery Anchors", anchor_block)
    documentation.write_text(updated_text, encoding="utf-8")
    print(f"Exported best loop recovery anchor to: {documentation}")
    return 0


def cmd_template(args: argparse.Namespace) -> int:
    template = render_round_card_template()
    if args.from_loop_task and args.from_loop_slug:
        root = Path(args.root).resolve()
        paths = build_paths(root, args.from_loop_task, args.from_loop_slug)
        session = load_session(paths)
        best = load_json(paths.best) if paths.best.exists() else None
        template = render_prefilled_round_card_template(session, best)
    elif args.from_loop_task or args.from_loop_slug:
        raise SystemExit("Both --from-loop-task and --from-loop-slug are required together")
    return emit_json_template(template, args.output, "runner card template")


def cmd_open_slots_template(args: argparse.Namespace) -> int:
    return emit_json_template(render_open_slots_template(), args.output, "open-slots template")


def cmd_feedback_delta_template(args: argparse.Namespace) -> int:
    return emit_json_template(render_feedback_delta_template(args.role), args.output, "feedback-delta template")


def cmd_feedback_disposition_template(args: argparse.Namespace) -> int:
    return emit_json_template(render_feedback_disposition_template(), args.output, "feedback-disposition template")


def cmd_feedback_pass_template(args: argparse.Namespace) -> int:
    output_dir = Path(args.output_dir).resolve()
    if output_dir.exists() and not output_dir.is_dir():
        raise SystemExit(f"Output path exists and is not a directory: {output_dir}")
    if output_dir.exists() and any(output_dir.iterdir()):
        if not args.force:
            raise SystemExit(f"Output directory is not empty: {output_dir}. Pass --force to replace it.")
        shutil.rmtree(output_dir)
    roles = args.role or []
    written = write_feedback_pass_bundle(output_dir, roles)
    print(f"Wrote feedback pass template to: {output_dir}")
    for path in written:
        print(f"- {path}")
    return 0


def cmd_feedback_open(args: argparse.Namespace) -> int:
    root = Path(args.root).resolve()
    paths = build_paths(root, args.task_slug, args.loop_slug)
    if not paths.base.exists():
        raise SystemExit(f"Loop not found: {paths.base}")
    output_dir = paths.feedback / (args.pass_slug or f"round-{args.round or 'current'}")
    if args.round:
        output_dir = paths.feedback / f"round-{args.round:03d}"
    roles = args.role or []
    written = write_feedback_pass_bundle(output_dir, roles)
    if args.slot:
        slots_path = output_dir / "open-slots.json"
        payload = load_json(slots_path)
        payload["open_slots"] = [
            {
                "slot": slot,
                "why_open": "",
                "change_budget": "narrow",
                "no_change_ok": True,
            }
            for slot in args.slot
        ]
        dump_json(slots_path, payload)
    print(f"Opened feedback bundle at: {output_dir}")
    for path in written:
        print(f"- {path}")
    return 0


def cmd_feedback_ingest(args: argparse.Namespace) -> int:
    root = Path(args.root).resolve()
    paths = build_paths(root, args.task_slug, args.loop_slug)
    session = load_session(paths)
    best = load_json(paths.best) if paths.best.exists() else None

    bundle_dir = Path(args.bundle_dir).resolve() if args.bundle_dir else None
    if bundle_dir is None:
        if args.pass_slug:
            bundle_dir = paths.feedback / args.pass_slug
        elif args.round is not None:
            bundle_dir = paths.feedback / f"round-{args.round:03d}"
        else:
            raise SystemExit("feedback-ingest requires --bundle-dir, or --pass-slug / --round")

    feedback_bundle = read_feedback_bundle(bundle_dir)
    card = render_feedback_ingest_card(session, best, feedback_bundle)

    if args.output:
        output_path = Path(args.output).resolve()
        dump_json(output_path, card)
        print(f"Wrote ingested feedback round card to: {output_path}")
    else:
        print(json.dumps(card, ensure_ascii=False, indent=2))

    if args.apply:
        if session["state"] != "open":
            raise SystemExit("Cannot apply ingested feedback to a closed loop")
        session = apply_round_card(paths, session, card)
        print(f"Applied feedback-ingest round {session['current_round']}")
        print(f"Loop state: {session['state']}")
        if session["best_round"] is not None:
            print(f"Best round: {session['best_round']}")

    return 0


def cmd_run(args: argparse.Namespace) -> int:
    root = Path(args.root).resolve()
    paths = build_paths(root, args.task_slug, args.loop_slug)
    rounds_executed = 0
    while rounds_executed < args.max_rounds:
        session = load_session(paths)
        if session["state"] != "open":
            print(f"Loop already closed after {rounds_executed} run round(s)")
            return 0

        best = load_json(paths.best) if paths.best.exists() else None
        prompt_text = render_prompt(session, best)
        paths.prompt.write_text(prompt_text, encoding="utf-8")

        result = subprocess.run(
            args.driver_cmd,
            input=prompt_text,
            text=True,
            shell=True,
            capture_output=True,
            cwd=root,
        )
        if result.returncode != 0:
            stderr = result.stderr.strip()
            raise SystemExit(
                f"Driver command failed with exit code {result.returncode}" + (f": {stderr}" if stderr else "")
            )

        stdout = result.stdout.strip()
        if not stdout:
            raise SystemExit("Driver command returned empty stdout; expected one JSON object")
        try:
            card = json.loads(stdout)
        except json.JSONDecodeError as exc:
            raise SystemExit(f"Driver command did not return valid JSON: {exc}") from exc

        session = apply_round_card(paths, session, card)
        rounds_executed += 1
        print(
            f"Run round {session['current_round']} applied; loop state={session['state']}; best_round={session['best_round']}"
        )
        if session["state"] != "open":
            break
    return 0


def cmd_reconcile(args: argparse.Namespace) -> int:
    root = Path(args.root).resolve()
    task_dir = ensure_task_dir(root, args.task_slug)
    reconcile_task_meta(task_dir / "Meta.md")
    if args.loop_slug:
        session = reconcile_loop_runtime(build_paths(root, args.task_slug, args.loop_slug))
        print(json.dumps({"meta": "reconciled", "loop": session}, ensure_ascii=False, indent=2))
    else:
        print(json.dumps({"meta": "reconciled", "loop": None}, ensure_ascii=False, indent=2))
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Durable loop runner for anchored multi-round thinking")
    parser.add_argument("--root", default=".", help="Repository root")
    sub = parser.add_subparsers(dest="command", required=True)

    init_p = sub.add_parser("init", help="Initialize a loop session")
    init_p.add_argument("task_slug")
    init_p.add_argument("loop_slug")
    init_p.add_argument("--objective", required=True)
    init_p.add_argument("--why-opened", required=True)
    init_p.add_argument("--reason-to-continue", required=True)
    init_p.add_argument("--reason-to-stop", required=True)
    init_p.add_argument("--next-external-action", required=True)
    init_p.add_argument("--soft-budget", type=int, default=3)
    init_p.add_argument("--hard-budget", type=int, default=6)
    init_p.set_defaults(func=cmd_init)

    prompt_p = sub.add_parser("prompt", help="Render the next prompt")
    prompt_p.add_argument("task_slug")
    prompt_p.add_argument("loop_slug")
    prompt_p.set_defaults(func=cmd_prompt)

    apply_p = sub.add_parser("apply", help="Apply a round card")
    apply_p.add_argument("task_slug")
    apply_p.add_argument("loop_slug")
    apply_p.add_argument("card_file")
    apply_p.set_defaults(func=cmd_apply)

    status_p = sub.add_parser("status", help="Show loop status")
    status_p.add_argument("task_slug")
    status_p.add_argument("loop_slug")
    status_p.set_defaults(func=cmd_status)

    close_p = sub.add_parser("close", help="Close a loop")
    close_p.add_argument("task_slug")
    close_p.add_argument("loop_slug")
    close_p.add_argument("--reason", default="closed manually")
    close_p.set_defaults(func=cmd_close)

    export_p = sub.add_parser("export-best-to-meta", help="Export the current best loop card into Meta.md")
    export_p.add_argument("task_slug")
    export_p.add_argument("loop_slug")
    export_p.set_defaults(func=cmd_export_best_to_meta)

    export_doc_p = sub.add_parser("export-best-to-documentation", help="Export the current best loop card into Documentation.md")
    export_doc_p.add_argument("task_slug")
    export_doc_p.add_argument("loop_slug")
    export_doc_p.set_defaults(func=cmd_export_best_to_documentation)

    template_p = sub.add_parser("template", help="Emit a valid runner card JSON skeleton")
    template_p.add_argument("--from-loop-task")
    template_p.add_argument("--from-loop-slug")
    template_p.add_argument("--output")
    template_p.set_defaults(func=cmd_template)

    slots_p = sub.add_parser("open-slots-template", help="Emit a JSON skeleton for currently open slots")
    slots_p.add_argument("--output")
    slots_p.set_defaults(func=cmd_open_slots_template)

    delta_p = sub.add_parser("feedback-delta-template", help="Emit a JSON skeleton for slot-bound supporting-agent feedback")
    delta_p.add_argument("--role")
    delta_p.add_argument("--output")
    delta_p.set_defaults(func=cmd_feedback_delta_template)

    disposition_p = sub.add_parser("feedback-disposition-template", help="Emit a JSON skeleton for primary-loop disposition")
    disposition_p.add_argument("--output")
    disposition_p.set_defaults(func=cmd_feedback_disposition_template)

    pass_p = sub.add_parser("feedback-pass-template", help="Emit a visible multi-agent feedback pass bundle")
    pass_p.add_argument("--output-dir", required=True)
    pass_p.add_argument("--role", action="append")
    pass_p.add_argument("--force", action="store_true")
    pass_p.set_defaults(func=cmd_feedback_pass_template)

    feedback_open_p = sub.add_parser("feedback-open", help="Create a feedback bundle inside a loop session")
    feedback_open_p.add_argument("task_slug")
    feedback_open_p.add_argument("loop_slug")
    feedback_open_p.add_argument("--pass-slug")
    feedback_open_p.add_argument("--round", type=int)
    feedback_open_p.add_argument("--slot", action="append")
    feedback_open_p.add_argument("--role", action="append")
    feedback_open_p.set_defaults(func=cmd_feedback_open)

    feedback_ingest_p = sub.add_parser(
        "feedback-ingest",
        help="Compile a visible feedback bundle into a loop-ready round card",
    )
    feedback_ingest_p.add_argument("task_slug")
    feedback_ingest_p.add_argument("loop_slug")
    feedback_ingest_p.add_argument("--bundle-dir")
    feedback_ingest_p.add_argument("--pass-slug")
    feedback_ingest_p.add_argument("--round", type=int)
    feedback_ingest_p.add_argument("--output")
    feedback_ingest_p.add_argument("--apply", action="store_true")
    feedback_ingest_p.set_defaults(func=cmd_feedback_ingest)

    run_p = sub.add_parser("run", help="Run a thin driver command for one or more loop rounds")
    run_p.add_argument("task_slug")
    run_p.add_argument("loop_slug")
    run_p.add_argument("--driver-cmd", required=True)
    run_p.add_argument("--max-rounds", type=int, default=1)
    run_p.set_defaults(func=cmd_run)

    reconcile_p = sub.add_parser("reconcile", help="Reconcile task meta and optionally one loop session")
    reconcile_p.add_argument("task_slug")
    reconcile_p.add_argument("loop_slug", nargs="?")
    reconcile_p.set_defaults(func=cmd_reconcile)

    return parser


def main() -> int:
    parser = build_parser()
    args = parser.parse_args()
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
