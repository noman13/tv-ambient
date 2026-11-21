# Third-party guides in this skill

The folders under `references/` are copied from the sources below, pinned to the commit shown. The only change:
each upstream `SKILL.md` was renamed `GUIDE.md`, so the combined skill ([`SKILL.md`](SKILL.md), our own text) is the
one skill an agent loads; the text of every guide is unchanged. Each folder keeps its upstream licence as
`LICENSE.upstream`. Excluded files are listed per guide.

| Guide (`references/…`) | Source | Commit | Licence | Excluded |
|---|---|---|---|---|
| `android-tv-design` | [DevelopersCoffee/airo](https://github.com/DevelopersCoffee/airo/tree/2b71ad6ea9c5077fa2227d022ffb2e501a8dfd2a/.claude/skills/android-tv-design) | `2b71ad6ea9c5` | MIT | none |
| `mobile-android-design` | [wshobson/agents](https://github.com/wshobson/agents/tree/42203085cfc5736204bf0f6d436dff7e1da0bf3f/plugins/ui-design/skills/mobile-android-design) | `42203085cfc5` | MIT | none |
| `r8-analyzer` | [android/skills](https://github.com/android/skills/tree/c72942584fff970dac86ced78b367fce0c441e52/performance/r8-analyzer) | `c72942584fff` | Apache-2.0 | none |
| `anr-audit-and-fix` | [android/skills](https://github.com/android/skills/tree/c72942584fff970dac86ced78b367fce0c441e52/performance/anr-audit-and-fix) | `c72942584fff` | Apache-2.0 | none |
| `android-permissions-security` | [android/skills](https://github.com/android/skills/tree/c72942584fff970dac86ced78b367fce0c441e52/security/android-permissions-security) | `c72942584fff` | Apache-2.0 | none |
| `android-intent-security` | [android/skills](https://github.com/android/skills/tree/c72942584fff970dac86ced78b367fce0c441e52/security/android-intent-security) | `c72942584fff` | Apache-2.0 | none |
| `android-profiler` | [android/skills](https://github.com/android/skills/tree/c72942584fff970dac86ced78b367fce0c441e52/profilers/android-profiler) | `c72942584fff` | Apache-2.0 | ^references/perfetto/bin/ |
