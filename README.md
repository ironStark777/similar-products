# Similar products

Spring Boot application for the similar products API agreed in the backend technical test, served on port 5000:

```
GET /product/{productId}/similar
```

## Running it

Requirements: Java 21 or later. Maven comes with the Maven Wrapper (`./mvnw`).

```bash
./mvnw spring-boot:run
```

To run the tests:

```bash
./mvnw verify
```
