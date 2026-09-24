#!/bin/bash
# extract_structdiff.sh -- exports one self-contained module of Autobuilder, the structural Java
# diff, as a standalone Maven library: the second adopted project for T4-CQ.
#
# Usage: scripts/extract_structdiff.sh <git-rev> <dest-dir>
#
# The module is read from git history at <git-rev>, never from the working tree, so the "pristine
# original" an adopted run starts from is reproducible and cannot pick up uncommitted edits. The
# ten classes are a closed set: they import nothing else from Autobuilder, and outside the JDK only
# the Java parser library and one JSON annotation. Package names are kept as they are.
set -euo pipefail
REV="${1:?usage: extract_structdiff.sh <git-rev> <dest-dir>}"
DEST="${2:?usage: extract_structdiff.sh <git-rev> <dest-dir>}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
B=src/main/java/com/walpro/autobuilder
FILES="
$B/analysis/diff/model/ElementDescriptor.java
$B/analysis/diff/model/ElementType.java
$B/analysis/diff/model/StructuralDiffNode.java
$B/analysis/diff/builder/StructuralDiffBuilder.java
$B/analysis/diff/builder/StructuralJavaDiffBuilder.java
$B/analysis/diff/util/DiffSummarizer.java
$B/analysis/diff/util/StructuralDiffUtils.java
$B/analysis/structure/JavaStructureParser.java
$B/analysis/structure/model/ParsedElement.java
$B/analysis/structure/model/ParsedElementBuilder.java
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
    <artifactId>structdiff</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <packaging>jar</packaging>
    <properties>
        <maven.compiler.release>21</maven.compiler.release>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>
    <dependencies>
        <dependency>
            <groupId>com.github.javaparser</groupId>
            <artifactId>javaparser-core</artifactId>
            <version>3.25.7</version>
        </dependency>
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
# structdiff

Structural diff of Java sources, extracted from Autobuilder. It parses Java source into a tree of
declared elements (types, fields, methods, constructors) and compares two versions of a file,
reporting which elements were added, removed or changed.
MD
echo "extracted $(echo $FILES | wc -w) classes at $REV into $DEST"
