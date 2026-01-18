# Use a lightweight version of Java 17
FROM amazoncorretto:17-alpine-jdk

# Set the working directory inside the server
WORKDIR /app

# Copy your files into the server
COPY ChatbotServer.java .
COPY index.html .

# Compile the Java code
RUN javac ChatbotServer.java

# Run the server when the container starts
CMD ["java", "ChatbotServer"]