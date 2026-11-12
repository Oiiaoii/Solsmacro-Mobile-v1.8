# Sol Macro 1.6 update (on top of 1.5)

Add/replace (same folders as in the zip):
- app/build.gradle.kts (version 1.6)
- app/src/main/assets/biomes/*.png  (NEW, 19 biome icons used as the embed picture)
- app/src/main/java/com/solmacro/: Ai.kt, Biome.kt (NEW), Hook.kt, MacroService.kt, MainActivity.kt, Panel.kt, Cfg.kt, Dbg.kt
Everything else is unchanged.

## Biome webhook (matches the reference picture)
- Message: "### Rainy Biome" heading, then your private server link (Rejoin settings > private server link).
- Embed: left bar color 0x829DB8 for Rainy (sampled from your picture; every biome has its own color), title "BIOME STARTED - RAINY Found", biome icon on the right, fields Channel (real channel mention), Time (Discord "x seconds ago"), Message ("Jump to Message", edited to point at the real message after it is posted), small footer.
- "BIOME ENDED - RAINY Ended" with a Duration field when a biome ends.
- biome_webhook_example.json shows the exact payload.
- Footer text: empty = "Message <@your ID> for any bugs" (your Discord ID from Word watch / ping settings), or set your own text in Discord > Biome alerts.

## Biome detection
- Works with or without fast capture (screenshots need Android 11+).
- Reads the area with 3 versions of the picture (bigger, high contrast, inverted), tolerates a misread letter, needs 2 reads in a row before it counts.
- Brry panel > Settings > Test biome read shows exactly what it read, so you can fix the area.
- Per-biome choice: off / message / message + ping (Discord > Biome alerts). Rare biomes (Null, Glitched, Dreamspace, Cyberspace) ping by default.
- Status report embed every N minutes (uptime, catches, per hour, sells, fails, biome, biomes seen).

## AI brain
- Tunes aim, tap strength (kp) and a timing trim; remembers tap lift and drain speed between sessions; goes back to defaults if tuned values do worse.
- Wait ratchet: lowers the waits after a reel one step after 3 clean reels, goes back up the moment a wait was too short, and remembers the limit.
- If the bar comes back after a reel was called finished, it keeps reeling (and learns the wait was too short).
- Reset button: Detection > Reset what the AI learned.

## Honest status
- Not compiled or run on a phone (no Android SDK here). The GitHub build is the first compile.
- The biome icons for biomes other than Rainy are generated look-alikes (same emblem, different colors), not the game's own art. Heaven, Limbo and Eggland are in the list on a best-guess basis.
