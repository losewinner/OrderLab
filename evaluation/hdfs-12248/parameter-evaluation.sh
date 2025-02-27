#!/usr/bin/env bash

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"

case_name=hdfs-12248

p1=1
p2=1
p3=1
p4=1
p5=1

tool_dir="${SCRIPT_DIR}/../.."
R='\033[0;31m'
G='\033[0;32m'
RESET='\033[0m'

function compile_before_analysis() {
  mvn clean
  mvn install -DskipTests
}

function compile_after_analysis() {
  mvn install -DskipTests
}

# Compile System code
echo -e "${R} Compiling system----------${RESET} \n"
pushd $tool_dir/systems/$case_name >/dev/null 

compile_before_analysis

popd >/dev/null

# Filter out important log messages
echo -e "${R} Log diff----------${RESET}"
pushd $tool_dir/ground_truth/$case_name >/dev/null 
cp diff_log_dd.txt diff_log.txt
#  ./make_diff.sh $tool_dir/tool/feedback/target/feedback-1.0-jar-with-dependencies.jar
popd >/dev/null

# Perform static analysis
echo -e "${R} Static analysis----------${RESET}"
pushd $tool_dir/tool/bin >/dev/null 
  ./analyzer-${case_name}.sh
  ../move/${case_name}.sh
  cp ./tree.json $tool_dir/evaluation/$case_name
popd >/dev/null


#echo -e "${R} Recompiling system----------${RESET}"
#pushd $tool_dir/systems/$case_name >/dev/null 
#  compile_after_analysis
#popd >/dev/null

pushd $tool_dir/evaluation/$case_name >/dev/null
  ./update.sh
  rm -rf deps
  ./make-deps.sh
  # Record fault instances dynamically
  ./run-instrumented-test.sh > record-inject 2>&1  
  # Calculate the time table
  java -jar feedback.jar -tf -g record-inject -b bad-run-log -s tree.json -obj time.bin

  rm -rf results
  mkdir results
  cp fir-trial.sh single-trial.sh

  echo -e "${R} k=1,s+1----------${RESET}"
  rm -rf trials/
  cp config-template config.properties
  echo "flakyAgent.feedback=true" >> config.properties
  echo "flakyAgent.augFeedback=true" >> config.properties
  echo "flakyAgent.occurrenceSize=1" >> config.properties
  echo "flakyAgent.slidingWindow=1" >> config.properties
  echo "flakyAgent.feedbackDelta=1" >> config.properties
  ./driver.sh $p1 > experiment.out 2>&1 
  cp $tool_dir/tool/reporter/target/reporter-1.0-SNAPSHOT-jar-with-dependencies.jar .
  echo -e "${G}k=1,s+1 result:"
  ./check-${case_name}.sh trials
  echo -e "${RESET}"
  mkdir results/full_feedback
  cp -a trials/. results/full_feedback/
  cp experiment.out results/full_feedback

  echo -e "${R} k=3,s+1----------${RESET}"
  rm -rf trials/
  cp config-template config.properties
  echo "flakyAgent.feedback=true" >> config.properties
  echo "flakyAgent.augFeedback=true" >> config.properties
  echo "flakyAgent.occurrenceSize=1" >> config.properties
  echo "flakyAgent.slidingWindow=3" >> config.properties
  echo "flakyAgent.feedbackDelta=1" >> config.properties
  ./driver.sh $p2 > experiment.out 2>&1 
  cp $tool_dir/tool/reporter/target/reporter-1.0-SNAPSHOT-jar-with-dependencies.jar .
  echo -e "${G}k=3,s+1 result:"
  ./check-${case_name}.sh trials
  echo -e "${RESET}"
  mkdir results/exhaustive_fault_instance
  cp -a trials/. results/exhaustive_fault_instance/
  cp experiment.out results/exhaustive_fault_instance/

  echo -e "${R} k=10,s+1----------${RESET}"
  rm -rf trials/
  cp config-template config.properties
  echo "flakyAgent.feedback=true" >> config.properties
  echo "flakyAgent.augFeedback=true" >> config.properties
  echo "flakyAgent.occurrenceSize=1" >> config.properties
  echo "flakyAgent.slidingWindow=10" >> config.properties
  echo "flakyAgent.feedbackDelta=1" >> config.properties
  ./driver.sh $p3 > experiment.out 2>&1 
  cp $tool_dir/tool/reporter/target/reporter-1.0-SNAPSHOT-jar-with-dependencies.jar .
  echo -e "${G}k=10,s+1 result:"
  ./check-${case_name}.sh trials
  echo -e "${RESET}"
  mkdir results/fault_site_distance
  cp -a trials/. results/fault_site_distance/
  cp experiment.out results/fault_site_distance/
  
  echo -e "${R} k=10,s+2----------${RESET}"
  rm -rf trials/
  cp config-template config.properties
  echo "flakyAgent.feedback=true" >> config.properties
  echo "flakyAgent.augFeedback=true" >> config.properties
  echo "flakyAgent.occurrenceSize=1" >> config.properties
  echo "flakyAgent.slidingWindow=1" >> config.properties
  echo "flakyAgent.feedbackDelta=2" >> config.properties
  ./driver.sh $p4 > experiment.out 2>&1 
  cp $tool_dir/tool/reporter/target/reporter-1.0-SNAPSHOT-jar-with-dependencies.jar .
  echo -e "${G}k=10,s+2 result:"
  ./check-${case_name}.sh trials
  echo -e "${RESET}"
  mkdir results/fault_site_distance_w_limit
  cp -a trials/. results/fault_site_distance_w_limit/
  cp experiment.out results/fault_site_distance_w_limit/

  echo -e "${R} k=10,s+10 Feedback----------${RESET}"
  rm -rf trials/
  cp config-template config.properties
  echo "flakyAgent.feedback=true" >> config.properties
  echo "flakyAgent.augFeedback=true" >> config.properties
  echo "flakyAgent.occurrenceSize=1" >> config.properties
  echo "flakyAgent.slidingWindow=10" >> config.properties
  echo "flakyAgent.feedbackDelta=10" >> config.properties
  ./driver.sh $p5 > experiment.out 2>&1 
  cp $tool_dir/tool/reporter/target/reporter-1.0-SNAPSHOT-jar-with-dependencies.jar .
  echo -e "${G}k=10,s+10 result:"
  ./check-${case_name}.sh trials
  echo -e "${RESET}"
  mkdir results/fault_site_feedback
  cp -a trials/. results/fault_site_feedback/
  cp experiment.out results/fault_site_feedback/

popd >/dev/null


