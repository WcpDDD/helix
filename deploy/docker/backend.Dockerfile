FROM docker.m.daocloud.io/library/eclipse-temurin:21-jre-jammy

RUN apt-get update \
	&& apt-get install -y --no-install-recommends git ca-certificates bash \
	&& rm -rf /var/lib/apt/lists/* \
	&& git config --system --add safe.directory '*'

WORKDIR /app
COPY helix.jar /app/helix.jar

EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/helix.jar"]
