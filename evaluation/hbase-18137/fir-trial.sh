#!/usr/bin/env bash

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"

output=$1
shift
p1=$1
shift
p2=$1
shift
p3=$1
shift
p4=$1
shift

if [ "$p4" = "_" ]; then
  p4=
fi

case_name=hbase-18137
hb_dir="${SCRIPT_DIR}/../../systems/$case_name"
classes_dir="$HOME/tmp/bytecode/${case_name}/classes"
runtime_jar="$classes_dir/runtime-1.0-jar-with-dependencies.jar"
for i in $SCRIPT_DIR/deps/*; do classes_dir="$classes_dir:$i";done
jars="$SCRIPT_DIR"
for i in `head -n1 $hb_dir/target/cached_classpath.txt|tr ':' '\n'`; do
  if [[ "$i" == *"hbase"* ]]; then
    if [[ "$i" == *"thirdparty"* ]]; then
      jars="$i:$jars"
    fi
  else
    jars="$i:$jars"
  fi
done
for i in `find $JAVA_HOME -name "*.jar"`; do jars="$i:$jars"; done

testcase="org.apache.hadoop.hbase.replication.TestReplicationSmallTests"

java -cp $classes_dir:$jars:$runtime_jar \
-noverify \
-Dlog4j.configuration=file:$SCRIPT_DIR/log4j.properties \
$@ runtime.TraceAgent $p1 $p2 $p3 $p4 org.junit.runner.JUnitCore $testcase \
> $SCRIPT_DIR/trial.out 2>&1
