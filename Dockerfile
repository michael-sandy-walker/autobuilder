FROM eclipse-temurin:21-jdk-alpine

# Set the app directory
WORKDIR /app

# Copy the fat jar into the container
COPY target/autobuilder-*.jar app.jar

# Set environment variables (override via docker run -e or compose)
ENV FILE_BASE_PATH=/data/firestarter

# Create volume mount point
VOLUME ["/data/firestarter"]

# Run the application
ENTRYPOINT ["java", "-jar", "app.jar"]