FROM hapiproject/hapi:base as build-hapi

ARG HAPI_FHIR_URL=https://github.com/elimuinformatics/hapi-fhir/
ARG HAPI_FHIR_BRANCH=master
ARG HAPI_FHIR_STARTER_URL=https://github.com/elimuinformatics/hapi-fhir-jpaserver-starter/
ARG HAPI_FHIR_STARTER_BRANCH=master

RUN git clone --branch ${HAPI_FHIR_BRANCH} ${HAPI_FHIR_URL}
WORKDIR /tmp/hapi-fhir/
RUN /tmp/apache-maven-3.6.2/bin/mvn dependency:resolve
RUN /tmp/apache-maven-3.6.2/bin/mvn install -DskipTests

WORKDIR /tmp
RUN git clone --branch ${HAPI_FHIR_STARTER_BRANCH} ${HAPI_FHIR_STARTER_URL}

WORKDIR /tmp/hapi-fhir-jpaserver-starter

COPY pom.xml .
COPY server.xml .
RUN mvn -ntp dependency:go-offline

RUN mkdir -p /data/hapi/lucenefiles && chmod 775 /data/hapi/lucenefiles && rm -rf /usr/local/tomcat/webapps/ROOT
COPY --from=build-hapi /tmp/hapi-fhir-jpaserver-starter/target/*.war /usr/local/tomcat/webapps/ROOT.war

FROM build-hapi AS build-distroless
RUN mvn package spring-boot:repackage -Pboot
RUN mkdir /app && cp /tmp/hapi-fhir-jpaserver-starter/target/ROOT.war /app/main.war

CMD ["catalina.sh", "run"]
