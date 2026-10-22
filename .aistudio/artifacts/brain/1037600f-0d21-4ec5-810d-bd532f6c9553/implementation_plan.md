# Prioritized Roadmap: History 3 Tabs First, RPD Selector & SRT Verification

Following user guidance, this implementation is structured in clear, incremental milestones starting with the **History 3-Tab UI First** so you can immediately see and test the categorized view.

---

## 🎯 Phased Execution Order

### Milestone 1: History 3-Tab System (First Priority)
- Add a clean Material 3 `TabRow` at the top of the History screen with 3 tabs:
  1. **`[ ✨ Auto-Fixed (SRT) ]`**
  2. **`[ 📝 Custom ]`**
  3. **`[ ⚠️ Failed / Incomplete ]`**
- Wire up the filtering logic for all current scripts in the database:
  - Scripts with missing/incomplete parts or errors immediately appear under **Failed / Incomplete**.
  - Verified scripts appear under **Auto-Fixed (SRT)**.
  - Standard/unfixed scripts appear under **Custom**.
- Ensure action buttons (`View Full Script`, `Regenerate All`, `Retry Special Parts`, `Retry Failed Parts`) remain fully functional on each card.

### Milestone 2: RPD Key Selector & Model Dropdown
- Upgrade the RPD timeout and retry configuration dialogs:
  - Display Gemini Model dropdown (updates RPD quotas live).
  - Quick-select list of all stored keys from Settings (Main Key + Spare Keys 1–19) with live colored quota indicators (`🟢 Available` / `🔴 Limit Reached`).
  - Eliminate copy-pasting so switching keys takes a single tap.
  - Retain "+ Add Custom Key" option for entering unlisted keys.

### Milestone 3: Dual-Version Retention & Auto-Fix Lifecycle
- When multi-part generation completes:
  - Save the initial complete draft directly into **Custom** (preserving the "Before Auto-Fixed" version).
  - If SRT is present, execute subtitle verification pass and generate auto-fixed version for **Auto-Fixed (SRT)**.
  - Inside **View Full Script**, add a version switch button between `[ ✨ Auto-Fixed ]` and `[ 📝 Before Auto-Fixed ]`.

### Milestone 4: Custom Script Retry Special Parts with SRT
- In the **Custom Tab**, enhance the `[ 🎯 Retry Special Parts ]` dialog:
  - Checkbox part selection.
  - `[ 📌 Fix with SRT ]` toggle enabling users to attach subtitles for specific selected parts.

---

## 🛡️ Untouched System Components
- Video upload, chunking, and Gemini file upload engine.
- Multi-key rotation engine and quota reset calculation.
- System prompt templates, Character detection, Audio player, and Settings.
