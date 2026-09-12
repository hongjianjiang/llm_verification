#!/usr/bin/env python3
"""Plot paired scaling curves from ``ltl_scaling_study.py`` records."""

from __future__ import annotations

import argparse
import json
import math
from pathlib import Path
import statistics

import matplotlib.pyplot as plt
from matplotlib.lines import Line2D
from matplotlib.ticker import FixedLocator, FuncFormatter


FAMILY_ORDER = ["dot", "Y", "no2a", "mono", "since", "slb", "marks", "same"]
FAMILY_TITLES = {
    "dot": r"$L_{\mathsf{dot}}$",
    "Y": r"$L_{\mathsf{Y}}$",
    "no2a": r"$L_{\mathsf{no2a}}$",
    "mono": r"$L_{\mathsf{mono}}$",
    "since": r"$L_{\mathsf{since}}$",
    "slb": r"$L_{\mathsf{slb}}$",
    "marks": r"$L_{\mathsf{marks}}$",
    "same": r"$L_{\mathsf{same}}$",
}
CIRCUIT_COLOR = "#0072B2"
DFA_COLOR = "#D55E00"


def load(results: Path) -> list[dict]:
    manifest = [json.loads(line) for line in (results / "manifest.jsonl").read_text().splitlines()]
    records = {}
    for path in sorted((results / "records").glob("*.json")):
        rows = json.loads(path.read_text())
        for row in rows:
            records.setdefault((row["cell"], row["route"]), []).append(row)

    combined = []
    for cell in manifest:
        row = dict(cell)
        for route in ("dfa", "circuit"):
            attempts = records.get((cell["cell"], route), [])
            successes = [attempt for attempt in attempts if attempt["status"] in {"empty", "nonempty"}]
            if successes:
                row[f"{route}_status"] = "solved"
                row[f"{route}_seconds"] = statistics.median(attempt["wall_seconds"] for attempt in successes)
                row[f"{route}_correct"] = all(attempt.get("matches_expected") for attempt in successes)
            elif attempts:
                row[f"{route}_status"] = attempts[-1]["status"]
                row[f"{route}_seconds"] = None
                row[f"{route}_correct"] = None
            else:
                row[f"{route}_status"] = "missing"
                row[f"{route}_seconds"] = None
                row[f"{route}_correct"] = None
        combined.append(row)
    return combined


def ticks(values: list[int]) -> list[int]:
    if len(values) <= 4:
        return values
    indices = sorted({0, len(values) // 2, len(values) - 1})
    return [values[index] for index in indices]


def scalar_tick(value: float, _position: float) -> str:
    return f"{value:g}"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--results", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--ceiling", type=float, default=120.0)
    args = parser.parse_args()
    rows = load(args.results)

    plt.rcParams.update(
        {
            "font.family": "serif",
            "font.serif": ["Times New Roman", "Times", "STIXGeneral"],
            "mathtext.fontset": "stix",
            "font.size": 8,
            "axes.labelsize": 8,
            "axes.titlesize": 9,
            "legend.fontsize": 8,
            "xtick.labelsize": 7,
            "ytick.labelsize": 7,
            "pdf.fonttype": 42,
            "ps.fonttype": 42,
        }
    )
    fig, axes = plt.subplots(2, 4, figsize=(6.5, 4.05), sharey=True)
    failure_y = args.ceiling

    for panel, (family, axis) in enumerate(zip(FAMILY_ORDER, axes.flat)):
        family_rows = sorted((row for row in rows if row["family"] == family), key=lambda row: row["parameter"])
        parameters = [row["parameter"] for row in family_rows]
        for route, color, marker in (
            ("circuit", CIRCUIT_COLOR, "o"),
            ("dfa", DFA_COLOR, "s"),
        ):
            solved = [row for row in family_rows if row[f"{route}_status"] == "solved"]
            axis.plot(
                [row["parameter"] for row in solved],
                [row[f"{route}_seconds"] for row in solved],
                color=color,
                marker=marker,
                markersize=3.2,
                linewidth=1.2,
                zorder=2,
            )
            for failure_status, failure_marker in (("timeout", "^"), ("size_limit", "x"), ("error", "X")):
                failed = [row for row in family_rows if row[f"{route}_status"] == failure_status]
                if failed:
                    axis.scatter(
                        [row["parameter"] for row in failed],
                        [failure_y] * len(failed),
                        color=color,
                        marker=failure_marker,
                        s=18,
                        linewidths=0.9,
                        clip_on=False,
                        zorder=3,
                    )

        circuit_solved = sum(row["circuit_status"] == "solved" for row in family_rows)
        dfa_solved = sum(row["dfa_status"] == "solved" for row in family_rows)
        axis.text(
            0.97,
            0.05,
            f"circuit {circuit_solved}/{len(family_rows)}; DFA {dfa_solved}/{len(family_rows)}",
            transform=axis.transAxes,
            ha="right",
            va="bottom",
            fontsize=6.8,
            color="0.28",
        )
        axis.set_title(f"({chr(ord('a') + panel)}) {FAMILY_TITLES[family]}", loc="left")
        axis.set_xscale("log", base=2)
        axis.set_yscale("log")
        axis.set_xlim(min(parameters) / 1.18, max(parameters) * 1.18)
        axis.set_ylim(0.3, args.ceiling * 1.5)
        axis.xaxis.set_major_locator(FixedLocator(ticks(parameters)))
        axis.xaxis.set_major_formatter(FuncFormatter(scalar_tick))
        axis.yaxis.set_major_locator(FixedLocator([0.5, 1, 10, 120]))
        axis.yaxis.set_major_formatter(FuncFormatter(scalar_tick))
        axis.grid(axis="both", which="major", color="0.87", linewidth=0.5)
        axis.spines["top"].set_visible(False)
        axis.spines["right"].set_visible(False)
        axis.tick_params(direction="out", length=2.4, width=0.6)
        axis.set_xlabel("Alphabet size" if family in {"mono", "since", "slb"} else "Depth")
        if panel % 4 == 0:
            axis.set_ylabel("Wall time (s)")

    legend = [
        Line2D([0], [0], color=CIRCUIT_COLOR, marker="o", markersize=4, linewidth=1.3, label="PVWAA-to-circuit"),
        Line2D([0], [0], color=DFA_COLOR, marker="s", markersize=4, linewidth=1.3, label="DFA baseline"),
        Line2D([0], [0], color="0.3", marker="^", linestyle="None", markersize=4, label="timeout"),
        Line2D([0], [0], color="0.3", marker="x", linestyle="None", markersize=4, label="size limit"),
    ]
    fig.legend(handles=legend, loc="upper center", ncol=4, frameon=False, bbox_to_anchor=(0.5, 0.995))
    fig.tight_layout(rect=(0, 0, 1, 0.90), w_pad=1.0, h_pad=1.15)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(args.out, bbox_inches="tight")
    fig.savefig(args.out.with_suffix(".png"), dpi=220, bbox_inches="tight")

    solved = {
        route: sum(row[f"{route}_status"] == "solved" for row in rows)
        for route in ("circuit", "dfa")
    }
    paired = [row for row in rows if row["circuit_status"] == row["dfa_status"] == "solved"]
    speedups = [row["dfa_seconds"] / row["circuit_seconds"] for row in paired]
    geometric = math.exp(sum(math.log(value) for value in speedups) / len(speedups)) if speedups else float("nan")
    print(
        json.dumps(
            {
                "instances": len(rows),
                "circuit_solved": solved["circuit"],
                "dfa_solved": solved["dfa"],
                "co_solved": len(paired),
                "circuit_faster": sum(value > 1 for value in speedups),
                "geometric_mean_speedup": geometric,
            },
            indent=2,
        )
    )


if __name__ == "__main__":
    main()
