#!/usr/bin/env python3
"""Draw the eight-family scaling curves reported in ICLR Table 2, with the LTLf solver baselines."""

from __future__ import annotations

import json
from pathlib import Path

import matplotlib.pyplot as plt
from matplotlib.lines import Line2D
from matplotlib.ticker import FixedLocator, FuncFormatter, NullFormatter


ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "iclr-circuit" / "figures" / "exp-scaling-comparison.pdf"

# family, parameter, DFA time, DFA failure, circuit time (the DFA columns are no
# longer plotted: Lisa is the automaton-based baseline)
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
    # k=200, 300 added from results/y_n200_n300_20260922 (DFA/old-circuit
    # columns are unused, see note above; left as None).
    ("Y", 200, None, None, None),
    ("Y", 300, None, None, None),
    ("Y", 500, None, "timeout", 0.9),
    ("Y", 8000, None, "timeout", 6.8),
    ("no2a", 5, 0.5, None, 1.0),
    ("no2a", 8, 0.7, None, 0.7),
    ("no2a", 12, 1.5, None, 0.6),
    ("no2a", 16, 12.9, None, 0.6),
    ("no2a", 24, None, "size_limit", 0.9),
    # k=200, 600 added from results/no2a_n200_n600_20260922 (DFA/old-circuit
    # columns are unused, see note above; left as None).
    ("no2a", 200, None, None, None),
    ("no2a", 600, None, None, None),
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
    # n=200, 800 added from results/marks_n200_n800_20260922 (DFA/old-circuit
    # columns are unused, see note above; left as None).
    ("marks", 200, None, None, None),
    ("marks", 600, 8.3, None, 2.4),
    ("marks", 800, None, None, None),
    ("marks", 1200, 29.5, None, 4.1),
    ("marks", 2400, 236.5, None, 7.7),
    ("same", 300, 9.8, None, 1.5),
    # k=400, 500 added from results/same_n400_n500_20260922 (DFA/old-circuit
    # columns are unused, see note above; left as None).
    ("same", 400, None, None, None),
    ("same", 500, None, None, None),
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
    ("Y", 200): (24.6, None),
    ("Y", 300): (122.8, None),
    ("Y", 500): (None, "timeout"),
    ("Y", 8000): (None, "timeout"),
    ("no2a", 5): (0.5, None),
    ("no2a", 8): (0.5, None),
    ("no2a", 12): (0.6, None),
    ("no2a", 16): (0.6, None),
    ("no2a", 24): (0.6, None),
    ("no2a", 200): (0.6, None),
    ("no2a", 600): (5.3, None),
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
    ("marks", 200): (None, "timeout"),
    ("marks", 600): (None, "timeout"),
    ("marks", 800): (None, "timeout"),
    ("marks", 1200): (None, "timeout"),
    ("marks", 2400): (None, "timeout"),
    ("same", 300): (None, "timeout"),
    ("same", 400): (None, "timeout"),
    ("same", 500): (None, "timeout"),
    ("same", 600): (None, "timeout"),
    ("same", 1200): (None, "timeout"),
}

# (family, parameter) -> (time, failure) for Lisa (compositional LTLf -> DFA, forced onto its
# Spot route with -nap 1000; its default MONA route aborts at sigma=12), from
# results/lisa_figure3_900s_20260921. Lisa's own resource failures are drawn as size limits.
# That run skipped a family's larger instances after its first failure; those are
# drawn with the same failure.
LISA = {
    ("dot", 100): (3.0, None),
    ("dot", 400): (31.8, None),
    ("dot", 800): (673.4, None),
    ("dot", 3200): (823.1, None),
    ("Y", 10): (0.4, None),
    ("Y", 12): (0.4, None),
    ("Y", 14): (0.4, None),
    ("Y", 16): (0.4, None),
    ("Y", 20): (0.4, None),
    ("Y", 100): (0.5, None),
    ("Y", 200): (0.8, None),
    ("Y", 300): (1.0, None),
    ("Y", 500): (1.7, None),
    ("Y", 8000): (134.4, None),
    ("no2a", 5): (0.4, None),
    ("no2a", 8): (0.5, None),
    ("no2a", 12): (1.0, None),
    ("no2a", 16): (12.5, None),
    ("no2a", 24): (None, "size_limit"),  # out of memory
    # k=200, 600 from results/no2a_n200_n600_20260922, run independently (not
    # skip-after-failure): both genuinely run to the 900 s deadline rather
    # than hitting Lisa's own resource limit as k=24 does.
    ("no2a", 200): (None, "timeout"),
    ("no2a", 600): (None, "timeout"),
    ("no2a", 1000): (None, "size_limit"),  # skipped after the smaller failure
    ("no2a", 4000): (None, "size_limit"),  # skipped after the smaller failure
    ("no2a", 64000): (None, "size_limit"),  # skipped after the smaller failure
    ("mono", 8): (0.7, None),
    ("mono", 9): (0.9, None),
    ("mono", 10): (1.5, None),
    ("mono", 11): (2.6, None),
    ("mono", 12): (5.2, None),
    ("mono", 16): (None, "size_limit"),
    ("mono", 32): (None, "size_limit"),  # skipped after the smaller failure
    ("mono", 128): (None, "size_limit"),  # skipped after the smaller failure
    ("mono", 256): (None, "size_limit"),  # skipped after the smaller failure
    ("since", 2): (0.4, None),
    ("since", 4): (0.5, None),
    ("since", 6): (0.6, None),
    ("since", 8): (1.3, None),
    ("since", 12): (66.9, None),
    ("since", 16): (None, "size_limit"),
    ("since", 32): (None, "size_limit"),  # skipped after the smaller failure
    ("since", 128): (None, "size_limit"),  # skipped after the smaller failure
    ("since", 256): (None, "size_limit"),  # skipped after the smaller failure
    ("slb", 2): (0.4, None),
    ("slb", 4): (0.5, None),
    ("slb", 6): (0.6, None),
    ("slb", 8): (1.1, None),
    ("slb", 12): (39.5, None),
    ("slb", 16): (None, "size_limit"),
    ("slb", 32): (None, "size_limit"),  # skipped after the smaller failure
    ("slb", 128): (None, "size_limit"),  # skipped after the smaller failure
    ("slb", 256): (None, "size_limit"),  # skipped after the smaller failure
    # n=200, 800 from results/marks_n200_n800_20260922, run independently (not
    # skip-after-failure): 200 hits the same Spot acceptance-set limit as 600;
    # 800 instead runs to the 900 s deadline without erroring first.
    ("marks", 200): (None, "size_limit"),  # Spot: more than 32 acceptance sets
    ("marks", 600): (None, "size_limit"),  # Spot: more than 32 acceptance sets
    ("marks", 800): (None, "timeout"),
    ("marks", 1200): (None, "size_limit"),  # skipped after the smaller failure
    ("marks", 2400): (None, "size_limit"),  # skipped after the smaller failure
    ("same", 300): (31.5, None),
    ("same", 400): (81.3, None),
    ("same", 500): (171.1, None),
    ("same", 600): (349.5, None),
    ("same", 1200): (None, "timeout"),
}

FAMILY_ORDER = ["dot", "Y", "no2a", "mono", "since", "slb", "marks", "same", "lms"]
FAMILY_TITLES = {
    "dot": r"$L_{\mathsf{dot}}$",
    "Y": r"$L_{\mathsf{Y}}$",
    "no2a": r"$L_{\mathsf{no2a}}$",
    "mono": r"$L_{\mathsf{mono}}$",
    "since": r"$L_{\mathsf{since}}$",
    "slb": r"$L_{\mathsf{slb}}$",
    "marks": r"$L_{\mathsf{marks}}$",
    "same": r"$L_{\mathsf{same}}$",
    "lms": r"$L_{\mathsf{lms}}$",
}
ALPHABET_FAMILIES = {"mono", "since", "slb"}
# Labelled x ticks; the densely sampled breakpoint regions would overlap otherwise.
X_TICKS = {
    "dot": [100, 400, 800, 3200],
    "Y": [10, 20, 100, 500, 8000],
    "no2a": [5, 24, 200, 1000, 64000],
    "mono": [8, 16, 32, 128, 256],
    "since": [2, 8, 32, 256],
    "slb": [2, 8, 32, 256],
    "marks": [200, 600, 1200, 2400],
    "same": [300, 600, 1200],
    "lms": [1, 2, 4, 8, 16],
}
TIMEOUT = 900.0

# The circuit series is the direct 2LTL -> circuit construction of Section 4, and
# L_lms (LMS'02 Thm. 3.3, scripts/lms_blocks.py) is the ninth family, all from
# results/figure_direct_20260922: 900 s per run, median of three successful runs.
# The ROWS circuit column (PVWAA-based implementation) is no longer plotted.
FIGURE_RUN = ROOT / "results" / "figure_direct_20260922"
LMS_N = list(range(1, 17))


def _instance_tasks() -> dict[str, tuple[str, int]]:
    tasks = {}
    for line in (ROOT / "results" / "lisa_figure3_900s_20260921" / "instances.tsv").read_text().splitlines():
        task, _, path = line.split("\t")
        family, parameter = task.split("__")
        tasks[Path(path).name] = (family, int(parameter))
    for n in LMS_N:
        tasks[f"lms_blocks__n-{n}.ltl"] = ("lms", n)
    return tasks


def _outcome(record: dict) -> tuple[float | None, str | None]:
    if record["status"] in ("empty", "nonempty"):
        return round(record["wall_seconds"], 1), None
    return None, "timeout" if record["status"] == "timeout" else "size_limit"


def _load(*files: str) -> dict[tuple[str, int], tuple[float | None, str | None]]:
    """Later files supersede earlier ones; a skipped instance inherits the failure
    that caused the skip (the first failure of its family), as for Lisa above."""
    tasks, records = _instance_tasks(), {}
    for name in files:
        for line in (FIGURE_RUN / name).read_text().splitlines():
            record = json.loads(line)
            if record["status"] != "skipped" or record["input"] not in records:
                records[record["input"]] = record
    table, first_failure = {}, {}
    for name, record in sorted(records.items(), key=lambda kv: tasks[kv[0]]):
        family, parameter = tasks[name]
        if record["status"] == "skipped":
            table[(family, parameter)] = (None, first_failure[family])
            continue
        table[(family, parameter)] = _outcome(record)
        if table[(family, parameter)][1] and family not in first_failure:
            first_failure[family] = table[(family, parameter)][1]
    return table


CIRCUIT = _load("direct/records.jsonl")
# n=200, 800 from results/marks_n200_n800_20260922/direct/records.jsonl (same
# direct-realizable route, 900 s / 4 GB heap / median of 3): both nonempty,
# solved in under a second.
CIRCUIT[("marks", 200)] = (0.6, None)
CIRCUIT[("marks", 800)] = (0.8, None)
# k=200, 600 from results/no2a_n200_n600_20260922/direct/records.jsonl (same
# direct-realizable route, 900 s / 4 GB heap / median of 3): both nonempty,
# solved in under a second.
CIRCUIT[("no2a", 200)] = (0.5, None)
CIRCUIT[("no2a", 600)] = (0.5, None)
# k=200, 300 from results/y_n200_n300_20260922/direct/records.jsonl (same
# direct-realizable route, 900 s / 4 GB heap / median of 3): both nonempty,
# solved in under a second.
CIRCUIT[("Y", 200)] = (0.6, None)
CIRCUIT[("Y", 300)] = (0.5, None)
# k=400, 500 from results/same_n400_n500_20260922/direct/records.jsonl (same
# direct-realizable route, 900 s / 4 GB heap / median of 3): both nonempty,
# solved in under a second.
CIRCUIT[("same", 400)] = (0.7, None)
CIRCUIT[("same", 500)] = (0.7, None)
# L_lms baselines first ran under a 120 s budget: Aalta on this run's inputs, Lisa
# (Spot route) from results/lms_blocks_20260922, whose inputs define the same
# language and differ only in how definitions are named. Lisa's n=2 timeout was
# rerun at 900 s on this run's inputs: n=2 solves (median of three) and n=3 times
# out, so the larger instances inherit that timeout.
# Every L_lms Aalta timeout at 120 s was rerun at 900 s: n=11 solves (median of
# three), and n=12 is killed after exhausting memory (drawn as a size limit), so
# the larger instances inherit that failure.
AALTA.update(_load("lms_aalta_120.jsonl", "lms_aalta_900_n11.jsonl", "lms_aalta_900_n12.jsonl"))
LISA.update(_load("../lms_blocks_20260922/lisa.jsonl", "lms_lisa_900.jsonl"))
# Failed runs are drawn above the plot area's data, one band per baseline.
FAILURE_HEIGHT = {"aalta": 1520.0, "lisa": 1960.0}
CIRCUIT_COLOR = "#0072B2"
AALTA_COLOR = "#009E73"
LISA_COLOR = "#E69F00"


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

    fig, axes = plt.subplots(3, 3, figsize=(6.45, 5.6), sharey=True)
    for panel, (family, axis) in enumerate(zip(FAMILY_ORDER, axes.flat)):
        parameters = LMS_N if family == "lms" else [row[1] for row in ROWS if row[0] == family]
        assert all(CIRCUIT[(family, p)][1] is None for p in parameters), "circuit failures are not drawn"

        axis.plot(
            parameters,
            [CIRCUIT[(family, p)][0] for p in parameters],
            color=CIRCUIT_COLOR,
            marker="o",
            markersize=3.8,
            linewidth=1.35,
            zorder=2,
        )
        for key, table, color, marker in (
            ("aalta", AALTA, AALTA_COLOR, "D"),
            ("lisa", LISA, LISA_COLOR, "P"),
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
        axis.set_xlabel("Alphabet size" if family in ALPHABET_FAMILIES
                        else "Propositions $n$" if family == "lms" else "Depth")
        if panel % 3 == 0:
            axis.set_ylabel("Wall time (s)")

    legend = [
        Line2D([0], [0], color=CIRCUIT_COLOR, marker="o", markersize=4, linewidth=1.3, label="Conditional-summary circuit"),
        Line2D([0], [0], color=AALTA_COLOR, marker="D", markersize=3.6, linewidth=1.3, label="Aalta (LTLf SAT)"),
        Line2D([0], [0], color=LISA_COLOR, marker="P", markersize=4, linewidth=1.3, label="Lisa (LTLf-to-DFA)"),
    ]
    fig.legend(
        handles=legend,
        loc="upper center",
        ncol=len(legend),
        frameon=False,
        bbox_to_anchor=(0.5, 1.0),
        handlelength=1.6,
        handletextpad=0.4,
        columnspacing=1.0,
    )
    fig.tight_layout(rect=(0, 0, 1, 0.95), w_pad=1.0, h_pad=1.15)
    fig.savefig(OUT, bbox_inches="tight")
    fig.savefig(OUT.with_suffix(".png"), dpi=220, bbox_inches="tight")
    print(OUT)
    write_tikz(OUT.with_suffix(".tex"))


def _tick_label(value: float) -> str:
    return f"{value / 1000:g}k" if value >= 1000 else f"{value:g}"


def _coordinates(points: list[tuple[float, float]]) -> str:
    return " ".join(f"({x:g},{y:g})" for x, y in points)


def write_tikz(path: Path) -> None:
    """The same figure as a pgfplots groupplot; needs pgfplots with the groupplots library."""
    lines = [
        "% Generated by scripts/plot_exp_scaling.py -- do not edit by hand.",
        "% Needs \\usepackage{pgfplots}, \\usepgfplotslibrary{groupplots}, \\pgfplotsset{compat=1.18}.",
        f"\\definecolor{{scCircuit}}{{HTML}}{{{CIRCUIT_COLOR[1:]}}}",
        f"\\definecolor{{scAalta}}{{HTML}}{{{AALTA_COLOR[1:]}}}",
        f"\\definecolor{{scLisa}}{{HTML}}{{{LISA_COLOR[1:]}}}",
        "\\begin{tikzpicture}",
        "\\begin{groupplot}[",
        "  group style={group size=3 by 3, horizontal sep=0.9em, vertical sep=3.9em,",
        "               yticklabels at=edge left, ylabels at=edge left},",
        "  scale only axis, width=0.27\\linewidth, height=0.105\\linewidth,",
        "  xmode=log, ymode=log, ymin=0.35, ymax=3100,",
        "  ytick={1,10,100,900}, yticklabels={1,10,100,900},",
        "  ylabel={Wall time (s)},",
        "  axis x line*=bottom, axis y line*=left,",
        "  grid=major, major grid style={gray!25, line width=0.3pt},",
        "  minor tick num=0, xminorticks=false, yminorticks=false,",
        "  tick align=outside, major tick length=2pt, tick style={line width=0.4pt},",
        "  tick label style={font=\\scriptsize}, label style={font=\\footnotesize},",
        "  xlabel style={yshift=0.4em}, ylabel style={yshift=-0.3em},",
        "  title style={font=\\footnotesize, at={(0,1)}, anchor=south west, yshift=-0.2em},",
        "  every axis plot/.append style={forget plot},",
        "  circuit/.style={color=scCircuit, mark=*, mark size=1.3pt, line width=0.9pt},",
        "  aalta/.style={color=scAalta, mark=diamond*, mark size=1.6pt, line width=0.8pt},",
        "  lisa/.style={color=scLisa, mark=square*, mark size=1.1pt, line width=0.8pt},",
        "  timeout/.style={only marks, mark=triangle*, mark size=1.8pt},",
        "  sizelimit/.style={only marks, mark=x, mark size=2pt, line width=0.7pt},",
        "]",
    ]
    for panel, family in enumerate(FAMILY_ORDER):
        parameters = LMS_N if family == "lms" else [row[1] for row in ROWS if row[0] == family]
        ticks = X_TICKS[family]
        xlabel = ("Alphabet size" if family in ALPHABET_FAMILIES
                  else "Propositions $n$" if family == "lms" else "Depth")
        lines += [
            f"\\nextgroupplot[title={{({chr(ord('a') + panel)}) {FAMILY_TITLES[family]}}},",
            f"  xmin={min(parameters) / 1.18:.4g}, xmax={max(parameters) * 1.18:.4g},",
            f"  xtick={{{','.join(f'{t:g}' for t in ticks)}}},"
            f" xticklabels={{{','.join(_tick_label(t) for t in ticks)}}},",
            f"  xlabel={{{xlabel}}}]",
            f"\\addplot[circuit] coordinates {{{_coordinates([(p, CIRCUIT[(family, p)][0]) for p in parameters])}}};",
        ]
        for key, table in (("aalta", AALTA), ("lisa", LISA)):
            runs = [(p, *table[(family, p)]) for p in parameters]
            solved = [(p, t) for p, t, _ in runs if t is not None]
            if solved:
                lines.append(f"\\addplot[{key}] coordinates {{{_coordinates(solved)}}};")
            for failure, style in (("timeout", "timeout"), ("size_limit", "sizelimit")):
                failed = [(p, FAILURE_HEIGHT[key]) for p, _, status in runs if status == failure]
                if failed:
                    lines.append(f"\\addplot[{key}, {style}] coordinates {{{_coordinates(failed)}}};")
        if panel == 1:
            lines += [
                "\\addlegendimage{circuit}\\addlegendentry{Conditional-summary circuit}",
                "\\addlegendimage{aalta}\\addlegendentry{Aalta (LTLf SAT)}",
                "\\addlegendimage{lisa}\\addlegendentry{Lisa (LTLf-to-DFA)}",
            ]
    lines += ["\\end{groupplot}", "\\end{tikzpicture}", ""]
    # The legend sits above the middle panel of the first row, i.e. centred on the figure.
    lines.insert(lines.index("]"), "  legend style={at={(0.5,1)}, anchor=south, yshift=2.3em, legend columns=3, draw=none,"
                 " font=\\footnotesize, /tikz/every even column/.append style={column sep=0.8em}},")
    path.write_text("\n".join(lines))
    print(path)


if __name__ == "__main__":
    main()
