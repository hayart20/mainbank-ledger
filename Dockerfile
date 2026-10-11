# ---- build stage ----
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests

# ---- runtime stage ----
FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /workspace/target/mainbank-ledger-1.0.0.jar app.jar
USER app
EXPOSE 8080
# Use JAVA_OPTS for flexible memory tuning
ENV JAVA_OPTS=""

# exec so that java is PID 1 and receives SIGTERM for graceful shutdown
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS \
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
    -jar /app/app.jar"]
