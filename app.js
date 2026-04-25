const messages = document.querySelector("#messages");
const askForm = document.querySelector("#askForm");
const questionInput = document.querySelector("#question");
const resumeText = document.querySelector("#resumeText");
const saveResume = document.querySelector("#saveResume");
const resumeFile = document.querySelector("#resumeFile");
const uploadResume = document.querySelector("#uploadResume");
const fileHint = document.querySelector("#fileHint");
const statusText = document.querySelector("#status");
const aiBadge = document.querySelector("#aiBadge");
const tone = document.querySelector("#tone");
const mode = document.querySelector("#mode");
const model = document.querySelector("#model");
const apiKey = document.querySelector("#apiKey");
const newChat = document.querySelector("#newChat");

apiKey.value = localStorage.getItem("candidate_ai_api_key") || "";
model.value = localStorage.getItem("candidate_ai_model") || model.value;

apiKey.addEventListener("input", () => {
  localStorage.setItem("candidate_ai_api_key", apiKey.value.trim());
  updateEngineBadge();
});

model.addEventListener("change", () => {
  localStorage.setItem("candidate_ai_model", model.value);
});

mode.addEventListener("change", updateEngineBadge);

async function api(path, options = {}) {
  const response = await fetch(path, {
    headers: { "Content-Type": "application/json" },
    ...options
  });
  const data = await response.json();
  if (!response.ok) {
    throw new Error(data.error || "Request failed");
  }
  return data;
}

function addMessage(role, text) {
  removeWelcome();
  const article = document.createElement("article");
  article.className = `message ${role}`;

  const avatar = document.createElement("div");
  avatar.className = "avatar";
  avatar.textContent = role === "user" ? "You" : "AI";

  const bubble = document.createElement("div");
  bubble.className = "bubble";
  if (role === "assistant") {
    bubble.appendChild(formatAssistantResponse(text));
  } else {
    bubble.textContent = text;
  }

  article.append(avatar, bubble);
  messages.appendChild(article);
  messages.scrollTop = messages.scrollHeight;
  return article;
}

function addThinking() {
  return addMessage("assistant", "Analyzing the candidate profile...");
}

function removeWelcome() {
  const welcome = messages.querySelector(".welcome");
  if (welcome) welcome.remove();
}

function restoreWelcome() {
  messages.innerHTML = `
    <section class="welcome">
      <div class="spark">AI</div>
      <h3>Candidate AI Studio</h3>
      <p>Upload the resume once. Ask direct facts or deeper candidate questions. Direct questions get direct answers; judgment questions get AI-style analysis.</p>
      <div class="prompt-grid">
        <button data-question="Is this candidate suitable for a Java developer role? Give a balanced answer.">Role fit</button>
        <button data-question="What are this candidate's strongest qualities?">Strengths</button>
        <button data-question="Give an interview-ready self introduction for this candidate.">Introduction</button>
        <button data-question="What should this candidate improve to become more employable?">Improvements</button>
      </div>
    </section>
  `;
  bindPromptButtons();
}

function formatAssistantResponse(text) {
  const response = document.createElement("div");
  response.className = "response-text";
  const clean = text.trim();

  if (!clean) {
    response.appendChild(paragraph("I could not create a response for that question."));
    return response;
  }

  const blocks = clean.split(/\n{2,}/).map((block) => block.trim()).filter(Boolean);
  blocks.forEach((block) => {
    const lines = block.split("\n").map((line) => line.trim()).filter(Boolean);
    const bulletLines = lines.filter((line) => /^[-*]\s+/.test(line));

    if (bulletLines.length && bulletLines.length === lines.length) {
      response.appendChild(list(bulletLines.map((line) => line.replace(/^[-*]\s+/, ""))));
      return;
    }

    response.appendChild(paragraph(lines.join(" ")));
  });

  return response;
}

function paragraph(text) {
  const p = document.createElement("p");
  p.textContent = text;
  return p;
}

function list(items) {
  const ul = document.createElement("ul");
  items.slice(0, 12).forEach((item) => {
    const li = document.createElement("li");
    li.textContent = item;
    ul.appendChild(li);
  });
  return ul;
}

async function loadResume() {
  const [resume, status] = await Promise.all([
    api("/api/resume"),
    api("/api/status")
  ]);
  resumeText.value = resume.resume;
  statusText.textContent = `${status.chunks} chunks`;
  updateEngineBadge(status.externalAiConfigured);
}

function updateEngineBadge(envKeyConfigured = false) {
  if (mode.value === "local") {
    aiBadge.textContent = "Offline";
    return;
  }
  aiBadge.textContent = apiKey.value.trim() || envKeyConfigured ? "AI ready" : "Needs key";
}

askForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  const question = questionInput.value.trim();
  if (!question) return;

  addMessage("user", question);
  questionInput.value = "";
  autosizeQuestion();

  const directAnswer = answerDirectFact(question, resumeText.value);
  if (directAnswer !== null) {
    addMessage("assistant", directAnswer);
    aiBadge.textContent = "Direct answer";
    return;
  }

  const loading = addThinking();

  try {
    const data = await api("/api/ask", {
      method: "POST",
      body: JSON.stringify({
        question,
        tone: tone.value,
        mode: mode.value,
        model: model.value,
        apiKey: apiKey.value.trim()
      })
    });
    loading.remove();
    addMessage("assistant", data.answer);
    aiBadge.textContent = data.usedExternalAi ? "AI answered" : "Offline answer";
  } catch (error) {
    loading.remove();
    addMessage("assistant", `I could not complete that request. ${error.message}`);
  }
});

function answerDirectFact(question, resume) {
  const q = question.toLowerCase();
  const text = normalizeResume(resume);

  if (hasAny(q, ["cgpa", "gpa"])) {
    return findCgpa(text) || "Not mentioned";
  }

  if (hasAny(q, ["percentage", "percent", "marks"])) {
    return firstMatch(text, [
      /\b(?:percentage|percent|marks)\s*[:\-]?\s*([0-9]{1,3}(?:\.[0-9]{1,2})?\s*%?)/i,
      /\b([0-9]{1,3}(?:\.[0-9]{1,2})?\s*%)\b/i
    ]) || "Not mentioned";
  }

  if (hasAny(q, ["email", "mail id", "mail"])) {
    return firstMatch(text, [/([A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,})/]) || "Not mentioned";
  }

  if (hasAny(q, ["phone", "mobile", "contact number"])) {
    return firstMatch(text, [/(?:\+?\d[\d\s\-()]{8,}\d)/]) || "Not mentioned";
  }

  if (hasAny(q, ["degree", "course"])) {
    return firstMatch(text, [
      /\b(Bachelor of [A-Za-z ]+|Master of [A-Za-z ]+|B\.?Tech|M\.?Tech|BCA|MCA|B\.?Sc|M\.?Sc|MBA)\b/i
    ]) || "Not mentioned";
  }

  if (hasAny(q, ["college", "university", "institution", "school"])) {
    return firstMatch(text, [
      /([A-Z][A-Za-z&. ]+(?:College|University|Institute|School)[A-Za-z&. ]*)/,
      /\b(?:college|university|institution|school)\s*[:\-]?\s*([^,.;\n]+)/i
    ]) || "Not mentioned";
  }

  if (hasAny(q, ["passing year", "graduation year", "passed out", "year of passing"])) {
    return firstMatch(text, [/\b(20\d{2}|19\d{2})\b/]) || "Not mentioned";
  }

  return null;
}

function normalizeResume(value) {
  return value
    .replace(/\u00a0/g, " ")
    .replace(/[–—]/g, "-")
    .replace(/\s+/g, " ")
    .trim();
}

function hasAny(value, words) {
  return words.some((word) => value.includes(word));
}

function firstMatch(text, patterns) {
  for (const pattern of patterns) {
    const match = text.match(pattern);
    if (match) {
      return (match[1] || match[0]).replace(/\s+/g, " ").trim();
    }
  }
  return null;
}

function findCgpa(text) {
  const cgpaLine = text.match(/[^.?!\n]*\bC\.?G\.?P\.?A\.?\b[^.?!\n]*/i) || text.match(/[^.?!\n]*\bG\.?P\.?A\.?\b[^.?!\n]*/i);
  const source = cgpaLine ? cgpaLine[0] : text;
  return firstMatch(source, [
    /\bC\.?G\.?P\.?A\.?\s*[:\-]?\s*([0-9](?:\.[0-9]{1,2})?\s*(?:\/\s*10)?)/i,
    /\bG\.?P\.?A\.?\s*[:\-]?\s*([0-9](?:\.[0-9]{1,2})?\s*(?:\/\s*10)?)/i,
    /\b([0-9](?:\.[0-9]{1,2})?\s*\/\s*10)\b/i
  ]);
}

questionInput.addEventListener("keydown", (event) => {
  if (event.key === "Enter" && !event.shiftKey) {
    event.preventDefault();
    askForm.requestSubmit();
  }
});

questionInput.addEventListener("input", autosizeQuestion);

function autosizeQuestion() {
  questionInput.style.height = "auto";
  questionInput.style.height = `${Math.min(questionInput.scrollHeight, 180)}px`;
}

saveResume.addEventListener("click", async () => {
  saveResume.disabled = true;
  saveResume.textContent = "Saving...";
  try {
    await api("/api/resume", {
      method: "POST",
      body: JSON.stringify({ resume: resumeText.value })
    });
    await loadResume();
    statusText.textContent = "Memory saved";
    addMessage("assistant", "I have updated the candidate memory. You can now ask detailed questions about suitability, strengths, gaps, interview answers, and role fit.");
  } catch (error) {
    statusText.textContent = error.message;
  } finally {
    saveResume.disabled = false;
    saveResume.textContent = "Save memory";
  }
});

uploadResume.addEventListener("click", async () => {
  const file = resumeFile.files[0];
  if (!file) {
    fileHint.textContent = "Choose a resume file first.";
    return;
  }

  uploadResume.disabled = true;
  uploadResume.textContent = "Reading";
  statusText.textContent = "Indexing";

  const formData = new FormData();
  formData.append("resume", file);

  try {
    const response = await fetch("/api/upload", {
      method: "POST",
      body: formData
    });
    const data = await response.json();
    if (!response.ok) {
      throw new Error(data.error || "Upload failed");
    }
    await loadResume();
    fileHint.textContent = `${data.fileName} is now candidate memory.`;
    addMessage("assistant", "The resume has been analyzed and saved as candidate memory. Ask me about the candidate's strengths, role fit, interview answers, gaps, or hiring recommendation.");
  } catch (error) {
    statusText.textContent = error.message;
    fileHint.textContent = "If this is a scanned PDF, use a .docx/.txt version or paste the text.";
  } finally {
    uploadResume.disabled = false;
    uploadResume.textContent = "Upload";
  }
});

resumeFile.addEventListener("change", () => {
  const file = resumeFile.files[0];
  fileHint.textContent = file ? `Selected ${file.name}` : "Upload a resume, then ask candidate questions.";
});

newChat.addEventListener("click", restoreWelcome);

function bindPromptButtons() {
  document.querySelectorAll("[data-question]").forEach((button) => {
    button.addEventListener("click", () => {
      questionInput.value = button.dataset.question;
      autosizeQuestion();
      questionInput.focus();
    });
  });
}

bindPromptButtons();
loadResume().catch((error) => {
  statusText.textContent = error.message;
  updateEngineBadge();
});
