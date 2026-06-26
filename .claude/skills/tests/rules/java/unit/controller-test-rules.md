---
title: Controller Test Rules
impact: HIGH
impactDescription: ensures correct web layer testing with MockMvc patterns
tags: java, tests, controller, webflux, webTestClient, spring
---

## Controller Test Rules

Test Spring controllers using `@WebFluxTest` for isolated web layer tests. Keep controller tests focused on HTTP concerns: request mapping, validation, serialization, and status codes.

### Test Setup

Use `@WebFluxTest` to load only the web layer for the target controller.

**FORBIDDEN:** Using `@SpringBootTest` for controller unit tests.

**Incorrect:**

```java
@SpringBootTest
class UserControllerTest {
    @Autowired
    private WebTestClient webTestClient;
    // Loads the ENTIRE application context - slow!
}
```

**Correct:**

```java
@WebFluxTest(UserController.class) // Only loads UserController & web-layer beans
class UserControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private UserService userService;

    @Test
    void getUser_existingId_returns200WithUser() throws Exception {
        // Given - Mock must return a Mono for WebFlux
        var expectedUser = new User("1", "John", "john@test.com");
        when(userService.findById("1")).thenReturn(Mono.just(expectedUser));

        // When-Then
        webTestClient.get()
                .uri("/api/users/1")
                .exchange() // Performs the request
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo("1")
                .jsonPath("$.name").isEqualTo("John")
                .jsonPath("$.email").isEqualTo("john@test.com");
    }
}
```

### Key Annotations

| Annotation                       | Usage |
|----------------------------------|-------|
| `@WebfluxTest(Controller.class)` | Loads only web layer for the specified controller |
| `@MockitoBean`                   | Creates a Mockito mock and registers it in the Spring context (Spring Boot 3.4+) |
| `@MockBean`                      | Use this instead of `@MockitoBean` for Spring Boot versions below 3.4 |

**Note:** Use `@MockitoBean`.

### What to Test in Controllers

1. **Request mapping**: Correct URL, HTTP method, content type
2. **Request validation**: `@Valid` / `@Validated` annotations trigger validation
3. **Response status codes**: 200, 201, 400, 401, 403, 404, etc.
4. **Response body**: JSON structure via `jsonPath()` assertions
5. **Path variables and query parameters**: Correct binding
6. **Exception handling**: `@ControllerAdvice` / `@ExceptionHandler` responses

### Request Validation Testing

```java
@Test
void createUser_blankName_returns400() {
    String requestJson = """
            { "name": "", "email": "john@test.com" }
            """;

    webTestClient.post().uri("/api/users")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(requestJson)
            .exchange()
            .expectStatus().isBadRequest();
}

@Test
void createUser_invalidEmail_returns400() {
    String requestJson = """
            { "name": "John", "email": "not-an-email" }
            """;

    webTestClient.post().uri("/api/users")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(requestJson)
            .exchange()
            .expectStatus().isBadRequest();
}
```

### Security Annotation Testing

When the controller uses `@PreAuthorize`, `@Secured`, or `@RolesAllowed`:

```java
@WebFluxTest(AdminController.class)
@Import(SecurityConfig.class)
class AdminControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockBean // Use @MockBean for WebFlux/Mockito compatibility
    private AdminService adminService;

    @Test
    @WithMockUser(roles = "ADMIN")
    void deleteUser_adminRole_returns204() {
        webTestClient
                .mutateWith(csrf()) // Adds CSRF token for POST/DELETE
                .delete().uri("/api/admin/users/1")
                .exchange()
                .expectStatus().isNoContent();
    }

    @Test
    @WithMockUser(roles = "USER")
    void deleteUser_userRole_returns403() {
        webTestClient.delete().uri("/api/admin/users/1")
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void deleteUser_unauthenticated_returns401() {
        webTestClient.delete().uri("/api/admin/users/1")
                .exchange()
                .expectStatus().isUnauthorized();
    }
}
```

### Service Exception Handling

Test how the controller handles exceptions thrown by the service layer:

```java
@Test
void getUser_nonExistentId_returns404() {
    // Given - Return a Mono.error instead of throwing
    when(userService.findById("999"))
            .thenReturn(Mono.error(new UserNotFoundException("999")));

    // When-Then
    webTestClient.get().uri("/api/users/999")
            .exchange()
            .expectStatus().isNotFound();
}
```

### Pagination and Query Parameters

```java
@Test
void listUsers_withPagination_returns200WithPage() {
    // Given
    var user = new User("1", "John", "john@test.com");
    when(userService.findAll(any()))
            .thenReturn(Flux.just(user)); // WebFlux often returns Flux for lists

    // When-Then
    webTestClient.get()
            .uri(uriBuilder -> uriBuilder
                    .path("/api/users")
                    .queryParam("page", "0")
                    .queryParam("size", "10")
                    .build())
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$").isArray()
            .jsonPath("$[0].id").isEqualTo("1");
}
```

### Service or Component

```java
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private UserService userService;

    @Test
    void findById_success() {
        // Given
        var user = new User("1", "John");
        when(userRepository.findById("1")).thenReturn(Mono.just(user));

        // When
        Mono<User> result = userService.findById("1");

        // Then
        StepVerifier.create(result)
                .expectNext(user)           // Assert the value
                .verifyComplete();         // Assert it finished successfully
    }

    @Test
    void findById_notFound() {
        // Given
        when(userRepository.findById("999")).thenReturn(Mono.empty());

        // When
        Mono<User> result = userService.findById("999");

        // Then
        StepVerifier.create(result)
                .verifyComplete();         // Mono.empty() completes with no items
    }

    @Test
    void findAll_returnsMultiple() {
        // Given
        when(userRepository.findAll()).thenReturn(Flux.just(new User("1", "A"), new User("2", "B")));

        // When
        Flux<User> result = userService.findAll();

        // Then
        StepVerifier.create(result)
                .expectNextMatches(u -> u.getName().equals("A"))
                .expectNextMatches(u -> u.getName().equals("B"))
                .verifyComplete();
    }
}
```