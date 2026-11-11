FROM eclipse-temurin:21-jdk AS build
WORKDIR /source
COPY src/common/java src/common/java
COPY src/server/java src/server/java
COPY mod/V71/assets/sprite/world/generatorMaps/Syx_Kingdoms_Online.png classes/syxkingdoms/world/Syx_Kingdoms_Online.png
RUN find src/common/java src/server/java -name '*.java' -print > sources.txt \
    && mkdir -p classes \
    && javac --release 21 -encoding UTF-8 -d classes @sources.txt \
    && jar --create --file syx-kingdoms-server.jar \
        --main-class syxkingdoms.server.ServerMain -C classes .

FROM eclipse-temurin:21-jre
RUN useradd --create-home --uid 10001 syx
WORKDIR /app
COPY --from=build /source/syx-kingdoms-server.jar /app/syx-kingdoms-server.jar
COPY server/server.properties /app/server/server.properties
RUN mkdir -p /app/server-data && chown -R syx:syx /app
USER syx
EXPOSE 49720
ENTRYPOINT ["java", "-jar", "/app/syx-kingdoms-server.jar", "--config", "/app/server/server.properties"]
