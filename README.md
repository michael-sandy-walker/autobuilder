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