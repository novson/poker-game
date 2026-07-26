# Repository Guidelines

## Project Structure & Module Organization

- `backend/`: Spring Boot 3 API, STOMP WebSocket endpoints, poker domain logic, AI/advisor services, and configuration.
- `backend/src/main/java/com/example/poker/`: production Java code organized into `config`, `controller`, `domain`, `dto`, and `service`.
- `backend/src/test/java/com/example/poker/`: JUnit tests mirroring production packages.
- `frontend/`: Vue 3 and Vite client.
- `frontend/src/components/`: Vue table and card components; `frontend/src/services/` contains API, session, rules, and presentation helpers plus colocated Vitest tests.
- `compose.yml`, `Jenkinsfile`, and module `Dockerfile`s define deployment and CI.

Generated directories (`backend/target`, `frontend/dist`, and `frontend/node_modules`) must not be committed.

## Build, Test, and Development Commands

- `cd backend && mvn spring-boot:run`: run the API on port 8080.
- `cd backend && mvn clean verify`: compile and run all backend tests.
- `cd frontend && npm ci`: install locked dependencies.
- `cd frontend && npm run dev`: start Vite on port 5173 with API/WebSocket proxies.
- `cd frontend && npm test`: run Vitest once.
- `cd frontend && npm run build`: create the production bundle in `frontend/dist`.
- `docker compose up -d --build`: build and run both modules at port 8088.

## Coding Style & Naming Conventions

Use four-space indentation for Java and two spaces for JavaScript, Vue, YAML, and CSS. Follow Java conventions: `PascalCase` types, `camelCase` methods/fields, and packages under `com.example.poker`. Use `camelCase` for JavaScript exports and descriptive Vue component names such as `PlayingCard.vue`. The frontend uses ES modules, single quotes, and generally omits semicolons. No formatter or linter is enforced, so preserve nearby style.

## Testing Guidelines

Backend tests use JUnit 5 and end in `Test.java`; frontend tests use Vitest and end in `.test.js`. Add regression tests for betting, side pots, reconnects, chip transfers, AI decisions, and responsive view helpers when changing those areas. Run both `mvn clean verify` and `npm test` before submitting; also run `npm run build` for UI changes.

## Commit & Pull Request Guidelines

History follows Conventional Commit prefixes such as `feat:`, `fix:`, `test:`, and `chore:`. Write imperative, narrowly scoped subjects. Pull requests should explain behavior changes, list verification commands, link related issues, and include desktop/mobile screenshots for visual changes. Call out configuration or deployment impacts explicitly.

## Security & Configuration

Do not commit `.env` files, credentials, admin tokens, or SSH keys. Configure allowed origins and runtime settings through environment variables or deployment configuration. Poker state is process-local unless explicitly persisted; consider restart and compatibility effects when changing state models.
