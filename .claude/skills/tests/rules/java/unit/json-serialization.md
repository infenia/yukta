---
title: JSON Serialization in Tests
impact: HIGH
impactDescription: prevents test fragility and ensures explicit test data
tags: java, tests, json, serialization, objectmapper, gson
---

## JSON Serialization in Tests

Use explicit JSON string literals instead of runtime serializers to ensure tests are deterministic and clearly show expected data.

### Rules

- **DO NOT** call runtime serializers in tests (`objectMapper.writeValueAsString`, `gson.toJson`, etc.)
- You **MUST** use explicit JSON string literals in stubs and assertions

**Incorrect:**

```java
@Test
void createUser_validRequest_returns201() {
    // AVOID: Using runtime serializer - if a Jackson annotation changes, the test still passes but the contract might break
    var request = new UserRequest("John", "john@test.com");

    webTestClient.post().uri("/users")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(objectMapper.writeValueAsString(request)) // Redundant and hides the format
            .exchange()
            .expectStatus().isCreated();
}

@Test
void getUser_existingId_returnsUser() {
    // Serializing response for comparison - hides expected structure
    var expectedUser = new User("1", "John");
    when(service.findById("1")).thenReturn(expectedUser);

    var result = controller.getUser("1");

    assertThat(objectMapper.writeValueAsString(result))
            .isEqualTo(objectMapper.writeValueAsString(expectedUser));
}
```

**Correct:**

```java
@Test
void createUser_validRequest_returns201() {
    // BETTER: Explicit JSON literal shows the exact expected wire format
    String requestJson = """
            {
                "name": "John",
                "email": "john@test.com"
            }
            """;

    webTestClient.post().uri("/users")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(requestJson)
            .exchange()
            .expectStatus().isCreated();
}

@Test
void getUser_existingId_returnsUserJson() {
    when(repository.findById("1")).thenReturn(Mono.just(new User("1", "John")));

    webTestClient.get().uri("/users/1")
            .exchange()
            .expectStatus().isOk()
            // Verify specific fields to ensure contract stability
            .expectBody()
            .jsonPath("$.id").isEqualTo("1")
            .jsonPath("$.name").isEqualTo("John");
}

@Test
void getAllUsers_returnsJsonArray() {
    when(repository.findAll()).thenReturn(Flux.just(new User("1", "John")));

    webTestClient.get().uri("/users")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            // Verify the exact structure of the response body
            .json("""
                    [
                        {"id": "1", "name": "John"}
                    ]
                    """);
}
```

### Why this matters for WebFlux
WebTestClient is designed to work with ExchangeResult. By using .expectBody().json(...) or .jsonPath(), you are testing the serialized output.

If you use .expectBody(User.class).isEqualTo(expectedUser), you are testing your Java logic against itself. If someone accidentally adds @JsonIgnore to the id field, the POJO-to-POJO test will still pass, but your mobile or frontend clients will break because the field disappeared from the actual JSON.

### Benefits
- Contract Safety: You are testing what actually goes over the wire.
- No Jackson Magic: Tests won't pass/fail based on global ObjectMapper configurations (like SNAKE_CASE vs CAMEL_CASE) unless intended.
- Reactive Friendly: WebTestClient handles the subscription and flushing of the byte buffers for you when you provide a String or use the JSON DSL.