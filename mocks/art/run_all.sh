#!/usr/bin/env bash
# Runs every planned batch in order; each batch skips nothing, so rerun plan.py first to drop finished items.
cd "$(dirname "$0")"
for w in units boats cities leaders wonders nwscenes; do
  echo "=== $w $(date +%T)"; python3 gen.py "jobs_$w.json"
done
echo "=== done $(date +%T)"
