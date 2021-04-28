#!/bin/sh
FOLDER=$(dirname $(readlink -f "$0"))
if [ "$1" = "debug" ]; then
    DEBUG="-Xdebug -Xrunjdwp:transport=dt_socket,server=y,suspend=n,address=7777"
fi
if [ -f $FOLDER/target/countries-0.0.1-runner ]; then
    $FOLDER/target/countries-0.0.1-runner $@
elif [ -f $FOLDER/target/quarkus-app/quarkus-run.jar ]; then
    java $DEBUG -jar $FOLDER/target/quarkus-app/quarkus-run.jar $@
else
    echo "Application is not compiled or you are in the wrong directory"
fi
