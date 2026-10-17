#!/bin/sh
# Консольный прототип боя. Нужна Java 21+.
cd "$(dirname "$0")" && sh ./gradlew -q :battle-core:installDist || exit 1
JAVA_OPTS="-Dstdout.encoding=UTF-8" sh battle-core/build/install/battle-core/bin/battle-core "$@"
