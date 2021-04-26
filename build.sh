#!/bin/sh

mvn clean
mkdir -p target/classes
for file in converter expression function resource utils; do
    cp src/main/kotlin/com/magicreg/uriel/$file.kt target/classes/$file.kt
done
./mvnw package $@

