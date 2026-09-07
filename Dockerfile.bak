FROM eclipse-temurin:25-jre
WORKDIR /app
RUN apt-get update && apt-get install -y wget curl && rm -rf /var/lib/apt/lists/*
RUN groupadd -r appuser && useradd -r -g appuser appuser
COPY target/ms-ai-guardian-1.0.49.jar app.jar
RUN chown -R appuser:appuser /app
USER appuser
EXPOSE 8088
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70.0 -XX:InitialRAMPercentage=25.0 -XX:+UseG1GC -XX:+UseContainerSupport -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
