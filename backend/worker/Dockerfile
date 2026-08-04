FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY backend backend
RUN chmod +x mvnw && ./mvnw -q -pl backend/worker -am -DskipTests package
RUN mkdir -p /opt/playwright \
    && cd /opt/playwright \
    && jar xf /root/.m2/repository/com/microsoft/playwright/driver-bundle/1.59.0/driver-bundle-1.59.0.jar driver/linux/node \
    && mv driver/linux/node /opt/playwright/node \
    && chmod 0555 /opt/playwright/node \
    && rm -rf /opt/playwright/driver

FROM eclipse-temurin:21-jre AS java-runtime

FROM mcr.microsoft.com/playwright/java:v1.59.0-noble
WORKDIR /app
COPY --from=java-runtime /opt/java/openjdk /opt/java/openjdk
COPY --from=build /opt/playwright/node /opt/playwright/node
COPY --from=build /workspace/backend/worker/target/auto-invoice-worker-*.jar /app/app.jar
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="/opt/java/openjdk/bin:${PATH}"
ENV PLAYWRIGHT_NODEJS_PATH=/opt/playwright/node
ENV PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1
USER pwuser
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
