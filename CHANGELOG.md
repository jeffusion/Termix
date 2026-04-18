# [1.2.0](https://github.com/jeffusion/Termix/compare/v1.1.0...v1.2.0) (2026-04-18)


### Bug Fixes

* **branding:** make monochrome icon generation color-agnostic ([a1a02f8](https://github.com/jeffusion/Termix/commit/a1a02f851e29a559963780b8385a7395b793636c))


### Features

* **github:** add issue forms for bug reports and feature requests ([2a6e2fb](https://github.com/jeffusion/Termix/commit/2a6e2fbd351bee772e3e44b1978038d9941af3db))

# [1.1.0](https://github.com/jeffusion/Termix/compare/v1.0.0...v1.1.0) (2026-04-18)


### Features

* **branding:** add logo asset workflow and refresh launcher images ([c3be7b5](https://github.com/jeffusion/Termix/commit/c3be7b54f7f69d7e429702d3dc5ae906a1b29a86))

# 1.0.0 (2026-04-17)


### Bug Fixes

* align default theme with readable terminal UI state ([9a2f79a](https://github.com/jeffusion/Termix/commit/9a2f79a442f9c4adf06dc2ce4aa894b2e04f858a))
* align shell hostname variables with runtime hostname ([e6a30e2](https://github.com/jeffusion/Termix/commit/e6a30e2e351fcf9e0e894fb8049ccd96b2ef509d))
* **android:** remove remaining legacy provider and signing paths ([59c580d](https://github.com/jeffusion/Termix/commit/59c580dbc1ef86f0b341e45028ccc15da854bd47))
* **ci:** use GH_TOKEN for semantic-release to bypass branch protection ([0692a40](https://github.com/jeffusion/Termix/commit/0692a40d5615162c96c54d9a950da060d1b169bd))
* ensure terminal view receives keyboard focus on launch and navigation ([1a0a04d](https://github.com/jeffusion/Termix/commit/1a0a04dc21d0f4a6f74d831d4b2d02e3b90b96b2))
* extend non-default background rects to cover full row height in TUI programs ([b86e7b0](https://github.com/jeffusion/Termix/commit/b86e7b08d2825690aa2d958145262e940bb3022b))
* propagate guest hostname through launcher scripts ([d2d3ce2](https://github.com/jeffusion/Termix/commit/d2d3ce298ec55a2ec36260a99052c925f1ba635f))
* **release:** avoid invalid secrets guards in workflow ([f0ebd76](https://github.com/jeffusion/Termix/commit/f0ebd761d0506b8e2c99101954e2743ba9ed28ec))
* **release:** fallback to testkey when secrets are empty ([e22e0f6](https://github.com/jeffusion/Termix/commit/e22e0f61eb54fcc2b1660b8ea7d5209ef3a1c46f))
* remove non-functional SECCOMP toggle and stop overriding proot embedded loader ([55c834e](https://github.com/jeffusion/Termix/commit/55c834e5bd49351fadf384d2f3bd98476b754daa))
* remove PASTE virtual key button from keyboard row ([e3aba71](https://github.com/jeffusion/Termix/commit/e3aba71eff28291650c681e3303c2ac38533553c))
* remove redundant /proc/self/fd bindings in root mode to suppress proot warnings ([b68f5c4](https://github.com/jeffusion/Termix/commit/b68f5c43ce97d72e68e814fc58935cd9fbd14857))
* **runtime:** redownload invalid cached runtime binaries ([d96e062](https://github.com/jeffusion/Termix/commit/d96e062c54b57c3c61111fb248342c4a8ddf7897))
* **runtime:** restore downloader mirror availability ([0782aea](https://github.com/jeffusion/Termix/commit/0782aea14ab2660fafb7740d230ff030691c6fba))


### Features

* add Chinese (zh) localization ([#48](https://github.com/jeffusion/Termix/issues/48)) ([a81c400](https://github.com/jeffusion/Termix/commit/a81c400fb9f835ebab4acf951a745d09cbbe5de3))
* add color scheme selector UI in customization screen ([4aee3e9](https://github.com/jeffusion/Termix/commit/4aee3e98d397b0ce6eb6676f5798611376c26974))
* add configurable keyboard shortcuts for paste and session management ([#49](https://github.com/jeffusion/Termix/issues/49)) ([0394960](https://github.com/jeffusion/Termix/commit/03949602c2f3bd1f5aaffee78ceb1fa253b9b66c))
* add configurable scrollback lines setting (500-50K) with slider UI ([0b7180f](https://github.com/jeffusion/Termix/commit/0b7180f86fb8904699163a45b1005e36b24d39ed))
* add dual layout mode (Phone/Desktop) with tab bar UI and session management improvements ([1625951](https://github.com/jeffusion/Termix/commit/1625951bd0f2e2d22ec862742607345a8d8da62d))
* add getInputMode() to TerminalView interface and implement 3-way input type switch ([b96eec5](https://github.com/jeffusion/Termix/commit/b96eec5aef5dfecdee844dda504a4be144728df4))
* add i18n strings for color scheme feature (en, zh, ar) ([c1f3349](https://github.com/jeffusion/Termix/commit/c1f334925358ceec95ebe15fdc5f1d48ac6bc072))
* add i18n strings for input mode setting (en, zh, ar) ([5145606](https://github.com/jeffusion/Termix/commit/5145606bc148ecd55c27aefcab31bf548cb8cda1))
* add i18n strings for root mode and shell selection (en, zh, ar) ([3a95657](https://github.com/jeffusion/Termix/commit/3a95657dfd4c9d9987fbfa2ed074f01f0c960af5))
* add i18n strings for settings reorganization (en, zh, ar) ([4d2fa52](https://github.com/jeffusion/Termix/commit/4d2fa5265fbf080d974105c90c4568ace356dcdd))
* add initial Arch Linux modes and bootstrap scripts ([d9c9a08](https://github.com/jeffusion/Termix/commit/d9c9a08a9041fa9580f20cdbf894b97acbc5c705))
* add modifier+number quick session switching with tab number badges ([c6aca0f](https://github.com/jeffusion/Termix/commit/c6aca0fff82be08e639e16bc60e42503da0bc1af))
* add option to launch the file permission screen ([f4e61bf](https://github.com/jeffusion/Termix/commit/f4e61bf2ec83bcdf6e4647b398a23092fc50c0b1))
* add shell selection setting (Bash/Ash/Zsh) in settings UI ([9037d1d](https://github.com/jeffusion/Termix/commit/9037d1de580567e9b2df84088b4cb4f83adce08a))
* add terminal color scheme data model and 16 built-in schemes ([683a5a3](https://github.com/jeffusion/Termix/commit/683a5a349b37cec178092c56c5d2cd6801f1f55f))
* add user-configurable input mode setting with 3 presets ([27f39eb](https://github.com/jeffusion/Termix/commit/27f39eb5e5ee89bafed3f21f7f1d109055c7e480))
* added secomp toggle ([da3efb2](https://github.com/jeffusion/Termix/commit/da3efb26d146657d050f62ad1de00f20521ded44))
* added secomp toggle ([54329cf](https://github.com/jeffusion/Termix/commit/54329cf4a9de05b74ee388a21af2869b201a4653))
* added wallpaper alpha slider ([25a65d5](https://github.com/jeffusion/Termix/commit/25a65d5e799d8aec74ac9475b5fcdba7672f3a3f))
* display working mode name and color-coded privilege level on session tabs ([b6ee1ba](https://github.com/jeffusion/Termix/commit/b6ee1ba5263f3940bcd042f3ca58ff2fa18a6866))
* improve setup progress and Arch install cleanup ([d898e51](https://github.com/jeffusion/Termix/commit/d898e5105778c6be7eb7cdec633bb01183ed74c8))
* integrate color scheme switching into app theme and terminal rendering ([aa91922](https://github.com/jeffusion/Termix/commit/aa91922c8caa9982eb0348467eedca6c57e86fa1))
* restore Alpine root working mode with su-based proot execution ([ce0923d](https://github.com/jeffusion/Termix/commit/ce0923dc54308134a8eb55e1d4f8863443c7d96c))
* Switch keyboard to normal so we can send cjk characters (Closes [#29](https://github.com/jeffusion/Termix/issues/29)) ([c0f4795](https://github.com/jeffusion/Termix/commit/c0f47950141ed4db2f7c3826389c286b1daeeda4))
* update terminalView ([b01ffd4](https://github.com/jeffusion/Termix/commit/b01ffd45f65700f7e206e831c3cf50fb19b04ba1))


### Performance Improvements

* increase IO buffers to 64KB and coalesce screen update invalidations ([2c76b98](https://github.com/jeffusion/Termix/commit/2c76b98645921e621767cd0e23ac04fc4eb35578))
* set controlling tty for subprocess pty ([bb6db50](https://github.com/jeffusion/Termix/commit/bb6db50b482431d3a84a437e2a3a74b12cbdf16c))
