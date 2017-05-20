FROM anapsix/alpine-java:8_jdk_unlimited

RUN adduser -D -H slb-controller

USER slb-controller

ENV JAVA_TOOL_OPTIONS "-Xms64m -Xmx128m -XX:MaxMetaspaceSize=64m"

COPY slb-controller.jar /slb-controller.jar

ENTRYPOINT ["java", "-server", "-jar", "/slb-controller.jar"]