#!/bin/sh
set -eu

# GITLAB_TOKEN 已在密钥里。每次启动登录一次，重启后 git 推送不用再配。
if [ -n "${GITLAB_TOKEN:-}" ] && [ -x /root/.helix/toolchain/bin/glab ]; then
	host=${GITLAB_HOST:-https://hackers.oalite.com}
	host=${host#https://}
	host=${host#http://}
	host=${host%%/*}
	if ! /root/.helix/toolchain/bin/glab auth status --hostname "$host" >/dev/null 2>&1; then
		printf '%s' "$GITLAB_TOKEN" | /root/.helix/toolchain/bin/glab auth login --hostname "$host" --stdin >/dev/null 2>&1 || true
	fi
	git config --global "credential.https://${host}.helper" '!/root/.helix/toolchain/bin/glab auth git-credential' || true
fi

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
