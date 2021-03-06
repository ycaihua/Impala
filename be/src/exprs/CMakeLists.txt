# Copyright 2012 Cloudera Inc.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.


# where to put generated libraries
set(LIBRARY_OUTPUT_PATH "${BUILD_OUTPUT_ROOT_DIRECTORY}/exprs")

# where to put generated binaries
set(EXECUTABLE_OUTPUT_PATH "${BUILD_OUTPUT_ROOT_DIRECTORY}/exprs")

add_library(Exprs
  agg-fn-evaluator.cc
  aggregate-functions.cc
  anyval-util.cc
  arithmetic-expr.cc
  binary-predicate.cc
  bool-literal.cc
  case-expr.cc
  cast-expr.cc
  char-literal.cc
  compound-predicate.cc
  conditional-functions.cc
  date-literal.cc
  decimal-functions.cc
  decimal-operators.cc
  expr.cc
  float-literal.cc
  function-call.cc
  hive-udf-call.cc
  in-predicate.cc
  int-literal.cc
  is-null-predicate.cc
  like-predicate.cc
  math-functions.cc
  null-literal.cc
  slot-ref.cc
  string-literal.cc
  string-functions.cc
  timestamp-functions.cc
  timestamp-literal.cc
  timezone_db.cc
  tuple-is-null-predicate.cc
  native-udf-expr.cc
  udf-builtins.cc
  utility-functions.cc
)

add_executable(expr-benchmark expr-benchmark.cc)
target_link_libraries(expr-benchmark ${IMPALA_TEST_LINK_LIBS})

ADD_BE_TEST(expr-test)
