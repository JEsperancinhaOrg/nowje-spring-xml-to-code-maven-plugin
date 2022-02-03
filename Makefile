b: build-maven
build:
	mvn clean install
build-maven:
	mvn clean install -DskipTests
test:
	mvn test
test-maven:
	mvn test
local: no-test
	mkdir -p bin
no-test:
	mvn clean install -DskipTests
docker:
	docker-compose down -v
	docker-compose rm -svf
	docker-compose up -d --build --remove-orphans
docker-databases: stop local
coverage:
	mvn clean install jacoco:prepare-agent package jacoco:report
	mvn omni-coveragereporter:report
build-images:
build-docker: stop no-test build-npm
	docker-compose up -d --build --remove-orphans
show:
	docker ps -a  --format '{{.ID}} - {{.Names}} - {{.Status}}'
docker-delete-idle:
	docker ps --format '{{.ID}}' -q --filter="name=spring-xml-bean-to-code-runner_"| xargs -I {} docker rm {}
docker-delete: stop
	docker ps -a --format '{{.ID}}' -q --filter="name=spring-xml-bean-to-code-runner_"| xargs -I {}  docker stop {}
	docker ps -a --format '{{.ID}}' -q --filter="name=spring-xml-bean-to-code-runner_"| xargs -I {}  docker rm {}
docker-cleanup: docker-delete
	docker images -q | xargs docker rmi
docker-clean:
	docker-compose down -v
	docker-compose rm -svf
docker-clean-build-start: docker-clean b docker
docker-delete-apps: stop
prune-all: docker-delete
	docker network prune
	docker system prune --all
	docker builder prune
	docker system prune --all --volumes
stop:
	docker-compose down --remove-orphans
install:
	nvm install --lts
	nvm use --lts
	brew tap kong/deck
	brew install deck
locust-welcome-start:
	cd locust/welcome && locust --host=localhost
