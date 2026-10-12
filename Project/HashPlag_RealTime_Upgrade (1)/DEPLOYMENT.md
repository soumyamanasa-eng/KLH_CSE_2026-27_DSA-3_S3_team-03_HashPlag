# HashPlag deployment notes

## Local Windows run

1. Install JDK 17+ (JDK 21 recommended for Docker).
2. Extract this project and open the `HashPlagWeb` root folder.
3. Double-click `run.bat`, or use VS Code Run Without Debugging with the included `.vscode` configuration.
4. Sign in with a local demo account and open the URL printed by the server.

## Docker

Install Docker Desktop, open a terminal in this folder, then run:

```bash
docker compose up --build
```

Open `http://localhost:8080`. Persistent scan metadata and report JSON are stored under `./data` and survive container restarts while that volume is retained.

For a public deployment, create a `.env` file from `.env.example`, replace all three passwords with long unique values, and do not commit `.env`. Ensure the hosting provider preserves the mounted `/app/data` directory. Set HTTPS at the hosting proxy and do not expose a development server directly to the public internet.

## Environment variables

- `PORT`: port to bind when running in a container or hosted environment.
- `HASHPLAG_DATA_DIR`: directory for saved scan history, defaults to `data`.
- `HASHPLAG_ADMIN_PASSWORD`: password for account `admin`.
- `HASHPLAG_FACULTY_PASSWORD`: password for account `faculty`.
- `HASHPLAG_STUDENT_PASSWORD`: password for account `student`.
- `OPEN_BROWSER`: set `false` for containers/headless environments.
- `HASHPLAG_COOKIE_SECURE`: set `true` when served over HTTPS; leave `false` for local HTTP development.

The current account layer is a small role-based demo intended for a PBL prototype. It stores only in-memory sessions and uses three configured users; it is not a full production identity system. For institutional/public use, add per-user registration, password hashing/rotation, rate limiting, CSRF protections, HTTPS, backups, database-backed accounts, and an audited retention policy.

## Internet source lookup

The source lookup makes a best-effort request to DuckDuckGo's public HTML results. Automated requests may be rate-limited or blocked, in which case HashPlag provides a direct search link. Results are candidate sources, not evidence by themselves, and should be manually checked. Do not bulk-scrape sites or submit private/unpublished content to a search engine.

## Privacy and interpretation

- The server stores result summaries and match snippets in `data/history`; it does not persist the uploaded source files.
- Students see their own history; faculty/admin accounts can view all saved scans in this prototype.
- AI-writing-style scoring is an experimental heuristic, not an AI detector. It must never be used alone to decide authorship or misconduct.
- Word n-gram overlap is lexical overlap, not semantic similarity. The code mode normalizes identifiers/literals heuristically and may flag boilerplate/common structures.
- Similarity percentage is a signal for human review, not a plagiarism verdict in itself.
