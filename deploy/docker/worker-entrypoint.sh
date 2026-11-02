#!/bin/sh
set -eu

# A configured Docker host, the same shared daemon gitlab-runner uses, is used as-is.
# Local dockerd is only started when no host was given.
if [ -n "${DOCKER_HOST:-}" ]; then
	export DOCKER_TLS_CERTDIR="${DOCKER_TLS_CERTDIR-}"
	exec docker-init -- "$@"
fi

# Same dind startup as the gitlab-runner build image. The daemon stays up,
# then the worker process becomes pid 1 via tini so it can reap container shims.
export DOCKER_TLS_CERTDIR=
export DOCKER_HOST=unix:///var/run/docker.sock

/usr/local/bin/dind dockerd \
	--host=unix:///var/run/docker.sock \
	--host=tcp://127.0.0.1:2375 \
	--tls=false \
	>/var/log/dockerd.log 2>&1 &

i=0
until docker info >/dev/null 2>&1; do
	i=$((i + 1))
	if [ "$i" -gt 60 ]; then
		echo "dockerd did not start" >&2
		tail -n 80 /var/log/dockerd.log >&2 || true
		exit 1
	fi
	sleep 1
done

exec docker-init -- "$@"
