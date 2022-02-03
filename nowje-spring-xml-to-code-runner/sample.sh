#!/bin/bash
mvn clean install -DskipTests
java -jar target/nowje-spring-xml-to-code-runner-1.0-SNAPSHOT.jar sourceFolder target.kt package.name
