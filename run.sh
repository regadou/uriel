#!/bin/bash

FOLDER=$(dirname $(readlink -f "$0"))
if [ "$1" = "debug" ]; then
    DEBUG="-Xdebug -Xrunjdwp:transport=dt_socket,server=y,suspend=n,address=7777"
fi
if [ -f $FOLDER/target/uriel-0.0.1-SNAPSHOT-runner ]; then
    $FOLDER/target/uriel-0.0.1-SNAPSHOT-runner $@
elif [ -f $FOLDER/target/quarkus-app/quarkus-run.jar ]; then
    java $DEBUG -jar $FOLDER/target/quarkus-app/quarkus-run.jar $@
else
    echo "Application is not compiled or you are in the wrong directory"
fi

