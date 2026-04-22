Maven rebuld: mvn clean install
docker-compose rebuild: docker-compose build --no-cache
docker-compose run: docker-compose up

Write something:
Invoke-WebRequest -Uri "http://localhost:8080/files/write?path=test/output.txt" `
>>   -Method POST `
>>   -Headers @{ "Content-Type" = "text/plain" } `
>>   -Body "Hello from Docker"

See events:
$response = Invoke-RestMethod -Uri "http://localhost:8080/events/recent"
$response

Swagger:
http://localhost:8080/swagger-ui/index.html

Evolve:
$response = Invoke-WebRequest -Uri http://localhost:8080/autobuilder/evolve -Method Post
$response.StatusCode
$response.Content

Initialize Neo4j graph db:
docker run -d --name neo4j -p7474:7474 -p7687:7687 \
  -e NEO4J_AUTH=neo4j/your_password \
  neo4j:5
  
View Neo4j graph db:
http://localhost:7474

Purge graph db:
MATCH (n)
DETACH DELETE n;

Generate plantuml: de.elnarion.maven:plantuml-generator-maven-plugin:3.0.1:generate
Render plantuml: plantuml target/plantuml/autobuilder-class-diagram.puml

Local LLM:

Install:
curl -fsSL https://ollama.com/install.sh | sh

Pull Qwen3-coder:30b:
ollama pull qwen3-coder:30b

Test:
ollama run qwen3-coder:30b "hello"

Available models:
curl http://localhost:11434/api/tags

currently loaded within VRAM:
curl http://localhost:11434/api/ps

basic version info:
curl http://localhost:11434/api/version

Run Open WebUI:
docker run -d \
--network=host \
--gpus=all \
-v open-webui:/app/backend/data \
-e OLLAMA_BASE_URL=http://127.0.0.1:11434 \
--name open-webui \
--restart always \
ghcr.io/open-webui/open-webui:cuda

GPU-level stats:
watch -n 1 nvidia-smi

Retry (coverage-review):
curl -X POST http://localhost:8081/autobuilder/coverage-review

HTML goal explorer:
http://localhost:8081/goals.html