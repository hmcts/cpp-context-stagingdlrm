#!/usr/bin/env bash

WILDFLY_DEPLOYMENT_DIR="${VAGRANT_DIR}/deployments"



function deployWars {
find . -path "*/target/*.war" |while read fname; do
  echo "$fname"
  cp "$fname" $WILDFLY_DEPLOYMENT_DIR
  echo "Copied $fname, now sleeping to allow wildfly to catch up"
  echo ""
  echo ""  
  sleep 10 
 
done
}

START_TIME=$(date)

deployWars

echo "Start time is $START_TIME"
END_TIME=$(date)
echo "End time is $END_TIME"
