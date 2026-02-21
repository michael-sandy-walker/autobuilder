#!/bin/bash

# ---- CONFIG ----
PROJECT_ROOT="/Users/michael_walker/workspaces_2023/autobuilder"
TARGET_DIR="$PROJECT_ROOT/collected-files"

# Absolute paths for provided files
PROJECT_PART="$PROJECT_ROOT/chatgpt_bundles/project-part-01.txt"
PROJECT_SUMMARY="$PROJECT_ROOT/gena/full/project-summary.lean.json"

# ---- FILE LIST ----
FILES=(
AdaptiveExecutionEngine
AutobuilderService
CycleContextCoordinator
CycleContextData
CycleExecutor
DefaultSemanticDiffAnalyzer
DeliberationService
FileService
GoalPlannerService
MetaDeliberationAgent
Neo4jAgentGraphService
Neo4jGoalGraphService
ProjectDiffService
EvaluationProtocolContract
EvolutionProtocolContract
MergeProtocolContract
PlannerProtocolContract
EvaluationLLMAdapter
EvolutionLLMAdapter
EvaluationAgent
EvolutionAgent
MergeAgent
PlannerAgent
)

# ---- EXECUTION ----
echo "Creating target directory: $TARGET_DIR"
mkdir -p "$TARGET_DIR"

echo "Searching and copying Java files..."

for CLASS in "${FILES[@]}"; do
  echo "Processing: $CLASS.java"
  
  FOUND=$(find "$PROJECT_ROOT" -type f -name "$CLASS.java" 2>/dev/null)
  
  if [ -z "$FOUND" ]; then
    echo "  -> NOT FOUND"
  else
    for FILE in $FOUND; do
      echo "  -> Copying $FILE"
      cp "$FILE" "$TARGET_DIR/"
    done
  fi
done

# ---- Copy external files ----
echo "Copying additional files..."

if [ -f "$PROJECT_PART" ]; then
  cp "$PROJECT_PART" "$TARGET_DIR/"
  echo "  -> Copied project-part-01.txt"
else
  echo "  -> project-part-01.txt not found"
fi

if [ -f "$PROJECT_SUMMARY" ]; then
  cp "$PROJECT_SUMMARY" "$TARGET_DIR/"
  echo "  -> Copied project-summary.lean.json"
else
  echo "  -> project-summary.lean.json not found"
fi

echo "Done. Files collected in:"
echo "$TARGET_DIR"