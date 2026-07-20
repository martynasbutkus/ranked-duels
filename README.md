# Ranked Duels

Challenge any player to a **ranked duel** with a right-click — fight anywhere in
Gielinor and climb a Glicko-2 rated ladder with regional rankings.

Ladder, profiles and match pages: **https://rankedduel.com**

## How it works

1. Install the plugin. That's the whole setup — you're placed on the ladder
   automatically the next time you log in.
2. Right-click any player → **Ranked Duel**. They get an accept prompt; both
   players must be on the same world. If you both challenge each other, the
   duel starts instantly.
3. The exact rating you'd gain or lose is shown before you accept, and stays
   on screen during the fight.
4. The first hit between you starts the duel. Fight anywhere: the Wilderness,
   PvP worlds, the PvP Arena — any gear, any rules you agree on.
5. Both fighters' equipment is snapshotted at the first hit and shown on the
   match page.
6. If anyone outside the duel damages either player, the duel is voided
   automatically — nobody gains or loses rating.
7. Results only count when **both clients** report the same outcome — no
   honor system, no screenshots, no disputes.

The side panel (hitsplat icon) shows your rating, global rank, peak, record
and recent duels.

## PK leaderboard

Separately from ranked duels, the plugin counts every player kill and death,
anywhere in the game, toward a raw kill leaderboard on the website. Your
opponents don't need the plugin for your kills to count.

## Website account (optional)

You never need a website account to duel. If you want one, register at
rankedduel.com and paste the link code from **My Account** into the plugin
settings — your rating and match history attach to your account, securing
your profile and unlocking a personal dashboard.

## Data

The plugin communicates with rankedduel.com:

- **On login:** your account hash (or a SHA-256 of your login name for
  non-Jagex accounts) and your display name, to register/update you on the
  ladder. Credentials are never read or sent.
- **During ranked duels** (challenge, accept, start, finish, void): world
  number, world datacenter location (for regional ladders), both fighters'
  equipped item IDs, and damage totals.
- **On player kills/deaths:** a kill or death event and the world number,
  for the PK leaderboard. No information about the other player is sent.

The API endpoint is visible and configurable in the plugin settings.

## License

BSD 2-Clause. Not affiliated with Jagex Ltd. Old School RuneScape is a
trademark of Jagex Ltd.
