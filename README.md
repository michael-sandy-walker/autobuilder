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

Run tests (no env var needed — the default base path is now relative):
mvn test
mvn test -Dtest=ClassName#method

Verifier precision benchmark (T5-AF layer 2):
Scores the completion verifier (T4-BP-3) on a labelled hollow/real corpus — baseline (flat file blob)
vs with the T4-BS structural wiring evidence — and prints a precision/recall confusion matrix.
Opt-in: needs the local model up, skipped in normal test runs. Measurement only (no pass/fail gate).
T5AF_VERIFIER_EVAL=1 mvn test -Dtest=CompletionVerifierPrecisionEvalTest
Endpoint override (defaults to local Ollama qwen3-coder:30b): LLM_API_URL / LLM_API_MODEL / OPENAI_API_KEY

Config flags worth knowing (application.yml):
autobuilder.build.executor                          (default maven) — set to javac-direct for the
  fast in-process compile probe.
autobuilder.goal.sync-from-graph / migrate-to-graph — load goal state from Neo4j on startup / push
  JSON goal state into Neo4j (used in tests).