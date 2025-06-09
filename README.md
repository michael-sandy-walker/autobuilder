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
