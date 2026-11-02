# Worker base is the gitlab-runner job image: Alpine dind with dockerd,
# the dind wrapper, and the docker CLI. Java on that image is Corretto 8;
# the worker process needs 21, so Temurin 21 is placed ahead of it on PATH.
FROM harbor.oalite.com/baseimage/build_image:638fe1f64a

RUN wget -O /tmp/jre.tgz "https://api.adoptium.net/v3/binary/latest/21/ga/alpine-linux/x64/jre/hotspot/normal/eclipse" \
	&& mkdir -p /opt/jdk-21 \
	&& tar -xzf /tmp/jre.tgz -C /opt/jdk-21 --strip-components=1 \
	&& rm /tmp/jre.tgz \
	&& /opt/jdk-21/bin/java -version

ENV JAVA_HOME=/opt/jdk-21
ENV PATH="/opt/jdk-21/bin:${PATH}"

COPY worker-entrypoint.sh /usr/local/bin/worker-entrypoint.sh
COPY helix.jar /app/helix.jar
RUN chmod +x /usr/local/bin/worker-entrypoint.sh

ENTRYPOINT ["/usr/local/bin/worker-entrypoint.sh"]
CMD ["java", "-Dloader.main=dev.helix.worker.WorkerProcess", "-cp", "/app/helix.jar", "org.springframework.boot.loader.launch.PropertiesLauncher"]
