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
# Intermediate rows come from results/table2_intermediate_20260917. Every
# timeout was rerun at 900 s (results/timeout900_20260918), which replaces it.
ROWS = [
    ("dot", 100, 1.1, None, 1.0),
    ("dot", 400, 3.7, None, 1.0),
    ("dot", 800, 13.7, None, 2.9),
    ("dot", 3200, 410.0, None, 9.4),
    ("Y", 10, 0.7, None, 0.6),
    ("Y", 12, 1.2, None, 0.6),
    ("Y", 14, 2.1, None, 0.8),
    ("Y", 16, 10.8, None, 0.9),
    ("Y", 20, 228.2, None, 0.7),
    ("Y", 100, None, "timeout", 1.1),
    ("Y", 500, None, "timeout", 0.9),
    ("Y", 8000, None, "timeout", 6.8),
    ("no2a", 5, 0.5, None, 1.0),
    ("no2a", 8, 0.7, None, 0.7),
    ("no2a", 12, 1.5, None, 0.6),
    ("no2a", 16, 12.9, None, 0.6),
    ("no2a", 24, None, "size_limit", 0.9),
    ("no2a", 1000, None, "size_limit", 1.5),
    ("no2a", 4000, None, "size_limit", 1.2),
    ("no2a", 64000, None, "timeout", 36.0),
    ("mono", 8, 1.0, None, 0.6),
    ("mono", 9, 2.1, None, 0.8),
    ("mono", 10, 5.0, None, 0.7),
    ("mono", 11, 15.3, None, 0.6),
    ("mono", 12, 68.0, None, 1.3),
    ("mono", 16, None, "size_limit", 0.6),
    ("mono", 32, None, "size_limit", 0.9),
    ("mono", 128, None, "size_limit", 9.2),
    ("mono", 256, None, "size_limit", 86.7),
    ("since", 2, 0.7, None, 0.6),
    ("since", 4, 0.7, None, 0.6),
    ("since", 6, 1.6, None, 0.6),
    ("since", 8, 21.4, None, 0.6),
    ("since", 12, None, "timeout", 0.8),
    ("since", 16, None, "size_limit", 0.8),
    ("since", 32, None, "size_limit", 0.7),
    ("since", 128, None, "size_limit", 1.3),
    ("since", 256, None, "size_limit", 2.8),
    ("slb", 2, 0.7, None, 1.3),
    ("slb", 4, 0.9, None, 0.8),
    ("slb", 6, 1.7, None, 0.9),
    ("slb", 8, 17.8, None, 0.6),
    ("slb", 12, None, "timeout", 1.5),
    ("slb", 16, None, "size_limit", 1.2),
    ("slb", 32, None, "size_limit", 0.7),
    ("slb", 128, None, "size_limit", 1.3),
    ("slb", 256, None, "size_limit", 2.7),
    ("marks", 600, 8.3, None, 2.4),
    ("marks", 1200, 29.5, None, 4.1),
    ("marks", 2400, 236.5, None, 7.7),
    ("same", 300, 9.8, None, 1.5),
    ("same", 600, 36.4, None, 2.1),
    ("same", 1200, 381.6, None, 3.9),
]

# (family, parameter) -> (Aalta time, Aalta failure), from
# results/aalta_baseline_20260918 (the parser-depth rerun supersedes records.jsonl).
AALTA = {
    ("dot", 100): (426.8, None),
    ("dot", 400): (None, "timeout"),
    ("dot", 800): (None, "timeout"),
    ("dot", 3200): (None, "timeout"),
    ("Y", 10): (0.4, None),
    ("Y", 12): (0.5, None),
    ("Y", 14): (0.5, None),
    ("Y", 16): (0.6, None),
    ("Y", 20): (0.9, None),
    ("Y", 100): (5.2, None),
    ("Y", 500): (None, "timeout"),
    ("Y", 8000): (None, "timeout"),
    ("no2a", 5): (0.5, None),
    ("no2a", 8): (0.5, None),
    ("no2a", 12): (0.6, None),
    ("no2a", 16): (0.6, None),
    ("no2a", 24): (0.6, None),
    ("no2a", 1000): (54.2, None),
    ("no2a", 4000): (None, "timeout"),
    ("no2a", 64000): (None, "timeout"),
    ("mono", 8): (1.1, None),
    ("mono", 9): (2.1, None),
    ("mono", 10): (4.6, None),
    ("mono", 11): (8.8, None),
    ("mono", 12): (26.0, None),
    ("mono", 16): (None, "size_limit"),
    ("mono", 32): (None, "size_limit"),
    ("mono", 128): (None, "size_limit"),
    ("mono", 256): (None, "size_limit"),
    ("since", 2): (0.5, None),
    ("since", 4): (0.5, None),
    ("since", 6): (0.8, None),
    ("since", 8): (2.0, None),
    ("since", 12): (133.4, None),
    ("since", 16): (None, "size_limit"),
    ("since", 32): (None, "size_limit"),
    ("since", 128): (None, "size_limit"),
    ("since", 256): (None, "size_limit"),
    ("slb", 2): (0.4, None),
    ("slb", 4): (0.5, None),
    ("slb", 6): (0.7, None),
    ("slb", 8): (1.7, None),
    ("slb", 12): (113.7, None),
    ("slb", 16): (None, "size_limit"),
    ("slb", 32): (None, "size_limit"),
    ("slb", 128): (None, "size_limit"),
    ("slb", 256): (None, "size_limit"),
    ("marks", 600): (None, "timeout"),
    ("marks", 1200): (None, "timeout"),
    ("marks", 2400): (None, "timeout"),
    ("same", 300): (None, "timeout"),
    ("same", 600): (None, "timeout"),
    ("same", 1200): (None, "timeout"),
}

# (family, parameter) -> (time, failure) for one-variable LTL checked as a
# sequential circuit, from results/circuit_one_variable_20260918 (the
# stack-overflow rerun supersedes the first two record files).
ONE_VARIABLE = {
    ("dot", 100): (1.0, None),
    ("dot", 400): (2.2, None),
    ("dot", 800): (3.5, None),
    ("dot", 3200): (78.5, None),
    ("Y", 10): (1.0, None),
    ("Y", 12): (1.0, None),
    ("Y", 14): (1.0, None),
    ("Y", 16): (0.9, None),
    ("Y", 20): (0.8, None),
    ("Y", 100): (0.8, None),
    ("Y", 500): (1.0, None),
    ("Y", 8000): (14.5, None),
    ("no2a", 5): (0.7, None),
    ("no2a", 8): (0.7, None),
    ("no2a", 12): (0.7, None),
    ("no2a", 16): (0.6, None),
    ("no2a", 24): (0.7, None),
    ("no2a", 1000): (1.1, None),
    ("no2a", 4000): (3.5, None),
    ("no2a", 64000): (None, "timeout"),
    ("mono", 8): (1.2, None),
    ("mono", 9): (2.1, None),
    ("mono", 10): (5.0, None),
    ("mono", 11): (17.0, None),
    ("mono", 12): (60.0, None),
    ("mono", 16): (None, "size_limit"),
    ("mono", 32): (None, "size_limit"),
    ("mono", 128): (None, "size_limit"),
    ("mono", 256): (None, "size_limit"),
    ("since", 2): (0.6, None),
    ("since", 4): (0.6, None),
    ("since", 6): (0.7, None),
    ("since", 8): (1.1, None),
    ("since", 12): (None, "timeout"),
    ("since", 16): (None, "size_limit"),
    ("since", 32): (None, "size_limit"),
    ("since", 128): (None, "size_limit"),
    ("since", 256): (None, "size_limit"),
    ("slb", 2): (0.6, None),
    ("slb", 4): (0.6, None),
    ("slb", 6): (0.7, None),
    ("slb", 8): (1.0, None),
    ("slb", 12): (None, "timeout"),
    ("slb", 16): (None, "size_limit"),
    ("slb", 32): (None, "size_limit"),
    ("slb", 128): (None, "size_limit"),
    ("slb", 256): (None, "size_limit"),
    ("marks", 600): (4.0, None),
    ("marks", 1200): (9.2, None),
    ("marks", 2400): (41.8, None),
    ("same", 300): (1.6, None),
    ("same", 600): (4.8, None),
    ("same", 1200): (11.5, None),
}

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
# Labelled x ticks; the densely sampled breakpoint regions would overlap otherwise.
X_TICKS = {
    "dot": [100, 400, 800, 3200],
    "Y": [10, 20, 100, 500, 8000],
    "no2a": [5, 24, 1000, 64000],
    "mono": [8, 16, 32, 128, 256],
    "since": [2, 8, 32, 256],
    "slb": [2, 8, 32, 256],
    "marks": [600, 1200, 2400],
    "same": [300, 600, 1200],
}
TIMEOUT = 900.0
# Failed runs are drawn above the plot area's data, one band per baseline.
FAILURE_HEIGHT = {"dfa": 1250.0, "aalta": 1720.0, "one_variable": 2370.0}
CIRCUIT_COLOR = "#0072B2"
DFA_COLOR = "#D55E00"
AALTA_COLOR = "#009E73"
ONE_VARIABLE_COLOR = "#CC79A7"


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
                    [FAILURE_HEIGHT["dfa"]] * len(failed),
                    color=DFA_COLOR,
                    marker=marker,
                    s=24,
                    linewidths=1.0,
                    zorder=3,
                )

        for key, table, color, marker in (
            ("aalta", AALTA, AALTA_COLOR, "D"),
            ("one_variable", ONE_VARIABLE, ONE_VARIABLE_COLOR, "v"),
        ):
            runs = [(parameter, *table[(family, parameter)]) for parameter in parameters]
            solved = [(parameter, time) for parameter, time, _ in runs if time is not None]
            axis.plot(
                [parameter for parameter, _ in solved],
                [time for _, time in solved],
                color=color,
                marker=marker,
                markersize=3.4,
                linewidth=1.25,
                zorder=2,
            )
            for failure, failure_marker in (("timeout", "^"), ("size_limit", "x")):
                failed = [parameter for parameter, _, status in runs if status == failure]
                if failed:
                    axis.scatter(
                        failed,
                        [FAILURE_HEIGHT[key]] * len(failed),
                        color=color,
                        marker=failure_marker,
                        s=24,
                        linewidths=1.0,
                        zorder=3,
                    )

        axis.set_title(f"({chr(ord('a') + panel)}) {FAMILY_TITLES[family]}", loc="left")
        axis.set_xscale("log")
        axis.set_yscale("log")
        axis.set_xlim(min(parameters) / 1.18, max(parameters) * 1.18)
        axis.set_ylim(0.35, 3100)
        axis.xaxis.set_major_locator(FixedLocator(X_TICKS[family]))
        axis.xaxis.set_major_formatter(FuncFormatter(scalar_tick))
        axis.xaxis.set_minor_formatter(NullFormatter())
        axis.yaxis.set_major_locator(FixedLocator([1, 10, 100, 900]))
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
        Line2D([0], [0], color=AALTA_COLOR, marker="D", markersize=3.6, linewidth=1.3, label="Aalta (LTLf SAT)"),
        Line2D([0], [0], color=ONE_VARIABLE_COLOR, marker="v", markersize=3.8, linewidth=1.3, label="1-var LTL-to-circuit"),
        Line2D([0], [0], color="0.35", marker="^", linestyle="None", markersize=4, label="timeout"),
        Line2D([0], [0], color="0.35", marker="x", linestyle="None", markersize=4, label="size limit"),
    ]
    fig.legend(handles=legend, loc="upper center", ncol=3, frameon=False, bbox_to_anchor=(0.5, 1.0))
    fig.tight_layout(rect=(0, 0, 1, 0.87), w_pad=1.0, h_pad=1.15)
    fig.savefig(OUT, bbox_inches="tight")
    fig.savefig(OUT.with_suffix(".png"), dpi=220, bbox_inches="tight")
    print(OUT)


if __name__ == "__main__":
    main()
