#!/bin/bash

. "$( dirname "${BASH_SOURCE[0]}" )/setenv.sh"

if [ -f "$JSVC_PID_FILE" ]; then
	echo "Deposit Daemon is already running. ($( cat "$JSVC_PID_FILE" ))" >&2
	exit 1
fi

echo 'Starting Deposit Daemon ${project.version} in Background.'

$JSVC_EXECUTABLE -server -cp "$JAVA_CLASSPATH" -user "$JSVC_USER" \
	-pidfile $JSVC_PID_FILE $JAVA_OPTS $JAVA_MAIN_CLASS