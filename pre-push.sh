#!/bin/bash

team="I2T"

branch=$(git symbolic-ref HEAD | sed -e 's,.*/\(.*\),\1,')

echo "Branch:$branch"

currentVersion=`mvn org.apache.maven.plugins:maven-help-plugin:2.1.1:evaluate -Dexpression=project.version | grep -v '\['`
echo "Current Version: $currentVersion"

if  [[ $branch == "$team" ]]
then
    if [[ $currentVersion == *"$team"* ]]
    then
        echo "Version is from $team"
    else
        echo "Version is not from $team"

        newVersion=`echo $currentVersion | sed 's/SNAPSHOT/I2T-SNAPSHOT/g'`
        echo "New Version: $newVersion"

        mvn -q versions:set -DnewVersion=$newVersion
        mvn -q versions:commit
        git commit -am "Version from CCR:$newVersion"
    fi
else
    echo "Branch is not $team"
fi