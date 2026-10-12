FROM eclipse-temurin:21-jdk
WORKDIR /app
COPY lib ./lib
COPY src ./src
COPY web ./web
RUN mkdir -p out /app/data && javac -cp "lib/*" -d out src/HashPlagServer.java
ENV PORT=8080
ENV HASHPLAG_DATA_DIR=/app/data
ENV OPEN_BROWSER=false
EXPOSE 8080
CMD ["sh", "-c", "java -cp 'out:lib/*' HashPlagServer"]
