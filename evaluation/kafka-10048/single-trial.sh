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

case_name=kafka-10048
ka_dir="${SCRIPT_DIR}/../../systems/$case_name"
classes_dir="$HOME/tmp/bytecode/${case_name}/classes"
runtime_jar="$classes_dir/runtime-1.0-jar-with-dependencies.jar"
for i in $SCRIPT_DIR/deps/*; do classes_dir="$classes_dir:$i";done
jars="$SCRIPT_DIR"
for i in `find $ka_dir -name "dependant-libs-2.13.3"`; do
  for j in `find $i -name "*.jar"`; do
    if [[ $j" != *"-SNAPSHOT.jar ]]; then
      if [[ "$j" != *"/slf4j-log4j12-1.7.30.jar" ]]; then
        if [[ "$j" != *"/slf4j-api-1.7.30.jar" ]]; then
          jars="$j:$jars";
        fi
      fi
    fi
  done
done

for i in `find $ka_dir -name "dependant-libs"`; do
  for j in `find $i -name "*.jar"`; do
    if [[ $j" != *"-SNAPSHOT.jar ]]; then
      if [[ "$j" != *"/slf4j-log4j12-1.7.30.jar" ]]; then
        if [[ "$j" != *"/slf4j-api-1.7.30.jar" ]]; then
          jars="$j:$jars";
        fi
      fi
    fi
  done
done

for i in `find $JAVA_HOME -name "*.jar"`; do jars="$i:$jars"; done

jars="$ka_dir/core/build/dependant-libs-2.13.3/slf4j-log4j12-1.7.30.jar:$ka_dir/core/build/dependant-libs-2.13.3/slf4j-api-1.7.30.jar:$jars"

testmethod="org.apache.kafka.connect.mirror.MirrorConnectorsIntegrationTest#testReplicationWithEmptyPartition"

java -noverify \
-Dlog4j.configuration=file:$SCRIPT_DIR/log4j.properties \
-cp $SCRIPT_DIR/junit-platform-console-standalone-1.7.0.jar:$classes_dir:$jars:$runtime_jar \
$@ runtime.TraceAgent $p1 $p2 $p3 $p4 org.junit.platform.console.ConsoleLauncher --select-method $testmethod \
> $SCRIPT_DIR/trial.out 2>&1
