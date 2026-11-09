# 集群 worker 的工具环境。WorkerProcess 启动时会 source 这份文件。
# JDK 21 留在 PATH 前面，worker 进程本身用 21。编译业务仓时用 JAVA8_HOME。
export JAVA8_HOME=/usr/lib/jvm/java-8-amazon-corretto
export JAVA21_HOME=/opt/jdk-21
export JAVA_HOME=/opt/jdk-21
export PATH="/root/.helix/toolchain/bin:/opt/python/bin:${PATH}"
unset LD_LIBRARY_PATH
export GLAB=/root/.helix/toolchain/bin/glab
export OPENSPEC=/usr/local/bin/openspec
export PNPM=/usr/local/bin/pnpm
export UV=/root/.helix/toolchain/bin/uv
export RG=/usr/local/bin/rg
export JQ=/usr/bin/jq
export MYSQL=/usr/bin/mysql
export KUBECTL=/usr/bin/kubectl
if [ -f /root/.kube/config ]; then
	export KUBECONFIG=/root/.kube/config
fi
