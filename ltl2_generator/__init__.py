"""Reference implementation and benchmark generator for strict past LTL₂."""

from .ast import *
from .parser import parse
from .eval import accepts, eval1, eval2

__version__ = "0.1.0"

