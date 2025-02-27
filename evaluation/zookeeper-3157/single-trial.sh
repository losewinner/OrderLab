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

case_name=zookeeper-3157
zk_dir="${SCRIPT_DIR}/../../systems/$case_name"
#btm_dir="${SCRIPT_DIR}/none"
target="$zk_dir"/build
classes_dir="$HOME/tmp/bytecode/$case_name/classes"
#runtime_classes_dir="$SCRIPT_DIR/runtime-classes"
#testclasses_dir="$target/test-classes"
jars="."
for i in $target/lib/*.jar; do jars="$i:$jars"; done
for i in $target/test/lib/*.jar; do jars="$i:$jars"; done
for i in `find $HOME/.m2 -name "*.jar"|grep 'javax.json'|grep '1.1.4'`; do jars="$i:$jars"; done
#for i in `find $HOME/.m2/repository/org/jboss/byteman/ -name "*.jar"`; do jars="$i:$jars"; done
for i in `find $JAVA_HOME -name "*.jar"`; do jars="$i:$jars"; done
testcase="org.apache.zookeeper.server.quorum.FuzzySnapshotRelatedTest"
#byteman=""
#for i in `find $HOME/.m2/repository/org/jboss/byteman/byteman/*/**.jar`; do byteman=$i; done

mkdir -p $SCRIPT_DIR/build

trials_dir=$SCRIPT_DIR/trials
mkdir -p $trials_dir
id=0


java -cp $classes_dir:$jars:$SCRIPT_DIR \
-Dlog4j.configuration=file:$SCRIPT_DIR/log4j.properties \
$@ runtime.TraceAgent $p1 $p2 $p3 $p4 org.junit.runner.JUnitCore $testcase \
> $SCRIPT_DIR/trial.out 2>&1
