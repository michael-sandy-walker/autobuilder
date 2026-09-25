#!/bin/bash
# extract_participation.sh -- exports one self-contained module of Autobuilder, the per-role
# participation windows and the request records they are built from, as a standalone Maven
# library: the third adopted project for T4-CQ, and the second module of Autobuilder itself.
#
# Usage: scripts/extract_participation.sh <git-rev> <dest-dir>
#
# The module is read from git history at <git-rev>, never from the working tree, so the "pristine
# original" an adopted run starts from is reproducible and cannot pick up uncommitted edits. The
# four classes are a closed set: they import nothing else from Autobuilder, and outside the JDK
# only one JSON annotation. Package names are kept as they are. Chosen over the other self-contained
# modules because it is a different kind from structdiff: immutable records whose instance methods
# return new states, not a parser or a tree.
set -euo pipefail
REV="${1:?usage: extract_participation.sh <git-rev> <dest-dir>}"
DEST="${2:?usage: extract_participation.sh <git-rev> <dest-dir>}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
B=src/main/java/com/walpro/autobuilder
FILES="
$B/core/lifecycle/SignalType.java
$B/core/lifecycle/ChannelRequest.java
$B/core/lifecycle/ParticipationWindow.java
$B/core/lifecycle/ParticipationWindows.java
"
[ -e "$DEST" ] && [ -n "$(ls -A "$DEST" 2>/dev/null)" ] && { echo "refusing: $DEST is not empty" >&2; exit 1; }
mkdir -p "$DEST"
for f in $FILES; do
    mkdir -p "$DEST/$(dirname "$f")"
    git -C "$ROOT" show "$REV:$f" > "$DEST/$f"
done
cat > "$DEST/pom.xml" <<'POM'
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <groupId>com.walpro.autobuilder</groupId>
    <artifactId>participation</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <packaging>jar</packaging>
    <properties>
        <maven.compiler.release>21</maven.compiler.release>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>
    <dependencies>
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-annotations</artifactId>
            <version>2.17.1</version>
        </dependency>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <version>5.10.2</version>
            <scope>test</scope>
        </dependency>
    </dependencies>
    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>3.12.1</version>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <version>3.2.5</version>
            </plugin>
        </plugins>
    </build>
</project>
POM
cat > "$DEST/README.md" <<'MD'
# participation

Per-role participation windows and the request records they are built from, extracted from
Autobuilder. A window says that a role participates, with a boost, for a stated number of cycles.
Windows and requests are immutable: granting, raising, shrinking and ageing one returns a new value,
and a value past its horizon or below its threshold reports itself expired.
MD
echo "extracted $(echo $FILES | wc -w) classes at $REV into $DEST"
