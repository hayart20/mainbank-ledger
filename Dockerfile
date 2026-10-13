# One Dockerfile for every service module of the repository:
#   docker build --build-arg MODULE=ledger-service --build-arg PORT=8080 .
#   docker build --build-arg MODULE=receipt-service --build-arg PORT=8081 .
#   (likewise notification-service :8082, statement-service :8083, api-gateway :8080)
# The module is built from the repository root; services share no code, only the Kafka event contract.

# ---- build stage ----
FROM maven:3.9-eclipse-temurin-25 AS build
ARG MODULE=ledger-service
WORKDIR /workspace
COPY pom.xml .
COPY ledger-service ./ledger-service
COPY receipt-service ./receipt-service
COPY notification-service ./notification-service
COPY statement-service ./statement-service
COPY api-gateway ./api-gateway
RUN --mount=type=cache,target=/root/.m2 \
    mvn -q -B -pl ${MODULE} -am package -DskipTests

# ---- runtime stage ----
FROM eclipse-temurin:25-jre-alpine
ARG MODULE=ledger-service
ARG PORT=8080
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /workspace/${MODULE}/target/${MODULE}-1.0.0.jar app.jar
USER app
EXPOSE ${PORT}

# JAVA_OPTS comes last on the command line, so it overrides the defaults below (e.g. -XX:MaxRAMPercentage=60)
ENV JAVA_OPTS=""

# exec so that java is PID 1 and receives SIGTERM for graceful shutdown
ENTRYPOINT ["sh", "-c", "exec java \
    --enable-native-access=ALL-UNNAMED \
    -XX:+UseZGC \
    -XX:+HeapDumpOnOutOfMemoryError \
    -XX:HeapDumpPath=/tmp \
    -XX:+ExitOnOutOfMemoryError \
    -XX:InitialRAMPercentage=25.0 \
    -XX:MaxRAMPercentage=70.0 \
    -Xss2m \
    -Dsun.net.inetaddr.ttl=60 \
    -Dsun.net.client.defaultConnectTimeout=10000 \
    -Dsun.net.client.defaultReadTimeout=30000 \
    -Djava.security.egd=file:/dev/urandom \
    -XshowSettings:vm \
    $JAVA_OPTS \
    -jar /app/app.jar"]
