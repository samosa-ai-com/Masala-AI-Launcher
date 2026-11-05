# Changelog

Notable changes to Masala, newest first. The release workflow publishes the `[Unreleased]` notes as
the GitHub release text and renames the section to the new version, so write them for people
installing the APK.

## [Unreleased]

### Changed
- **Clearer "Allow this?" questions.** Before Masala changes something, it now says what will happen
  in plain words, such as "Change your home screen: “Add Calculator and Clock”". The exact request
  is still one tap away under **Show details**.

### Fixed
- Settings showed "Custom ·" before a model was chosen. It now says "No model set up yet".

## [0.1.0]
First public preview. Expect rough edges: layouts, settings and data may change between previews.

### Added
- **A home screen you can talk to.** Masala replaces your launcher: clock, search, dock, an app drawer
  with grid, list and globe views, and a long-press menu for every app.
- **Layouts built by AI.** Describe the home screen you want, or show a photo of one, and Masala
  builds it. Every layout is checked before it is applied, and you can undo up to 50 changes.
- **Chat and voice.** Ask Masala to open apps, set things up or answer questions, by typing or by
  tapping the mic.
- **Bring your own model.** Masala ships with no AI account and no key. Choose a provider (OpenAI,
  Anthropic, Gemini, Groq, OpenRouter, Mistral, LM Studio, and others) and paste your own key under
  Settings → Models & keys. Keys are encrypted on the phone.
- **Updates inside the app.** Settings → About shows when a new version is out, with what's new, and
  installs it after you confirm. You get one notification per new version (you can turn it off).
- **Changes ask first.** Anything that changes your phone asks for confirmation and is logged.

### Note
- This preview includes power-user tools (all-files access, a Termux bridge, installing APKs) that
  ask for special permissions only when you use them.
