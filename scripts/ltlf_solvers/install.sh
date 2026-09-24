#!/usr/bin/env bash
# Build the LTLf baselines used by scripts/ltlf_solver_baseline.py into a user
# prefix (default ~/opt/ltlf-solvers/prefix): BLACK, Lisa, and a Lydia
# emptiness driver. No sudo; Homebrew is only read from.
#
# Assumed already installed (Homebrew on macOS): llvm (C++20 clang), cmake,
# z3, graphviz, spot (>= 2.9, for Lisa), mona (system copy, called by Lisa).
# Aalta (`aaltaf`) is built separately, see results/aalta_baseline_20260918/AUDIT.md.
#
# Local changes, all needed for correct emptiness answers (see README.md):
#   lisa.patch        print EMPTY/NONEMPTY; fix final states of a merged
#                     end-of-trace sink; stop echoing the formula into MONA
#                     comments (overflows MONA's YYLMAX)
#   bddx_shim.h       rename Spot/BuDDy's `typedef int BDD`, which clashes
#                     with CUDD's C++ class of the same name
#   lydia_empty.cpp   emptiness driver over the Lydia library (the stock CLI
#                     requires Syft, which is only used for synthesis)
set -euo pipefail

ROOT=${LTLF_SOLVERS:-$HOME/opt/ltlf-solvers}
P=$ROOT/prefix
S=$ROOT/src
HERE=$(cd "$(dirname "$0")" && pwd)
LLVM=$(brew --prefix llvm)
Z3=$(brew --prefix z3)
GV=$(brew --prefix graphviz)
export CC=$LLVM/bin/clang CXX=$LLVM/bin/clang++
export PATH=$P/bin:$PATH
RPATH="-L$LLVM/lib/c++ -Wl,-rpath,$LLVM/lib/c++"
mkdir -p "$P" "$S" "$ROOT/shim"

fetch() {  # fetch <dir> <url> <commit>
  [ -d "$S/$1" ] || git clone -q "$2" "$S/$1"
  git -C "$S/$1" checkout -q "$3"
  git -C "$S/$1" submodule update -q --init --recursive
}

fetch fmt           https://github.com/fmtlib/fmt.git           6d71f74
fetch json          https://github.com/nlohmann/json.git        c41152e
fetch hopscotch-map https://github.com/Tessil/hopscotch-map.git 2bdc14c
fetch black         https://github.com/black-sat/black.git      b3d373a
fetch cudd          https://github.com/KavrakiLab/cudd.git      5b9f842
fetch lisa          https://github.com/vardigroup/lisa.git      2501034
fetch MONA          https://github.com/whitemech/MONA.git       f00d139
fetch lydia         https://github.com/whitemech/lydia.git      f036d62

# Header-only / small dependencies of BLACK.
for d in fmt json hopscotch-map; do
  cmake -S "$S/$d" -B "$S/$d/build" -DCMAKE_INSTALL_PREFIX="$P" -DCMAKE_BUILD_TYPE=Release \
    -DFMT_TEST=OFF -DJSON_BuildTests=OFF -DCMAKE_POSITION_INDEPENDENT_CODE=ON -Wno-dev >/dev/null
  cmake --build "$S/$d/build" -j8 >/dev/null && cmake --install "$S/$d/build" >/dev/null
done

# BLACK, Z3 backend only.
cmake -S "$S/black" -B "$S/black/build" -DCMAKE_INSTALL_PREFIX="$P" -DCMAKE_PREFIX_PATH="$P" \
  -DCMAKE_BUILD_TYPE=Release -DZ3_LIBRARY="$Z3/lib/libz3.dylib" -DZ3_INCLUDE_DIR="$Z3/include" \
  -DENABLE_CMSAT=NO -DENABLE_MATHSAT=NO -DENABLE_CVC5=NO -DENABLE_MINISAT=NO -DBLACK_TESTS=NO \
  -DCMAKE_EXE_LINKER_FLAGS="$RPATH" -Wno-dev >/dev/null
cmake --build "$S/black/build" -j8 >/dev/null && cmake --install "$S/black/build" >/dev/null

# CUDD (KavrakiLab fork: C++ classes live in namespace CUDD, as Lisa expects).
(cd "$S/cudd" && (autoreconf -fi >/dev/null 2>&1 || true) &&
  ./configure --prefix="$P" --enable-obj --enable-dddmp --enable-shared=no CFLAGS=-O2 CXXFLAGS=-O2 >/dev/null &&
  make -j8 >/dev/null && make install >/dev/null)

# Lisa, patched, against Homebrew Spot/BuDDy and the system `mona`.
cp "$HERE/bddx_shim.h" "$ROOT/shim/bddx.h"
git -C "$S/lisa" checkout -q -- src
git -C "$S/lisa" apply "$HERE/lisa.patch"
(cd "$S/lisa/src" && $CXX -std=c++17 -O3 -D_LIBCPP_ENABLE_CXX17_REMOVED_UNARY_BINARY_FUNCTION \
  -I"$ROOT/shim" -I"$P/include" -I/usr/local/include \
  lisa.cc dfwavar.cc dfwa.cc spotutil.cc ltlf2fol.cc mona.cc dfwamin.cc synt.cc strategy.cc dfwamin2.cc \
  -o "$P/bin/lisa" -L"$P/lib" -L/usr/local/lib -lspot -lbddx -lcudd $RPATH -Wl,-rpath,/usr/local/lib)

# Bison >= 3 and a flex whose FlexLexer.h matches its generator (Apple's do not).
if [ ! -x "$P/bin/bison" ]; then
  (cd "$S" && curl -sSfLO https://ftp.gnu.org/gnu/bison/bison-3.8.2.tar.xz &&
    echo "9bba0214ccf7f1079c5d59210045227bcf619519840ebfa80cd3849cff5a5bf2  bison-3.8.2.tar.xz" | shasum -a 256 -c - &&
    tar xf bison-3.8.2.tar.xz && cd bison-3.8.2 && ./configure --prefix="$P" >/dev/null && make -j8 >/dev/null && make install >/dev/null)
fi
if [ ! -x "$P/bin/flex" ]; then
  (cd "$S" && curl -sSfLO https://github.com/westes/flex/releases/download/v2.6.4/flex-2.6.4.tar.gz &&
    echo "e87aae032bf07c26f85ac0ed3250998c37621d95f8bd748b31f15b33c45ee995  flex-2.6.4.tar.gz" | shasum -a 256 -c - &&
    tar xzf flex-2.6.4.tar.gz && cd flex-2.6.4 && ./configure --prefix="$P" --disable-nls >/dev/null && make -j8 >/dev/null && make install >/dev/null)
fi

# MONA (whitemech fork) with its internal headers, which Lydia includes.
(cd "$S/MONA" && ./configure --prefix="$P" CFLAGS=-O2 CXXFLAGS=-O2 >/dev/null && make -j8 >/dev/null && make install >/dev/null)
for d in BDD DFA GTA Mem; do cp -n "$S/MONA/$d"/*.h "$P/include/mona/" 2>/dev/null || true; done

# Lydia library (no Syft) and the emptiness driver.
rm -f "$S"/lydia/lib/include/lydia/parser/*/lexer.yy.cc
cmake -S "$S/lydia" -B "$S/lydia/build" -DCMAKE_BUILD_TYPE=Release -DWITH_SYFT=OFF \
  -DLYDIA_ENABLE_TESTS=OFF -DLYDIA_ENABLE_BENCHMARK=OFF \
  -DBISON_EXECUTABLE="$P/bin/bison" -DFLEX_EXECUTABLE="$P/bin/flex" \
  -DFLEX_INCLUDE_DIR="$P/include" -DFL_LIBRARY="$P/lib/libfl.a" \
  -DCUDD_USE_STATIC_LIBS=ON -DCUDD_INCLUDE_DIRS="$P/include" -DCUDD_LIBRARIES="$P/lib/libcudd.a" \
  -DMONA_INCLUDE_DIRS="$P/include" -DMONA_BDD_LIBRARY="$P/lib/libmonabdd.a" -DMONA_DFA_LIBRARY="$P/lib/libmonadfa.a" \
  -DMONA_GTA_LIBRARY="$P/lib/libmonagta.a" -DMONA_MEM_LIBRARY="$P/lib/libmonamem.a" \
  -DCMAKE_CXX_FLAGS="-idirafter $GV/include" -DCMAKE_EXE_LINKER_FLAGS="$RPATH" -Wno-dev >/dev/null
cmake --build "$S/lydia/build" -j8 >/dev/null
L=$S/lydia
$CXX -std=c++17 -O2 -I"$L/lib/include" -I"$L/third_party/spdlog/include" -I"$L/third_party/cppitertools" \
  -I"$P/include" -idirafter "$GV/include" "$HERE/lydia_empty.cpp" -o "$P/bin/lydia-empty" \
  "$L/build/lib/liblydia.a" "$P/lib/libcudd.a" "$P/lib/libmonadfa.a" "$P/lib/libmonabdd.a" "$P/lib/libmonamem.a" \
  "$P/lib/libfl.a" -L"$GV/lib" -lgvc -lcgraph -lcdt $RPATH -Wl,-rpath,"$GV/lib"

echo "installed: $(ls "$P/bin" | grep -E '^(black|lisa|lydia-empty)$' | tr '\n' ' ')"
