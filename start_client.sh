#!/bin/bash

# es wird die Verzeichnisstruktur von IntelliJ IDEA angenommen
# für Eclipse sind die Verzeichnisse entsprechend anzupassen

host=$1
port=$2
video=$3

src=src
bin=out/production/RTSP-Streaming


# Kompilierung
echo "compile classes..."
javac -cp $src ${src}/Client.java  -d $bin 

# Start
echo "start classes..."
java -cp $bin  Client $host $port $video 
