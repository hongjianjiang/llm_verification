#!/usr/bin/env python3
"""Plot per-instance UHAT training, equivalence, and emptiness times."""

from __future__ import annotations

import csv
import json
from collections import defaultdict
from pathlib import Path

import matplotlib.pyplot as plt
from matplotlib.lines import Line2D
from matplotlib.ticker import FixedLocator, FuncFormatter, NullFormatter


ROOT = Path(__file__).resolve().parents[1]
RESULTS = ROOT / "results" / "uhat_figure2"
OUT = ROOT / "iclr" / "figures" / "uhat-instance-times.pdf"

FAMILY_ORDER = ["dot", "y", "no2a", "mono_word", "since", "slb", "marks", "same"]
FAMILY_TITLES = {
    "dot": r"$L_{\mathsf{dot}}$",
    "y": r"$L_{\mathsf{Y}}$",
    "no2a": r"$L_{\mathsf{no2a}}$",
    "mono_word": r"$L_{\mathsf{mono}}$",
    "since": r"$L_{\mathsf{since}}$",
    "slb": r"$L_{\mathsf{slb}}$",
    "marks": r"$L_{\mathsf{marks}}$",
    "same": r"$L_{\mathsf{same}}$",
}
ALPHABET_FAMILIES = {"mono_word", "since", "slb"}

TRAIN_COLOR = "#0072B2"
EQUIV_COLOR = "#D55E00"
EMPTY_COLOR = "#009E73"


def load_rows() -> dict[str, list[dict]]:
    selected = [
        json.loads(line)
        for line in (RESULTS / "verification" / "selected.jsonl").read_text().splitlines()
    ]
    equivalence = {
        row["task"]: row
        for row in csv.DictReader((RESULTS / "verification" / "equivalence.csv").open())
    }
    emptiness = {
        row["task"]: row
        for row in csv.DictReader((RESULTS / "verification" / "emptiness.csv").open())
    }
    runs = {
        path.stem: json.loads(path.read_text())
        for path in (RESULTS / "runs").glob("*.json")
    }

    rows: dict[str, list[dict]] = defaultdict(list)
    for selection in selected:
        task = selection["task"]
        family_part, parameter_part = task.removeprefix("figure2_").split("__")
        parameter = int(parameter_part.split("-", 1)[1])
        run = runs[selection["id"]]
        equiv = equivalence[task]
        empty = emptiness[task]
        rows[family_part].append(
            {
                "parameter": parameter,
                "best_effort": selection["selection"] == "best-effort",
                "training": float(run["seconds"]),
                "equivalence": float(equiv["verification_seconds"]),
                "equivalent": equiv["formal_verdict"] == "equivalent",
                "emptiness": float(empty["seconds"]),
                "nonempty": empty["status"] == "nonempty",
            }
        )
    for family_rows in rows.values():
        family_rows.sort(key=lambda row: row["parameter"])
    return rows


def time_tick(value: float, _position: float) -> str:
    if value >= 1000:
        return f"{value / 1000:g}k"
    return f"{value:g}"


def main() -> None:
    rows = load_rows()
    OUT.parent.mkdir(parents=True, exist_ok=True)
    plt.rcParams.update(
        {
            "font.family": "serif",
            "font.serif": ["Times New Roman", "Times", "STIXGeneral"],
            "mathtext.fontset": "stix",
            "font.size": 8,
            "axes.labelsize": 8,
            "axes.titlesize": 9,
            "legend.fontsize": 7.5,
            "xtick.labelsize": 7,
            "ytick.labelsize": 7,
            "pdf.fonttype": 42,
            "ps.fonttype": 42,
        }
    )

    fig, axes = plt.subplots(2, 4, figsize=(6.45, 3.7), sharey=True)
    for panel, (family, axis) in enumerate(zip(FAMILY_ORDER, axes.flat)):
        family_rows = rows[family]
        positions = list(range(len(family_rows)))

        axis.plot(
            positions,
            [row["training"] for row in family_rows],
            color=TRAIN_COLOR,
            marker="o",
            markersize=4.0,
            linewidth=1.35,
            zorder=3,
        )
        for key, verdict_key, color, marker in (
            ("equivalence", "equivalent", EQUIV_COLOR, "s"),
            ("emptiness", "nonempty", EMPTY_COLOR, "D"),
        ):
            axis.plot(
                positions,
                [row[key] for row in family_rows],
                color=color,
                linewidth=1.25,
                zorder=2,
            )
            for position, row in zip(positions, family_rows):
                axis.scatter(
                    position,
                    row[key],
                    marker=marker,
                    s=20,
                    facecolor=color if row[verdict_key] else "white",
                    edgecolor=color,
                    linewidth=1.0,
                    zorder=4,
                )

        tick_labels = [
            rf"${row['parameter']}^\dagger$" if row["best_effort"] else str(row["parameter"])
            for row in family_rows
        ]
        axis.set_xticks(positions, tick_labels)
        axis.set_xlim(-0.35, len(positions) - 0.65)
        axis.set_yscale("log")
        axis.set_ylim(0.5, 4000)
        axis.yaxis.set_major_locator(FixedLocator([1, 10, 100, 1000]))
        axis.yaxis.set_major_formatter(FuncFormatter(time_tick))
        axis.yaxis.set_minor_formatter(NullFormatter())
        axis.grid(axis="y", which="major", color="0.87", linewidth=0.5)
        axis.spines["top"].set_visible(False)
        axis.spines["right"].set_visible(False)
        axis.tick_params(direction="out", length=2.4, width=0.6)
        axis.set_title(f"({chr(ord('a') + panel)}) {FAMILY_TITLES[family]}", loc="left")
        axis.set_xlabel(r"$\sigma$" if family in ALPHABET_FAMILIES else r"$k$")
        if panel % 4 == 0:
            axis.set_ylabel("Wall time (s)")

    legend = [
        Line2D([0], [0], color=TRAIN_COLOR, marker="o", markersize=4, linewidth=1.3, label="training"),
        Line2D([0], [0], color=EQUIV_COLOR, marker="s", markersize=4, linewidth=1.3, label="equivalence"),
        Line2D([0], [0], color=EMPTY_COLOR, marker="D", markersize=3.8, linewidth=1.3, label="emptiness"),
        Line2D([0], [0], color=EQUIV_COLOR, marker="s", markerfacecolor="white", markersize=4,
               linewidth=0, label="inequivalent"),
        Line2D([0], [0], color=EMPTY_COLOR, marker="D", markerfacecolor="white", markersize=3.8,
               linewidth=0, label="empty"),
        Line2D([0], [0], color="none", marker=r"$\dagger$", markeredgecolor="0.25",
               markersize=6, linewidth=0, label="best-effort"),
    ]
    fig.legend(handles=legend, loc="upper center", ncol=3, frameon=False, bbox_to_anchor=(0.5, 1.0))
    fig.tight_layout(rect=(0, 0, 1, 0.84), w_pad=1.0, h_pad=1.05)
    fig.savefig(OUT, bbox_inches="tight")
    fig.savefig(OUT.with_suffix(".png"), dpi=240, bbox_inches="tight")
    print(OUT)


if __name__ == "__main__":
    main()
