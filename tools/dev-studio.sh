#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 LiQiyeDev
# SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
#
# Dev Studio with code hot reload: javafx:run on a JetBrains Runtime with enhanced class redefinition, and an agent
# that redefines a class in the running Studio each time it is recompiled into target/classes.
#
#   tools/dev-studio.sh            start Studio (run from anywhere)
#   mvn -o -q -pl botmaker-studio compile    after an edit (or let the IDE build): the open Studio takes it
#
# JBR: $BOTMAKER_JBR, else ~/.jdks/jbr-*. Stock JDK still starts but reloads method bodies only.
# The agent redefines classes; objects already on screen keep their old state until the view that built them is
# rebuilt (reopen the window or the file).
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
umbrella="$(cd "$here/../.." && pwd)"
jbr="${BOTMAKER_JBR:-$(ls -d "$HOME"/.jdks/jbr-* 2>/dev/null | sort | tail -1 || true)}"
[ -n "$jbr" ] && [ -x "$jbr/bin/java" ] || echo "no JBR found (set BOTMAKER_JBR): method-body reloads only" >&2

agent_dir="$(mktemp -d)"
trap 'rm -rf "${agent_dir:?}"' EXIT
javac --release 25 -d "$agent_dir" "$here/devreload/DevReloadAgent.java"
jar cfm "$agent_dir/devreload.jar" "$here/devreload/MANIFEST.MF" -C "$agent_dir" .

cd "$umbrella"
# Upstream -SNAPSHOTs come from ~/.m2: run /rebuild-chain botmaker-studio first
# (`mvn -q -pl botmaker-studio,botmaker-plugin-archetype -am install -DskipTests`; the cli copies the archetype zip).
JAVA_HOME="${jbr:-$JAVA_HOME}" mvn -o -pl botmaker-studio -Pdevreload -Ddevreload.agent="$agent_dir/devreload.jar" \
    -DskipTests javafx:run
