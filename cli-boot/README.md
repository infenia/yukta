# Yukta CLI - Standalone Application

## Building

### JVM Build
```bash
./gradlew :cli-boot:bootJar
```

The JAR will be available at: `cli-boot/build/libs/cli-boot-*.jar`

### Native Image Build
```bash
./gradlew :cli-boot:nativeCompile
```

The native executable will be available at: `cli-boot/build/native/nativeCompile/yukta-cli`

## Running

### JVM Mode
```bash
java -jar cli-boot/build/libs/cli-boot-*.jar control heartbeat
```

### Native Mode
```bash
./cli-boot/build/native/nativeCompile/yukta-cli control heartbeat
```

### Starting Daemon
```bash
./cli-boot/build/native/nativeCompile/yukta-cli daemon start
```

## Configuration

The CLI reads configuration from:
- `application.yaml` (classpath, runtime configuration)
- Environment variables (override file config)
- Command-line arguments (highest priority)

Key properties:
- `yukta.server.url` - Yukta server base URL (default: `http://localhost:8080`)
- `yukta.daemon.port` - Daemon listening port (default: `9001`)
- `yukta.daemon.dir` - Daemon working directory (default: `~/.yukta/daemon`)
