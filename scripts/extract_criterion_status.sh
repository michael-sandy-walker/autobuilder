#!/bin/bash
# extract_criterion_status.sh -- exports the home of T3-CB's rule (is the status a criterion asserts
# one its specification supports?) as a standalone Maven library: the first Autobuilder module
# adopted to change it against a REAL ticket (T4-CF's "goal from a ticket"; T5-BR's first extraction
# made the home small and free of Spring).
#
# The module is the rule's class and its closure over Autobuilder's own classes, read from git at
# <git-rev>: the criterion parser, the specification's per-endpoint status reader, and the two
# endpoint primitives they share. Its existing behaviour is characterised by Autobuilder's OWN tests
# of that closure, taken from the same revision -- the regression suite.
#
# Usage: scripts/extract_criterion_status.sh <git-rev> <dest-dir>
set -euo pipefail
REV="${1:?usage: extract_criterion_status.sh <git-rev> <dest-dir>}"
DEST="${2:?usage: extract_criterion_status.sh <git-rev> <dest-dir>}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
B=com/walpro/autobuilder
FILES="
src/main/java/$B/analysis/structure/Endpoint.java
src/main/java/$B/analysis/structure/EndpointPaths.java
src/main/java/$B/analysis/structure/SpecEndpointBehaviourQuery.java
src/main/java/$B/core/build/BehaviouralCriterionParser.java
src/main/java/$B/core/build/SeedStatusSupport.java
src/test/java/$B/analysis/structure/SpecEndpointBehaviourQueryTest.java
src/test/java/$B/core/build/BehaviouralCriterionParserTest.java
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
    <artifactId>criterion-status</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <packaging>jar</packaging>
    <properties>
        <maven.compiler.release>21</maven.compiler.release>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>
    <dependencies>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <version>5.10.2</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.assertj</groupId>
            <artifactId>assertj-core</artifactId>
            <version>3.25.3</version>
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
# criterion-status

How an acceptance criterion about an HTTP endpoint is read, and what a specification states about
each endpoint it names, extracted from Autobuilder. The criterion parser reads which endpoint and
which response status a criterion asserts; the specification reader reads, for every endpoint a
specification names, the response statuses it states for it. Both are pure: text in, values out.
MD
echo "extracted $(echo $FILES | wc -w) files at $REV into $DEST"
