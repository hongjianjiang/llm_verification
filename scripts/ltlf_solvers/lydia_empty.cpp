// Emptiness driver over the Lydia library: parse an LTLf formula (one line on
// stdin), build its DFA the way the `lydia` CLI does, and print EMPTY or
// NONEMPTY. Written because the stock CLI needs Syft only for synthesis.
#include <iostream>
#include <sstream>
#include <cctype>
#include <string>
#include "lydia/logic/to_ldlf.hpp"
#include <lydia/dfa/mona_dfa.hpp>
#include <lydia/parser/ltlf/driver.cpp>
#include <lydia/to_dfa/core.hpp>
#include <lydia/to_dfa/strategies/compositional/base.hpp>

extern "C" {
#include <mona/dfa.h>
}

int main() {
  std::stringstream raw_input;
  raw_input << std::cin.rdbuf();
  std::string text = raw_input.str();
  // Lydia's grammar rejects a trailing newline.
  while (!text.empty() && std::isspace(static_cast<unsigned char>(text.back()))) text.pop_back();
  std::stringstream input(text);
  auto driver = std::make_shared<whitemech::lydia::parsers::ltlf::LTLfDriver>();
  driver->parse(input);
  auto ltl = std::static_pointer_cast<const whitemech::lydia::LTLfFormula>(driver->get_result());
  auto ldlf = whitemech::lydia::to_ldlf(*ltl);
  // Same as `lydia --no-empty`: the empty trace is not a model.
  auto context = driver->context;
  auto end = context->makeLdlfEnd();
  auto not_end = context->makeLdlfNot(end);
  ldlf = context->makeLdlfAnd({ldlf, not_end});

  auto strategy = whitemech::lydia::CompositionalStrategy();
  auto translator = whitemech::lydia::Translator(strategy);
  auto dfa = std::dynamic_pointer_cast<whitemech::lydia::mona_dfa>(translator.to_dfa(*ldlf));
  DFA *raw = dfa->get_dfa();
  int n = dfa->get_nb_variables();
  std::vector<unsigned> indices(n);
  for (int i = 0; i < n; ++i) indices[i] = i;
  char *example = dfaMakeExample(raw, 1, n, indices.data());
  std::cout << "states: " << dfa->get_nb_states() << "\n";
  std::cout << (example ? "NONEMPTY" : "EMPTY") << std::endl;
  return 0;
}
