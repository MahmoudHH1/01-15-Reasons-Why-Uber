#!/usr/bin/env bash
# Usage:
#   ./run-grader.sh task2
#   ./run-grader.sh task3
#   ./run-grader.sh m2
set -euo pipefail

IMAGE="abuelmagd/scalable-grading-system:spring2026-latest"
COMMON=(
  --rm
  -v /var/run/docker.sock:/var/run/docker.sock
  --tmpfs /grader-ram:exec,size=1024m
  -v "$(pwd):/repo"
  -e REPO_PATH=/repo
)
if [[ "${DEBUG:-0}" == "1" ]]; then
  COMMON+=(-e DEBUG=1)
fi

case "${1:-}" in
  task2)
    docker run "${COMMON[@]}" -e TASK=2 "$IMAGE"
    ;;
  task3)
    docker run "${COMMON[@]}" -e TASK=3 "$IMAGE"
    ;;
  m2)
    docker run "${COMMON[@]}" --add-host=host.docker.internal:host-gateway \
      -e PROJECT=M2 -e THEME=Uber \
      -e M2_NO_CASSANDRA_OVERRIDE=1 \
      -e M2_KEEP_DATA=1 \
      "$IMAGE"
    ;;
  *)
    echo "Usage: $0 {task2|task3|m2}" >&2
    exit 1
    ;;
esac
