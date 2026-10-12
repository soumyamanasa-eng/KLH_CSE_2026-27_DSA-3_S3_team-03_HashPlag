# HashPlag Detect — Academic Document Similarity Platform

HashPlag is a Java + HTML/CSS/JavaScript PBL prototype for comparing documents and programming assignments. It combines Rabin–Karp with Polynomial Rolling Hash, line-aware matching, optional token-normalized code comparison, a word n-gram overlap score, writing-style signals, persistent scan history, PDF export, public-web source lookup, role-aware demo accounts, and live progress reporting.

## Features

- Upload several supported file types and compare every unique document pair.
- Exact line-aware matches using Rabin–Karp + Polynomial Rolling Hash; hash hits are verified against actual text.
- Overlapping windows are merged into phrases; tiny fragments are not displayed as standalone matches.
- Text mode preprocesses punctuation and case while preserving line breaks. Code mode normalizes identifiers and literals heuristically, which can identify structurally similar snippets but can also flag common boilerplate.
- Word 3-gram Jaccard overlap provides an additional lexical signal. It does not understand semantics and is not a paraphrase detector.
- Experimental writing-style signals. The score is not a probability of AI authorship and cannot prove whether a human or AI wrote text.
- Login roles: student, faculty, administrator. Students see only their own saved scans; faculty/admin can inspect all reports in this prototype.
- Scan progress polling and a cancel request that is checked at processing checkpoints.
- Scan history persisted under `data/history` as report JSON plus metadata files; original uploaded documents are not stored.
- Export a readable PDF report.
- Search for possible public internet sources for a matching phrase using best-effort public web search; a direct search link is provided if retrieval is blocked.
- Windows launch via `run.bat`, VS Code compile/run configuration, and Docker deployment assets.

## Local Windows run

1. Install JDK 17 or newer.
2. Extract the ZIP and open the extracted `HashPlagWeb` root folder (the folder containing `src`, `web`, and `lib`).
3. Double-click `run.bat`, or open `src/HashPlagServer.java` and choose **Run Without Debugging** in VS Code.
4. Open the URL printed in the terminal, usually `http://localhost:8080`.
5. Sign in with a demo account:
   - Student: `student` / `student123`
   - Faculty: `faculty` / `faculty123`
   - Administrator: `admin` / `admin123`
6. Upload at least two documents, choose `Document text` or `Programming assignments`, set the window size, and click **Compare documents**.

If a port from 8080–8089 is occupied, the server selects another free port and prints it. Always open the URL shown in the terminal.

## VS Code

The included `.vscode/launch.json` runs the Java server, uses `out` and `lib/*` on the classpath, runs the compile task first, and opens the local URL after the server-ready message. If the Java extension is still importing the project, use `run.bat` instead.

## PDFBox dependency

`lib/pdfbox-2.0.24-with-dependencies.jar` is included for PDF text extraction and report generation. The JDK's built-in HTTP server is used for routes.

## Docker

With Docker Desktop installed, run in the project root:

```bash
docker compose up --build
```

Open `http://localhost:8080`. Report history is kept in `./data` through the configured volume.

For a public deployment, set all account password environment variables to strong unique values, set `HASHPLAG_COOKIE_SECURE=true`, use HTTPS, preserve the data directory on a persistent volume, and add production-grade identity/security controls. See `DEPLOYMENT.md`.

## Internet source lookup notes

The web lookup makes a best-effort query against a public DuckDuckGo HTML endpoint. Automated access can be limited or the page format may change. If parsing does not work, the UI offers a direct search link. Returned pages are potential sources only and require manual verification.

## Limits

- Scanned/image-only PDFs are not OCR'd.
- Old binary `.doc`, `.ppt`, `.xls` files must be converted to modern formats or PDF.
- AI-writing analysis is heuristic only and must not be used to accuse a student or establish authorship.
- Exact match percentages and word n-gram scores are evidence for review, not automatic proof of plagiarism.
- The built-in role accounts are for demonstration, not production. See `DEPLOYMENT.md` before putting the app on the public internet.
