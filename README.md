# untitled

Java starter project. Nothing fancy here — just the skeleton.
Was created from IntelliJ default template. We use it as a base for experiments or quick POCs.

---

## What Is This

Simple Java application with Gradle build system.
Entry point is `Main.java`, it print hello and loop five times. That's it.

The point is not the code — the point is the build setup and the project structure is ready to go.

---

## Requirements

Before you start, make sure you have:

- **JDK 17+** — anything lower and Gradle will complain, trust me
- **Gradle 9.x** — wrapper is included, you don't need to install separately
- **IntelliJ IDEA** (recommended) — project files are already there under `.idea/`

If you run it from terminal that's also fine, see below.

---

## How To Run

**From IntelliJ:**
Just open the project and press Run. IntelliJ will detect the Gradle config automatically.

**From terminal:**
```bash
./gradlew run
```

On Windows:
```cmd
gradlew.bat run
```

If you get permission error on Linux/Mac:
```bash
chmod +x gradlew
```

---

## How To Build

```bash
./gradlew build
```

Output goes to `build/` directory. The jar will be under `build/libs/`.

---

## How To Run Tests

```bash
./gradlew test
```

Tests use JUnit 5 (Jupiter). Test results are in `build/reports/tests/test/index.html` — open in browser if you want nice output.

There are no tests yet in this project. Add them under `src/test/java/`.

---

## Project Structure

```
untitled/
├── src/
│   └── main/
│       └── java/
│           └── org/
│               └── Main.java       # entry point
├── build.gradle.kts                # build config, dependencies here
├── settings.gradle.kts             # project name
└── gradlew                         # use this, not system gradle
```

---

## Adding Dependencies

Open `build.gradle.kts` and add to the `dependencies` block:

```kotlin
dependencies {
    implementation("com.google.guava:guava:32.1.3-jre")  // example
}
```

All dependencies resolve from Maven Central. No private registry configured here.

---

## Common Issues

**`JAVA_HOME` not set** — set it to your JDK installation. On Mac with SDKMAN:
```bash
export JAVA_HOME=$(java -XshowSettings:property -version 2>&1 | grep java.home | awk '{print $3}')
```

**Gradle daemon timeout** — just run again, it will start fresh.

**IntelliJ shows red code after clone** — go to `File > Sync Project with Gradle Files`. Always solves it.

---

## Notes

- The `.idea/` folder is committed intentionally — makes onboarding faster for the team.
- Do not commit the `build/` directory, it is in `.gitignore` already.
- If you need to change Java version, update `build.gradle.kts` — look for `sourceCompatibility`.

---

## Who Maintains This

Yishay Merzbach — ping me if something broken or you have question.
