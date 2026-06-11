#!/usr/bin/env bash

#This script will unzip the command and query raml files onto the
# integration-test target folder, so that the Integration Tests can be run from the IDE.

#Copy files
cp stagingdlrm-command/stagingdlrm-command-api/target/*raml.jar stagingdlrm-integration-test/target/ ;
cp stagingdlrm-query/stagingdlrm-query-api/target/*raml.jar stagingdlrm-integration-test/target/ ;

#unzip files
unzip -o stagingdlrm-integration-test/target/stagingdlrm-command-api-*-raml.jar raml/* -d stagingdlrm-integration-test/target/test-classes/;
unzip -o stagingdlrm-integration-test/target/stagingdlrm-query-api-*-raml.jar raml/* -d stagingdlrm-integration-test/target/test-classes/;
