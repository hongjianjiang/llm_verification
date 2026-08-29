"""Registered benchmark-family generators."""
from .core import core_benchmarks
from .literature import literature_benchmarks

REGISTRY = {"core": core_benchmarks, "literature": literature_benchmarks}
