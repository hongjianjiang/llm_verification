#!/usr/bin/env python3
"""Draw the eight-family scaling curves reported in ICLR Table 2."""

from __future__ import annotations

from pathlib import Path

import matplotlib.pyplot as plt
from matplotlib.lines import Line2D
from matplotlib.ticker import FixedLocator, FuncFormatter, NullFormatter


ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "iclr" / "figures" / "exp-scaling-comparison.pdf"

# family, parameter, DFA time, DFA failure, circuit time
ROWS = [
    ("dot", 100, 1.1, None, 1.0),
    ("dot", 400, 3.7, None, 1.0),
    ("dot", 800, 13.7, None, 2.9),
    ("dot", 3200, None, "timeout", 9.4),
    ("Y", 10, 0.7, None, 0.6),
    ("Y", 100, None, "timeout", 1.1),
    ("Y", 500, None, "timeout", 0.9),
    ("Y", 8000, None, "timeout", 6.8),
    ("no2a", 5, 0.5, None, 1.0),
    ("no2a", 1000, None, "timeout", 1.5),
    ("no2a", 4000, None, "timeout", 1.2),
    ("no2a", 64000, None, "timeout", 36.0),
    ("mono", 8, 1.0, None, 0.6),
    ("mono", 32, None, "size_limit", 0.9),
    ("mono", 128, None, "size_limit", 9.2),
    ("mono", 256, None, "size_limit", 86.7),
    ("since", 8, 21.4, None, 0.6),
    ("since", 32, None, "size_limit", 0.7),
    ("since", 128, None, "size_limit", 1.3),
    ("since", 256, None, "size_limit", 2.8),
    ("slb", 8, 17.8, None, 0.6),
    ("slb", 32, None, "size_limit", 0.7),
    ("slb", 128, None, "size_limit", 1.3),
    ("slb", 256, None, "size_limit", 2.7),
    ("marks", 600, 8.3, None, 2.4),
    ("marks", 1200, 29.5, None, 4.1),
    ("marks", 2400, None, "timeout", 7.7),
    ("same", 300, 9.8, None, 1.5),
    ("same", 600, 36.4, None, 2.1),
    ("same", 1200, None, "timeout", 3.9),
]

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
ALPHABET_FAMILIES = {"mono", "since", "slb"}
TIMEOUT = 120.0
CIRCUIT_COLOR = "#0072B2"
DFA_COLOR = "#D55E00"


def scalar_tick(value: float, _position: float) -> str:
    if value >= 1000:
        return f"{value / 1000:g}k"
    return f"{value:g}"


def main() -> None:
    OUT.parent.mkdir(parents=True, exist_ok=True)
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

    fig, axes = plt.subplots(2, 4, figsize=(6.45, 4.0), sharey=True)
    for panel, (family, axis) in enumerate(zip(FAMILY_ORDER, axes.flat)):
        rows = [row for row in ROWS if row[0] == family]
        parameters = [row[1] for row in rows]
        dfa_solved = [row for row in rows if row[2] is not None]

        axis.plot(
            parameters,
            [row[4] for row in rows],
            color=CIRCUIT_COLOR,
            marker="o",
            markersize=3.8,
            linewidth=1.35,
            zorder=2,
        )
        axis.plot(
            [row[1] for row in dfa_solved],
            [row[2] for row in dfa_solved],
            color=DFA_COLOR,
            marker="s",
            markersize=3.8,
            linewidth=1.25,
            zorder=2,
        )
        for failure, marker in (("timeout", "^"), ("size_limit", "x")):
            failed = [row for row in rows if row[3] == failure]
            if failed:
                axis.scatter(
                    [row[1] for row in failed],
                    [TIMEOUT] * len(failed),
                    color=DFA_COLOR,
                    marker=marker,
                    s=24,
                    linewidths=1.0,
                    zorder=3,
                )

        axis.text(
            0.96,
            0.05,
            f"circuit {len(rows)}/{len(rows)}; DFA {len(dfa_solved)}/{len(rows)}",
            transform=axis.transAxes,
            ha="right",
            va="bottom",
            fontsize=6.8,
            color="0.28",
        )
        axis.set_title(f"({chr(ord('a') + panel)}) {FAMILY_TITLES[family]}", loc="left")
        axis.set_xscale("log")
        axis.set_yscale("log")
        axis.set_xlim(min(parameters) / 1.18, max(parameters) * 1.18)
        axis.set_ylim(0.35, 180)
        axis.xaxis.set_major_locator(FixedLocator(parameters))
        axis.xaxis.set_major_formatter(FuncFormatter(scalar_tick))
        axis.xaxis.set_minor_formatter(NullFormatter())
        axis.yaxis.set_major_locator(FixedLocator([0.5, 1, 10, 120]))
        axis.yaxis.set_major_formatter(FuncFormatter(scalar_tick))
        axis.yaxis.set_minor_formatter(NullFormatter())
        axis.grid(axis="both", which="major", color="0.87", linewidth=0.5)
        axis.spines["top"].set_visible(False)
        axis.spines["right"].set_visible(False)
        axis.tick_params(direction="out", length=2.4, width=0.6)
        axis.set_xlabel("Alphabet size" if family in ALPHABET_FAMILIES else "Depth")
        if panel % 4 == 0:
            axis.set_ylabel("Wall time (s)")

    legend = [
        Line2D([0], [0], color=CIRCUIT_COLOR, marker="o", markersize=4, linewidth=1.3, label="PVWAA-to-circuit"),
        Line2D([0], [0], color=DFA_COLOR, marker="s", markersize=4, linewidth=1.3, label="DFA baseline"),
        Line2D([0], [0], color=DFA_COLOR, marker="^", linestyle="None", markersize=4, label="timeout"),
        Line2D([0], [0], color=DFA_COLOR, marker="x", linestyle="None", markersize=4, label="size limit"),
    ]
    fig.legend(handles=legend, loc="upper center", ncol=4, frameon=False, bbox_to_anchor=(0.5, 0.995))
    fig.tight_layout(rect=(0, 0, 1, 0.90), w_pad=1.0, h_pad=1.15)
    fig.savefig(OUT, bbox_inches="tight")
    fig.savefig(OUT.with_suffix(".png"), dpi=220, bbox_inches="tight")
    print(OUT)


if __name__ == "__main__":
    main()
