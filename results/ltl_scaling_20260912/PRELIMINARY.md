# Preliminary local screening

The publication run is configured for three repetitions and a 120-second
per-route limit on the MPI `cpu20` partition. Before submission, the complete
94-instance manifest was screened locally with one repetition, four concurrent
cells, a 15-second limit, and a 2 GB JVM heap. This screening is only a check of
the parameter grid and plotting code; its timings are not paper results.

The screen decided 90/94 instances with the PVWAA-to-circuit route and 45/94
with the DFA route. The four circuit limits were the largest two
`L_Y` and largest two `L_mono` inputs. The curves expose a gradual scaling
region and a clear DFA breakpoint in every family. The full 120-second MPI run
is required before the figure or aggregate speedups are reported in the paper.
