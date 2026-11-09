# 在已发布的 worker 镜像上补齐 agent 运行环境。
# 构建机的容器网络到不了公网，所以工具包先下到宿主机，再 COPY 进来。
# tools/ 只存在于构建上下文，不进仓库。
FROM harbor.oalite.com/devops/helix-worker:20261103-1

COPY tools/node.tar.xz /tmp/node.tar.xz
COPY tools/pnpm-prefix/ /opt/pnpm/
COPY tools/glab.tgz /tmp/glab.tgz
COPY tools/uv.tgz /tmp/uv.tgz
COPY tools/rg.tgz /tmp/rg.tgz
COPY tools/python.tgz /tmp/python.tgz
COPY tools/openspec/ /opt/openspec/
COPY tools/wheels/ /tmp/wheels/
COPY tools/apks/ /tmp/apks/

RUN set -eu \
	&& tar -xJf /tmp/node.tar.xz -C /usr/local --strip-components=1 \
	&& ln -sfn /opt/pnpm/node_modules/.bin/pnpm /usr/local/bin/pnpm \
	&& mkdir -p /opt/python \
	&& tar -xzf /tmp/python.tgz -C /opt/python --strip-components=1 \
	&& ln -sfn /opt/python/bin/python3 /usr/local/bin/python3 \
	&& ln -sfn /opt/python/bin/python3 /usr/local/bin/python \
	&& mkdir -p /root/.helix/toolchain/bin /tmp/glab /tmp/uv /tmp/rg \
	&& tar -xzf /tmp/glab.tgz -C /tmp/glab \
	&& install -m 0755 "$(find /tmp/glab -type f -name glab | head -n 1)" /root/.helix/toolchain/bin/glab \
	&& tar -xzf /tmp/uv.tgz -C /tmp/uv \
	&& install -m 0755 "$(find /tmp/uv -type f -name uv | head -n 1)" /root/.helix/toolchain/bin/uv \
	&& tar -xzf /tmp/rg.tgz -C /tmp/rg \
	&& install -m 0755 "$(find /tmp/rg -type f -name rg | head -n 1)" /usr/local/bin/rg \
	&& ln -sfn /opt/openspec/node_modules/.bin/openspec /usr/local/bin/openspec \
	&& apk add --network=no --allow-untrusted /tmp/apks/*.apk \
	&& ln -sfn /usr/local/bin/rg /root/.helix/toolchain/bin/rg \
	&& ln -sfn /usr/bin/jq /root/.helix/toolchain/bin/jq \
	&& ln -sfn /usr/bin/kubectl /root/.helix/toolchain/bin/kubectl \
	&& ln -sfn /usr/local/bin/pnpm /root/.helix/toolchain/bin/pnpm \
	&& ln -sfn /usr/local/bin/openspec /root/.helix/toolchain/bin/openspec \
	&& ln -sfn /usr/bin/mysql /root/.helix/toolchain/bin/mysql \
	&& ln -sfn /usr/bin/mysqldump /root/.helix/toolchain/bin/mysqldump \
	&& /opt/python/bin/python3 -m pip install --no-index --find-links /tmp/wheels pymysql boto3 matplotlib openpyxl \
	&& rm -rf /tmp/node.tar.xz /tmp/glab /tmp/glab.tgz /tmp/uv /tmp/uv.tgz /tmp/rg /tmp/rg.tgz /tmp/python.tgz /tmp/wheels /tmp/apks \
	&& test -x /opt/jdk-21/bin/java \
	&& test -x /usr/lib/jvm/java-8-amazon-corretto/bin/javac \
	&& test -f /usr/lib/jvm/java-8-amazon-corretto/lib/tools.jar \
	&& node -v \
	&& pnpm -v \
	&& python3 --version \
	&& python3 -c 'import pymysql,boto3,matplotlib,openpyxl' \
	&& /root/.helix/toolchain/bin/glab version \
	&& /root/.helix/toolchain/bin/uv --version \
	&& openspec --version \
	&& rg --version \
	&& mysql --version

ENV JAVA_HOME=/opt/jdk-21
ENV JAVA8_HOME=/usr/lib/jvm/java-8-amazon-corretto
ENV JAVA21_HOME=/opt/jdk-21
ENV PATH="/root/.helix/toolchain/bin:/opt/python/bin:/opt/jdk-21/bin:${PATH}"

COPY toolchain-env.sh /root/.helix/toolchain/env.sh
COPY worker-entrypoint.sh /usr/local/bin/worker-entrypoint.sh
COPY helix.jar /app/helix.jar
RUN chmod +x /usr/local/bin/worker-entrypoint.sh

ENTRYPOINT ["/usr/local/bin/worker-entrypoint.sh"]
CMD ["java", "-Dloader.main=dev.helix.worker.WorkerProcess", "-cp", "/app/helix.jar", "org.springframework.boot.loader.launch.PropertiesLauncher"]
