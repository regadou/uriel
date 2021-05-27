#!/bin/sh

mvn clean
mkdir -p target/classes
for file in audio context converter expression function music resource utils; do
    cp src/main/kotlin/com/magicreg/uriel/$file.kt target/classes/$file.kt
done
DSP_FILE=src/lib/TarsosDSP-2.4-bin.jar
if [ ! -f $DSP_FILE ]; then
    mkdir -p src/lib
    echo "Dowloading audio library to $DSP_FILE ..."
    wget -qO $DSP_FILE https://0110.be/releases/TarsosDSP/TarsosDSP-2.4/TarsosDSP-2.4-bin.jar
fi
if [ "$1" = "native" ]; then
    NATIVE="-Pnative -Dquarkus.native.container-build=true -Dquarkus.native.builder-image=quay.io/quarkus/ubi-quarkus-mandrel:{mandrel-flavor}"
    shift
fi
./mvnw package $NATIVE $@

