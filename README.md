# Ranked Duels

Challenge any player to a **ranked duel** with a right-click — fight anywhere in Gielinor, and climb a Glicko-2 rated ladder with regional rankings.

## How it works

1. Register at the Ranked Duels website and copy your one-time link code from **My Account**.
2. Paste the code into this plugin's settings and log in — your account links automatically.
3. Right-click any registered player → **Ranked Duel**. They get an accept prompt; both players must be on the same world.
4. The first hit starts the duel. Fight anywhere: Wilderness, PvP worlds, the PvP Arena.
5. If anyone outside the duel damages either player, the duel is voided automatically.
6. Results only count when **both clients** report the same outcome — no honor system.

The side panel (hitsplat icon) shows your rating, global rank, peak, record and recent duels.

## PvP Performance Tracker

If you also run Matsyir's PvP Performance Tracker, its detailed fight stats (off-pray %, deserved damage, magic luck, KO chances) are attached to your duel results and shown on the website. This is read-only and optional.

## Data

On duel events (challenge, accept, start, finish, void) the plugin sends to the ladder server: your account hash, display name, world, gear item IDs of both fighters, damage totals, and (if present) PvP Performance Tracker fight data. Nothing is sent outside of ranked duels you explicitly accept.
