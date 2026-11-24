#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 LiQiyeDev
# SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
#
# Dev Studio with code hot reload: javafx:run -Pdevreload on a JetBrains Runtime with enhanced class redefinition.
# The profile's agent redefines a class in the running Studio each time it is recompiled into target/classes.
#
#   tools/dev-studio.sh                      start Studio (run from anywhere)
#   mvn -o -q -pl botmaker-studio compile    after an edit (or let the IDE build): the open Studio takes it
#
# `botmaker studio start` and the MCP's start_studio start the same dev Studio. JBR: $BOTMAKER_JBR, else the newest
# ~/.jdks/jbr-*; on a stock JDK only method bodies reload, and the agent says which class needs a restart.
# Upstream -SNAPSHOTs come from ~/.m2: run /rebuild-chain botmaker-studio first
# (`mvn -q -pl botmaker-studio,botmaker-plugin-archetype -am install -DskipTests`; the cli copies the archetype zip).
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$here/../.."
jbr="${BOTMAKER_JBR:-$(ls -d "$HOME"/.jdks/jbr-* 2>/dev/null | sort | tail -1 || true)}"
if [ -n "$jbr" ] && [ -x "$jbr/bin/java" ]; then export JAVA_HOME="$jbr"
else echo "no JBR found (set BOTMAKER_JBR): method-body reloads only" >&2; fi
exec mvn -o -pl botmaker-studio -Pdevreload javafx:run
