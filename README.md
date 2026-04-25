# Resume RAG AI Java App

A smooth Java-based RAG application for asking questions about your resume. It runs as a local web app and needs no external dependencies.

## Features

- Java backend using the built-in HTTP server
- Resume RAG search over `data/resume.txt`
- Attractive gradient chat UI
- Resume editor in the browser
- Resume document upload from the browser
- Suggested question buttons
- Answer style options
- Local answer mode
- Optional OpenAI-powered answer mode with `OPENAI_API_KEY`

## Requirements

- Java 17 or newer

Check Java:

```powershell
java -version
```

## Run

From this project folder:

Option 1: double-click `run.bat`.

Option 2: run manually:

```powershell
javac -d out src/main/java/com/resumerag/ResumeRagApp.java
java -cp out com.resumerag.ResumeRagApp
```

Open:

```text
http://localhost:8080
```

If port `8080` is already busy, `run.bat` automatically tries `8081` and then `8082`. Use the URL shown in the command window.

## Add Your Resume

You can either:

- Paste your resume into the app sidebar and click `Save resume`
- Upload a `.txt`, `.md`, `.docx`, or text-readable `.pdf` resume from the sidebar
- Edit `data/resume.txt` directly

The app re-indexes the resume after saving.

Note: scanned image PDFs may not contain selectable text. For those files, use a `.docx` version or paste the resume text.

## Optional OpenAI Mode

The app works locally without an API key. For stronger generative answers, set an OpenAI key before running:

```powershell
$env:OPENAI_API_KEY="your_api_key_here"
$env:OPENAI_MODEL="gpt-4.1-mini"
java -cp out com.resumerag.ResumeRagApp
```

The assistant is instructed to answer only from resume context. If the information is not in the resume, it should say that it is not found.

## Answer Accuracy

The app first reads the resume into a candidate profile: summary, skills, projects, education, and experience. Questions are answered from that profile and the most relevant resume sections.

Local mode gives structured candidate-focused answers without sending data online. For the most natural AI-style reasoning, use OpenAI mode by setting `OPENAI_API_KEY`.

The app is designed to behave like a software representative of the candidate. It uses resume details internally, but the chat response should be an accurate answer about the candidate rather than copied resume text.

You can ask questions such as:

- Is this candidate suitable for a Java developer role?
- What are the candidate's strengths and improvement areas?
- How should the candidate introduce themselves in an interview?
- What projects prove the candidate's skills?
- Why should this candidate be selected?

## Project Structure

```text
src/main/java/com/resumerag/ResumeRagApp.java
public/index.html
public/styles.css
public/app.js
data/resume.txt
README.md
```
